package com.example.netease.nativeui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.netease.net.Dto
import com.example.netease.svc.LocalPlaylists
import com.example.netease.ui.controller.PlaylistController

/**
 * 歌单页（docs/PLAN.md §7.3）：左栏歌单分类/条目与本地歌单，右栏当前来源、曲目与播放操作，
 * 底部是本页独立的同步状态区（不另写同步流程，只映射 SyncCoordinator 的既有状态）。
 *
 * <p>窄窗（宽度 < 760dp）时左栏默认收起，用顶部「歌单列表」按钮展开，不挤压右栏曲目表。</p>
 */
@Composable
internal fun NativePlaylistPage() {
    val controller = NativeUiSession.playlist()
    val state = NativeUiState.playlist
    if (controller == null || state == null) {
        SaltText("歌单控制器尚未就绪（插件可能正在启动或已停用）", style = HostStyle.typography.bodyMedium)
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 760.dp
        var navOpen by remember { mutableStateOf(false) }
        val showNav = !narrow || navOpen
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PlaylistToolbar(controller, state, narrow, navOpen, onToggleNav = { navOpen = !navOpen })
            PlaylistInputs(controller, onSave = { NativeUiPresentation.openLocalSave() })
            LocalPlaylistBox(controller)
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (showNav) {
                    BriefsPane(controller, state, Modifier.width(if (narrow) 220.dp else 260.dp).fillMaxHeight())
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TracksHeader(controller, state)
                    TracksList(controller, state, Modifier.weight(1f))
                }
            }
            SaltGroup { Column(Modifier.padding(12.dp)) { NativeSyncBlock(compact = true) } }
        }
    }
}

@Composable
internal fun LocalSaveDialog(state: PlaylistController.State) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
    fun cancel() { NativeUiPresentation.closeLocalSave(cancel = true) }
    EmbeddedSaltDialog("存为本地歌单", onDismiss = { cancel() },
        onConfirm = { NativeUiSession.submitLocalSave(NativeUiState.saveName); NativeUiPresentation.closeLocalSave(cancel = false) },
        confirmVisible = true, cancelText = "取消", confirmText = "保存") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SaltText("保存当前列表中的 ${state.tracks().size} 首歌曲", style = HostStyle.typography.titleMedium)
            SaltInput(NativeUiState.saveName, { NativeUiState.saveName = it }, Modifier.fillMaxWidth().focusRequester(focus),
                hint = NativeUiState.localSave?.defaultName().orEmpty().ifBlank { "歌单名称" })
        }
    }
}

@Composable
private fun PlaylistToolbar(
    controller: PlaylistController,
    state: PlaylistController.State,
    narrow: Boolean,
    navOpen: Boolean,
    onToggleNav: () -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (narrow) {
            SaltSecondaryButton(onClick = onToggleNav) { SaltText(if (navOpen) "收起歌单列表" else "歌单列表") }
        }
        SaltSecondaryButton(onClick = { controller.loadLiked() }) { SaltText("我喜欢的音乐") }
        SaltSecondaryButton(onClick = { controller.loadDaily() }) { SaltText("每日推荐") }
        SaltSecondaryButton(onClick = { controller.loadMine() }) { SaltText(if (state.briefsLoaded()) "刷新我的歌单" else "载入我的歌单") }
        SaltSecondaryButton(onClick = { NativeUiSession.requestLocalRefresh() }) { SaltText("刷新本地歌单") }
        if (state.canRetry()) SaltSecondaryButton(
            onClick = { controller.retry() },
            enabled = state.canRetry(),
        ) {
            SaltText(if (state.retryLabel().isBlank()) "重试" else state.retryLabel())
        }
    }
}

