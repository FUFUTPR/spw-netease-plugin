package com.example.netease.nativeui

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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
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
        val focus = remember { FocusRequester() }
        SearchResultList(controller, state, focus, Modifier.weight(1f))
        if (NativeSongMenu.visible) NativeSongMenu.state?.let {
            HostSongPopup.Menu(it, NativeSongMenu::dismiss, NativeSongMenu::add)
        }
    }
}

@Composable
private fun SearchResultList(controller: SearchController, state: SearchController.State,
                             focus: FocusRequester, modifier: Modifier) {
    val rows = state.rows()
    if (rows.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SaltText(when (state.phase()) {
                SearchController.Phase.SEARCHING -> "搜索中…"
                SearchController.Phase.FAILED -> "搜索暂不可用，请在顶栏重新搜索"
                SearchController.Phase.EMPTY -> "没有找到相关歌曲"
                else -> "在顶栏输入关键词搜索"
            }, modifier = Modifier.padding(24.dp))
        }
        return
    }
    val list = rememberLazyListState()
    val selected = rows.indexOfFirst { it.id() == state.selectedSongId() }
    var keyboardIndex by remember(selected) { mutableStateOf(selected) }
    var scrollTarget by remember { mutableStateOf(-1) }
    var scrollRequest by remember { mutableStateOf(0) }
    val displayRows = remember(rows) { rows.map { SearchSong(it) } }
    val press = remember(focus) { { runCatching { focus.requestFocus() }; Unit } }
    val select = remember(controller, focus) { { row: Int ->
        press(); scrollTarget = -1; controller.selectRow(row)
    } }
    val play = remember(controller, focus) { { row: Int ->
        press(); controller.selectRow(row); controller.playSelected()
    } }
    LaunchedEffect(scrollRequest) {
        if (scrollTarget in rows.indices && list.layoutInfo.visibleItemsInfo.none { it.index == scrollTarget })
            list.animateScrollToItem(scrollTarget)
    }
    Box(modifier.fillMaxWidth().focusRequester(focus).onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
            Key.DirectionDown, Key.DirectionUp -> {
                keyboardIndex = SearchKeyNav.move(keyboardIndex, rows.size, if (event.key == Key.DirectionDown) 1 else -1)
                controller.selectRow(keyboardIndex); scrollTarget = keyboardIndex; scrollRequest++; true
            }
            Key.Enter, Key.NumPadEnter -> {
                if (keyboardIndex in rows.indices) { controller.selectRow(keyboardIndex); controller.playSelected() }
                else { keyboardIndex = 0; controller.selectRow(0); scrollTarget = 0; scrollRequest++ }
                true
            }
            else -> false
        }
    }.focusable()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(displayRows, key = { index, song -> "${song.value.id()}:$index" }, contentType = { _, _ -> "song" }) { index, song ->
                SearchSongRow(song, index, index == selected, press, select, play)
            }
        }
    }
}

/** Dto.Song is an immutable Java record; allow unchanged rows to skip composition. */
@Immutable private data class SearchSong(val value: com.example.netease.net.Dto.Song)
@Composable private fun SearchSongRow(song: SearchSong, index: Int, selected: Boolean,
                                     press: () -> Unit, select: (Int) -> Unit, play: (Int) -> Unit) {
    SaltTrack(song.value, index,
        Modifier.fillMaxWidth().then(if (selected) Modifier.background(HostStyle.colors.subBackground) else Modifier),
        onPress = press, onSelect = remember(select, index) { { select(index) } },
        onPlay = remember(play, index) { { play(index) } }, showNote = false,
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
