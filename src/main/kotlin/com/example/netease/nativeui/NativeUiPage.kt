@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.example.netease.nativeui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.netease.net.Dto
import com.example.netease.svc.LocalPlaylists
import com.example.netease.svc.SyncCoordinator
import com.example.netease.svc.AccountService
import com.example.netease.ui.controller.*
import com.example.netease.cfg.PluginConfig

internal object NativeUiRuntime {
    var hostVersion: String = "unknown"
    var pluginVersion: String = "unknown"
}

/**
 * 原生页面内的路由（不涉及宿主导航，纯插件内部页面切换）。
 *
 * <p>DEVELOPER 只给开发/验收用的诊断页，普通用户页不放密度/挂载/自检等开发内容
 * （REVIEW issue6）。</p>
 */
internal enum class NativePage(val title: String) {
    HOME("首页"),
    SEARCH("搜索歌曲"),
    PLAYLIST("歌单与同步状态"),
    SYNC("同步状态"),
    LOGIN("登录"),
    ACCOUNT("账号"),
    DEVELOPER("开发者信息"),
}

/**
 * 页面状态：visible=false 时只留右下角入口，宿主原有配置内容照常可见可用。
 *
 * <p>控制器状态（search/playlist）由 [NativeUiSession] 的后台 tick 线程写回，
 * 读取时是 Compose snapshot 状态，切页不丢。</p>
 */
internal object NativeUiState {
    var visible by mutableStateOf(false)
    var clicks by mutableStateOf(0)
    var status by mutableStateOf("尚未注册 Hook")
    var page by mutableStateOf(NativePage.HOME)

    var search by mutableStateOf<SearchController.State?>(null)
    var playlist by mutableStateOf<PlaylistController.State?>(null)
    var sync by mutableStateOf<SyncCoordinator.Status?>(null)
    var syncRequestNote by mutableStateOf("")

    var searchKeyword by mutableStateOf("")
    var playlistInput by mutableStateOf("")
    var localEntries by mutableStateOf<List<LocalPlaylists.Entry>>(emptyList())
    var localNote by mutableStateOf("")
    var saveName by mutableStateOf("")
    var localSave by mutableStateOf<LocalSaveController.State?>(null)

    /** 登录页状态：唯一登录流程的界面快照（账号事实仍只在 AccountService）。 */
    var login by mutableStateOf<LoginController.State?>(null)

    /** 行为验证内嵌区域状态（VerifyHost 助手进程 + WebView2，批次 4）。 */
    var verify by mutableStateOf<VerifyEmbedController.State?>(null)
    var loginPhone by mutableStateOf("")
    var loginCode by mutableStateOf("")

    /** 账号页快照：直接取 AccountService.status()，页面不另算一套。 */
    var account by mutableStateOf<AccountService.LoginStatus?>(null)
    var accountNote by mutableStateOf("")

    /** 打开歌单页时按需刷新一次本地歌单列表（读盘在后台线程）。 */
    var localRefreshRequested by mutableStateOf(false)
}

/** Uses the host's actual Salt components inside its original CompositionLocals. */
@Composable
internal fun NativeUiPanel() {
    DisposableEffect(Unit) {
        onDispose {
            // 路由卸载后不会再有 Transition 观察者来清 animating/closing；先回落路由，再统一清标志，
            // 否则 leaveHostRoute() 里的 showConfiguration() 会把 modalClosing/pageClosing 留在 true。
            NativeUiPresentation.leaveHostRoute()
            NativeUiPresentation.resetMotion()
        }
    }
    val host = HostSaltTheme.read()
    if (host == null) {
        NativeThemeMissing()
        return
    }
    CompositionLocalProvider(LocalSaltSnapshot provides host) { NativeUiShell() }
}

