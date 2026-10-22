package com.example.netease.nativeui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
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
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Enter || event.key == Key.NumPadEnter)
                    ) {
                        controller.search(NativeUiState.searchKeyword)
                        true
                    } else {
                        false
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
        SearchResultList(controller, state, Modifier.weight(1f))
        Text(
            text = "规则：新搜索会保留旧列表并标明新关键词；只有成功才同时替换列表与来源；" +
                "失败保留旧列表且可重试；播放用宿主返回的请求编号读反馈，20 秒搜索超时与 30 秒播放反馈窗口不变。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SearchResultList(controller: SearchController, state: SearchController.State, modifier: Modifier) {
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
    LazyColumn(modifier.fillMaxWidth()) {
        itemsIndexed(rows) { index, song ->
            SearchRow(
                index = index,
                song = song,
                selected = state.selectedSongId() == song.id(),
                onSelect = { controller.select(song.id()) },
                onPlay = {
                    controller.select(song.id())
                    controller.playSelected()
                },
            )
        }
    }
}

@Composable
private fun SearchRow(index: Int, song: Dto.Song, selected: Boolean, onSelect: () -> Unit, onPlay: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .pointerInput(song.id()) {
                detectTapGestures(
                    onTap = { onSelect() },
                    onDoubleTap = { onPlay() },
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
        "结果来源：「" + source + "」 · " + state.rows().size + " 条 · 双击结果行或点「播放所选」播放"
    }
}
