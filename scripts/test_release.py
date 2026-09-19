#!/usr/bin/env python3
"""scripts/release.py 的行为测试。

用本地假 GitHub API 覆盖版本分配、草稿复用、发布重跑和附件上传，无需网络。
"""

from __future__ import annotations

import http.server
import json
import os
import re
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.parse
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import release  # noqa: E402

SLUG = "example/CashierHelper"
SHA_A = "a" * 40
SHA_B = "b" * 40

EXPECTED_ASSETS = [
    "CashierHelper-v1.0.2.apk",
    "SHA256SUMS",
    "mapping.txt",
    "release-metadata.json",
]


class FakeGitHub:
    """最小可用的 GitHub Releases / Refs API 替身。"""

    def __init__(self):
        self.releases: list = []
        self.refs: "dict[str, str]" = {}
        self.assets: "dict[int, dict]" = {}
        self.upload_failures: "dict[str, int]" = {}
        self.make_latest: "dict[int, str]" = {}
        self.next_release_id = 1
        self.next_asset_id = 1
        self.base = "http://127.0.0.1"
        self.log: list = []

    # -- 测试辅助 ---------------------------------------------------------

    def release_by_id(self, release_id: int):
        for release in self.releases:
            if release["id"] == release_id:
                return release
        return None

    def create_release(self, payload: dict):
        tag = payload["tag_name"]
        if any(release["tag_name"] == tag for release in self.releases):
            return None
        release_id = self.next_release_id
        self.next_release_id += 1
        release = {
            "id": release_id,
            "tag_name": tag,
            "name": payload.get("name", tag),
            "body": payload.get("body", ""),
            "draft": bool(payload.get("draft", False)),
            "prerelease": bool(payload.get("prerelease", False)),
            "upload_url": f"{self.base}/uploads/repos/{SLUG}/releases/{release_id}/assets{{?name,label}}",
        }
        self.releases.append(release)
        self.assets[release_id] = {}
        return release

    def asset_list(self, release_id: int) -> list:
        return [
            {"id": asset_id, "name": asset["name"]}
            for asset_id, asset in sorted(self.assets.get(release_id, {}).items())
        ]

    def add_asset(self, release_id: int, name: str, data: bytes):
        existing = self.assets.setdefault(release_id, {})
        if any(asset["name"] == name for asset in existing.values()):
            return None
        asset_id = self.next_asset_id
        self.next_asset_id += 1
        existing[asset_id] = {"name": name, "data": data}
        return {"id": asset_id, "name": name}

    def drop_asset(self, asset_id: int):
        for assets in self.assets.values():
            if asset_id in assets:
                del assets[asset_id]
                return True
        return False