@Composable
private fun PlaylistInputs(controller: PlaylistController, onSave: () -> Unit) {
    val save = NativeUiState.localSave
    val busy = save?.busy() == true
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SaltGroup { SaltInput(NativeUiState.playlistInput, { NativeUiState.playlistInput = it }, Modifier.fillMaxWidth(),
            hint = "粘贴歌单链接或输入歌单 ID") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SaltButton(onClick = { controller.loadPlaylistText(NativeUiState.playlistInput) }) { SaltText("载入歌单") }
            SaltSecondaryButton(onClick = onSave, enabled = !busy && NativeUiState.playlist?.tracks()?.isNotEmpty() == true) {
                SaltText(if (busy) "保存中…" else "存为本地歌单")
            }
        }
        val msg = save?.message().orEmpty()
        if (msg.isNotBlank()) {
            SaltText(
                text = "本地命名：" + msg + "（待保存 " + (save?.trackCount() ?: 0) + " 首）",
                style = HostStyle.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LocalPlaylistBox(controller: PlaylistController) {
    val entries: List<LocalPlaylists.Entry> = NativeUiState.localEntries
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SaltText(
                text = "本地歌单（" + entries.size + "）",
                style = HostStyle.typography.bodyMedium,
                modifier = Modifier.clickable { open = !open },
            )
            SaltText(
                text = if (open) "收起" else "展开载入",
                style = HostStyle.typography.labelSmall,
                modifier = Modifier.clickable { open = !open },
            )
            if (NativeUiState.localNote.isNotBlank()) {
                SaltText(NativeUiState.localNote, style = HostStyle.typography.labelSmall)
            }
        }
        if (open) {
            if (entries.isEmpty()) {
                SaltText("还没有本地歌单；可用上方「存为本地歌单」把当前曲目表存下来。", style = HostStyle.typography.bodySmall)
            } else {
                Column(Modifier.fillMaxWidth().height(88.dp)) {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        itemsIndexed(entries) { _, e ->
                            SaltText(
                                text = "本地歌单「" + e.name() + "」 · " + e.trackCount() + " 首",
                                style = HostStyle.typography.bodySmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { controller.loadLocal(e.key(), e.name()) }
                                    .padding(vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BriefsPane(controller: PlaylistController, state: PlaylistController.State, modifier: Modifier) {
    val briefs: List<Dto.PlaylistBrief> = state.briefs()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SaltText("我的歌单", style = HostStyle.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp))
        if (briefs.isEmpty()) {
            SaltText(
                text = if (state.briefsLoaded()) "没有歌单；可用上方 ID 载入公开歌单。" else "点上方「载入我的歌单」拉取列表。",
                style = HostStyle.typography.bodySmall,
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(briefs) { _, b ->
                    SaltItem(b.name().orEmpty(), (if (b.mine()) "创建" else "收藏") + " · " + b.trackCount() + " 首",
                        Modifier.fillMaxWidth()) { controller.loadPlaylist(b.id(), b.name()) }
                }
            }
        }
    }
}

@Composable
private fun TracksHeader(controller: PlaylistController, state: PlaylistController.State) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SaltText(
            text = if (state.sourceName().isBlank()) "来源：未载入" else "来源：" + state.sourceName() + " · " + state.tracks().size + " 首",
            style = HostStyle.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.message().isNotBlank() && state.message() != "就绪")
            SaltText(state.message(), style = HostStyle.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.busy()) {
                SaltText(
                    text = "载入中：" + state.pendingLabel() + "（保留上次列表）",
                    style = HostStyle.typography.labelSmall,
                )
            }
            if (state.playbackText().isNotBlank()) {
                SaltText(
                    text = "播放状态：" + state.playbackText(),
                    style = HostStyle.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.pendingPlayRequest() > 0) SaltSecondaryButton(onClick = { controller.retryPlayback() }, enabled = !state.playing()) {
                SaltText("重试上次播放")
            }
        }
    }
}

@Composable
private fun TracksList(controller: PlaylistController, state: PlaylistController.State, modifier: Modifier) {
    val tracks: List<Dto.Song> = state.tracks()
    if (tracks.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SaltText(
            text = if (state.busy()) "正在载入歌曲…" else "选择一个歌单，或粘贴歌单链接载入歌曲",
            style = HostStyle.typography.bodyMedium,
            modifier = Modifier.padding(24.dp),
        ) }
        return
    }
    LazyColumn(modifier.fillMaxWidth()) {
        itemsIndexed(tracks) { index, song ->
            TrackRow(index, song, onPlay = { controller.playFrom(index) })
        }
    }
}

@Composable
private fun TrackRow(index: Int, song: Dto.Song, onPlay: () -> Unit) {
    SaltTrack(song, index, Modifier.fillMaxWidth(), onSelect = onPlay)
}
