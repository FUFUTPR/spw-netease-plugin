package com.example.netease.nativeui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.example.netease.net.Dto
import com.example.netease.ui.controller.SearchController

/** Submitted results only. The one search input lives in the host titlebar. */
@Composable
internal fun NativeSearchPage() {
    val controller = NativeUiSession.search()
    val state = NativeUiState.search
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (controller == null || state == null) return@Column
        SaltText("${state.rows().size} 首歌曲", style = HostStyle.typography.bodyMedium)
        if (state.phase() == SearchController.Phase.SEARCHING) SaltText("搜索中…", style = HostStyle.typography.bodySmall)
        if (state.phase() == SearchController.Phase.FAILED) SaltText(state.message(), style = HostStyle.typography.bodySmall)
        if (state.playbackText().isNotBlank()) SaltText(state.playbackText(), style = HostStyle.typography.bodySmall)
        NativeSongMenu.state?.message()?.takeIf { it.isNotBlank() }?.let {
            SaltText(it, style = HostStyle.typography.bodySmall)
        }
        SearchResultList(controller, state, Modifier.weight(1f))
        if (NativeSongMenu.visible) NativeSongMenu.state?.let {
            HostSongPopup.Menu(it, NativeSongMenu::dismiss, NativeSongMenu::add)
        }
    }
}

