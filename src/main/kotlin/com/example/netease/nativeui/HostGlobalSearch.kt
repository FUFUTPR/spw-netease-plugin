@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class, androidx.compose.runtime.InternalComposeApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.example.netease.nativeui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.*
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
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
    private data class PopupMaterial(val wallpaper: Any, val popup: Any)
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
            if (enabled) NativeTopSearch.start()
            PluginConfig.setSearchBoxHook { value ->
                enabled = value
                if (value) NativeTopSearch.start() else NativeTopSearch.stop()
            }
            PluginLog.i("native-ui", "全局顶栏搜索已注册：两个精确调用点，主窗口内前五首候选")
        } catch (error: Throwable) { uninstall(); throw error }
    }
    fun uninstall() {
        HostCloudSongMenu.uninstall()
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
            Box(Modifier.fillMaxSize().onGloballyPositioned { rootBounds = it.boundsInRoot() }
                .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                    val position = event.changes.firstOrNull()?.position?.plus(rootBounds.topLeft)
                    if (position != null && (NativeTopSearch.focused || NativeTopSearch.expanded) &&
                        !NativeTopSearch.fieldBounds.contains(position) &&
                        !(NativeTopSearch.expanded && NativeTopSearch.suggestionBounds.contains(position))) {
                        NativeTopSearch.collapse(); focus.clearFocus()
                        // Observe only: the same click still reaches the host control underneath.
                    }
                }) {
                draw(root, listOf(call.arguments.getOrNull(0) ?: false), currentComposer)
                if (enabled && mainTitle && titleBounds.width > 0f && !NativeUiPresentation.anyDialogOpen()) {
                    HostSaltTheme.read()?.let { snapshot ->
                        CompositionLocalProvider(LocalSaltSnapshot provides snapshot) { Overlay() }
                    }
                }
                if (HostCloudSongMenu.removal != null || HostCloudSongMenu.message.isNotBlank()) {
                    popupMaterial?.let { material ->
                        HostSaltTheme.read()?.let { snapshot ->
                            CompositionLocalProvider(LocalSaltSnapshot provides snapshot,
                                hazeLocals[0] provides material.wallpaper, hazeLocals[1] provides material.popup) {
                                HostCloudSongMenu.Dialogs()
                            }
                        }
                    }
                }
            }
        }
    }
    private object TitleHook : ComposableHook() {
        @Composable override fun Content(call: UiHookCall) {
            val main = (call.arguments.getOrNull(0) as? Enum<*>)?.name == "Main"
            val nav = currentComposer.consume(navigatorLocal)
            val material = if(main) PopupMaterial(currentComposer.consume(hazeLocals[0]), currentComposer.consume(hazeLocals[1])) else null
            SideEffect {
                mainTitle = main
                if (main) { popupMaterial = material; if (nav != null) navigator = WeakReference(nav) }
            }
            Box(Modifier.fillMaxWidth().onGloballyPositioned { titleBounds = it.boundsInRoot() }) {
                draw(title, call.arguments.take(4), currentComposer)
            }
            DisposableEffect(Unit) { onDispose { mainTitle = false; navigator.clear(); popupMaterial = null } }
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
    @Composable private fun BoxScope.Overlay() {
        val density = LocalDensity.current
        val focus = LocalFocusManager.current
        val x = with(density) { (titleBounds.left - rootBounds.left).toDp() }
        val y = with(density) { (titleBounds.top - rootBounds.top).toDp() }
        val w = with(density) { titleBounds.width.toDp() }
        val h = with(density) { titleBounds.height.toDp() }
        Box(Modifier.offset(x, y).width(w).height(h).padding(start = minOf(220.dp, w * .2f), end = minOf(330.dp, w * .28f)), contentAlignment = Alignment.Center) {
            NativeTopSearchField(::navigate, Modifier.widthIn(max = 740.dp).fillMaxWidth().height(minOf(34.dp, h)))
        }
        Box(Modifier.offset(x, y + h).width(w)
            .padding(start = minOf(220.dp, w * .2f), end = minOf(330.dp, w * .28f)), contentAlignment = Alignment.TopCenter) {
            AnimatedVisibility(NativeTopSearch.expanded,
                enter = fadeIn(tween(140)) + expandVertically(tween(180), expandFrom = Alignment.Top),
                exit = fadeOut(tween(100)) + shrinkVertically(tween(140), shrinkTowards = Alignment.Top)) {
                NativeTopSearchSuggestions(Modifier.widthIn(max = 740.dp).fillMaxWidth())
            }
        }
    }
}