class _Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    @property
    def state(self) -> FakeGitHub:
        return self.server.state  # type: ignore[attr-defined]

    def log_message(self, *args):  # 保持测试输出干净
        return

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        return json.loads(raw) if raw else {}

    def _send(self, status: int, payload=None) -> None:
        body = json.dumps(payload).encode("utf-8") if payload is not None else b""
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _split(self):
        parsed = urllib.parse.urlparse(self.path)
        return parsed.path, urllib.parse.parse_qs(parsed.query)

    def do_GET(self):
        path, query = self._split()
        state = self.state
        state.log.append(("GET", path))

        if path == f"/repos/{SLUG}/releases":
            page = int((query.get("page") or ["1"])[0])
            self._send(200, state.releases if page == 1 else [])
            return

        matched = re.fullmatch(rf"/repos/{SLUG}/releases/(\d+)", path)
        if matched:
            release = state.release_by_id(int(matched.group(1)))
            self._send(200, release) if release else self._send(404, {"message": "Not Found"})
            return

        matched = re.fullmatch(rf"/repos/{SLUG}/releases/(\d+)/assets", path)
        if matched:
            self._send(200, state.asset_list(int(matched.group(1))))
            return

        matched = re.fullmatch(rf"/repos/{SLUG}/git/matching-refs/tags/(.+)", path)
        if matched:
            prefix = matched.group(1)
            refs = [
                {"ref": f"refs/tags/{tag}", "object": {"sha": sha, "type": "commit"}}
                for tag, sha in sorted(state.refs.items())
                if tag.startswith(prefix)
            ]
            self._send(200, refs)
            return

        matched = re.fullmatch(rf"/repos/{SLUG}/git/ref/tags/(.+)", path)
        if matched:
            tag = matched.group(1)
            sha = state.refs.get(tag)
            if sha is None:
                self._send(404, {"message": "Not Found"})
            else:
                self._send(200, {"ref": f"refs/tags/{tag}", "object": {"sha": sha, "type": "commit"}})
            return

        self._send(404, {"message": "Not Found"})

    def do_POST(self):
        path, query = self._split()
        state = self.state
        state.log.append(("POST", path))

        if path == f"/repos/{SLUG}/releases":
            release = state.create_release(self._read_json())
            if release is None:
                self._send(422, {"message": "Validation Failed"})
            else:
                self._send(201, release)
            return

        matched = re.fullmatch(rf"/uploads/repos/{SLUG}/releases/(\d+)/assets", path)
        if matched:
            release_id = int(matched.group(1))
            name = (query.get("name") or [""])[0]
            length = int(self.headers.get("Content-Length") or 0)
            data = self.rfile.read(length) if length else b""
            if state.upload_failures.get(name, 0) > 0:
                state.upload_failures[name] -= 1
                self._send(500, {"message": "Server Error"})
                return
            asset = state.add_asset(release_id, name, data)
            if asset is None:
                self._send(422, {"message": "already_exists"})
            else:
                self._send(201, asset)
            return

        if path == f"/repos/{SLUG}/git/refs":
            payload = self._read_json()
            tag = str(payload.get("ref", "")).rsplit("/", 1)[-1]
            if tag in state.refs:
                self._send(422, {"message": "Reference already exists"})
            else:
                state.refs[tag] = payload.get("sha", "")
                self._send(201, {"ref": payload.get("ref"), "object": {"sha": payload.get("sha"), "type": "commit"}})
            return

        self._send(404, {"message": "Not Found"})

    def do_PATCH(self):
        path, _ = self._split()
        state = self.state
        state.log.append(("PATCH", path))
        matched = re.fullmatch(rf"/repos/{SLUG}/releases/(\d+)", path)
        if matched:
            release_id = int(matched.group(1))
            release = state.release_by_id(release_id)
            if release is None:
                self._send(404, {"message": "Not Found"})
                return
            payload = self._read_json()
            if "draft" in payload:
                release["draft"] = bool(payload["draft"])
            if "make_latest" in payload:
                state.make_latest[release_id] = payload["make_latest"]
            self._send(200, release)
            return
        self._send(404, {"message": "Not Found"})

    def do_DELETE(self):
        path, _ = self._split()
        state = self.state
        state.log.append(("DELETE", path))
        matched = re.fullmatch(rf"/repos/{SLUG}/releases/assets/(\d+)", path)
        if matched:
            state.drop_asset(int(matched.group(1)))
            self._send(204)
            return
        self._send(404, {"message": "Not Found"})


