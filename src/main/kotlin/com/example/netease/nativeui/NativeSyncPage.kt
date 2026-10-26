package com.example.netease.nativeui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.netease.svc.SyncCoordinator

internal fun syncProgressMessage(status: SyncCoordinator.Status?): String {
    status ?: return ""
    val text = when (status.phase()) {
        SyncCoordinator.Phase.WAITING -> "正在获取歌单，请稍等片刻"
        SyncCoordinator.Phase.FETCHING -> "正在获取歌单与歌曲，请稍等片刻"
        SyncCoordinator.Phase.WRITING -> "正在显示歌单与歌曲，请稍等片刻"
        else -> return ""
    }
    return text + if (status.total() > 0) "（${status.completed()} / ${status.total()} 个歌单）" else ""
}

/**
 * 状态行快照：阶段（跨阶段淡入淡出）+ 该阶段当下的完整文案。
 *
 * <p>AnimatedContent 用 contentKey = 阶段：同一阶段里进度计数原地更新、不重播过渡，
 * 只有阶段切换才过渡；退场内容读到的是自己那份快照，不会去读会立刻变化的全局状态。</p>
 */
@Immutable
private data class SyncHeadline(val phase: SyncCoordinator.Phase?, val text: String)

/**
 * 只读同步状态块（docs/PLAN.md §7.4）：直接映射 SyncCoordinator 既有 Status，
 * 不新写同步流程、不按动画时间伪造进度或百分比；「立即同步」仍走 SYNC.request。
 *
 * <p>[compact] 为 true 时用在歌单页底部（只显示两行），为 false 时是完整同步页。</p>
 */
@Composable
internal fun NativeSyncBlock(compact: Boolean) {
    val status = NativeUiState.sync
    val headline = SyncHeadline(
        phase = status?.phase(),
        text = "同步状态：" + (if (status == null) "尚未读取" else describeStatus(status)),
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AnimatedContent(
                targetState = headline,
                contentKey = { it.phase },
                transitionSpec = { fadeIn(Motion.state()) togetherWith fadeOut(Motion.state()) },
                label = "sync-headline",
            ) { shown ->
                SaltText(
                    text = shown.text,
                    style = if (compact) HostStyle.typography.bodySmall else HostStyle.typography.bodyMedium,
                    fontWeight = if (compact) FontWeight.Normal else FontWeight.SemiBold,
                )
            }
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

/** 同步页：完整状态 + 说明 + 请求入口；只渲染关闭前冻结的 SyncDisplay。 */
@Composable
internal fun NativeSyncPage(display: SyncDisplay, interactive: Boolean) {
    val status = display.status
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SaltText(
            text = "歌单同步",
            style = HostStyle.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (status == null) {
            SaltText("尚未读到同步状态；插件可能仍在启动。", style = HostStyle.typography.bodyMedium)
        } else {
            // 状态行按原生行统一（SaltGroup），阶段词单独淡入淡出，计数/说明原地更新。
            SaltGroup {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PhaseRow(status)
                    SyncProgressBar(status)
                    StatusRow("进度计数", status.completed().toString() + " / " + status.total())
                    if (!status.message().isNullOrBlank()) StatusRow("说明", status.message())
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SaltButton(enabled = interactive, onClick = { if (interactive) NativeUiSession.requestSync() }) {
                SaltText("立即同步")
            }
            SaltSecondaryButton(enabled = interactive, onClick = { if (interactive) NativeUiSession.refreshSync() }) {
                SaltText("刷新状态")
            }
        }
        if (display.requestNote.isNotBlank()) {
            SaltText("最近一次请求结果：" + display.requestNote, style = HostStyle.typography.bodyMedium)
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

/**
 * 「阶段」行：syncPhaseLabel 只由阶段决定（不含计数），所以直接把它当 AnimatedContent 的
 * targetState —— 退场内容天生是旧快照，不会读会立刻变化的全局状态。
 */
@Composable
private fun PhaseRow(status: SyncCoordinator.Status) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SaltText("阶段", style = HostStyle.typography.bodySmall, modifier = Modifier.width(72.dp))
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 状态徽标只做 120ms 颜色过渡；阶段文案仍由 AnimatedContent 淡入淡出。
            PhaseBadge(status.phase())
            AnimatedContent(
                targetState = syncPhaseLabel(status),
                modifier = Modifier.weight(1f),
                transitionSpec = { fadeIn(Motion.state()) togetherWith fadeOut(Motion.state()) },
                label = "sync-phase",
            ) { label ->
                SaltText(label, style = HostStyle.typography.bodySmall)
            }
        }
    }
}

/** 阶段徽标：空闲灰、进行中高亮、完成成功色、中止/失败警告色；只改颜色，不改文案。 */
@Composable
private fun PhaseBadge(phase: SyncCoordinator.Phase?) {
    val target = when (phase) {
        SyncCoordinator.Phase.COMPLETED -> HostStyle.colors.success
        SyncCoordinator.Phase.ABORTED -> HostStyle.colors.warning
        SyncCoordinator.Phase.FAILED -> HostStyle.colors.error
        SyncCoordinator.Phase.WAITING, SyncCoordinator.Phase.FETCHING,
        SyncCoordinator.Phase.WRITING -> HostStyle.colors.highlight
        else -> HostStyle.colors.subText
    }
    val color by animateColorAsState(targetValue = target, animationSpec = Motion.state(), label = "sync-badge")
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

/**
 * 进度条：分子分母都直接来自 SyncCoordinator.Status，动画只平滑两次采样之间的跳变，
 * 绝不按时间伪造进度；total 为 0（未知总量）时不画，避免编造百分比。
 */
@Composable
private fun SyncProgressBar(status: SyncCoordinator.Status) {
    val total = status.total()
    if (total <= 0) return
    val fraction by animateFloatAsState(
        targetValue = (status.completed().toFloat() / total.toFloat()).coerceIn(0f, 1f),
        animationSpec = Motion.state(),
        label = "sync-progress",
    )
    Box(
        Modifier.fillMaxWidth().height(4.dp)
            .clip(RoundedCornerShape(2.dp)).background(HostStyle.colors.stroke),
    ) {
        Box(
            Modifier.fillMaxWidth(fraction).fillMaxHeight()
                .clip(RoundedCornerShape(2.dp)).background(HostStyle.colors.highlight),
        )
    }
}
