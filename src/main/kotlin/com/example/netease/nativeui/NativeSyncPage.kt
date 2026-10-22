package com.example.netease.nativeui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.netease.svc.SyncCoordinator

/**
 * 只读同步状态块（docs/PLAN.md §7.4）：直接映射 SyncCoordinator 既有 Status，
 * 不新写同步流程、不按动画时间伪造进度或百分比；「立即同步」仍走 SYNC.request。
 *
 * <p>[compact] 为 true 时用在歌单页底部（只显示两行），为 false 时是完整同步页。</p>
 */
@Composable
internal fun NativeSyncBlock(compact: Boolean) {
    val status = NativeUiState.sync
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "同步状态：" + (if (status == null) "尚未读取" else describeStatus(status)),
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                fontWeight = if (compact) FontWeight.Normal else FontWeight.SemiBold,
            )
            if (!compact) {
                outOfSyncButtons()
            }
        }
        if (NativeUiState.syncRequestNote.isNotBlank()) {
            Text(
                text = "最近一次请求：" + NativeUiState.syncRequestNote,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (!compact && status != null) {
            Text(
                text = "请求编号：" + status.requestId() + " · 阶段枚举：" + status.phase().name +
                    " · 计数：" + status.completed() + "/" + status.total(),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "说明：STARTED 只表示宿主已接收请求；BUSY 表示已有任务，不能再创建第二轮；" +
                    "未登录 / 关闭合并 / 已停用会分别显示原因；关闭页面不会取消已接收的同步。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun outOfSyncButtons() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { NativeUiSession.requestSync() }) { Text("立即同步") }
        OutlinedButton(onClick = { NativeUiSession.refreshSync() }) { Text("刷新状态") }
    }
}

internal fun describeStatus(status: SyncCoordinator.Status): String {
    val phase = syncPhaseLabel(status)
    val extra = StringBuilder(phase)
    if (status.total() > 0) {
        extra.append(" ").append(status.completed()).append("/").append(status.total())
    }
    val message = status.message()
    if (!message.isNullOrBlank()) {
        extra.append("：").append(message)
    }
    return extra.toString()
}

/** 同步页：完整状态 + 说明 + 请求入口。 */
@Composable
internal fun NativeSyncPage() {
    val status = NativeUiState.sync
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "同步状态（只读结构化状态：与配置页「同步状态」读同一份 Status）",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (status == null) {
            Text("尚未读到同步状态；插件可能仍在启动。", style = MaterialTheme.typography.bodyMedium)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusRow("阶段", syncPhaseLabel(status) + "（" + status.phase().name + "）")
                StatusRow("描述", status.describe().orEmpty())
                StatusRow("请求编号", status.requestId().toString())
                StatusRow("进度计数", status.completed().toString() + " / " + status.total())
                StatusRow("附带消息", if (status.message().isNullOrBlank()) "（无）" else status.message())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { NativeUiSession.requestSync() }) { Text("立即同步") }
            OutlinedButton(onClick = { NativeUiSession.refreshSync() }) { Text("刷新状态") }
        }
        if (NativeUiState.syncRequestNote.isNotBlank()) {
            Text("最近一次请求结果：" + NativeUiState.syncRequestNote, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = "映射规则（docs/PLAN.md §7.4）：IDLE 尚未同步 / WAITING 等待同步 / FETCHING 拉取中 / " +
                "WRITING 写入中 / COMPLETED 同步完成 / ABORTED 同步中止 / FAILED 同步失败；" +
                "禁用、未登录、关闭合并不再成功，但页面只按 Phase 与消息如实显示，不伪造进度。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(72.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
