@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class, androidx.compose.runtime.InternalComposeApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.example.netease.nativeui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.*
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.awt.LocalAwtWindow
import com.example.netease.core.PluginLog
import com.example.netease.cfg.PluginConfig
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.*
import java.lang.reflect.Method
import java.lang.ref.WeakReference

/** Two exact call sites: keep the native titlebar/window intact, overlay only
 * the search controls in the same main-window composition. No Popup/Window. */
internal object HostGlobalSearch {
    private const val ROOT = "androidx.compose.ui.\u0450"
    private const val ROOT_CALLER = "androidx.compose.ui.\u0ab3"
    private const val TITLE = "androidx.compose.ui.\u029c"
    private const val TITLE_CALLER = "androidx.compose.ui.\u054a"
    private val titleParams = listOf("androidx.compose.ui.\u04a1", "androidx.compose.ui.\u10ac",
        "androidx.compose.ui.\u0f43", "androidx.compose.ui.Modifier")
    private val handles = mutableListOf<HookHandle>()
    private lateinit var root: Method
    private lateinit var title: Method
    var titleBounds by mutableStateOf(Rect.Zero)
        private set
    private var rootBounds by mutableStateOf(Rect.Zero)
    private var mainTitle by mutableStateOf(false)
    private var navigator = WeakReference<Any>(null)
    // MainWindowContent supplies these *inside* root(), so its sibling overlays
    // cannot inherit them. Capture the actual host states at the title call site.
    private data class PopupMaterial(val wallpaper: Any, val popup: Any, val window: Any?)
    private var popupMaterial by mutableStateOf<PopupMaterial?>(null)
    private val hazeLocals by lazy {
        val type = Class.forName("com.xuncorp.voxzen.ui.component.\u052c")
        listOf("\u037f", "\u0528").map { getter ->
            @Suppress("UNCHECKED_CAST")
            (type.getMethod(getter).invoke(null) as ProvidableCompositionLocal<Any>)
        }
    }
    private var enabled by mutableStateOf(PluginConfig.searchBoxEnabled())
    private val navigatorLocal by lazy {
        @Suppress("UNCHECKED_CAST")
        (Class.forName("com.xuncorp.voxzen.ui.navigation.AppNavigationKt")
            .getMethod("getLocalNavigator").invoke(null) as CompositionLocal<Any?>)
    }

