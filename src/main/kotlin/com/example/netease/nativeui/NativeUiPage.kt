@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.example.netease.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
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
    val host = HostSaltTheme.read() ?: return
    DisposableEffect(Unit) { onDispose { NativeUiPresentation.leaveHostRoute() } }
    CompositionLocalProvider(LocalSaltSnapshot provides host) { NativeUiShell() }
}

@Composable
private fun NativeUiShell() {
    val awtWindow = LocalAwtWindow.current
    NativeModalEscapeGate(awtWindow)
    SideEffect { VerifyHostWindow.capture(awtWindow) }
    val background = NativeUiPresentation.backgroundPage()
    val modal = NativeUiPresentation.modalOpen()
    if (background == NativePage.HOME && !NativeUiPresentation.anyDialogOpen() && PluginConfig.devMode()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (PluginConfig.devMode()) SaltPlainButton(onClick = { NativeUiPresentation.openPage(NativePage.DEVELOPER) }) {
                    SaltText("开发者信息")
                }
            }
        }
    }
    if (NativeUiPresentation.isPage(background)) {
        NativeUiBackground {
            SaltScreen(background.title,
                action = { SaltBackButton(onClick = { NativeUiPresentation.showConfiguration() }) },
                tools = {},
            ) { insets ->
                Box(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
                    if (background == NativePage.SEARCH) NativeSearchPage() else NativePlaylistPage()
                }
            }
        }
    }
    if (modal) {
        val page = NativeUiState.page
        EmbeddedSaltDialog(page.title, onDismiss = { NativeUiPresentation.dismissModal() }) {
            when (page) {
                NativePage.LOGIN -> NativeLoginPage()
                NativePage.ACCOUNT -> NativeAccountPage()
                NativePage.SYNC -> NativeSyncPage()
                NativePage.DEVELOPER -> NativeDeveloperPage()
                else -> Unit
            }
        }
    }
    if (NativeUiPresentation.localSaveOpen) NativeUiState.playlist?.let { LocalSaveDialog(it) }
}

/** The modal stays above the existing page; block its focus and accessibility. */
@Composable
internal fun NativeUiBackground(blocked: Boolean = NativeUiPresentation.anyDialogOpen(), content: @Composable () -> Unit) {
    val modifier = if (blocked) Modifier.focusProperties { onEnter = { cancelFocusChange() } }
        .focusGroup().clearAndSetSemantics {} else Modifier
    Box(modifier.fillMaxSize()) { content() }
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
