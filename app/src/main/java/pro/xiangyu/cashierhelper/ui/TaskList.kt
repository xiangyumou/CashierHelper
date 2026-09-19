package pro.xiangyu.cashierhelper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import pro.xiangyu.cashierhelper.tasks.PendingTask
import pro.xiangyu.cashierhelper.tasks.TaskStatus
import pro.xiangyu.cashierhelper.ui.theme.MutedInk
import pro.xiangyu.cashierhelper.ui.theme.Success
import pro.xiangyu.cashierhelper.ui.theme.Warning

@Composable
fun TaskSection(
    tasks: List<PendingTask>,
    onRetryOriginal: (String) -> Unit,
    onContinueQuery: (String) -> Unit,
    onDeleteTask: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("任务", style = MaterialTheme.typography.titleLarge)
        if (tasks.isEmpty()) {
            Text(
                "暂无任务。",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedInk,
            )
            return@Column
        }
        tasks.forEach { task ->
            TaskRow(task, onRetryOriginal, onContinueQuery, onDeleteTask)
        }
    }
}

@Composable
private fun TaskRow(
    task: PendingTask,
    onRetryOriginal: (String) -> Unit,
    onContinueQuery: (String) -> Unit,
    onDeleteTask: (String) -> Unit,
) {
    val terminal = task.status.isTerminal
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (task.status == TaskStatus.COMPLETED) {
                    Icons.Outlined.CheckCircle
                } else {
                    Icons.Outlined.Warning
                },
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = when {
                    task.status == TaskStatus.COMPLETED -> Success
                    terminal -> Warning
                    else -> MaterialTheme.colorScheme.primary
                },
            )
            Text(statusLabel(task.status), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(task.createdAtMillis)),
                style = MaterialTheme.typography.bodySmall,
                color = MutedInk,
            )
        }

        detailText(task)?.let { detail ->
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            task.baseUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MutedInk,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (task.canRetryOriginal) {
                TaskActionButton("重试原任务") { onRetryOriginal(task.id) }
            }
            if (task.canContinueQuery) {
                TaskActionButton("继续查询") { onContinueQuery(task.id) }
            }
            TaskActionButton("删除") { onDeleteTask(task.id) }
        }
    }
}

@Composable
private fun TaskActionButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 44.dp),
        contentPadding = ButtonDefaults.TextButtonContentPadding,
    ) {
        Text(label)
    }
}

private fun detailText(task: PendingTask): String? = when (task.status) {
    TaskStatus.COMPLETED -> task.summary
    TaskStatus.INVALID, TaskStatus.FAILED ->
        task.errorMessage ?: task.errorCode
    TaskStatus.SUBMIT_UNCONFIRMED ->
        "提交结果未知；如已在 Cashier 看到记录，请勿重试。"
    TaskStatus.NEEDS_REVIEW ->
        "本地截图已过期，请先在 Cashier 核对记录。"
    TaskStatus.CONFIG_PAUSED ->
        "连接配置已变化，已暂停处理。"
    TaskStatus.QUERY_PAUSED ->
        "查询已暂停，可继续查询。"
    TaskStatus.ACCEPTED -> "服务器正在分析。"
    TaskStatus.PENDING_UPLOAD -> "等待提交。"
    TaskStatus.CANCELLED -> "服务器已取消本次分析。"
}

private fun statusLabel(status: TaskStatus): String = when (status) {
    TaskStatus.PENDING_UPLOAD -> "待提交"
    TaskStatus.SUBMIT_UNCONFIRMED -> "提交结果待确认"
    TaskStatus.ACCEPTED -> "正在分析"
    TaskStatus.QUERY_PAUSED -> "查询已暂停"
    TaskStatus.CONFIG_PAUSED -> "配置已变化，已暂停"
    TaskStatus.NEEDS_REVIEW -> "需人工核对"
    TaskStatus.COMPLETED -> "已完成"
    TaskStatus.INVALID -> "截图无法用于记账"
    TaskStatus.FAILED -> "分析失败"
    TaskStatus.CANCELLED -> "已取消"
}