@Composable
private fun NativeUiShell() {
    val awtWindow = LocalAwtWindow.current
    // Esc 的分发器只在 RootHook 注册一次；这里重复注册会让一次 Esc 走两条路径。
    SideEffect { VerifyHostWindow.capture(awtWindow) }
    val background = NativeUiPresentation.backgroundPage()
    val pageTarget = background.takeIf { NativeUiPresentation.isPage(background) }
    // 绘制与 isRunning 必须来自同一个 Transition：另一组 AnimatedContent 自己维护状态时，
    // 退场首帧可能已把它归零，顶栏/背景就会提前恢复焦点与点击。
    val pageTransition = updateTransition(pageTarget, label = "native-page")
    // 直接在组合里读同一个 Transition 的 running 状态：焦点归还/遮罩判定不必等
    // snapshotFlow 的下一帧，且与绘制来源完全一致。
    val pageRunning = pageTransition.isRunning || pageTransition.currentState != pageTransition.targetState
    LaunchedEffect(pageTransition) {
        snapshotFlow { pageTransition.isRunning || pageTransition.currentState != pageTransition.targetState }
            .collect { running ->
                NativeUiPresentation.pageAnimating = running
                if (!running) NativeUiPresentation.pageSettled()
            }
    }
    val modalTarget = NativeUiState.page.takeIf { NativeUiPresentation.modalOpen() }
    val modalTransition = updateTransition(modalTarget, label = "native-modal")
    val modalRunning = modalTransition.isRunning || modalTransition.currentState != modalTransition.targetState
    LaunchedEffect(modalTransition) {
        snapshotFlow { modalTransition.isRunning || modalTransition.currentState != modalTransition.targetState }
            .collect { running ->
                NativeUiPresentation.modalAnimating = running
                if (!running) NativeUiPresentation.modalSettled()
            }
    }
    // 登录成功是流程结束：在常驻 Shell 里观察一次，退场页不再执行业务 effect。
    LaunchedEffect(NativeUiState.login?.phase(), NativeUiState.page, NativeUiState.visible, modalTarget != null) {
        val state = NativeUiState.login
        if (NativeUiState.visible && NativeUiState.page == NativePage.LOGIN && state?.phase() == LoginController.Phase.LOGGED_IN) {
            NativeUiSession.closeLogin()
            NativeUiPresentation.openPage(NativePage.ACCOUNT)
        }
    }
    val localSave = remember { MutableTransitionState(false) }
    SideEffect { localSave.targetState = NativeUiPresentation.localSaveOpen }
    val localSaveRunning = localSave.currentState != localSave.targetState
    LaunchedEffect(localSave) {
        snapshotFlow { localSave.currentState != localSave.targetState }
            .collect {
                NativeUiPresentation.localSaveAnimating = it
                if (!it) NativeUiPresentation.localSaveSettled()
            }
    }
    val settled = !NativeUiPresentation.anyDialogOpen() && !NativeUiPresentation.pageAnimating &&
        !pageRunning && !modalRunning && !localSaveRunning
    LaunchedEffect(settled, awtWindow) {
        if (settled && NativeUiPresentation.consumeFocusReturn()) FocusReturn.restore(awtWindow)
    }
    // 路由在转场中途被卸载时，动画状态不会自己归零；残留的 true 会永久挡住背景焦点与点击。
    DisposableEffect(Unit) {
        onDispose { NativeUiPresentation.resetMotion() }
    }

    if (background == NativePage.HOME && !NativeUiPresentation.anyDialogOpen() && PluginConfig.devMode()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (PluginConfig.devMode()) SaltPlainButton(onClick = { NativeUiPresentation.openPage(NativePage.DEVELOPER) }) {
                    SaltText("开发者信息")
                }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        pageTransition.AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            // sizeTransform=null：默认 spring 会把整层内容裁到变化中的尺寸，退场时全屏遮罩
            // 先被裁掉、Transition 也会拖到 ~450ms；页面/模态都只做透明度，尺寸由内容自身固定。
            transitionSpec = { ContentTransform(fadeIn(Motion.page()), fadeOut(Motion.page()), 0f, null) },
            contentAlignment = Alignment.TopStart,
        ) { page ->
            if (page != null) {
                val current = page == pageTarget && !NativeUiPresentation.pageAnimating && !pageRunning
                NativeUiBackground(blocked = NativeUiPresentation.backgroundBlocked() || !current || modalRunning) {
                    CompositionLocalProvider(LocalNativePageInteractive provides current) {
                        SaltScreen(page.title,
                            action = { SaltBackButton(onClick = { NativeUiPresentation.showConfiguration() }) },
                            tools = {},
                        ) { insets ->
                            Box(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
                                if (page == NativePage.SEARCH) NativeSearchPage() else NativePlaylistPage()
                            }
                        }
                    }
                }
            }
        }
        if (NativeUiPresentation.pageAnimating) {
            Box(Modifier.matchParentSize().blockAllPointerEvents())
        }
        // 遮罩只做整层透明度；缩放只作用于 EmbeddedSaltDialog 内的卡片，原生验证子窗不参与任何位置/缩放动画。
        modalTransition.AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { ContentTransform(fadeIn(Motion.dialogEnter()), fadeOut(Motion.dialogExit()), 0f, null) },
            contentAlignment = Alignment.Center,
        ) { page ->
            if (page != null) {
                val interactive = NativeUiPresentation.dialogInteractive(page)
                EmbeddedSaltDialog(
                    title = page.title,
                    onDismiss = { NativeUiPresentation.dismissModal() },
                    interactive = interactive,
                ) {
                    when (page) {
                        NativePage.LOGIN -> {
                            val display = rememberLoginDisplay(interactive)
                            NativeLoginPage(
                                display = display,
                                actions = remember(display.generation) { LoginActions(display.generation) },
                                interactive = interactive,
                            )
                        }
                        NativePage.ACCOUNT -> NativeAccountPage(
                            display = rememberAccountDisplay(interactive),
                            interactive = interactive,
                        )
                        NativePage.SYNC -> NativeSyncPage(
                            display = rememberSyncDisplay(interactive),
                            interactive = interactive,
                        )
                        NativePage.DEVELOPER -> NativeDeveloperPage()
                        else -> Unit
                    }
                }
            }
        }
        val playlist = rememberRetained(NativeUiState.playlist)
        AnimatedVisibility(
            visibleState = localSave,
            enter = fadeIn(Motion.dialogEnter()),
            exit = fadeOut(Motion.dialogExit()),
            label = "native-local-save",
        ) {
            Box(Modifier.fillMaxSize()) {
                playlist?.let { LocalSaveDialog(it, interactive = NativeUiPresentation.localSaveOpen) }
                if (!NativeUiPresentation.localSaveOpen) Box(Modifier.matchParentSize().blockAllPointerEvents())
            }
        }
    }
}