@Composable
private fun SearchResultList(controller: SearchController, state: SearchController.State,
                             modifier: Modifier) {
    // 批身份只由来源关键词决定：同一关键词重搜（封面/metadata/顺序刷新）复用同一个 SearchBatch，
    // 原地更新 rows，Crossfade 的 target 相等 → 不重启 120ms 过渡，LazyListState 与键盘游标也保留。
    val holder = remember { SearchBatchHolder() }
    val body = holder.present(SearchBatchPolicy.identity(state.sourceKeyword()), state.rows())
    Crossfade(
        targetState = body,
        modifier = modifier.fillMaxWidth(),
        animationSpec = Motion.state(),
        label = "search-body",
    ) { shown ->
        // 在内容里直接读宿主开关：换页退场时 staticCompositionLocalOf 会把这里整段重组，
        // 与旧批自身的 interactive 叠加后立即禁掉指针/键盘/语义。
        val pageInteractive = LocalNativePageInteractive.current
        // 占位文案照旧在结果区垂直居中：Crossfade 已吃掉 weight 高度，这里补满尺寸。
        if (shown == null) SearchPlaceholder(state, Modifier.fillMaxSize())
        else SearchBatchList(
            controller = controller,
            batch = shown,
            selectedSongId = state.selectedSongId(),
            interactive = pageInteractive && shown.interactive,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SearchPlaceholder(state: SearchController.State, modifier: Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        SaltText(when (state.phase()) {
            SearchController.Phase.SEARCHING -> "搜索中…"
            SearchController.Phase.FAILED -> "搜索暂不可用，请在顶栏重新搜索"
            SearchController.Phase.EMPTY -> "没有找到相关歌曲"
            else -> "在顶栏输入关键词搜索"
        }, modifier = Modifier.padding(24.dp))
    }
}

/**
 * 一个批次内的结果列表。每批各自持有 LazyListState、键盘游标与 FocusRequester：
 * 关键词变化后新批次从顶部开始，旧批次在淡出期间仍用自己的状态，互不干扰。
 *
 * <p>退场批次由 [SearchBatch.interactive] 关掉指针/键盘/语义；即使它仍被点中，
 * 闭包携带的也只是歌曲身份（id + 重复序号），真正下标永远回 controller 当前 rows 重新解析。</p>
 */
@Composable
private fun SearchBatchList(
    controller: SearchController,
    batch: SearchBatch,
    selectedSongId: Long?,
    interactive: Boolean,
    modifier: Modifier,
) {
    val rows = batch.rows
    // 不能 remember(rows)：同批刷新时 rows 是同一个 SnapshotStateList，内容原地变化；
    // 每次组合按当前快照重建显示模型，读取元素会把 rows 的写入登记成重组依赖。
    val displayRows = rows.toSearchSongs()
    val selected = displayRows.indexOfFirst { it.value.id() == selectedSongId }
    var keyboardIndex by remember(selected) { mutableStateOf(selected) }
    var scrollTarget by remember { mutableStateOf(-1) }
    var scrollRequest by remember { mutableStateOf(0) }
    val list = rememberLazyListState()
    // 每批自持焦点请求器：新旧两批同时在场时不会争抢同一个 FocusRequester。
    val focus = remember { FocusRequester() }
    // 显式标注返回类型：Remember 的 T 若靠内层 lambda 反推会被推成 Any，
    // 进而让 rowPress 的 if 分支类型对不上 (() -> Unit)。
    val press: () -> Unit = remember(focus) { { runCatching { focus.requestFocus() }; Unit } }

    // 退场内容里的点击闭包只携带歌曲身份；下标永远按 controller 的当前 rows 重新解析。
    // 目标已不在当前批次（换关键词/被过滤）时 resolveRow 返回 -1，什么都不做，绝不按旧下标播放。
    val select: (Long, Int) -> Unit = remember(controller, press) { { songId: Long, occurrence: Int ->
        press()
        val row = SearchBatchPolicy.resolveRow(controller.state().rows(), songId, occurrence)
        if (row >= 0) controller.selectRow(row)
    } }
    val play: (Long, Int) -> Unit = remember(controller, press) { { songId: Long, occurrence: Int ->
        press()
        val row = SearchBatchPolicy.resolveRow(controller.state().rows(), songId, occurrence)
        if (row >= 0) {
            controller.selectRow(row)
            controller.playSelected()
        }
    } }

    fun liveRow(displayRow: Int): Int {
        val item = displayRows.getOrNull(displayRow) ?: return -1
        return SearchBatchPolicy.resolveRow(controller.state().rows(), item.value.id(), item.occurrence)
    }

    LaunchedEffect(scrollRequest) {
        if (scrollTarget in displayRows.indices && list.layoutInfo.visibleItemsInfo.none { it.index == scrollTarget })
            list.animateScrollToItem(scrollTarget)
    }

    val listModifier = if (interactive) {
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionDown, Key.DirectionUp -> {
                        val target = SearchKeyNav.move(
                            keyboardIndex, displayRows.size,
                            if (event.key == Key.DirectionDown) 1 else -1,
                        )
                        keyboardIndex = target
                        val row = liveRow(target)
                        if (row >= 0) controller.selectRow(row)
                        scrollTarget = target
                        scrollRequest++
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        // 保持 0.12.17 契约：没有选中行时第一次 Enter 只选中首行，
                        // 第二次 Enter 才播放；不要因为这里直接 resolve 下标而跳过选择。
                        when (SearchKeyNav.enterAction(displayRows.size, keyboardIndex)) {
                            SearchKeyNav.EnterAction.NO_ROWS -> true
                            SearchKeyNav.EnterAction.SELECT_FIRST -> {
                                keyboardIndex = 0
                                scrollTarget = 0
                                scrollRequest++
                                val row = liveRow(0)
                                if (row >= 0) controller.selectRow(row)
                                true
                            }
                            SearchKeyNav.EnterAction.PLAY_SELECTED -> {
                                val row = liveRow(keyboardIndex)
                                if (row >= 0) {
                                    controller.selectRow(row)
                                    controller.playSelected()
                                }
                                true
                            }
                        }
                    }
                    else -> false
                }
            }
            .focusable()
    } else {
        // 退场列表：指针事件全消费（含点击/滚轮），语义树清空（读屏/自动化都摸不到按钮），
        // 也不装 onKeyEvent/focusable，键盘无法把焦点送进来。
        Modifier.fillMaxSize().blockAllPointerEvents().clearAndSetSemantics { }
    }

    // 退场批即使被键盘 Tab 摸到，回调也已经是空实现：不抢焦点、不选中、不播放。
    // 用闭包而不是 `if (interactive) press else {}`：Kotlin 2.4 不会把声明处的
    // `() -> Unit` 期望类型传进 if 分支的裸 lambda，else {} 会被推成 Unit，整个 if 变 Any。
    val rowPress: () -> Unit = { if (interactive) press() }
    val rowSelect: (Long, Int) -> Unit = { id, occ -> if (interactive) select(id, occ) }
    val rowPlay: (Long, Int) -> Unit = { id, occ -> if (interactive) play(id, occ) }

    Box(listModifier) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(displayRows, key = { _, song -> song.key }, contentType = { _, _ -> "song" }) { index, song ->
                // 不装位置动画：SearchSongRow 是 SaltTrack（右键菜单用 localToWindow 取坐标），
                // 行在做位移动画时命中区域会停留在旧位置，右键会落到别的行。
                SearchSongRow(song, index, index == selected, rowPress, rowSelect, rowPlay,
                    Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = null))
            }
        }
    }
}

/** Dto.Song 是不可变 Java record；key 只用于 LazyColumn 的稳定标识。 */
@Immutable private data class SearchSong(val key: String, val value: Dto.Song, val occurrence: Int)

/**
 * 一批搜索结果：稳定 id + 行快照。
 *
 * <p>同 id 重搜时由 [SearchBatchHolder] 复用本对象，只更新 [rows]（SnapshotStateList 原地刷新），
 * 因此 Crossfade 的 target 相等、过渡不重启、LazyListState 不丢。</p>
 *
 * <p>id 变化时 holder 先把旧批 [interactive] 置 false：旧批在淡出期间不接受指针/键盘，
 * 语义树也被 [clearAndSetSemantics] 清空；旧批 rows 仍是它自己的快照，不会读到新批次内容。</p>
 */
@Stable
private class SearchBatch(val id: String, initial: List<Dto.Song>) {
    var interactive by mutableStateOf(true)
        private set

    private val liveRows = mutableStateListOf<Dto.Song>().apply { addAll(initial) }

    /** 本批自己的行快照；同 id 刷新时这个 List 实例不变，只有内容更新。 */
    val rows: List<Dto.Song> get() = liveRows

