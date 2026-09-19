# Cashier Helper

Cashier Helper 是一个面向 Android 11 及以上设备的原生截图提交工具。它不显示账单；用户主动触发后，应用截取当前屏幕并调用 Cashier 的最小 v1 接口。

## 配置与使用

1. 安装 APK，首次打开后填写 Cashier 的 HTTPS Base URL 和 Service Credential。
2. 在设置页打开“截图服务”，在 Android 无障碍设置中启用 `Cashier Helper 截图服务`。
3. 允许结果通知。
4. Samsung S24：进入“设置 → 高级功能 → 侧键 → 双击 → 打开应用”，选择 Cashier Helper。
5. 在需要记账的页面双击侧键。截图完成时会短震确认；提交成功后会先通知已受理，再在分析完成、异常、失败、取消或查询超时时通知结果。提交失败时 JPEG 会保存到 `Pictures/CashierHelper`。

配置完成后，点击应用图标也会直接触发截图。长按桌面图标并选择“设置”可重新修改连接信息。

银行、密码管理器等使用安全窗口的应用会被 Android 拒绝截图。透明启动窗口是否完全不影响画面需要在目标 One UI 版本上实机确认。

## 请求契约

```http
POST {BASE_URL}/api/v1/source-documents
Authorization: Bearer {API_KEY}
Content-Type: application/json
Idempotency-Key: {每次提交生成的 UUID}
```

```json
{
  "images": [
    {
      "data": "<raw JPEG Base64>",
      "mimeType": "image/jpeg"
    }
  ]
}
```

客户端不发送日期或图片哈希，也不会自动重试 POST。POST 和后续 GET 使用不同的响应契约：只有 `201` POST 响应同时包含非空 `sourceDocumentId` 和 `revisionState: "processing"` 时才视为提交成功。

提交成功后，客户端从第 2 秒开始每 2 秒查询：

```http
GET {BASE_URL}/api/v1/source-documents/{sourceDocumentId}
Authorization: Bearer {API_KEY}
```

GET 响应通过顶层 `status` 表示 `processing`、`completed`、`anomaly`、`failed` 或 `cancelled`。完成后的消费明细位于 `result.entries`，异常和失败的错误码位于 `error.code`。

查询最长持续 1 分钟。`processing`、临时网络错误、`429` 和 `5xx` 会继续查询；认证失败、文档不存在或响应格式错误会立即停止。不同截图的分析查询相互独立，不阻止继续提交截图，也不会互相覆盖结果通知。应用进程重启后不会恢复尚未完成的查询。

`completed` 通知会按原币种和类别汇总服务端返回的消费明细，不跨币种相加；`anomaly` 和 `failed` 会显示服务端错误码，`cancelled` 会显示分析已取消。

## 构建

项目使用 Android Studio 自带的 JDK 21 运行 Gradle，字节码目标为 Java 17：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 安全

- Release 构建禁用明文 HTTP。
- API Key 使用 Android Keystore AES-GCM 加密，本应用禁止云备份与设备迁移备份。
- API Key、截图和服务端响应正文都不会写入日志或通知。
- 曾出现在截图中的 Service Credential 应在使用本应用前撤销并重新生成。