/** The modal stays above the existing page; block its focus and accessibility. */
@Composable
internal fun NativeUiBackground(blocked: Boolean = NativeUiPresentation.anyDialogOpen(), content: @Composable () -> Unit) {
    val modifier = if (blocked) Modifier.focusProperties { onEnter = { cancelFocusChange() } }
        .focusGroup().clearAndSetSemantics {} else Modifier
    Box(modifier.fillMaxSize()) { content() }
}

@Composable
private fun NativeThemeMissing() {
    SideEffect { NativeUiState.status = "原生主题不可用" }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        Box(Modifier.padding(16.dp).background(Color(0xCC1B1B1B), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)) {
            BasicText("原生主题不可用：请在椒盐 1.19.x 中重新打开插件页",
                style = TextStyle(color = Color(0xFFFFB4AB), fontSize = 13.sp))
        }
    }
}

@Composable
private fun NativeDeveloperPage() {
    val density = LocalDensity.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SaltText("挂载状态：" + NativeUiState.status)
        SaltText("宿主：" + NativeUiRuntime.hostVersion + " · 插件：" + NativeUiRuntime.pluginVersion)
        SaltText("组件：" + HostSaltComponents.describe())
        SaltText("主题：" + HostSaltTheme.describe(HostSaltTheme.read()))
        SaltText("密度：${density.density} · 字体缩放：${density.fontScale}")
        SaltSecondaryButton(onClick = { NativeUiState.clicks++ }) { SaltText("状态自检（${NativeUiState.clicks}）") }
    }
}

internal fun syncPhaseLabel(status: SyncCoordinator.Status): String = when (status.phase()) {
    SyncCoordinator.Phase.IDLE -> "尚未同步"
    SyncCoordinator.Phase.WAITING -> "等待同步"
    SyncCoordinator.Phase.FETCHING -> "拉取中"
    SyncCoordinator.Phase.WRITING -> "写入中"
    SyncCoordinator.Phase.COMPLETED -> "同步完成"
    SyncCoordinator.Phase.ABORTED -> "同步中止"
    SyncCoordinator.Phase.FAILED -> "同步失败"
}

/** 曲目时长格式化（与旧窗口一致的 mm:ss）。 */
internal fun formatDuration(ms: Long): String {
    if (ms <= 0L) {
        return "--:--"
    }
    val total = ms / 1000L
    return String.format("%02d:%02d", total / 60L, total % 60L)
}

/** 列表里的一行状态：不可播原因优先，其次 VIP 提示，最后时长。 */
internal fun trackNote(song: Dto.Song): String = when {
    !song.playable() -> "无版权/不可播"
    song.fee() == 1 -> "VIP 曲目（可能降级）"
    else -> formatDuration(song.durationMs())
}

/** 消费指针事件：本层在最上面，宿主内容在下面，不消费会点穿。 */
internal fun Modifier.consumeAllPointerEvents(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}

/** Initial 阶段就消费：转场期间既挡住本层按钮，也挡住宿主的点击穿透。 */
internal fun Modifier.blockAllPointerEvents(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}
