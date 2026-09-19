package pro.xiangyu.cashierhelper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pro.xiangyu.cashierhelper.feedback.NotificationAvailability
import pro.xiangyu.cashierhelper.tasks.PendingTask
import pro.xiangyu.cashierhelper.ui.theme.Success
import pro.xiangyu.cashierhelper.ui.theme.Warning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initialBaseUrl: String,
    initialApiKey: String,
    accessibilityEnabled: Boolean,
    serviceConnected: Boolean,
    notificationStatus: NotificationAvailability.Status,
    tasks: List<PendingTask>,
    onSave: (String, String) -> Result<String>,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenAppNotificationSettings: () -> Unit,
    onOpenChannelNotificationSettings: () -> Unit,
    onRetryOriginal: (String) -> Unit,
    onContinueQuery: (String) -> Unit,
    onDeleteTask: (String) -> Unit,
) {
    var baseUrl by rememberSaveable { mutableStateOf(initialBaseUrl) }
    var apiKey by rememberSaveable { mutableStateOf(initialApiKey) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Cashier Helper", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            "截图提交设置",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                item {
                    SettingsSection(title = "连接") {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = {
                                baseUrl = it
                                validationError = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("服务器地址") },
                            placeholder = { Text("https://cashier.example.com") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                keyboardType = KeyboardType.Uri,
                            ),
                        )
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = {
                                apiKey = it
                                validationError = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("API Key") },
                            leadingIcon = {
                                Icon(Icons.Outlined.Lock, contentDescription = null)
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                keyboardType = KeyboardType.Password,
                            ),
                            isError = validationError != null,
                            supportingText = validationError?.let { message ->
                                { Text(message) }
                            },
                        )
                        Button(
                            onClick = {
                                onSave(baseUrl, apiKey)
                                    .onSuccess { normalizedUrl ->
                                        baseUrl = normalizedUrl
                                        validationError = null
                                        scope.launch { snackbarHostState.showSnackbar("设置已保存") }
                                    }
                                    .onFailure { error ->
                                        validationError = error.message ?: "无法保存设置"
                                    }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onBackground,
                                contentColor = MaterialTheme.colorScheme.background,
                            ),
                        ) {
                            Icon(Icons.Outlined.Done, contentDescription = null)
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text("保存设置")
                        }
                    }
                }

                item {
                    HorizontalDivider(color = DividerDefaults.color)
                }

                item {
                    SettingsSection(title = "权限") {
                        PermissionRow(
                            icon = Icons.Outlined.Lock,
                            title = "无障碍权限",
                            enabled = accessibilityEnabled,
                            enabledLabel = "已开启",
                            disabledLabel = "需要开启",
                            actionLabel = "打开系统设置",
                            onAction = onOpenAccessibilitySettings,
                        )
                        PermissionRow(
                            icon = Icons.Outlined.Build,
                            title = "截图服务",
                            enabled = serviceConnected,
                            enabledLabel = "已连接",
                            disabledLabel = "未连接",
                            actionLabel = "重新连接",
                            onAction = onOpenAccessibilitySettings,
                        )
                        PermissionRow(
                            icon = Icons.Outlined.Notifications,
                            title = "结果通知",
                            enabled = notificationStatus.isVisible,
                            enabledLabel = "已开启",
                            disabledLabel = "未开启",
                            actionLabel = notificationActionLabel(notificationStatus),
                            onAction = {
                                when {
                                    !notificationStatus.permissionGranted -> onRequestNotifications()
                                    !notificationStatus.appEnabled -> onOpenAppNotificationSettings()
                                    else -> onOpenChannelNotificationSettings()
                                }
                            },
                        )
                    }
                }

                item {
                    HorizontalDivider(color = DividerDefaults.color)
                }

                item {
                    SettingsSection(title = "触发") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                Icons.Outlined.Build,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("侧键双击", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "设置 → 高级功能 → 侧键 → 双击 → 打开应用",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "选择 Cashier Helper。长按桌面图标可重新进入本页。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                item {
                    HorizontalDivider(color = DividerDefaults.color)
                }

                item {
                    TaskSection(
                        tasks = tasks,
                        onRetryOriginal = onRetryOriginal,
                        onContinueQuery = onContinueQuery,
                        onDeleteTask = onDeleteTask,
                    )
                }
            }
        }
    }
}

private fun notificationActionLabel(status: NotificationAvailability.Status): String = when {
    !status.permissionGranted -> "允许通知"
    !status.appEnabled -> "打开通知设置"
    else -> "打开渠道设置"
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        content()
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    enabled: Boolean,
    enabledLabel: String,
    disabledLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (enabled) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (enabled) Success else Warning,
                    )
                    Text(
                        if (enabled) enabledLabel else disabledLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) Success else Warning,
                    )
                }
            }
        }
        if (!enabled) {
            OutlinedButton(
                onClick = onAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ExitToApp, contentDescription = null)
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(actionLabel)
            }
        }
    }
}