class ReleaseTestCase(unittest.TestCase):
    def setUp(self):
        self.state = FakeGitHub()
        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        self.httpd.state = self.state  # type: ignore[attr-defined]
        self.state.base = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.httpd.server_close)
        self.addCleanup(self.httpd.shutdown)

        self.tempdir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tempdir, ignore_errors=True)
        self.output_file = self.tempdir / "github-output.txt"
        self.assets_dir = self.tempdir / "release-assets"

        environment = {
            "GITHUB_REPOSITORY": SLUG,
            "GH_TOKEN": "test-token",
            "GITHUB_API_URL": self.state.base,
            "GITHUB_OUTPUT": str(self.output_file),
            "GITHUB_STEP_SUMMARY": str(self.tempdir / "summary.md"),
            "PYTHONUTF8": "1",
        }
        patcher = mock.patch.dict(os.environ, environment)
        patcher.start()
        self.addCleanup(patcher.stop)
        delay = mock.patch.object(release, "RETRY_BASE_DELAY", 0)
        delay.start()
        self.addCleanup(delay.stop)

    # -- 辅助 -------------------------------------------------------------

    def run_script(self, *argv) -> int:
        return release.main(list(argv))

    def outputs(self) -> dict:
        values: dict = {}
        if not self.output_file.exists():
            return values
        for line in self.output_file.read_text(encoding="utf-8").splitlines():
            key, _, value = line.partition("=")
            values[key] = value
        return values

    def prepare(self, sha: str = SHA_A, run_id: str = "1") -> dict:
        self.run_script("prepare", "--sha", sha, "--run-id", run_id)
        return self.outputs()

    def seed_release(self, version_name: str, version_code: int, sha: str = SHA_B, draft: bool = False):
        metadata = {
            "sha": sha,
            "runId": "1",
            "runAttempt": "1",
            "versionName": version_name,
            "versionCode": version_code,
        }
        return self.state.create_release(
            {
                "tag_name": f"v{version_name}",
                "name": f"Cashier Helper v{version_name}",
                "body": release.build_release_body(metadata),
                "draft": draft,
                "prerelease": False,
            }
        )

    def stage_assets(self, outputs: dict, sha: str = SHA_A, run_id: str = "1") -> None:
        apk = self.tempdir / "app-release.apk"
        apk.write_bytes(b"fake-apk-bytes")
        mapping = self.tempdir / "mapping.txt"
        mapping.write_text("pro.xiangyu.cashierhelper -> a:\n", encoding="utf-8")
        self.run_script(
            "stage-assets",
            "--apk",
            str(apk),
            "--mapping",
            str(mapping),
            "--out-dir",
            str(self.assets_dir),
            "--version-name",
            outputs["version_name"],
            "--version-code",
            outputs["version_code"],
            "--sha",
            sha,
            "--run-id",
            run_id,
            "--run-url",
            f"https://example.invalid/runs/{run_id}",
        )

    def publish(self, outputs: dict, sha: str = SHA_A) -> int:
        return self.run_script(
            "publish",
            "--release-id",
            outputs["release_id"],
            "--dir",
            str(self.assets_dir),
            "--sha",
            sha,
        )

    def publish_flow(self, sha: str = SHA_A, run_id: str = "1") -> dict:
        outputs = self.prepare(sha, run_id)
        self.stage_assets(outputs, sha, run_id)
        self.publish(outputs, sha)
        return outputs


