package com.example.netease.nativeui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.clearAndSetSemantics
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
                // 窄窗导航栏：横向展开/收起 + 淡入淡出，按钮与业务回调完全不变。
                AnimatedVisibility(
                    visible = showNav,
                    enter = expandHorizontally(Motion.state()) + fadeIn(Motion.state()),
                    exit = shrinkHorizontally(Motion.state()) + fadeOut(Motion.state()),
                    label = "playlist-nav",
                ) {
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
internal fun LocalSaveDialog(state: PlaylistController.State, interactive: Boolean = true) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    // 退场快照（interactive=false）里不再抢焦点，避免焦点落到正在消失的输入框。
    androidx.compose.runtime.LaunchedEffect(interactive) { if (interactive) focus.requestFocus() }
    fun cancel() { NativeUiPresentation.closeLocalSave(cancel = true) }
    EmbeddedSaltDialog("存为本地歌单", onDismiss = { cancel() },
        onConfirm = { NativeUiSession.submitLocalSave(NativeUiState.saveName); NativeUiPresentation.closeLocalSave(cancel = false) },
        confirmVisible = true, cancelText = "取消", confirmText = "保存", interactive = interactive) {
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
        if (com.example.netease.cfg.PluginConfig.dailyRecommendations())
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
            // 保存入口会打开本地保存模态、而歌单页仍在组合树上：按窗口登记真实触发点，
            // 模态关闭后焦点归还到这里，而不是归还给一个没有真实触发过按钮的容器。
            SaltSecondaryButton(
                onClick = onSave,
                modifier = Modifier.focusReturnTarget(),
                enabled = !busy && NativeUiState.playlist?.tracks()?.isNotEmpty() == true,
            ) {
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
        // 复用宿主 PlainButton 的 ripple/按压反馈：标题与展开/收起合并为一个真实按钮，
        // 不再用裸 Modifier.clickable 手搓反馈；展开、载入与提示文案保持不变。
        SaltPlainButton(
            onClick = { open = !open },
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) {
            SaltText(
                text = "本地歌单（" + entries.size + "）",
                style = HostStyle.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            SaltText(
                text = if (open) "收起" else "展开载入",
                style = HostStyle.typography.labelSmall,
            )
        }
        if (NativeUiState.localNote.isNotBlank()) {
            SaltText(
                NativeUiState.localNote,
                style = HostStyle.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        // 本地面板：淡入 + 纵向展开 / 淡出 + 纵向收起；条目标识仍按歌单 key，只做布局移动。
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(Motion.state()) + expandVertically(Motion.state(), expandFrom = Alignment.Top),
            exit = fadeOut(Motion.state()) + shrinkVertically(Motion.state(), shrinkTowards = Alignment.Top),
            label = "local-playlists",
        ) {
            if (entries.isEmpty()) {
                SaltText("还没有本地歌单；可用上方「存为本地歌单」把当前曲目表存下来。", style = HostStyle.typography.bodySmall)
            } else {
                Column(Modifier.fillMaxWidth().height(88.dp)) {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        itemsIndexed(entries, key = { _, e -> "local:" + e.key() }, contentType = { _, _ -> "local" }) { _, e ->
                            // 宿主 Item 自带点击/按压反馈；保留原「本地歌单『名』 · N 首」信息。
                            SaltItem(
                                title = "本地歌单「" + e.name() + "」",
                                subtitle = e.trackCount().toString() + " 首",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = Motion.state()),
                                onClick = { controller.loadLocal(e.key(), e.name()) },
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
                itemsIndexed(briefs, key = { _, b -> "brief:" + b.id() }, contentType = { _, _ -> "brief" }) { _, b ->
                    SaltItem(b.name().orEmpty(), (if (b.mine()) "创建" else "收藏") + " · " + b.trackCount() + " 首",
                        Modifier.fillMaxWidth()
                            .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = Motion.state())) {
                        controller.loadPlaylist(b.id(), b.name())
                    }
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
    // 批身份 = 来源名 + 账号代次：两个非空歌单之间会真正整批过渡；
    // 同一来源的刷新（封面/metadata、播放状态变化）复用同一批对象原地更新 tracks，
    // Crossfade 的 target 相等 → 不重启 120ms 过渡，LazyListState 与滚动位置保留。
    val holder = remember { PlaylistTracksHolder() }
    val batch = holder.present(
        PlaylistBatchPolicy.identity(state.sourceName(), state.accountEpoch()),
        state.tracks(),
    )
    val body: TracksBody = when {
        batch != null -> TracksBody.Batch(batch)
        state.busy() -> TracksBody.Loading
        else -> TracksBody.Empty
    }
    Crossfade(
        targetState = body,
        modifier = modifier.fillMaxWidth(),
        animationSpec = Motion.state(),
        label = "tracks-state",
    ) { shown ->
        when (shown) {
            is TracksBody.Batch -> PlaylistBatchList(
                controller = controller,
                batch = shown.batch,
                // 页面退场（换页动画）时宿主把 LocalNativePageInteractive 置 false；
                // 换来源时旧批 interactive 也被置 false。两者任一关掉，列表立即不可交互。
                interactive = LocalNativePageInteractive.current && shown.batch.interactive,
                modifier = Modifier.fillMaxSize(),
            )
            TracksBody.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SaltText("正在载入歌曲…", style = HostStyle.typography.bodyMedium, modifier = Modifier.padding(24.dp))
            }
            TracksBody.Empty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SaltText("选择一个歌单，或粘贴歌单链接载入歌曲", style = HostStyle.typography.bodyMedium, modifier = Modifier.padding(24.dp))
            }
        }
    }
}

/** 曲目表内容：整批歌曲、载入中、空态。批次对象身份不同才会触发 Crossfade。 */
private sealed interface TracksBody {
    // data class：同一批次对象的 target 相等，播放状态等其它 State 变化不会重启过渡。
    data class Batch(val batch: PlaylistTracksBatch) : TracksBody
    data object Loading : TracksBody
    data object Empty : TracksBody
}

/**
 * 一批来源曲目：稳定 id + 行快照。
 *
 * <p>同 id 刷新由 [PlaylistTracksHolder] 复用本对象，仅原地更新 tracks，Crossfade 不重启、
 * 滚动位置不丢；id（来源名/账号代次）变化时旧批 [interactive] 置 false，淡出期间不接受点击，
 * 语义树也被清空，行点击闭包仍按歌曲身份在 controller 当前 tracks 中重新解析下标。</p>
 */
@Stable
private class PlaylistTracksBatch(val id: String, initial: List<Dto.Song>) {
    var interactive by mutableStateOf(true)
        private set

    private val liveTracks = mutableStateListOf<Dto.Song>().apply { addAll(initial) }

    /** 本批自己的曲目快照；同 id 刷新时这个 List 实例不变，只有内容更新。 */
    val tracks: List<Dto.Song> get() = liveTracks

    fun update(next: List<Dto.Song>) {
        if (samePlaylistTracks(liveTracks, next)) return
        liveTracks.clear()
        liveTracks.addAll(next)
    }

    fun deactivate() {
        interactive = false
    }
}

/** 批槽：同 id 复用对象并原地更新 tracks；id 变化才换批并让旧批退场。 */
private class PlaylistTracksHolder {
    private var current: PlaylistTracksBatch? = null

    fun present(id: String, tracks: List<Dto.Song>): PlaylistTracksBatch? {
        val shown = current
        if (tracks.isEmpty()) {
            shown?.deactivate()
            current = null
            return null
        }
        if (shown != null && shown.id == id) {
            shown.update(tracks)
            return shown
        }
        shown?.deactivate()
        return PlaylistTracksBatch(id, tracks).also { current = it }
    }
}

/** 逐项比较；SnapshotStateList 的 equals 不是内容比较，不能用 == 判断是否需要刷新。 */
private fun samePlaylistTracks(a: List<Dto.Song>, b: List<Dto.Song>): Boolean {
    if (a.size != b.size) return false
    for (index in a.indices) if (a[index] != b[index]) return false
    return true
}

/** Dto.Song 是不可变 Java record；key = 歌曲 id + 同一来源里的重复出现序号。 */
@Immutable private data class PlaylistTrack(val key: String, val value: Dto.Song, val occurrence: Int)

private fun List<Dto.Song>.toPlaylistTracks(): List<PlaylistTrack> {
    val seen = HashMap<Long, Int>(size)
    return map { song ->
        val occurrence = seen[song.id()] ?: 0
        seen[song.id()] = occurrence + 1
        PlaylistTrack(PlaylistBatchPolicy.trackKey(song.id(), occurrence), song, occurrence)
    }
}

@Composable
private fun PlaylistBatchList(
    controller: PlaylistController,
    batch: PlaylistTracksBatch,
    interactive: Boolean,
    modifier: Modifier,
) {
    val tracks = batch.tracks
    // 不能 remember(tracks)：同批刷新时 tracks 是同一个 SnapshotStateList，内容原地变化；
    // 每次组合按当前快照重建显示模型，读取元素会把 tracks 的写入登记成重组依赖。
    val displayTracks = tracks.toPlaylistTracks()
    // 每批自持滚动状态：同来源刷新不丢滚动，换来源从顶部开始。
    val list = rememberLazyListState()
    // 退场批即使被键盘 Tab 摸到，回调也已经换成空实现：不播放、不抢焦点。
    val playRow: (Dto.Song, Int) -> Unit
    if (interactive) {
        playRow = { song, occurrence ->
            val row = PlaylistBatchPolicy.resolveRow(controller.state().tracks(), song.id(), occurrence)
            if (row >= 0) controller.playFrom(row)
        }
    } else {
        playRow = { _, _ -> }
    }
    val listModifier = if (interactive) {
        Modifier.fillMaxSize()
    } else {
        // 退场批次：指针事件全消费（含点击/滚轮），语义树清空，读屏/自动化都摸不到行按钮。
        Modifier.fillMaxSize().blockAllPointerEvents().clearAndSetSemantics { }
    }
    Box(modifier.then(listModifier)) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(displayTracks, key = { _, item -> item.key }, contentType = { _, _ -> "track" }) { index, item ->
                TrackRow(
                    index, item.value,
                    modifier = Modifier.animateItem(
                        fadeInSpec = Motion.state(), placementSpec = null, fadeOutSpec = Motion.state(),
                    ),
                    // 只携带歌曲身份；点击时回 controller 当前 tracks 重新解析下标，
                    // 目标已不在当前列表（换来源/被过滤）则解析为 -1，什么都不做。
                    onPlay = { playRow(item.value, item.occurrence) },
                )
            }
        }
    }
}

@Composable
private fun TrackRow(index: Int, song: Dto.Song, modifier: Modifier = Modifier, onPlay: () -> Unit) {
    // 只用 alpha 做进出场：SaltTrack 行的 root 坐标被右键菜单读取，不能叠加位移。
    SaltTrack(song, index, modifier.fillMaxWidth(), onSelect = onPlay)
}

/**
 * 歌单曲目批的纯逻辑（Java 回归探针可直接反射调用）。
 *
 * <p>批身份用 PlaylistController.State 的 sourceName + accountEpoch：两者都只在曲目真正
 * 落地时变化（载入期间保留旧 tracks 与旧 sourceName），因此同一歌单刷新留在同一批，
 * 两个不同歌单/账号切换才换批。State 没有单独 requestSeq，这里不编造字段。</p>
 */
internal object PlaylistBatchPolicy {
    fun identity(sourceName: String?, accountEpoch: Long): String =
        "playlist:" + (sourceName ?: "").trim() + "@" + accountEpoch

    /** 与旧 stableTrackKeys 完全一致的键格式，避免滚动位置因键变化而丢失。 */
    fun trackKey(trackId: Long, occurrence: Int): String =
        if (occurrence == 0) "track:" + trackId else "track:" + trackId + "#" + occurrence

    /** 按歌曲 id + 同 id 重复出现序号在当前曲目表里定位；找不到返回 -1。 */
    fun resolveRow(tracks: List<Dto.Song>, trackId: Long, occurrence: Int): Int {
        if (occurrence < 0) return -1
        var seen = 0
        for (index in tracks.indices) {
            val song = tracks[index]
            if (song.id() != trackId) continue
            if (seen == occurrence) return index
            seen++
        }
        return -1
    }

    fun resolveRow(tracks: List<Dto.Song>, trackId: Long): Int = resolveRow(tracks, trackId, 0)
}