    internal fun rootMethod(): Method = resolve(ROOT, listOf("boolean"))
    internal fun titleMethod(): Method = resolve(TITLE, titleParams)
    internal fun rootCaller() = HostUiAbi.name(ROOT_CALLER)
    internal fun titleCaller() = HostUiAbi.name(TITLE_CALLER)
    private fun resolve(owner: String, params: List<String>): Method {
        val signature = params.map(HostUiAbi::name) + listOf("androidx.compose.runtime.Composer", "int", "int")
        return HostUiAbi.type(owner).declaredMethods.single { it.name == "\u037f" && it.parameterTypes.map(Class<*>::getName) == signature }
            .apply { isAccessible = true }
    }
    fun install() {
        if (handles.isNotEmpty()) return
        root = rootMethod(); title = titleMethod()
        try {
            handles += WorkshopApi.hookRegistrar.replaceComposable(HostUiAbi.name(ROOT), "\u037f", rootCaller(), listOf("boolean"), 100, RootHook)
            handles += WorkshopApi.hookRegistrar.replaceComposable(HostUiAbi.name(TITLE), "\u037f", titleCaller(), titleParams.map(HostUiAbi::name), 100, TitleHook)
            enabled = PluginConfig.searchBoxEnabled()
            HostCloudSongMenu.install()
            HostDailyRecommendations.install()
            HostCacheUsage.install()
            HostManagedPlaylists.install()
            NativeUpdateUi.attach()
            if (enabled) NativeTopSearch.start()
            PluginConfig.setSearchBoxHook { value ->
                enabled = value
                if (value) NativeTopSearch.start() else NativeTopSearch.stop()
            }
            PluginLog.i("native-ui", "全局顶栏搜索已注册：两个精确调用点，主窗口内前五首候选")
        } catch (error: Throwable) { uninstall(); throw error }
    }
    fun uninstall() {
        NativeUpdateUi.stop()
        HostCloudSongMenu.uninstall()
        HostDailyRecommendations.uninstall()
        HostCacheUsage.uninstall()
        HostManagedPlaylists.uninstall()
        PluginConfig.setSearchBoxHook(null)
        NativeTopSearch.stop()
        handles.asReversed().forEach { runCatching { it.unhook() } }
        handles.clear(); navigator.clear(); titleBounds = Rect.Zero; mainTitle = false; popupMaterial = null
    }
    private fun draw(method: Method, args: List<Any?>, composer: Composer) {
        try { method.invoke(null, *(args + listOf(composer, 0, 0)).toTypedArray()) }
        catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
    }
    private object RootHook : ComposableHook() {
        @Composable override fun Content(call: UiHookCall) {
            NativeModalEscapeGate(LocalAwtWindow.current)
            val focus = LocalFocusManager.current
            val window = LocalAwtWindow.current
            ObserveTitlebarPress(window) { focus.clearFocus() }
            // 材质只属于主标题所在窗口/展示代次：窗口变了立即丢弃，绝不给新窗口复用旧 PopupHazeState。
            val material = popupMaterial?.takeIf { it.window == null || it.window === window }
            LaunchedEffect(material == null, HostCloudSongMenu.dialogOpen()) {
                if (material == null && HostCloudSongMenu.dialogOpen()) HostCloudSongMenu.stop()
            }
            LaunchedEffect(material == null, NativeUpdateUi.dialogOpen()) {
                if(material == null && NativeUpdateUi.dialogOpen()) NativeUpdateUi.windowUnavailable()
            }
            LaunchedEffect(material == null, HostDailyRecommendations.dialogOpen()) {
                if (material == null && HostDailyRecommendations.dialogOpen()) HostDailyRecommendations.dismiss()
            }
            LaunchedEffect(material == null, HostManagedPlaylists.dialogOpen()) {
                if(material==null && HostManagedPlaylists.dialogOpen())HostManagedPlaylists.dismiss()
            }
            LaunchedEffect(mainTitle, HostCloudSongMenu.dialogOpen(), NativeUpdateUi.dialogOpen(), HostDailyRecommendations.dialogOpen(), material) {
                // 标题退场后，等云歌单菜单的退场动画结束再释放材质（否则 PopupHazeState 会缺席）。
                if (!mainTitle && !HostCloudSongMenu.dialogOpen() && !NativeUpdateUi.dialogOpen() && !HostDailyRecommendations.dialogOpen() && !HostManagedPlaylists.dialogOpen()) popupMaterial = null
            }
            DisposableEffect(Unit) { guardedCleanup { popupMaterial = null } }
            BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { rootBounds = it.boundsInRoot() }
                .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                    val position = event.changes.firstOrNull()?.position?.plus(rootBounds.topLeft)
                    if (position != null && !NativeUiPresentation.anyDialogOpen() &&
                        (NativeTopSearch.focused || NativeTopSearch.expanded || NativeTopSearch.compactOpen) &&
                        !NativeTopSearch.fieldBounds.contains(position) &&
                        !(NativeTopSearch.compactOpen && NativeTopSearch.compactBounds.contains(position)) &&
                        !(NativeTopSearch.expanded && NativeTopSearch.suggestionBounds.contains(position))) {
                        NativeTopSearch.collapse(); focus.clearFocus()
                        // Observe only: the same click still reaches the host control underneath.
                    }
                }) {
                draw(root, listOf(call.arguments.getOrNull(0) ?: false), currentComposer)
                if (mainTitle && titleBounds.width > 0f) {
                    // 对话框打开时不卸载搜索层：它是真实的焦点归还目标（按窗口登记），
                    // 卸载会让关闭对话框后无目标可还。弹窗期间保留显示，仅阻止交互。
                    val interactive = !NativeUiPresentation.anyDialogOpen()
                    HostSaltTheme.read()?.let { snapshot ->
                        CompositionLocalProvider(LocalSaltSnapshot provides snapshot) {
                            Overlay(interactive, maxWidth)
                            NativeUpdateUi.run { Bubble(maxWidth) }
                            HostDailyRecommendations.run { Toolbar(rootBounds, interactive) }
                        }
                    }
                }
                if (HostCloudSongMenu.dialogOpen() && material != null) {
                    HostSaltTheme.read()?.let { snapshot ->
                        CompositionLocalProvider(LocalSaltSnapshot provides snapshot,
                            hazeLocals[0] provides material.wallpaper, hazeLocals[1] provides material.popup) {
                            HostCloudSongMenu.Dialogs()
                        }
                    }
                }
                if (NativeUpdateUi.dialogOpen() && material != null) {
                    HostSaltTheme.read()?.let { snapshot ->
                        CompositionLocalProvider(LocalSaltSnapshot provides snapshot,
                            hazeLocals[0] provides material.wallpaper,hazeLocals[1] provides material.popup) {
                            NativeUpdateUi.Dialogs()
                        }
                    }
                }
                if (HostDailyRecommendations.dialogOpen() && material != null) {
                    HostSaltTheme.read()?.let { snapshot ->
                        CompositionLocalProvider(LocalSaltSnapshot provides snapshot,
                            hazeLocals[0] provides material.wallpaper, hazeLocals[1] provides material.popup) {
                            HostDailyRecommendations.Dialogs()
                        }
                    }
                }
                if(HostManagedPlaylists.dialogOpen() && material!=null){
                    HostSaltTheme.read()?.let {snapshot->
                        CompositionLocalProvider(LocalSaltSnapshot provides snapshot,hazeLocals[0] provides material.wallpaper,hazeLocals[1] provides material.popup){HostManagedPlaylists.Dialogs()}
                    }
                }
            }
        }
    }
    private object TitleHook : ComposableHook() {
        @Composable override fun Content(call: UiHookCall) {
            val main = (call.arguments.getOrNull(0) as? Enum<*>)?.name == "Main"
            val nav = currentComposer.consume(navigatorLocal)
            val material = if(main) PopupMaterial(currentComposer.consume(hazeLocals[0]),
                currentComposer.consume(hazeLocals[1]), LocalAwtWindow.current) else null
            SideEffect {
                mainTitle = main
                if (main) { popupMaterial = material; if (nav != null) navigator = WeakReference(nav) }
            }
            Box(Modifier.fillMaxWidth().onGloballyPositioned { titleBounds = it.boundsInRoot() }) {
                draw(title, call.arguments.take(4), currentComposer)
            }
            // 只标记标题已退场；材质由 RootHook 在「无对话框且无退场动画」后统一释放，避免撤得太早。
            DisposableEffect(Unit) { guardedCleanup { mainTitle = false; navigator.clear() } }
        }
    }
    private fun navigate(): Boolean {
        val nav = navigator.get() ?: return false
        if (!NativeUiPresentation.openPage(NativePage.SEARCH)) return false
        return try {
            val route = Class.forName(HostUiMountScope.MOD_CONFIG_ROUTE_CLASS).getConstructor(String::class.java).newInstance("com.example.netease")
            val stack = nav.javaClass.getMethod("getNavBackStack").invoke(nav) as? List<*>
            if (stack?.lastOrNull() != route) nav.javaClass.getMethod("navigate", Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute")).invoke(nav, route)
            true
        } catch (error: Throwable) { PluginLog.w("native-ui", "打开搜索结果失败：$error"); false }
    }

    internal fun currentRoute(): Any? = navigator.get()?.let { nav ->
        (nav.javaClass.getMethod("getNavBackStack").invoke(nav) as? List<*>)?.lastOrNull()
    }

    @Composable private fun ObserveTitlebarPress(window: java.awt.Window?, clear: () -> Unit) {
        DisposableEffect(window) {
            val listener=java.awt.event.AWTEventListener { raw ->
                val event=raw as? java.awt.event.MouseEvent
                if(window!=null && event?.id==java.awt.event.MouseEvent.MOUSE_PRESSED && !NativeUiPresentation.anyDialogOpen()) {
                    val owner=event.component?.let { if(it is java.awt.Window)it else javax.swing.SwingUtilities.getWindowAncestor(it) }
                    if(owner===window) runCatching {
                        val point=javax.swing.SwingUtilities.convertPoint(event.component,event.point,window)
                        val inset=window.insets
                        val scale=rootBounds.width/(window.width-inset.left-inset.right).coerceAtLeast(1)
                        val position=Offset((point.x-inset.left)*scale,(point.y-inset.top)*scale)
                        if(titleBounds.contains(position) && !NativeTopSearch.fieldBounds.contains(position) &&
                            !NativeTopSearch.compactBounds.contains(position)) {
                            java.awt.EventQueue.invokeLater { NativeTopSearch.collapse();runCatching(clear) }
                        }
                    }
                }
            }
            if(window!=null)java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(listener,java.awt.AWTEvent.MOUSE_EVENT_MASK)
            guardedCleanup { if(window!=null)java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
        }
    }

    internal fun openWorkshop(): Boolean {
        val nav=navigator.get() ?: return false
        return try {
            NativeUiPresentation.showConfiguration()
            nav.javaClass.getMethod("navigate",Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute"))
                .invoke(nav,HostUiAbi.workshopRoute())
            true
        } catch(error:Throwable) {
            PluginLog.w("updates","打开工坊页面失败：$error");false
        }
    }

    internal fun captureModManagementNavigation(): Runnable? {
        val nav=navigator.get() ?: return null
        return try {
            val route=HostUiAbi.modManagementRoute()
            val method=nav.javaClass.getMethod("navigate",Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute"))
            // Capture before self-unload clears this plugin's weak navigator.
            Runnable { method.invoke(nav,route) }
        } catch(error:Throwable) {
            PluginLog.w("updates","模组管理页入口不可用：${error.javaClass.simpleName}");null
        }
    }

    @Composable private fun BoxScope.Overlay(interactive: Boolean, rootWidth: Dp) {
        val density = LocalDensity.current
        val focus = LocalFocusManager.current
        val overlayAlpha = 1f
        val x = with(density) { (titleBounds.left - rootBounds.left).toDp() }
        val y = with(density) { (titleBounds.top - rootBounds.top).toDp() }
        // Read current measurement constraints, not the previous frame's global
        // bounds. Main title spans the root inside the symmetric native frame.
        val w = (rootWidth - x * 2).coerceAtLeast(0.dp)
        val h = with(density) { titleBounds.height.toDp() }
        val syncMessage = syncProgressMessage(NativeUiState.sync)
        val message = refreshProgressMessage(NativeUiState.sync, NativeUiState.daily)
        val visibility = remember { MutableTransitionState(false) }
        visibility.targetState = message.isNotBlank()
        val showing = visibility.currentState || visibility.targetState
        SideEffect { HostLibraryNotices.progressVisible = showing }
        DisposableEffect(Unit) { guardedCleanup { HostLibraryNotices.progressVisible = false } }
        val count = NativeUiState.sync?.takeIf { syncMessage.isNotBlank() && it.total() > 0 }?.let { "${it.completed()}/${it.total()}" }.orEmpty()
        val shown = rememberRetained(message.takeIf { it.isNotBlank() }?.let {
            val label = it.substringBefore("（")
                .replace("正在获取歌单与歌曲，请稍等片刻", "正在刷新歌单")
                .replace("正在获取歌单，请稍等片刻", "正在刷新歌单")
                .replace("正在显示歌单与歌曲，请稍等片刻", "正在更新歌单")
            Triple(label, count, it)
        })
        if (w < 420.dp) return
        val contentLeft = 244.dp
        val left = minOf(contentLeft, (w - 510.dp).coerceAtLeast(56.dp))
        val right = w - 330.dp
        val available = (right - left).coerceAtLeast(0.dp)
        // Modal fading must not also expand/collapse the titlebar underneath it.
        val reserved = message.isNotBlank() || showing
        var wasCompact by remember { mutableStateOf(false) }
        val threshold = if (reserved) 580.dp else 180.dp
        // Resize noise near the breakpoint cannot flip input modes every frame.
        val compact = enabled && available < threshold + if (wasCompact) 12.dp else (-12).dp
        SideEffect {
            wasCompact = compact
            // A previous compact session must not reopen on the next shrink.
            if (!compact) NativeTopSearch.compactOpen = false
        }
        LaunchedEffect(compact) {
            if (compact && !NativeTopSearch.compactOpen) {
                NativeTopSearch.suspendForDialog()
                focus.clearFocus()
            }
        }
        LaunchedEffect(enabled, interactive) {
            if (!enabled) NativeTopSearch.collapse()
            else if (!interactive) NativeTopSearch.suspendForDialog()
        }
        val gap = key(w) { animateDpAsState(if (reserved && enabled) 8.dp else 0.dp,
            Motion.topbar(), label="refresh-search-gap").value }
        val noticeTarget = if (reserved) {
            val searchSpace = if (!enabled) 0.dp else if (compact) 42.dp else 252.dp
            minOf(360.dp, (available - searchSpace).coerceAtLeast(0.dp))
        } else 0.dp
        val noticeWidth = key(w) { animateDpAsState(noticeTarget, Motion.topbar(), label="refresh-notice-width").value }
        val fieldTarget = (available - noticeWidth - gap).coerceAtLeast(0.dp)
        val fieldWidth = (available - noticeWidth - gap).coerceAtLeast(0.dp)
        val dropdown = compact && NativeTopSearch.compactOpen
        val editorVisible = enabled && (!compact || dropdown)
        // Animate user interaction as a fraction of the CURRENT layout. A resize
        // replaces only these animation states at their current semantic targets,
        // so geometry snaps to the new constraints without rebuilding the editor.
        val focusFraction = key(w, compact) {
            animateFloatAsState(if (NativeTopSearch.focused) 1f else 0f,
                Motion.topbar(), label = "top-focus-expansion").value
        }
        val popupFraction = key(w, h, compact) {
            animateFloatAsState(if (dropdown) 1f else 0f,
                Motion.topbar(), label = "top-popup-reveal").value
        }
        val unfocusedWidth = minOf(220.dp, fieldTarget)
        val focusedWidth = minOf(370.dp, fieldTarget)
        val inputTarget = if (compact) minOf(370.dp, ((w - 32.dp) / 2).coerceAtLeast(minOf(220.dp, w - 32.dp))) else
            unfocusedWidth + (focusedWidth - unfocusedWidth) * focusFraction
        // Window changes are synchronous; only clicks/focus changes animate.
        val inputWidth = inputTarget
        val inputLeft = if (compact) (w - inputTarget) / 2 else left + (fieldTarget - inputTarget) / 2
        val inputTop = if (compact) h + 12.dp * (1f - popupFraction) else (h - 34.dp) / 2
        // Reset reveal state on a mode change; never fade the old inline editor
        // at the dropdown's new location before the user clicks the icon.
        val inputAlpha = overlayAlpha * if (compact) popupFraction else 1f
        val safeInputWidth = minOf(inputWidth, if (compact) w - 32.dp else fieldWidth).coerceAtLeast(0.dp)
        val safeInputLeft = inputLeft.coerceIn(16.dp, (w - 16.dp - safeInputWidth).coerceAtLeast(16.dp))
        val suggestionTargetWidth = if (compact) inputTarget else
            minOf(w - contentLeft - 16.dp, maxOf(320.dp, minOf(370.dp, fieldTarget)))
        val suggestionTargetLeft = if (compact) (w - suggestionTargetWidth) / 2 else (left + (fieldTarget - suggestionTargetWidth) / 2)
            .coerceIn(contentLeft, (w - 16.dp - suggestionTargetWidth).coerceAtLeast(contentLeft))
        val suggestionWidth = minOf(suggestionTargetWidth, w - 32.dp)
        val suggestionLeft = suggestionTargetLeft.coerceIn(16.dp, (w - 16.dp - suggestionWidth).coerceAtLeast(16.dp))
        // Layout changes caused by refresh progress animate; actual window resizes start at new constraints.
        val slotWidth=fieldWidth
        val fieldLeft=safeInputLeft
        val shownFieldWidth=safeInputWidth
        val noticeLeft=right-noticeWidth
        val shownNoticeWidth=noticeWidth
        if (enabled) {
            Box(Modifier.offset(x + left, y).width(slotWidth).height(h).alpha(overlayAlpha)
                .testTag("top-search-slot"), contentAlignment = Alignment.Center) {
                AnimatedVisibility(compact, enter = fadeIn(Motion.state()), exit = fadeOut(Motion.state())) {
                    SearchIcon(interactive && compact) {
                        if (NativeTopSearch.compactOpen) { NativeTopSearch.collapse(); focus.clearFocus() }
                        else NativeTopSearch.compactOpen = true
                    }
                }
            }
            // One persistent input/focus node in both modes; no Crossfade or
            // AnimatedVisibility may keep a second live editor during a handoff.
            Box(Modifier.offset(x + fieldLeft, y + inputTop).width(shownFieldWidth)
                .height(if (compact) 40.dp else 34.dp).alpha(inputAlpha)
                .then(if (interactive && editorVisible) Modifier else
                    Modifier.blockAllPointerEvents().clearAndSetSemantics {})
                .layout { measurable, constraints ->
                    val child = measurable.measure(constraints)
                    layout(child.width, child.height) {
                        // Keep composition identity without an invisible hit target
                        // over the host once the compact editor has faded away.
                        if (editorVisible || inputAlpha > .001f) child.place(0, 0)
                    }
                }) {
                NativeTopSearchField(::navigate, Modifier.fillMaxSize()
                    .then(if (compact) Modifier.background(HostStyle.colors.popup, HostStyle.shapes.small)
                        .padding(horizontal = 8.dp) else Modifier),
                    interactive = interactive && editorVisible,
                    requestFocus = NativeTopSearch.compactOpen, fillAvailable = true)
            }
        }
        AnimatedVisibility(visibleState = visibility,
            modifier = Modifier.offset(x + noticeLeft, y + (h - 30.dp) / 2)
                .width(shownNoticeWidth).clipToBounds().alpha(overlayAlpha),
            enter = fadeIn(Motion.state()), exit = fadeOut(Motion.state())) {
            val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
            // A changing count must not remeasure/restart the marquee. Its fixed
            // slot is based on total digits, not on the current completion count.
            val totalDigits = shown?.second?.substringAfter('/')?.length?.coerceAtLeast(1) ?: 1
            val countSlot = minOf(80.dp, ((totalDigits * 2 + 1) * 7 + 4).dp)
            Row(Modifier.fillMaxWidth().height(30.dp).testTag("top-sync-notice")
                .background(HostStyle.colors.subBackground, HostStyle.shapes.small)
                .semantics { contentDescription = shown?.third.orEmpty() }
                .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SaltText(shown?.first.orEmpty(), modifier = Modifier.weight(1f)
                    .testTag(if (motionScale > 0f) "sync-marquee" else "sync-static").then(
                    if (motionScale > 0f) Modifier.basicMarquee(iterations = Int.MAX_VALUE,
                        initialDelayMillis = 900, repeatDelayMillis = 900, velocity = 28.dp) else Modifier),
                    style = HostStyle.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (noticeTarget >= 120.dp && shown?.second?.isNotBlank() == true) {
                    SaltText(shown.second, Modifier.width(countSlot).padding(start = 4.dp),
                        style = HostStyle.typography.bodySmall, maxLines = 1)
                }
            }
        }
        Box(Modifier.offset(x + suggestionLeft, y + inputTop + if (compact) 40.dp else 34.dp + (h - 34.dp) / 2).width(suggestionWidth)
            .alpha(if (compact && !dropdown) 0f else overlayAlpha)
            .then(if (interactive && (!compact || dropdown)) Modifier else Modifier.blockAllPointerEvents().clearAndSetSemantics {}),
            contentAlignment = Alignment.TopCenter) {
            AnimatedVisibility(NativeTopSearch.expanded && (!compact || dropdown),
                enter = fadeIn(tween(140)) + expandVertically(tween(180), expandFrom = Alignment.Top),
                exit = fadeOut(tween(100)) + shrinkVertically(tween(140), shrinkTowards = Alignment.Top)) {
                NativeTopSearchSuggestions(Modifier.widthIn(max = 740.dp).fillMaxWidth())
            }
        }
    }

    @Composable private fun SearchIcon(interactive: Boolean, onClick: () -> Unit) {
        SaltPlainButton(onClick, modifier = Modifier.size(34.dp).testTag("top-search-icon")
            .onGloballyPositioned { NativeTopSearch.compactBounds = it.boundsInRoot() }
            .semantics { contentDescription = "打开搜索" }, enabled = interactive) {
            val color = HostStyle.colors.text
            Canvas(Modifier.size(16.dp)) {
                val stroke = 1.6.dp.toPx()
                drawCircle(color, radius = size.minDimension * .30f,
                    center = Offset(size.width * .4f, size.height * .4f), style = Stroke(stroke))
                drawLine(color, Offset(size.width * .63f, size.height * .63f),
                    Offset(size.width * .9f, size.height * .9f), strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
    }
}
