@file:OptIn(androidx.compose.runtime.InternalComposeApi::class)
package com.example.netease.nativeui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.example.netease.net.Dto
import com.example.netease.ui.controller.SearchController
import kotlinx.coroutines.delay

/** Suggestions own a separate controller: typing must not replace the submitted results. */
internal object NativeTopSearch {
    var expanded by mutableStateOf(false)
    var focused by mutableStateOf(false)
    var compactOpen by mutableStateOf(false)
    private var focusOwner: Any? = null
    var fieldBounds = Rect.Zero
    var compactBounds = Rect.Zero
    var suggestionBounds = Rect.Zero
    fun collapse() { expanded = false; focused = false; compactOpen = false; highlighted = -1; focusOwner = null }
    fun suspendForDialog() { expanded = false; focused = false; highlighted = -1; focusOwner = null }
    fun focusChanged(owner: Any, hasFocus: Boolean) {
        if (hasFocus) {
            focusOwner = owner; focused = true; expanded = NativeUiState.searchKeyword.isNotBlank()
        } else if (focusOwner === owner) {
            focusOwner = null; focused = false; expanded = false
        }
    }
    var state by mutableStateOf<SearchController.State?>(null)
    var highlighted by mutableStateOf(-1)
    @Volatile private var controller: SearchController? = null
    @Synchronized fun start() {
        if (controller == null) controller = SearchController.createDefault({ state = it }, 5)
    }
    fun stop() {
        val old = synchronized(this) { val c = controller; controller = null; c }
        old?.close()
        collapse(); state = null; fieldBounds = Rect.Zero; compactBounds = Rect.Zero; suggestionBounds = Rect.Zero
    }
    fun edit(text: String) {
        NativeUiState.searchKeyword = text
        controller?.search("") // Immediately invalidate the old in-flight request, before debounce.
        state = null; highlighted = -1; expanded = text.isNotBlank()
    }
    fun suggest(keyword: String) { if (keyword.isNotBlank()) controller?.search(keyword) }
    fun rows(): List<Dto.Song> = state?.takeIf {
        it.phase() == SearchController.Phase.RESULTS && it.sourceKeyword() == NativeUiState.searchKeyword.trim()
    }?.rows()?.take(5).orEmpty()
    fun play(song: Dto.Song) {
        controller?.select(song.id()); controller?.playSelected()
        clearSubmittedText()
    }
    private fun clearSubmittedText() {
        // Do not call edit(""): it cancels the candidate controller's playback feedback.
        NativeUiState.searchKeyword = ""
        collapse()
    }
    fun accountChanged() { controller?.onAccountChanged(); collapse(); state = null }
    fun tick() { controller?.tick() }
    fun submit(navigate: () -> Boolean) {
        val keyword = NativeUiState.searchKeyword.trim()
        if (keyword.isEmpty()) { expanded = false; return }
        if (!navigate()) return
        expanded = false
        NativeUiSession.search()?.search(keyword)
        clearSubmittedText()
    }
}

/**
 * 候选区只有这四种稳定阶段：结果、无结果、失败、加载中。
 *
 * AnimatedContent 的 targetState 只用它（枚举），绝不用 List 身份或行数，
 * 因此同一批候选被播放状态刷新时不会重播过渡。
 */
private enum class SuggestPhase { LOADING, RESULTS, EMPTY, FAILED }

/**
 * 由候选行与控制器阶段推导目标阶段。
 *
 * rows() 已经过滤掉「上一个关键词」的结果：此时 rows 为空但 phase 仍是 RESULTS，
 * 必须当作加载中，不能误报「没有找到相关歌曲」。
 */
private fun suggestPhase(rows: List<Dto.Song>): SuggestPhase = when {
    rows.isNotEmpty() -> SuggestPhase.RESULTS
    NativeTopSearch.state?.phase() == SearchController.Phase.FAILED -> SuggestPhase.FAILED
    NativeTopSearch.state?.phase() == SearchController.Phase.EMPTY -> SuggestPhase.EMPTY
    else -> SuggestPhase.LOADING
}

/** 行标识 = 歌曲 id + 同一批候选里的重复序号；不使用 List 下标做 key。 */
private fun suggestRowKey(song: Dto.Song, occurrence: Int): String = song.id().toString() + ":" + occurrence

