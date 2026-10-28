@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class, androidx.compose.runtime.InternalComposeApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.example.netease.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.netease.cfg.PluginConfig
import com.example.netease.core.PluginLog
import com.example.netease.host.HostDailyPlaylistAbi
import com.example.netease.svc.DailyRecommendations
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.*
import kotlinx.coroutines.delay

/** Add controls above the native playlist content. Queue, selection and playback remain native. */
internal object HostDailyRecommendations {
    private val handles = mutableListOf<HookHandle>()
    private val defaults = mutableListOf<AutoCloseable>()
    private var target: HostDailyPlaylistAbi.Target? = null
    private var dialog by mutableStateOf("")
    private var state by mutableStateOf(DailyRecommendations.state())
    private var pageBounds by mutableStateOf(Rect.Zero)
    private var pageOwner by mutableStateOf<Any?>(null)
    fun dialogOpen() = dialog.isNotEmpty()
    fun dismiss() { dialog = ""; NativeUiPresentation.requestFocusReturn() }
    fun install() {
        if (handles.isNotEmpty()) return
        val resolved = HostDailyPlaylistAbi.resolve()
        target = resolved
        try {
            resolved.callers().forEach { caller ->
                handles += WorkshopApi.hookRegistrar.replaceComposable(resolved.method().declaringClass.name,
                    resolved.method().name, caller, resolved.method().parameterTypes.take(2).map { it.name }, 100, Hook)
                defaults += HostReplacementDefaults.prepare(WorkshopApi.hookRegistrar, resolved.method(), caller)
            }
            PluginLog.i("daily-ui", "每日推荐歌单工具栏已注册（含宿主默认参数调用）")
        } catch (error: Throwable) { uninstall(); throw error }
    }
    fun uninstall() {
        defaults.asReversed().forEach { runCatching { it.close() } }; defaults.clear()
        handles.asReversed().forEach { runCatching { it.unhook() } }; handles.clear()
        target = null; dialog = ""; pageOwner = null; pageBounds = Rect.Zero
    }
    private object Hook : ComposableHook() {
        @Composable override fun Content(call: UiHookCall) {
            val method = target?.method() ?: return
            val route = call.arguments[0]
            val id = route?.javaClass?.getMethod("getId")?.invoke(route) as? String
            val daily = id == DailyRecommendations.HOST_ID
            @Composable fun original() {
                method.invoke(null, route, call.arguments[1], currentComposer, 0, if (call.arguments[1] == null) 2 else 0)
            }
            if (!daily) { original(); return }
            DisposableEffect(route) {
                pageOwner = route
                guardedCleanup { if (pageOwner === route) { pageOwner = null; pageBounds = Rect.Zero } }
            }
            BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { pageBounds = it.boundsInRoot() }) {
                val top = if (maxWidth >= 850.dp) 0.dp else if (maxWidth < 420.dp) 96.dp else 56.dp
                Box(Modifier.fillMaxSize().padding(top = top)) { original() }
            }
        }
    }

    /** Root sibling above native navigation: its hit targets cannot be covered by host content. */
    @Composable fun BoxScope.Toolbar(rootBounds: Rect, interactive: Boolean) {
        if (pageOwner == null || pageBounds.width <= 0f) return
        // The navigation stack changes before the outgoing screen's animation disposes it.
        if (HostGlobalSearch.currentRoute() != pageOwner) return
        LaunchedEffect(pageOwner) { while (true) { state = DailyRecommendations.state(); delay(200) } }
        val density = LocalDensity.current
        val width = with(density) { pageBounds.width.toDp() }
        val left = with(density) { (pageBounds.left - rootBounds.left).toDp() }
        val top = with(density) { (pageBounds.top - rootBounds.top).toDp() }
        val wide = width >= 850.dp
        val toolbarWidth = if (wide) 400.dp else (width - 32.dp).coerceAtLeast(0.dp)
        Column(Modifier.offset(if (wide) left + width - 244.dp - toolbarWidth else left + 16.dp,
                top + if (wide) 16.dp else 8.dp).width(toolbarWidth).testTag("daily-toolbar"),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val available = interactive && PluginConfig.dailyRecommendations()
                SaltSecondaryButton(onClick = { PluginLog.i("daily-ui", "点击今日日推"); DailyRecommendations.today() }, enabled = available && !state.busy()) { SaltText("今日日推") }
                SaltSecondaryButton(onClick = { PluginLog.i("daily-ui", "点击风格推荐"); DailyRecommendations.style() }, enabled = available && !state.busy()) { SaltText("风格推荐") }
                SaltSecondaryButton(onClick = { PluginLog.i("daily-ui", "点击历史日推"); dialog = "history"; DailyRecommendations.historyDates() },
                    enabled = available && !state.busy()) { SaltText("历史日推") }
                SaltSecondaryButton(onClick = { PluginLog.i("daily-ui", "点击关闭每日推荐"); dialog = "disable" }, enabled = available) { SaltText("关闭每日推荐") }
            }
            SaltText(state.message(), style = HostStyle.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    @Composable fun Dialogs() {
        LaunchedEffect(Unit) { while (true) { state = DailyRecommendations.state(); delay(200) } }
        DisposableEffect(Unit) { guardedCleanup { dialog = "" } }
        when (dialog) {
            "disable" -> EmbeddedSaltDialog("关闭每日推荐", ::dismiss, dismissVisible = false) {
                SaltText("是否关闭每日推荐？后续想使用，请去插件配置页开启。")
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SaltButton(onClick = { DailyRecommendations.disable(); dismiss() }, modifier = Modifier.weight(1f)) { SaltText("确定") }
                    SaltSecondaryButton(onClick = ::dismiss, modifier = Modifier.weight(1f)) { SaltText("取消") }
                }
            }
            "history" -> EmbeddedSaltDialog("历史日推", ::dismiss) {
                SaltText(state.message())
                if (state.dates().isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(state.dates(), key = { it }) { date ->
                        SaltItem(date, "查看当天推荐", Modifier.fillMaxWidth()) { DailyRecommendations.history(date); dismiss() }
                    }
                }
            }
        }
    }
}
