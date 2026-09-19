# Cashier Helper

Cashier Helper 是一个面向 Android 11（API 30）及以上设备的原生截图提交工具，用 Kotlin 与 Jetpack Compose 编写。主要验收设备是 Samsung Galaxy S24（One UI），实现只使用标准 Android 接口，因此其他厂商设备同样可用，但未逐一实测。

应用不显示账单，也不常驻后台。你主动触发后，它截取默认显示屏，把 JPEG 提交到配置好的 Cashier 服务，并把分析结果写入通知和设置页的任务列表。

## 安装与配置

1. 安装 Debug APK（见下方“构建”一节）。
2. 打开应用，填写 Cashier 的 HTTPS 服务器地址和 API Key（Service Credential），保存。
3. 打开“截图服务”，在系统无障碍设置中启用 `Cashier Helper 截图服务`。
4. 允许结果通知（Android 13+ 会在弹窗中询问；如果已拒绝，通知不可用时设置页会给出进入系统设置的入口）。
5. Samsung S24 侧键触发：`设置 → 高级功能 → 侧键 → 双击 → 打开应用`，选择 Cashier Helper。

配置完成后双击侧键即可截图提交；点击应用图标也会直接触发一次截图（冷启动时最多等待 2 秒让截图服务连接）。长按桌面图标选择“设置”可修改连接信息并查看任务列表。

## 请求契约

提交：

```http
POST {BASE_URL}/api/v1/source-documents
Authorization: Bearer {API_KEY}
Content-Type: application/json
Idempotency-Key: {任务创建时生成、重试时复用的 UUID}
```

```json
{ "images": [ { "data": "<raw JPEG Base64>", "mimeType": "image/jpeg" } ] }
```

只有 `201` 响应同时包含非空 `sourceDocumentId` 和 `revisionState: "processing"` 时才视为提交成功。客户端不发送日期或图片哈希，也不会自动重试 POST。POST 整体超时 150 秒，GET 单次超时 5 秒，并会随协程取消调用 `Call.cancel()`。

提交成功后查询：

```http
GET {BASE_URL}/api/v1/source-documents/{sourceDocumentId}
Authorization: Bearer {API_KEY}
```

GET 通过顶层 `status` 表示 `processing`、`completed`、`invalid`、`failed` 或 `cancelled`；旧版 `anomaly` 仍被识别，按“无法用于记账”处理。消费明细位于 `result.entries`，错误信息位于 `error.code` 与 `error.message`。未知状态、缺失必填字段或无法解析的 JSON 都按协议错误处理。

### 查询节奏

- 首次查询与后续查询间隔都是 5 秒，一轮查询总预算 60 秒（包含排队、退避和请求耗时）。
- 服务端返回 `Retry-After`（秒数或 HTTP 日期）时按其等待；要求的等待超过本轮剩余预算就停止本轮并保留任务。
- 同一服务器与凭据共享调度：GET 最快每 5 秒发起一次，多个任务公平轮转，上传优先于尚未发出的 GET。
- 任意请求收到 `429` 会为该配置设置共享冷却，POST 与 GET 都遵守，不会忙循环，也不会自动重发 POST。
- 一轮超时后提示“一分钟内未取得结果，可在设置中继续查询”，这不表示服务端处理失败。

`completed` 会按原币种与类别分别汇总，不跨币种相加；`invalid` 与 `failed` 显示服务端错误码，`invalid` 通知固定显示“该截图无法用于记账”，设置页任务详情可以展示去除控制字符、最多 200 字的消息，消息为空时显示错误码。

## 任务持久化与恢复

网络任务由应用级 `TaskCoordinator` 管理，不依附无障碍服务的生命周期，任务保存到 `noBackupFilesDir/pending_tasks`：

1. 截图后先生成任务 UUID 和幂等键，加密保存原始 JPEG、配置指纹与任务状态，成功落盘后才发起 POST。
2. POST 成功后先保存 `sourceDocumentId`，再删除私有缓存图片并开始查询。
3. POST 超时、断网、取消或响应无法确认结果时，任务标记为“提交结果待确认”，不会用新幂等键自动重发。
4. 应用下次运行或服务重新连接时，恢复已知 `sourceDocumentId` 的未完成查询；同一任务同时只有一个执行实例。
5. 没有 `sourceDocumentId` 的待确认任务不自动重发；在设置页点“重试原任务”，复用原图、原幂等键和原配置。
6. 查询超过一分钟后暂停；设置页“继续查询”开启新一轮 60 秒查询，不重新提交图片。