    fun update(next: List<Dto.Song>) {
        if (sameSearchRows(liveRows, next)) return
        liveRows.clear()
        liveRows.addAll(next)
    }

    fun deactivate() {
        interactive = false
    }
}

/**
 * 批槽：同 id 复用对象并原地更新 rows；id 变化才换批并让旧批退场。
 * 用普通字段而非 Compose State 持有 current，Crossfade 的目标相等性由 SearchBatch 的身份保证。
 */
private class SearchBatchHolder {
    private var current: SearchBatch? = null

    fun present(id: String, rows: List<Dto.Song>): SearchBatch? {
        val shown = current
        if (rows.isEmpty()) {
            shown?.deactivate()
            current = null
            return null
        }
        if (shown != null && shown.id == id) {
            shown.update(rows)
            return shown
        }
        shown?.deactivate()
        return SearchBatch(id, rows).also { current = it }
    }
}

/** 逐项比较；SnapshotStateList 的 equals 不是内容比较，不能用 == 判断是否需要刷新。 */
private fun sameSearchRows(a: List<Dto.Song>, b: List<Dto.Song>): Boolean {
    if (a.size != b.size) return false
    for (index in a.indices) if (a[index] != b[index]) return false
    return true
}

/** 行 key = 歌曲 id + 同一批结果里的重复出现序号；同一首歌出现多次也稳定且唯一。 */
private fun List<Dto.Song>.toSearchSongs(): List<SearchSong> {
    val seen = HashMap<Long, Int>(size)
    return map { song ->
        val occurrence = seen[song.id()] ?: 0
        seen[song.id()] = occurrence + 1
        SearchSong(SearchBatchPolicy.rowKey(song.id(), occurrence), song, occurrence)
    }
}

@Composable private fun SearchSongRow(song: SearchSong, index: Int, selected: Boolean,
                                     press: () -> Unit, select: (Long, Int) -> Unit,
                                     play: (Long, Int) -> Unit, modifier: Modifier = Modifier) {
    // 选中底色只做 120ms 颜色过渡：背景色不参与测量，行坐标与右键菜单判定不受影响。
    val rowBackground by animateColorAsState(
        targetValue = if (selected) HostStyle.colors.subBackground else Color.Transparent,
        animationSpec = Motion.state(),
        label = "search-row",
    )
    SaltTrack(song.value, index,
        modifier.fillMaxWidth().background(rowBackground),
        onPress = press,
        onSelect = remember(select, song) { { select(song.value.id(), song.occurrence) } },
        onPlay = remember(play, song) { { play(song.value.id(), song.occurrence) } }, showNote = false,
        onContext = remember(song) { { NativeSongMenu.open(song.value, it) } })
}

/** Pure navigation logic shared by the topbar suggestions and result list. */
internal object SearchKeyNav {
    const val NO_SELECTION: Int = -1
    enum class EnterAction { PLAY_SELECTED, SELECT_FIRST, NO_ROWS }
    fun move(current: Int, count: Int, delta: Int): Int {
        if (count <= 0) return NO_SELECTION
        if (delta == 0) return current.coerceIn(0, count - 1)
        val base = when {
            current < 0 -> if (delta > 0) NO_SELECTION else count
            current >= count -> count - 1
            else -> current
        }
        return (base + delta).coerceIn(0, count - 1)
    }
    fun enterAction(rowCount: Int, selectedRow: Int): EnterAction = when {
        rowCount <= 0 -> EnterAction.NO_ROWS
        selectedRow in 0 until rowCount -> EnterAction.PLAY_SELECTED
        else -> EnterAction.SELECT_FIRST
    }
}

/**
 * 搜索结果批的纯逻辑（Java 回归探针可直接反射调用）。
 *
 * <p>批身份只用 SearchController.State 里真实存在、且确实代表「当前显示来源」的字段：
 * sourceKeyword 是结果真正落地时写入的关键词。requestedKeyword 在请求刚发出时就变化，
 * 若纳入身份会让一次搜索在「发起」和「落地」各换一次批；State 也没有 requestSeq/epoch，
 * 这里不编造字段。同一关键词重搜因此落在同一批，rows 原地刷新。</p>
 */
internal object SearchBatchPolicy {
    fun identity(sourceKeyword: String?): String = "search:" + (sourceKeyword ?: "").trim()

    fun rowKey(songId: Long, occurrence: Int): String = songId.toString() + ":" + occurrence

    /** 按歌曲 id + 同 id 重复出现序号在当前列表里定位；找不到返回 -1。 */
    fun resolveRow(rows: List<Dto.Song>, songId: Long, occurrence: Int): Int {
        if (occurrence < 0) return -1
        var seen = 0
        for (index in rows.indices) {
            val song = rows[index]
            if (song.id() != songId) continue
            if (seen == occurrence) return index
            seen++
        }
        return -1
    }

    fun resolveRow(rows: List<Dto.Song>, songId: Long): Int = resolveRow(rows, songId, 0)
}
