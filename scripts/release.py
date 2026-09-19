#!/usr/bin/env python3
"""Cashier Helper 发布工具：版本分配、草稿 Release 管理与附件发布。

由 .github/workflows/release.yml 调用。子命令：

  prepare        计算或复用版本号，创建草稿 Release 并创建 tag
  stage-assets   生成发布附件目录（APK、mapping、元数据、校验和）
  publish        校验附件、上传到草稿 Release 并公开

所有对 GitHub 的访问都通过 REST API，凭据来自 GH_TOKEN / GITHUB_TOKEN，
接口地址来自 GITHUB_API_URL（默认 https://api.github.com）。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import NoReturn

# 仓库里 app/build.gradle.kts 的默认版本是 1.0.1 / 2，它就是自动递增的基线。
BASELINE_VERSION_NAME = "1.0.1"
BASELINE_VERSION_CODE = 2

VERSION_NAME_PREFIX = "1.0."
TAG_PREFIX = "v1.0."

METADATA_MARKER = "<!-- cashierhelper-release-metadata"
METADATA_MARKER_END = "-->"

CHECKSUM_FILE = "SHA256SUMS"
METADATA_FILE = "release-metadata.json"
MAPPING_FILE = "mapping.txt"

RETRY_STATUS = {429, 500, 502, 503, 504}
RETRY_BASE_DELAY = 2
RETRY_ATTEMPTS = 4


def fail(message: str) -> NoReturn:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def note(message: str) -> None:
    print(message)


# --------------------------------------------------------------------------
# 纯函数：版本、元数据、附件名
# --------------------------------------------------------------------------


def patch_from_version_name(value: object) -> "int | None":
    """把 1.0.N 解析为 N；其他形式返回 None。"""
    if not isinstance(value, str) or not value.startswith(VERSION_NAME_PREFIX):
        return None
    suffix = value[len(VERSION_NAME_PREFIX) :]
    return int(suffix) if suffix.isdigit() else None


def patch_from_tag(value: object) -> "int | None":
    """把 v1.0.N 解析为 N；其他形式返回 None。"""
    if not isinstance(value, str) or not value.startswith(TAG_PREFIX):
        return None
    suffix = value[len(TAG_PREFIX) :]
    return int(suffix) if suffix.isdigit() else None


def parse_metadata(body: object) -> "dict | None":
    """从 Release 正文中取出元数据 JSON 注释，解析失败返回 None。"""
    if not isinstance(body, str):
        return None
    start = body.find(METADATA_MARKER)
    if start < 0:
        return None
    end = body.find(METADATA_MARKER_END, start + len(METADATA_MARKER))
    if end < 0:
        return None
    raw = body[start + len(METADATA_MARKER) : end].strip()
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        return None
    return data if isinstance(data, dict) else None


def apk_file_name(version_name: str) -> str:
    return f"CashierHelper-v{version_name}.apk"


def next_version(releases, tag_patches):
    """返回下一个 (patch, versionCode)。

    同时读取 Release（含草稿）元数据里的版本记录和已有的 v1.0.* tag，
    保证已经占用过的版本号不会被重复使用。
    """
    patches = {patch_from_version_name(BASELINE_VERSION_NAME) or 1}
    codes = {BASELINE_VERSION_CODE}

    for release in releases:
        metadata = parse_metadata(release.get("body"))
        if metadata:
            patch = patch_from_version_name(metadata.get("versionName"))
            if patch is not None:
                patches.add(patch)
            code = metadata.get("versionCode")
            if isinstance(code, int) and not isinstance(code, bool):
                codes.add(code)
        patch = patch_from_tag(release.get("tag_name"))
        if patch is not None:
            patches.add(patch)

    patches.update(tag_patches)
    return max(patches) + 1, max(codes) + 1


def plan_release(releases, tag_patches, sha: str) -> dict:
    """决定这次运行要做什么：skip / reuse / create。

    skip   同一提交已经有公开的 Release，直接结束。
    reuse  同一提交已经存在草稿（上一次运行失败留下），复用它的版本。
    create 分配新的补丁版本和 versionCode。
    """
    matches = [
        release
        for release in releases
        if (parse_metadata(release.get("body")) or {}).get("sha") == sha
    ]
    published = [release for release in matches if not release.get("draft")]
    if published:
        release = max(published, key=lambda item: item.get("id") or 0)
        metadata = parse_metadata(release.get("body")) or {}
        return {
            "action": "skip",
            "reason": f"提交 {sha[:12]} 已经发布了 {release.get('tag_name')}",
            "release_id": release.get("id"),
            "tag": release.get("tag_name"),
            "version_name": metadata.get("versionName"),
            "version_code": metadata.get("versionCode"),
        }

    if matches:
        release = max(matches, key=lambda item: item.get("id") or 0)
        metadata = parse_metadata(release.get("body")) or {}
        return {
            "action": "reuse",
            "reason": f"复用提交 {sha[:12]} 已存在的草稿 {release.get('tag_name')}",
            "release_id": release.get("id"),
            "tag": release.get("tag_name"),
            "version_name": metadata.get("versionName"),
            "version_code": metadata.get("versionCode"),
        }

    patch, version_code = next_version(releases, tag_patches)
    version_name = f"1.0.{patch}"
    return {
        "action": "create",
        "reason": f"分配新版本 {version_name}",
        "release_id": None,
        "tag": f"v{version_name}",
        "version_name": version_name,
        "version_code": version_code,
    }


def build_release_body(metadata: dict) -> str:
    version_name = metadata["versionName"]
    version_code = metadata["versionCode"]
    apk_name = apk_file_name(version_name)
    lines = [
        f"Cashier Helper v{version_name}（versionCode {version_code}）",
        "",
        "推送到 `main` 后由 GitHub Actions 自动构建并签名，可直接安装。",
        "",
        "| 附件 | 说明 |",
        "| --- | --- |",
        f"| `{apk_name}` | 可直接安装的 Release APK。 |",
        f"| `{CHECKSUM_FILE}` | 附件校验和，可用 `sha256sum -c {CHECKSUM_FILE}` 校验。 |",
        f"| `{MAPPING_FILE}` | 本版本的混淆映射，用于还原崩溃堆栈。 |",
        f"| `{METADATA_FILE}` | 构建提交、运行 ID 与版本信息。 |",
        "",
        "如果设备上已安装的 Cashier Helper 来自其他签名密钥，需要先卸载再安装本版本。",
        "",
        METADATA_MARKER,
        json.dumps(metadata, ensure_ascii=False, sort_keys=True),
        METADATA_MARKER_END,
        "",
    ]
    return "\n".join(lines)


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def write_checksums(directory: Path) -> Path:
    entries = []
    for path in sorted(directory.iterdir()):
        if not path.is_file() or path.name == CHECKSUM_FILE:
            continue
        entries.append(f"{file_sha256(path)}  {path.name}")
    target = directory / CHECKSUM_FILE
    target.write_text("\n".join(entries) + "\n", encoding="utf-8")
    return target


def read_checksums(directory: Path) -> dict:
    target = directory / CHECKSUM_FILE
    if not target.is_file():
        fail(f"附件目录缺少 {CHECKSUM_FILE}")
    expected = {}
    for line in target.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        digest, _, name = line.partition("  ")
        if not name:
            fail(f"{CHECKSUM_FILE} 行格式无法解析：{line}")
        expected[name] = digest
    return expected


def verify_checksums(directory: Path) -> None:
    expected = read_checksums(directory)
    actual = {
        path.name: file_sha256(path)
        for path in sorted(directory.iterdir())
        if path.is_file() and path.name != CHECKSUM_FILE
    }
    if set(expected) != set(actual):
        fail(
            f"{CHECKSUM_FILE} 与目录内容不一致："
            f"记录 {sorted(expected)}，实际 {sorted(actual)}"
        )
    for name, digest in sorted(expected.items()):
        if actual[name] != digest:
            fail(f"{name} 的校验和不匹配，附件可能已损坏")
    note(f"校验通过：{len(actual)} 个附件")


# --------------------------------------------------------------------------
# GitHub REST API
# --------------------------------------------------------------------------


def api_base() -> str:
    return os.environ.get("GITHUB_API_URL", "https://api.github.com").rstrip("/")


def repo_slug() -> str:
    slug = os.environ.get("GITHUB_REPOSITORY")
    if not slug:
        fail("缺少 GITHUB_REPOSITORY 环境变量")
    return slug


def require_token() -> str:
    value = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not value:
        fail("缺少 GH_TOKEN 或 GITHUB_TOKEN 环境变量")
    return value


def decode(payload: bytes) -> str:
    return payload.decode("utf-8", "replace")


def request(
    method: str,
    url: str,
    *,
    data: "bytes | None" = None,
    content_type: "str | None" = None,
    expected=(200,),
    attempts: "int | None" = None,
):
    """调用 GitHub API，expected 中的状态码返回 None，5xx/429 自动重试。"""
    attempts = attempts or RETRY_ATTEMPTS
    last_error = "未知错误"
    for attempt in range(1, attempts + 1):
        request_object = urllib.request.Request(url, data=data, method=method)
        request_object.add_header("Authorization", f"Bearer {require_token()}")
        request_object.add_header("Accept", "application/vnd.github+json")
        request_object.add_header("X-GitHub-Api-Version", "2022-11-28")
        request_object.add_header("User-Agent", "cashierhelper-release")
        if content_type:
            request_object.add_header("Content-Type", content_type)
        try:
            with urllib.request.urlopen(request_object, timeout=120) as response:
                payload = response.read()
                status = response.status
        except urllib.error.HTTPError as error:
            payload = error.read()
            status = error.code
            if status in expected:
                return None
            last_error = f"HTTP {status}: {decode(payload)[:400]}"
            if status in RETRY_STATUS and attempt < attempts:
                time.sleep(RETRY_BASE_DELAY * attempt)
                continue
            fail(f"{method} {url} 失败，{last_error}")
        except urllib.error.URLError as error:
            last_error = str(getattr(error, "reason", error))
            if attempt < attempts:
                time.sleep(RETRY_BASE_DELAY * attempt)
                continue
            fail(f"{method} {url} 网络错误：{last_error}")

        if status not in expected:
            fail(f"{method} {url} 返回未预期的状态码 {status}：{decode(payload)[:400]}")
        if status == 204 or not payload:
            return None
        try:
            return json.loads(decode(payload))
        except json.JSONDecodeError:
            fail(f"{method} {url} 返回的响应不是合法 JSON")
    fail(f"{method} {url} 重试耗尽：{last_error}")


def list_releases() -> list:
    """列出全部 Release，包含草稿，按页取完。"""
    slug = repo_slug()
    releases: list = []
    page = 1
    while True:
        chunk = request(
            "GET",
            f"{api_base()}/repos/{slug}/releases?per_page=100&page={page}",
            expected=(200,),
        )
        if not chunk:
            return releases
        releases.extend(chunk)
        if len(chunk) < 100:
            return releases
        page += 1


def list_tag_patches() -> set:
    """列出已有的 v1.0.* tag，返回补丁号集合。"""
    slug = repo_slug()
    refs = request(
        "GET",
        f"{api_base()}/repos/{slug}/git/matching-refs/tags/{TAG_PREFIX}",
        expected=(200, 404),
    )
    patches = set()
    for ref in refs or []:
        tag = str(ref.get("ref", "")).rsplit("/", 1)[-1]
        patch = patch_from_tag(tag)
        if patch is not None:
            patches.add(patch)
    return patches


def ensure_tag(tag: str, sha: str) -> None:
    """保证 refs/tags/<tag> 存在并指向 sha。"""
    slug = repo_slug()
    path = f"{api_base()}/repos/{slug}/git/ref/tags/{tag}"
    ref = request("GET", path, expected=(200, 404))
    if ref is None:
        note(f"创建 tag {tag} -> {sha[:12]}")
        request(
            "POST",
            f"{api_base()}/repos/{slug}/git/refs",
            data=json.dumps({"ref": f"refs/tags/{tag}", "sha": sha}).encode("utf-8"),
            content_type="application/json",
            expected=(201,),
        )
        ref = request("GET", path, expected=(200,))
    object_sha = ((ref or {}).get("object") or {}).get("sha")
    if object_sha != sha:
        fail(f"tag {tag} 已指向 {object_sha}，与本次构建的 {sha} 不一致，需要人工处理")


def upload_asset(release: dict, path: Path) -> None:
    upload_url = str(release.get("upload_url", "")).split("{", 1)[0]
    if not upload_url:
        fail("Release 响应缺少 upload_url")
    query = urllib.parse.urlencode({"name": path.name})
    with open(path, "rb") as handle:
        payload = handle.read()
    request(
        "POST",
        f"{upload_url}?{query}",
        data=payload,
        content_type="application/octet-stream",
        expected=(201,),
    )
    note(f"上传附件 {path.name}（{len(payload)} 字节）")


def list_assets(release_id: int) -> list:
    slug = repo_slug()
    assets = request(
        "GET",
        f"{api_base()}/repos/{slug}/releases/{release_id}/assets?per_page=100",
        expected=(200,),
    )
    return assets or []


def delete_asset(asset_id: int) -> None:
    slug = repo_slug()
    request(
        "DELETE",
        f"{api_base()}/repos/{slug}/releases/assets/{asset_id}",
        expected=(204,),
    )


def is_highest_release(releases, release_id, version_name: str) -> bool:
    """判断 version_name 是否是当前最高的 1.0.* 版本。"""
    mine = patch_from_version_name(version_name)
    if mine is None:
        return False
    for release in releases:
        if release.get("id") == release_id:
            continue
        metadata = parse_metadata(release.get("body")) or {}
        other = patch_from_version_name(metadata.get("versionName"))
        if other is None:
            other = patch_from_tag(release.get("tag_name"))
        if other is not None and other > mine:
            return False
    return True


# --------------------------------------------------------------------------
# 环境输出
# --------------------------------------------------------------------------


def write_outputs(values: dict) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as handle:
        for key in sorted(values):
            handle.write(f"{key}={values[key]}\n")


def write_summary(lines) -> None:
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")


# --------------------------------------------------------------------------
# 子命令
# --------------------------------------------------------------------------


def cmd_prepare(args) -> int:
    releases = list_releases()
    plan = plan_release(releases, list_tag_patches(), args.sha)
    note(plan["reason"])

    if plan["action"] == "create":
        metadata = {
            "sha": args.sha,
            "runId": str(args.run_id),
            "runAttempt": str(args.run_attempt),
            "versionName": plan["version_name"],
            "versionCode": plan["version_code"],
        }
        release = request(
            "POST",
            f"{api_base()}/repos/{repo_slug()}/releases",
            data=json.dumps(
                {
                    "tag_name": plan["tag"],
                    "target_commitish": args.sha,
                    "name": f"Cashier Helper {plan['tag']}",
                    "body": build_release_body(metadata),
                    "draft": True,
                    "prerelease": False,
                }
            ).encode("utf-8"),
            content_type="application/json",
            expected=(201,),
        )
        plan["release_id"] = release["id"]
        note(f"创建草稿 Release {plan['tag']}（{plan['version_name']}）")

    if plan["action"] in {"create", "reuse"}:
        ensure_tag(plan["tag"], args.sha)

    skip = "true" if plan["action"] == "skip" else "false"
    write_outputs(
        {
            "skip": skip,
            "reason": plan["reason"],
            "release_id": plan["release_id"] or "",
            "tag": plan["tag"] or "",
            "version_name": plan["version_name"] or "",
            "version_code": plan["version_code"] or "",
        }
    )

    title = "无需发布" if plan["action"] == "skip" else f"准备发布 {plan['version_name']}"
    write_summary(
        [
            f"### {title}",
            "",
            f"- 动作：`{plan['action']}`",
            f"- 提交：`{args.sha}`",
            f"- 版本：`{plan['version_name']}`（versionCode {plan['version_code']}）",
            f"- tag：`{plan['tag']}`",
            f"- 说明：{plan['reason']}",
        ]
    )
    return 0


def cmd_stage_assets(args) -> int:
    directory = Path(args.out_dir)
    if directory.exists():
        shutil.rmtree(directory)
    directory.mkdir(parents=True)

    apk_source = Path(args.apk)
    if not apk_source.is_file():
        fail(f"找不到 APK：{apk_source}")
    mapping_source = Path(args.mapping)
    if not mapping_source.is_file():
        fail(f"找不到混淆映射：{mapping_source}")

    apk_name = apk_file_name(args.version_name)
    shutil.copyfile(apk_source, directory / apk_name)
    shutil.copyfile(mapping_source, directory / MAPPING_FILE)

    metadata = {
        "apkFile": apk_name,
        "repository": repo_slug(),
        "runAttempt": str(args.run_attempt),
        "runId": str(args.run_id),
        "sha": args.sha,
        "versionCode": int(args.version_code),
        "versionName": args.version_name,
        "workflowRunUrl": args.run_url,
    }
    (directory / METADATA_FILE).write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    write_checksums(directory)
    note(f"生成附件目录 {directory}：{sorted(path.name for path in directory.iterdir())}")
    return 0


def cmd_publish(args) -> int:
    directory = Path(args.dir)
    if not directory.is_dir():
        fail(f"附件目录不存在：{directory}")
    verify_checksums(directory)

    release = request(
        "GET",
        f"{api_base()}/repos/{repo_slug()}/releases/{args.release_id}",
        expected=(200,),
    )
    if not release.get("draft"):
        note(f"{release.get('tag_name')} 已经是公开状态，无需重复发布")
        return 0

    metadata = parse_metadata(release.get("body")) or {}
    if metadata.get("sha") != args.sha:
        fail(
            f"草稿 {release.get('tag_name')} 记录的提交是 {metadata.get('sha')}，"
            f"与本次构建的 {args.sha} 不一致"
        )
    ensure_tag(str(release.get("tag_name")), args.sha)

    existing = {asset["name"]: asset for asset in list_assets(args.release_id)}
    for path in sorted(directory.iterdir()):
        if not path.is_file():
            continue
        previous = existing.get(path.name)
        if previous is not None:
            note(f"覆盖已有附件 {path.name}")
            delete_asset(previous["id"])
        upload_asset(release, path)

    make_latest = "true" if is_highest_release(list_releases(), release.get("id"), metadata.get("versionName")) else "false"
    request(
        "PATCH",
        f"{api_base()}/repos/{repo_slug()}/releases/{args.release_id}",
        data=json.dumps({"draft": False, "make_latest": make_latest}).encode("utf-8"),
        content_type="application/json",
        expected=(200,),
    )
    note(f"已公开 {release.get('tag_name')}（make_latest={make_latest}）")
    write_summary(
        [
            f"### 已发布 {release.get('tag_name')}",
            "",
            f"- 版本：`{metadata.get('versionName')}`（versionCode {metadata.get('versionCode')}）",
            f"- 提交：`{args.sha}`",
            f"- 附件：{sorted(path.name for path in directory.iterdir())}",
        ]
    )
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="release.py", description="Cashier Helper 发布工具")
    subparsers = parser.add_subparsers(dest="command", required=True)

    prepare = subparsers.add_parser("prepare", help="分配或复用版本并创建草稿 Release")
    prepare.add_argument("--sha", required=True)
    prepare.add_argument("--run-id", required=True)
    prepare.add_argument("--run-attempt", default="1")
    prepare.set_defaults(func=cmd_prepare)

    stage = subparsers.add_parser("stage-assets", help="生成发布附件目录")
    stage.add_argument("--apk", required=True)
    stage.add_argument("--mapping", required=True)
    stage.add_argument("--out-dir", required=True)
    stage.add_argument("--version-name", required=True)
    stage.add_argument("--version-code", required=True)
    stage.add_argument("--sha", required=True)
    stage.add_argument("--run-id", required=True)
    stage.add_argument("--run-attempt", default="1")
    stage.add_argument("--run-url", default="")
    stage.set_defaults(func=cmd_stage_assets)

    publish = subparsers.add_parser("publish", help="上传附件并公开草稿 Release")
    publish.add_argument("--release-id", required=True)
    publish.add_argument("--dir", required=True)
    publish.add_argument("--sha", required=True)
    publish.set_defaults(func=cmd_publish)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
