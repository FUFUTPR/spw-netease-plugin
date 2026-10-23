package com.example.netease.nativeui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SaltText(
                text = "同步状态：" + (if (status == null) "尚未读取" else describeStatus(status)),
                style = if (compact) HostStyle.typography.bodySmall else HostStyle.typography.bodyMedium,
                fontWeight = if (compact) FontWeight.Normal else FontWeight.SemiBold,
            )
            if (!compact) {
                outOfSyncButtons()
            } else SaltPlainButton(onClick = { NativeUiPresentation.openPage(NativePage.SYNC) }) { SaltText("查看详情") }
        }
        if (NativeUiState.syncRequestNote.isNotBlank()) {
            SaltText(
                text = "最近一次请求：" + NativeUiState.syncRequestNote,
                style = HostStyle.typography.labelSmall,
            )
        }

    }
}

@Composable
private fun outOfSyncButtons() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SaltButton(onClick = { NativeUiSession.requestSync() }) { SaltText("立即同步") }
        SaltSecondaryButton(onClick = { NativeUiSession.refreshSync() }) { SaltText("刷新状态") }
    }
}

internal fun describeStatus(status: SyncCoordinator.Status): String {
    val phase = syncPhaseLabel(status)
    val extra = StringBuilder(phase)
    if (status.total() > 0) {
        extra.append(" ").append(status.completed()).append("/").append(status.total())
    }
    val message = status.message().orEmpty().removePrefix(phase).trimStart('：', '，', ':', ' ')
    if (message.isNotBlank()) {
        extra.append("：").append(message)
    }
    return extra.toString()
}

/** 同步页：完整状态 + 说明 + 请求入口。 */
@Composable
internal fun NativeSyncPage() {
    val status = NativeUiState.sync
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SaltText(
            text = "歌单同步",
            style = HostStyle.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (status == null) {
            SaltText("尚未读到同步状态；插件可能仍在启动。", style = HostStyle.typography.bodyMedium)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusRow("阶段", syncPhaseLabel(status))
                StatusRow("进度计数", status.completed().toString() + " / " + status.total())
                if (!status.message().isNullOrBlank()) StatusRow("说明", status.message())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SaltButton(onClick = { NativeUiSession.requestSync() }) { SaltText("立即同步") }
            SaltSecondaryButton(onClick = { NativeUiSession.refreshSync() }) { SaltText("刷新状态") }
        }
        if (NativeUiState.syncRequestNote.isNotBlank()) {
            SaltText("最近一次请求结果：" + NativeUiState.syncRequestNote, style = HostStyle.typography.bodyMedium)
        }
        SaltText("同步任务会在后台继续，关闭此页面不会取消同步。", style = HostStyle.typography.bodySmall)
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SaltText(label, style = HostStyle.typography.bodySmall, modifier = Modifier.width(72.dp))
        SaltText(value, style = HostStyle.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}