@Composable
internal fun NativeTopSearchField(navigate: () -> Boolean, modifier: Modifier = Modifier, interactive: Boolean = true,
                                  requestFocus: Boolean = false, fillAvailable: Boolean = false) {
    val keyword = NativeUiState.searchKeyword
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    val focusOwner = remember { Any() }
    LaunchedEffect(requestFocus) { if (requestFocus && interactive) requester.requestFocus() }
    // 关闭弹窗（存歌单/登录等）后把焦点还给顶栏搜索框（按窗口登记真实触发点）。
    val radius by animateDpAsState(if (NativeTopSearch.focused) 9.dp else 18.dp,
        tween(220, easing = FastOutSlowInEasing), label = "search-radius")
    val shape = RoundedCornerShape(radius)
    // 焦点态只做 120ms 颜色过渡：边框是画笔颜色，不参与测量，候选面板判定坐标不变。
    val borderColor by animateColorAsState(
        targetValue = if (NativeTopSearch.focused) HostStyle.colors.highlight else HostStyle.colors.stroke,
        animationSpec = Motion.state(),
        label = "search-border",
    )
    LaunchedEffect(NativeTopSearch.focused) { if (!NativeTopSearch.focused && !requestFocus) focus.clearFocus() }
    // 对话框打开时搜索层仍保留在组合里（关闭后要靠它归还焦点），但必须立刻收起候选并让出焦点。
    LaunchedEffect(interactive) {
        if (!interactive) {
            NativeTopSearch.suspendForDialog()
            focus.clearFocus()
        }
    }
    LaunchedEffect(keyword, NativeTopSearch.expanded) {
        if (NativeTopSearch.expanded && keyword.isNotBlank()) { delay(250); NativeTopSearch.suggest(keyword.trim()) }
    }
    // 焦点归还登记挂在下面 SaltInput 的 Modifier 上（非布局 Modifier）；这里不再重复登记，
    // BoxWithConstraints 的测量顺序（width / height / fieldBounds）保持原样。
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
    val availableWidth = maxWidth
    val width = if (fillAvailable) maxWidth else animateDpAsState(
        if (NativeTopSearch.focused) maxWidth else minOf(220.dp, maxWidth),
        Motion.topbar(), label = "search-width").value
    Row(Modifier.width(minOf(width, maxWidth)).height(34.dp)
        .onGloballyPositioned { NativeTopSearch.fieldBounds = it.boundsInRoot() }
        .border(1.dp, borderColor, shape)
        .background(HostStyle.colors.subBackground, shape).clip(shape),
        verticalAlignment = Alignment.CenterVertically) {
        SaltInput(keyword, { text -> if (interactive) NativeTopSearch.edit(text) },
            Modifier.weight(1f).focusRequester(requester).focusReturnTarget().onFocusChanged {
                NativeTopSearch.focusChanged(focusOwner, it.isFocused)
            }
                .onPreviewKeyEvent { event ->
                    if (!interactive) true
                    else if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.Enter, Key.NumPadEnter -> { NativeTopSearch.submit(navigate); focus.clearFocus(); true }
                        Key.Escape -> { NativeTopSearch.collapse(); focus.clearFocus(); true }
                        Key.DirectionDown -> { NativeTopSearch.expanded = keyword.isNotBlank();
                            NativeTopSearch.highlighted = SearchKeyNav.move(NativeTopSearch.highlighted, NativeTopSearch.rows().size, 1); true }
                        Key.DirectionUp -> { NativeTopSearch.highlighted = SearchKeyNav.move(NativeTopSearch.highlighted, NativeTopSearch.rows().size, -1); true }
                        else -> false
                    }
                }, hint = if (availableWidth < 200.dp) "搜索" else "搜索歌曲、歌手或专辑", compact = true)
        if (NativeTopSearch.focused) SaltPlainButton(onClick = { NativeTopSearch.submit(navigate); focus.clearFocus() },
            modifier = Modifier.focusProperties { canFocus = false }) { SaltText("搜索") }
    }
    }
}

@Composable
internal fun NativeTopSearchSuggestions(modifier: Modifier = Modifier) {
    val rows = NativeTopSearch.rows()
    val phase = suggestPhase(rows)
    val focus = LocalFocusManager.current
    // 退场帧仍要画出「最后一批候选」：此时业务状态已清空，rows() 读到的只会是空列表，
    // 所以用 retain 的不可变快照喂 AnimatedContent 的旧内容。
    val shownRows = rememberRetained(rows.takeIf { it.isNotEmpty() })
    Column(modifier.onGloballyPositioned { NativeTopSearch.suggestionBounds = it.boundsInRoot() }
        .shadow(10.dp, HostStyle.shapes.small).clip(HostStyle.shapes.small)
        .background(HostStyle.colors.popup, HostStyle.shapes.small)
        .border(1.dp, HostStyle.colors.stroke, HostStyle.shapes.small).padding(vertical = 4.dp)) {
        // 只在阶段之间过渡；整块展开/收起仍由宿主侧既有的 AnimatedVisibility 负责。
        // 这里不给被 suggestionBounds 测量的 Column 加任何 transform。
        AnimatedContent(
            targetState = phase,
            modifier = Modifier.fillMaxWidth(),
            transitionSpec = { fadeIn(Motion.state()) togetherWith fadeOut(Motion.state()) },
            label = "top-search-candidates",
        ) { target ->
            when (target) {
                SuggestPhase.RESULTS -> Column(Modifier.fillMaxWidth()) {
                    val songs = shownRows.orEmpty()
                    val seen = HashMap<Long, Int>(songs.size)
                    songs.forEachIndexed { index, song ->
                        val occurrence = seen[song.id()] ?: 0
                        seen[song.id()] = occurrence + 1
                        key(suggestRowKey(song, occurrence)) {
                            SuggestRow(song, index) { NativeTopSearch.play(song); focus.clearFocus() }
                        }
                    }
                }
                SuggestPhase.EMPTY -> SaltText("没有找到相关歌曲",
                    modifier = Modifier.padding(16.dp), style = HostStyle.typography.bodySmall)
                SuggestPhase.FAILED -> SaltText("搜索暂不可用，按回车重试",
                    modifier = Modifier.padding(16.dp), style = HostStyle.typography.bodySmall)
                SuggestPhase.LOADING -> SaltText("搜索中…",
                    modifier = Modifier.padding(16.dp), style = HostStyle.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SuggestRow(song: Dto.Song, index: Int, onSelect: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // 悬停/键盘高亮只做 120ms 颜色过渡：背景色不参与测量，候选面板与外部点击判定坐标不变。
    val rowBackground by animateColorAsState(
        targetValue = if (hovered || index == NativeTopSearch.highlighted) HostStyle.colors.subBackground
        else Color.Transparent,
        animationSpec = Motion.state(),
        label = "search-suggestion",
    )
    SaltTrack(song, index,
        Modifier.fillMaxWidth().hoverable(interaction).background(rowBackground),
        onSelect = onSelect, onPlay = null, showNote = false)
}
