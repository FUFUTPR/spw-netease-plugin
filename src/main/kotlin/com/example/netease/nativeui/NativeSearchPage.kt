package com.example.netease.nativeui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.netease.net.Dto
import com.example.netease.ui.controller.SearchController

/**
 * 搜索页（docs/PLAN.md §7.2）：标题与返回由外壳提供；这里负责输入、提交、结果列表、
 * 播放所选、当前请求反馈与两个独立的「重试」（搜索重试 / 播放重试分开）。
 *
 * <p>状态全部来自 [SearchController.State]：列表与来源永远同进同退；新搜索进行中显示的是
 * 旧列表 + 新关键词；失败时保留旧列表并在文案里说明。</p>
 *
 * <p>键盘约定：搜索框的 Enter 只提交搜索；点击结果行（或在搜索框按 ↓）让结果区获得焦点后，
 * ↑/↓ 移动选中行并自动滚动到可见，Enter 播放当前选中；鼠标单击选中、双击播放保持不变。</p>
 */
@Composable
internal fun NativeSearchPage() {
    val controller = NativeUiSession.search()
    val state = NativeUiState.search
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (controller == null || state == null) {
            Text("搜索控制器尚未就绪（插件可能正在启动或已停用）", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        val resultFocusRequester = remember { FocusRequester() }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = NativeUiState.searchKeyword,
                onValueChange = { NativeUiState.searchKeyword = it },
                singleLine = true,
                modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                    when {
                        event.type != KeyEventType.KeyDown -> false
                        // 搜索框的 Enter 只提交搜索，不能被结果区劫持。
                        event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                            controller.search(NativeUiState.searchKeyword)
                            true
                        }
                        // 有结果时按 ↓ 把键盘焦点送入结果区，方便纯键盘操作。
                        event.key == Key.DirectionDown && state.rows().isNotEmpty() ->
                            runCatching { resultFocusRequester.requestFocus() }.isSuccess
                        else -> false
                    }
                },
                placeholder = { Text("输入关键词，回车或点「搜索」提交") },
            )
            Button(onClick = { controller.search(NativeUiState.searchKeyword) }) {
                Text("搜索")
            }
        }
        Text(
            text = searchSourceLine(state),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { controller.playSelected() },
                enabled = state.selectedSongId() != null,
            ) {
                Text("播放所选")
            }
            OutlinedButton(
                onClick = { controller.retrySearch() },
                enabled = state.searchRetryable(),
            ) {
                Text("重试搜索")
            }
            OutlinedButton(
                onClick = { controller.retryPlayback() },
                enabled = state.playRetryable(),
            ) {
                Text("重试上次播放")
            }
            Text(
                text = state.message(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.playbackText().isNotBlank()) {
            Text(
                text = "播放反馈（宿主回报，不代表已实际出声）：" + state.playbackText(),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        SearchResultList(controller, state, resultFocusRequester, Modifier.weight(1f))
        Text(
            text = "规则：新搜索会保留旧列表并标明新关键词；只有成功才同时替换列表与来源；" +
                "失败保留旧列表且可重试；播放用宿主返回的请求编号读反馈，20 秒搜索超时与 30 秒播放反馈窗口不变。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SearchResultList(
    controller: SearchController,
    state: SearchController.State,
    focusRequester: FocusRequester,
    modifier: Modifier,
) {
    val rows = state.rows()
    if (rows.isEmpty()) {
        Text(
            text = when (state.phase()) {
                SearchController.Phase.IDLE -> "尚未搜索：输入关键词后回车。"
                SearchController.Phase.SEARCHING -> "搜索中…"
                SearchController.Phase.EMPTY -> "没有找到结果，可换个关键词重试。"
                SearchController.Phase.FAILED -> "搜索失败：可点「重试搜索」重试。"
                SearchController.Phase.RESULTS -> ""
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.padding(vertical = 12.dp),
        )
        return
    }
    val listState = rememberLazyListState()
    var focused by remember { mutableStateOf(false) }
    // 键盘导航的当前位置：外部状态变化（点击、新列表）后通过 remember 键重新同步。
    val selectedIndex = rows.indexOfFirst { it.id() == state.selectedSongId() }
    var keyboardIndex by remember(selectedIndex) { mutableStateOf(selectedIndex) }
    var pendingKeyboardScroll by remember { mutableStateOf(SearchKeyNav.NO_SELECTION) }
    // 用单调递增的请求编号触发滚动：即使目标行号没变（例如在首行继续按 ↑），也能重新滚到可见。
    var keyboardScrollRequest by remember { mutableStateOf(0) }
    LaunchedEffect(keyboardScrollRequest) {
        val target = pendingKeyboardScroll
        // 只有键盘移动/Enter 才自动滚动，鼠标点击不强行把行滚到顶部。
        if (target in rows.indices &&
            listState.layoutInfo.visibleItemsInfo.none { it.index == target }
        ) {
            listState.animateScrollToItem(target)
        }
    }
    Column(modifier.fillMaxWidth()) {
        Text(
            text = if (focused) {
                "结果区已获得键盘焦点：↑/↓ 选择，Enter 播放所选"
            } else {
                "点击结果行聚焦（也可在搜索框按 ↓ 进入结果区）：↑/↓ 选择，Enter 播放所选"
            },
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (event.key) {
                            Key.DirectionUp -> {
                                val current = if (keyboardIndex in rows.indices) keyboardIndex else selectedIndex
                                val target = SearchKeyNav.move(current, rows.size, -1)
                                keyboardIndex = target
                                controller.selectRow(target)
                                pendingKeyboardScroll = target
                                keyboardScrollRequest++
                                true
                            }
                            Key.DirectionDown -> {
                                val current = if (keyboardIndex in rows.indices) keyboardIndex else selectedIndex
                                val target = SearchKeyNav.move(current, rows.size, 1)
                                keyboardIndex = target
                                controller.selectRow(target)
                                pendingKeyboardScroll = target
                                keyboardScrollRequest++
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                val current = if (keyboardIndex in rows.indices) keyboardIndex else selectedIndex
                                when (SearchKeyNav.enterAction(rows.size, current)) {
                                    SearchKeyNav.EnterAction.PLAY_SELECTED -> {
                                        controller.selectRow(current)
                                        controller.playSelected()
                                        true
                                    }
                                    SearchKeyNav.EnterAction.SELECT_FIRST -> {
                                        keyboardIndex = 0
                                        controller.selectRow(0)
                                        pendingKeyboardScroll = 0
                                        keyboardScrollRequest++
                                        true
                                    }
                                    SearchKeyNav.EnterAction.NO_ROWS -> false
                                }
                            }
                            else -> false
                        }
                    }
                }
                .focusable()
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(rows) { index, song ->
                    SearchRow(
                        index = index,
                        song = song,
                        selected = index == selectedIndex,
                        onFocusRequest = { requestFocusSafely(focusRequester) },
                        onSelect = {
                            pendingKeyboardScroll = SearchKeyNav.NO_SELECTION
                            controller.select(song.id())
                        },
                        onPlay = {
                            controller.select(song.id())
                            controller.playSelected()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchRow(
    index: Int,
    song: Dto.Song,
    selected: Boolean,
    onFocusRequest: () -> Unit,
    onSelect: () -> Unit,
    onPlay: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val selectedBorder = if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary) else Modifier
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .then(selectedBorder)
            .pointerInput(song.id()) {
                detectTapGestures(
                    // 按下即请求焦点：单击选中会等双击判定，先把键盘焦点交给结果区，避免紧接着按 Enter 仍落在搜索框。
                    onPress = { onFocusRequest() },
                    onTap = {
                        onFocusRequest()
                        onSelect()
                    },
                    onDoubleTap = {
                        onFocusRequest()
                        onPlay()
                    },
                )
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = (index + 1).toString(),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.5f),
        )
        Text(
            text = song.name().orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
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
        Text(
            text = trackNote(song),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1.2f),
        )
    }
}

/** 列表与来源一句话：搜索中要同时出现「旧来源」与「新关键词」。 */
private fun searchSourceLine(state: SearchController.State): String {
    val source = if (state.sourceKeyword().isBlank()) "尚未搜索" else state.sourceKeyword()
    val requested = state.requestedKeyword()
    return if (state.phase() == SearchController.Phase.SEARCHING && requested != state.sourceKeyword()) {
        "正在搜索「" + requested + "」，下方仍是「" + source + "」的结果（" + state.rows().size + " 条）"
    } else {
        "结果来源：「" + source + "」 · " + state.rows().size +
            " 条 · 双击结果行、点「播放所选」，或选中后按 Enter 播放"
    }
}

/** 焦点请求在窗口尚未激活等情况下可能抛异常；这里按「未获得焦点」处理，不打断点击。 */
private fun requestFocusSafely(focusRequester: FocusRequester) {
    runCatching { focusRequester.requestFocus() }
}

/**
 * 搜索结果键盘导航的纯逻辑，刻意不引用 Compose，离线探针可直接反射调用。
 *
 * <p>[move] 采用夹取（clamp）而不是回绕：到首/尾后继续按同方向键会停在原地；
 * 无选中（负数）时 ↓ 选第一行、↑ 选最后一行；越界的高行号按最后一行处理。
 * [enterAction] 只有存在有效选中时才播放，有结果但未选中时先选第一行，空结果不响应。</p>
 */
internal object SearchKeyNav {
    /** 无选中行的哨兵值，与 SearchController.State.selectedRow() 的空值一致。 */
    const val NO_SELECTION: Int = -1

    /** Enter 在结果区的归约结果。 */
    enum class EnterAction {
        /** 有有效选中，应当播放。 */
        PLAY_SELECTED,

        /** 有结果但没有有效选中，先选第一行（不直接播放）。 */
        SELECT_FIRST,

        /** 没有结果，不响应。 */
        NO_ROWS,
    }

    /**
     * 上下移动选中行。
     *
     * @param current 当前选中行；[NO_SELECTION] 或负数表示未选中
     * @param count 结果行数
     * @param delta 移动方向：负数向上，正数向下，0 保持
     * @return 夹取到 `0 until count` 的目标行；没有结果时返回 [NO_SELECTION]
     */
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

    /** 把 Enter 归约为「播放 / 先选第一行 / 无动作」。 */
    fun enterAction(rowCount: Int, selectedRow: Int): EnterAction = when {
        rowCount <= 0 -> EnterAction.NO_ROWS
        selectedRow in 0 until rowCount -> EnterAction.PLAY_SELECTED
        else -> EnterAction.SELECT_FIRST
    }
}