class VersionPlanTest(ReleaseTestCase):
    def test_first_release_starts_at_1_0_2(self):
        outputs = self.prepare()
        self.assertEqual(outputs["skip"], "false")
        self.assertEqual(outputs["version_name"], "1.0.2")
        self.assertEqual(outputs["version_code"], "3")
        self.assertEqual(outputs["tag"], "v1.0.2")
        self.assertEqual(self.state.refs["v1.0.2"], SHA_A)
        self.assertEqual(len(self.state.releases), 1)
        self.assertTrue(self.state.releases[0]["draft"])

    def test_published_releases_advance_the_version(self):
        self.seed_release("1.0.2", 3)
        self.seed_release("1.0.3", 4)
        outputs = self.prepare()
        self.assertEqual(outputs["version_name"], "1.0.4")
        self.assertEqual(outputs["version_code"], "5")

    def test_other_commit_draft_still_consumes_a_version(self):
        self.seed_release("1.0.2", 3, sha=SHA_B, draft=True)
        outputs = self.prepare()
        self.assertEqual(outputs["version_name"], "1.0.3")
        self.assertEqual(outputs["version_code"], "4")

    def test_tag_without_release_consumes_a_version(self):
        self.state.refs["v1.0.7"] = SHA_B
        outputs = self.prepare()
        self.assertEqual(outputs["version_name"], "1.0.8")
        self.assertEqual(outputs["version_code"], "3")

    def test_unrelated_releases_and_tags_are_ignored(self):
        self.state.releases.append(
            {
                "id": 99,
                "tag_name": "nightly-2024",
                "body": "no metadata here",
                "draft": False,
            }
        )
        self.state.refs["nightly"] = SHA_B
        outputs = self.prepare()
        self.assertEqual(outputs["version_name"], "1.0.2")
        self.assertEqual(outputs["version_code"], "3")

    def test_same_commit_draft_is_reused(self):
        first = self.prepare(run_id="1")
        second = self.prepare(run_id="2")
        self.assertEqual(second["skip"], "false")
        self.assertEqual(second["version_name"], "1.0.2")
        self.assertEqual(second["release_id"], first["release_id"])
        self.assertEqual(len(self.state.releases), 1)

    def test_published_commit_is_skipped(self):
        self.publish_flow()
        outputs = self.prepare(run_id="2")
        self.assertEqual(outputs["skip"], "true")
        self.assertEqual(outputs["version_name"], "1.0.2")
        self.assertEqual(len(self.state.releases), 1)

    def test_tag_pointing_at_another_commit_is_rejected(self):
        self.state.refs["v1.0.9"] = SHA_B
        with self.assertRaises(SystemExit):
            release.ensure_tag("v1.0.9", SHA_A)


class PublishTest(ReleaseTestCase):
    def test_publish_uploads_every_asset_and_opens_the_release(self):
        outputs = self.publish_flow()
        release_id = int(outputs["release_id"])
        names = sorted(asset["name"] for asset in self.state.asset_list(release_id))
        self.assertEqual(names, EXPECTED_ASSETS)
        self.assertFalse(self.state.release_by_id(release_id)["draft"])
        self.assertEqual(self.state.make_latest[release_id], "true")
        self.assertEqual(self.state.refs["v1.0.2"], SHA_A)
        self.assertIn("CashierHelper-v1.0.2.apk", [path.name for path in self.assets_dir.iterdir()])

    def test_publishing_twice_is_a_no_op(self):
        outputs = self.publish_flow()
        release_id = int(outputs["release_id"])
        before = self.state.asset_list(release_id)
        self.assertEqual(self.publish(outputs), 0)
        self.assertEqual(self.state.asset_list(release_id), before)

    def test_partially_uploaded_assets_are_replaced(self):
        outputs = self.prepare()
        release_id = int(outputs["release_id"])
        self.state.add_asset(release_id, "mapping.txt", b"stale-mapping")
        self.stage_assets(outputs)
        self.publish(outputs)
        names = sorted(asset["name"] for asset in self.state.asset_list(release_id))
        self.assertEqual(names, EXPECTED_ASSETS)
        stored = {asset["name"]: asset for asset in self.state.assets[release_id].values()}
        self.assertEqual(stored["mapping.txt"]["data"], (self.tempdir / "mapping.txt").read_bytes())

    def test_interrupted_upload_is_retried(self):
        outputs = self.prepare()
        self.state.upload_failures["release-metadata.json"] = 1
        self.stage_assets(outputs)
        self.assertEqual(self.publish(outputs), 0)
        names = sorted(asset["name"] for asset in self.state.asset_list(int(outputs["release_id"])))
        self.assertEqual(names, EXPECTED_ASSETS)

    def test_tampered_assets_are_refused(self):
        outputs = self.prepare()
        self.stage_assets(outputs)
        (self.assets_dir / "mapping.txt").write_text("tampered", encoding="utf-8")
        with self.assertRaises(SystemExit):
            self.publish(outputs)
        self.assertTrue(self.state.release_by_id(int(outputs["release_id"]))["draft"])

    def test_publish_refuses_a_different_commit(self):
        outputs = self.prepare()
        self.stage_assets(outputs)
        with self.assertRaises(SystemExit):
            self.publish(outputs, sha=SHA_B)
        self.assertTrue(self.state.release_by_id(int(outputs["release_id"]))["draft"])

    def test_recovered_older_draft_does_not_become_latest(self):
        self.seed_release("1.0.3", 4, sha=SHA_B)
        self.seed_release("1.0.2", 3, sha=SHA_A, draft=True)
        outputs = self.prepare()
        self.assertEqual(outputs["version_name"], "1.0.2")
        self.stage_assets(outputs)
        self.publish(outputs)
        self.assertEqual(self.state.make_latest[int(outputs["release_id"])], "false")

    def test_checksums_are_generated_for_every_asset(self):
        outputs = self.prepare()
        self.stage_assets(outputs)
        recorded = release.read_checksums(self.assets_dir)
        self.assertEqual(sorted(recorded), sorted(name for name in EXPECTED_ASSETS if name != "SHA256SUMS"))
        for name, digest in recorded.items():
            self.assertEqual(release.file_sha256(self.assets_dir / name), digest)


