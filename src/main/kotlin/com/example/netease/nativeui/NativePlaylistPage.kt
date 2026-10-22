package com.example.netease.nativeui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
        Text("歌单控制器尚未就绪（插件可能正在启动或已停用）", style = MaterialTheme.typography.bodyMedium)
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 760.dp
        var navOpen by remember { mutableStateOf(false) }
        val showNav = !narrow || navOpen
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PlaylistToolbar(controller, state, narrow, navOpen, onToggleNav = { navOpen = !navOpen })
            PlaylistInputs(controller)
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
            NativeSyncBlock(compact = true)
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
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (narrow) {
            OutlinedButton(onClick = onToggleNav) { Text(if (navOpen) "收起歌单列表" else "歌单列表") }
        }
        OutlinedButton(onClick = { controller.loadLiked() }) { Text("我喜欢的音乐") }
        OutlinedButton(onClick = { controller.loadDaily() }) { Text("每日推荐") }
        OutlinedButton(onClick = { controller.loadMine() }) { Text(if (state.briefsLoaded()) "刷新我的歌单" else "载入我的歌单") }
        OutlinedButton(onClick = { NativeUiSession.requestLocalRefresh() }) { Text("刷新本地歌单") }
        OutlinedButton(
            onClick = { controller.retry() },
            enabled = state.canRetry(),
        ) {
            Text(if (state.retryLabel().isBlank()) "重试" else state.retryLabel())
        }
    }
}

@Composable
private fun PlaylistInputs(controller: PlaylistController) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = NativeUiState.playlistInput,
            onValueChange = { NativeUiState.playlistInput = it },
            singleLine = true,
            modifier = Modifier.weight(1f),
            placeholder = { Text("歌单 ID 或链接（如 https://music.163.com/playlist?id=123456）") },
        )
        Button(onClick = { controller.loadPlaylistText(NativeUiState.playlistInput) }) { Text("载入歌单") }
        OutlinedTextField(
            value = NativeUiState.saveName,
            onValueChange = { NativeUiState.saveName = it },
            singleLine = true,
            modifier = Modifier.width(180.dp),
            placeholder = { Text("本地歌单名") },
        )
        OutlinedButton(onClick = { NativeUiSession.saveCurrentAsLocal(NativeUiState.saveName) }) {
            Text("存为本地歌单")
        }
    }
}

@Composable
private fun LocalPlaylistBox(controller: PlaylistController) {
    val entries: List<LocalPlaylists.Entry> = NativeUiState.localEntries
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "本地歌单（" + entries.size + "）",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable { open = !open },
            )
            Text(
                text = if (open) "收起" else "展开载入",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clickable { open = !open },
            )
            if (NativeUiState.localNote.isNotBlank()) {
                Text(NativeUiState.localNote, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (open) {
            if (entries.isEmpty()) {
                Text("还没有本地歌单；可用上方「存为本地歌单」把当前曲目表存下来。", style = MaterialTheme.typography.bodySmall)
            } else {
                Column(Modifier.fillMaxWidth().height(88.dp)) {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        itemsIndexed(entries) { _, e ->
                            Text(
                                text = "本地歌单「" + e.name() + "」 · " + e.trackCount() + " 首",
                                style = MaterialTheme.typography.bodySmall,
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
    Column(modifier) {
        Text("我的歌单（创建 / 收藏）", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        if (briefs.isEmpty()) {
            Text(
                text = if (state.briefsLoaded()) "没有歌单；可用上方 ID 载入公开歌单。" else "点上方「载入我的歌单」拉取列表。",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(briefs) { _, b ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { controller.loadPlaylist(b.id(), b.name()) }
                            .padding(vertical = 5.dp, horizontal = 4.dp),
                    ) {
                        Text(
                            text = b.name().orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = (if (b.mine()) "创建" else "收藏") + " · " + b.trackCount() + " 首",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TracksHeader(controller: PlaylistController, state: PlaylistController.State) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = if (state.sourceName().isBlank()) "来源：未载入" else "来源：" + state.sourceName() + " · " + state.tracks().size + " 首",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(state.message(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.busy()) {
                Text(
                    text = "载入中：" + state.pendingLabel() + "（保留上次列表）",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (state.playbackText().isNotBlank()) {
                Text(
                    text = "播放反馈（宿主回报，不代表已实际出声）：" + state.playbackText(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(onClick = { controller.retryPlayback() }, enabled = state.playing() || state.pendingPlayRequest() > 0) {
                Text("重试上次播放")
            }
        }
    }
}

@Composable
private fun TracksList(controller: PlaylistController, state: PlaylistController.State, modifier: Modifier) {
    val tracks: List<Dto.Song> = state.tracks()
    if (tracks.isEmpty()) {
        Text(
            text = "曲目表是空的：先从上排选一个来源，或在左栏选歌单 / 用 ID 载入。",
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.padding(vertical = 12.dp),
        )
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
    Row(
        Modifier
            .fillMaxWidth()
            .pointerInput(song.id()) { detectTapGestures(onTap = { onPlay() }) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text((index + 1).toString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(34.dp))
        Text(
            text = song.name().orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(3f),
        )
        Text(
            text = song.artists().orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(2f),
        )
        Text(
            text = song.album().orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(2f),
        )
        Text(trackNote(song), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(96.dp))
    }
}