防重与保存规则：

- 后端幂等记录有效期 24 小时，客户端只允许创建后 23 小时内重试原 POST；超出窗口提示“请先在 Cashier 核对记录”，不直接重发。
- 配置指纹绑定规范化服务器地址与凭据。配置改变后旧任务暂停，禁止把旧截图发往新服务器或用新凭据重试。
- `409` 保留为待确认，不通过换键绕过冲突。
- 存在待确认提交时，新截图只会提示先处理待确认任务；已受理、正在分析的任务不阻止新截图。
- 私有任务文件使用 Android Keystore AES-GCM 加密并原子写入，不保存明文 API Key；落盘失败时不发 POST，改为尝试原有失败图片保存流程。
- 任务列表最多保留 100 条；终态记录保留 7 天，优先清理过期终态，不静默删除未完成任务；达到上限时提示清理后再提交。
- 未确认任务的私有图片最多保留 24 小时，删除图片后保留“需人工核对”状态。用户主动删除任务只清除本地记录，不代表撤销了服务端提交。
- 恢复以应用下次运行作为触发点，不增加常驻服务，也不承诺被系统强制停止后仍能执行。

## 通知

结果反馈依赖通知是否真正可见，设置页与发送端共用同一套检查：

- Android 13+ 未授予 `POST_NOTIFICATIONS` 时会请求权限。
- 应用通知总开关或 `capture_results` 渠道被关闭时，提示并打开对应系统设置；从系统设置返回后刷新状态。
- 通知不可用时保留 Toast 反馈，同时把最终结果写进设置页任务列表，不会只能靠瞬时提示获知结果。
- 分析任务使用稳定的任务标识作为通知 tag，进程重启后不会因编号变化产生重复通知。
- 通知与日志不包含凭据、截图、完整响应或完整请求头；协议错误统一显示“服务器响应格式不兼容”。

## 失败图片

上传前失败（含超过后端 3 MiB 上限被拒绝）或提交失败时，JPEG 会保存到 `Pictures/CashierHelper`（通过 MediaStore）。超过 3 MiB 会在上传前明确拒绝并保留失败图片，不归类为网络错误。

## 安全

- 只接受 HTTPS 地址；禁止用户名密码、查询参数、片段和无效端口，合法的基础路径会保留。
- API Key 去除首尾空白后只接受非空、无空白及控制字符的可打印 ASCII。
- API Key 使用 Android Keystore AES-GCM 加密；旧配置在读取和保存时都会重新校验，不合法时提示“连接配置无效，请重新保存”。
- 应用禁止云备份与设备迁移：`allowBackup=false`，并在 `data_extraction_rules.xml` 中排除 `sharedpref`、`file`、`database` 与 `root` 域。
- API Key、截图、完整响应正文与完整请求头都不写入日志或通知。
- 曾出现在截图中的 Service Credential 应先撤销并重新生成。

## 构建

项目使用 JDK 21 运行 Gradle，Kotlin 与 Java 字节码目标为 17。Linux/WSL 下：

```bash
source /home/xiangyu/.config/android-env.sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

产物：

| 产物 | 路径 | 说明 |
|---|---|---|
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` | 可直接安装验证。 |
| Release APK（未签名） | `app/build/outputs/apk/release/app-release-unsigned.apk` | 构建成功不代表可分发；仓库没有签名配置。 |

要得到可安装、可分发的 Release 包，需要自备 keystore，对上面这个未签名产物执行 `apksigner` 签名（必要时先 `zipalign`）。仓库不提供签名私钥。

## 已知限制

- 截图范围仅为默认显示屏（`DEFAULT_DISPLAY`），不支持 DeX 外接屏或其他显示器。
- 银行、密码管理器等使用安全窗口的界面会被 Android 拒绝截图，无法绕过。
- 三星电源管理、透明启动窗口残影、旋转与分屏、锁屏、省电模式等行为仍需在目标 One UI 版本上实机验证；自动化测试不能替代设备结论。
- 本仓库不包含自动更新、检查更新或更新提醒；APK 更新需要手动安装。
- 不承诺系统强制停止进程后继续执行任务，恢复只发生在应用下次运行时。