class PureLogicTest(unittest.TestCase):
    def test_metadata_round_trip(self):
        metadata = {
            "sha": SHA_A,
            "runId": "42",
            "runAttempt": "2",
            "versionName": "1.0.5",
            "versionCode": 6,
        }
        body = release.build_release_body(metadata)
        self.assertEqual(release.parse_metadata(body), metadata)
        self.assertIsNone(release.parse_metadata("没有元数据的正文"))
        self.assertIsNone(release.parse_metadata(None))
        self.assertIsNone(release.parse_metadata(release.METADATA_MARKER + " {broken"))

    def test_patch_parsing(self):
        self.assertEqual(release.patch_from_version_name("1.0.10"), 10)
        self.assertIsNone(release.patch_from_version_name("1.1.0"))
        self.assertIsNone(release.patch_from_version_name("1.0."))
        self.assertEqual(release.patch_from_tag("v1.0.4"), 4)
        self.assertIsNone(release.patch_from_tag("1.0.4"))

    def test_next_version_uses_the_highest_record_of_each_kind(self):
        releases = [
            {"tag_name": "v1.0.5", "body": release.build_release_body({"versionName": "1.0.5", "versionCode": 9})},
            {"tag_name": "v1.0.9", "body": "无元数据"},
        ]
        self.assertEqual(release.next_version(releases, set()), (10, 10))

    def test_next_version_starts_from_the_baseline(self):
        self.assertEqual(release.next_version([], set()), (2, 3))

    def test_plan_reports_skip_for_a_published_commit(self):
        releases = [
            {
                "id": 3,
                "tag_name": "v1.0.2",
                "draft": False,
                "body": release.build_release_body({"sha": SHA_A, "versionName": "1.0.2", "versionCode": 3}),
            }
        ]
        plan = release.plan_release(releases, set(), SHA_A)
        self.assertEqual(plan["action"], "skip")
        self.assertEqual(plan["version_name"], "1.0.2")

    def test_plan_ignores_deleted_draft_shapes(self):
        plan = release.plan_release([], set(), SHA_A)
        self.assertEqual(plan["action"], "create")
        self.assertEqual((plan["version_name"], plan["version_code"]), ("1.0.2", 3))

    def test_checksum_verification_rejects_missing_files(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            (directory / "a.txt").write_text("a", encoding="utf-8")
            (directory / "b.txt").write_text("b", encoding="utf-8")
            release.write_checksums(directory)
            (directory / "b.txt").unlink()
            with self.assertRaises(SystemExit):
                release.verify_checksums(directory)


if __name__ == "__main__":
    unittest.main(verbosity=2)
