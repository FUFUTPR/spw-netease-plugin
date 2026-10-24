@file:OptIn(androidx.compose.runtime.InternalComposeApi::class)
package com.example.netease.nativeui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.ui.focus.focusProperties
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
    var fieldBounds = Rect.Zero
    var suggestionBounds = Rect.Zero
    fun collapse() { expanded = false; focused = false; highlighted = -1 }
    var state by mutableStateOf<SearchController.State?>(null)
    var highlighted by mutableStateOf(-1)
    @Volatile private var controller: SearchController? = null
    @Synchronized fun start() {
        if (controller == null) controller = SearchController.createDefault({ state = it }, 5)
    }
    fun stop() {
        val old = synchronized(this) { val c = controller; controller = null; c }
        old?.close()
        collapse(); state = null; fieldBounds = Rect.Zero; suggestionBounds = Rect.Zero
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

@Composable
internal fun NativeTopSearchField(navigate: () -> Boolean, modifier: Modifier = Modifier) {
    val keyword = NativeUiState.searchKeyword
    val focus = LocalFocusManager.current
    val radius by animateDpAsState(if (NativeTopSearch.focused) 9.dp else 18.dp,
        tween(220, easing = FastOutSlowInEasing), label = "search-radius")
    val shape = RoundedCornerShape(radius)
    LaunchedEffect(NativeTopSearch.focused) { if (!NativeTopSearch.focused) focus.clearFocus() }
    LaunchedEffect(keyword, NativeTopSearch.expanded) {
        if (NativeTopSearch.expanded && keyword.isNotBlank()) { delay(250); NativeTopSearch.suggest(keyword.trim()) }
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
    val availableWidth = maxWidth
    val width by animateDpAsState(if (NativeTopSearch.focused) maxWidth else minOf(220.dp, maxWidth),
        tween(220, easing = FastOutSlowInEasing), label = "search-width")
    Row(Modifier.width(minOf(width, maxWidth)).height(34.dp)
        .onGloballyPositioned { NativeTopSearch.fieldBounds = it.boundsInRoot() }
        .border(1.dp, if (NativeTopSearch.focused) HostStyle.colors.highlight else HostStyle.colors.stroke, shape)
        .background(HostStyle.colors.subBackground, shape).clip(shape),
        verticalAlignment = Alignment.CenterVertically) {
        SaltInput(keyword, NativeTopSearch::edit,
            Modifier.weight(1f).onFocusChanged {
                NativeTopSearch.focused = it.isFocused
                NativeTopSearch.expanded = it.isFocused && NativeUiState.searchKeyword.isNotBlank()
            }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
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
    val focus = LocalFocusManager.current
    Column(modifier.onGloballyPositioned { NativeTopSearch.suggestionBounds = it.boundsInRoot() }
        .shadow(10.dp, HostStyle.shapes.small).clip(HostStyle.shapes.small)
        .background(HostStyle.colors.popup, HostStyle.shapes.small)
        .border(1.dp, HostStyle.colors.stroke, HostStyle.shapes.small).padding(vertical = 4.dp)) {
        if (rows.isEmpty()) SaltText(
            when (NativeTopSearch.state?.phase()) {
                SearchController.Phase.FAILED -> "搜索暂不可用，按回车重试"
                SearchController.Phase.EMPTY -> "没有找到相关歌曲"
                else -> "搜索中…"
            }, modifier = Modifier.padding(16.dp), style = HostStyle.typography.bodySmall)
        rows.forEachIndexed { index, song ->
            val interaction = remember(song.id()) { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            SaltTrack(song, index,
                Modifier.fillMaxWidth().hoverable(interaction).then(if (hovered || index == NativeTopSearch.highlighted)
                    Modifier.background(HostStyle.colors.subBackground) else Modifier),
                onSelect = { NativeTopSearch.play(song); focus.clearFocus() }, onPlay = null, showNote = false)
        }
    }
}
