@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.netease.nativeui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.netease.net.Dto
import com.example.netease.svc.LocalPlaylists
import com.example.netease.svc.SyncCoordinator
import com.example.netease.svc.AccountService
import com.example.netease.ui.controller.LocalSaveController
import com.example.netease.ui.controller.LoginController
import com.example.netease.ui.controller.PlaylistController
import com.example.netease.ui.controller.SearchController
import com.example.netease.ui.controller.VerifyEmbedController

/**
 * 主题适配（REVIEW issue6）。
 *
 * <p>宿主 1.19.0 的配置页不是 Material3 界面：宿主用自建的 SaltTheme CompositionLocal
 * （SaltColors/SaltTextStyles/SaltShapes/SaltConfigs）绘制全部原生页面。Hook 注入点
 * 位于宿主 AppTheme 之内，所以这里直接读取宿主实际提供的配色、字体与圆角
 * （见 [HostSaltTheme]），再交给 MaterialTheme——深浅色、品牌色、字体和圆角都与宿主
 * 当前页面一致，不再用 isSystemInDarkTheme() + 默认色板去猜。</p>
 *
 * <p>只有宿主混淆名失效、读不到 SaltTheme 时才降级到 Material3 默认主题；开发者页会
 * 显示实际主题来源与降级原因，默认色板不会被当作验收结果。</p>
 */
@Composable
private fun NativeUiTheme(content: @Composable () -> Unit) {
    val host = HostSaltTheme.read()
    if (host == null) {
        MaterialTheme(
            colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            content = content,
        )
        return
    }
    MaterialTheme(
        colorScheme = hostColorScheme(host),
        typography = hostTypography(host),
        shapes = hostShapes(host),
        content = content,
    )
}

/** 宿主 SaltColors → Material3 ColorScheme；只映射本插件页面实际用到的颜色角色。 */
private fun hostColorScheme(host: HostSaltTheme.Snapshot): ColorScheme {
    val p = host.palette
    val scheme = if (host.dark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = p.highlight,
        onPrimary = p.onHighlight,
        primaryContainer = p.highlight,
        onPrimaryContainer = p.onHighlight,
        secondary = p.highlight,
        onSecondary = p.onHighlight,
        secondaryContainer = p.subBackground,
        onSecondaryContainer = p.text,
        tertiary = p.success,
        onTertiary = p.background,
        background = p.background,
        onBackground = p.text,
        surface = p.background,
        onSurface = p.text,
        surfaceVariant = p.subBackground,
        onSurfaceVariant = p.subText,
        error = p.error,
        onError = p.background,
        errorContainer = p.subBackground,
        onErrorContainer = p.error,
        outline = p.stroke,
        outlineVariant = p.stroke,
    )
}

/** 宿主 SaltTextStyles → Material3 Typography：正文/次要文本/段落/大标题都用宿主字体。 */
private fun hostTypography(host: HostSaltTheme.Snapshot): Typography {
    val t = host.textStyles
    return Typography(
        displayLarge = t.largeTitle,
        displayMedium = t.largeTitle,
        displaySmall = t.largeTitle,
        headlineLarge = t.largeTitle,
        headlineMedium = t.largeTitle,
        headlineSmall = t.largeTitle,
        titleLarge = t.main,
        titleMedium = t.main,
        titleSmall = t.main,
        bodyLarge = t.paragraph,
        bodyMedium = t.main,
        bodySmall = t.sub,
        labelLarge = t.main,
        labelMedium = t.sub,
        labelSmall = t.sub,
    )
}

/** 宿主 SaltShapes → Material3 Shapes（宿主返回 Shape，M3 只收 CornerBasedShape）。 */
private fun hostShapes(host: HostSaltTheme.Snapshot): Shapes {
    val s = host.shapes
    return Shapes(
        extraSmall = cornerShape(s.small, 4.dp),
        small = cornerShape(s.small, 8.dp),
        medium = cornerShape(s.medium, 12.dp),
        large = cornerShape(s.large, 16.dp),
        extraLarge = cornerShape(s.large, 20.dp),
    )
}

private fun cornerShape(shape: Shape, fallback: Dp): CornerBasedShape =
    shape as? CornerBasedShape ?: RoundedCornerShape(fallback)

/** Hook 注册结果，供页面显示与真机排查（只写内存，不落盘）。 */
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
    PLAYLIST("歌单与同步"),
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

/**
 * 插件在宿主「插件配置」页内容区之上的原生页面（docs/PLAN.md 批次 1/2）。
 *
 * <p>约束：只在宿主当前配置页属于本插件时才绘制（见 [HostUiMountScope]）；不创建
 * 独立窗口；主题沿用宿主 SaltTheme 的配色/字体/圆角；缩放跟随宿主 CompositionLocals；
 * 关闭后只撤掉本层，宿主内容不动；页面打开时吞掉指针事件，避免点穿到宿主配置项。</p>
 */
@Composable
internal fun NativeUiPanel() = NativeUiTheme { NativeUiShell() }

@Composable
private fun NativeUiShell() {
    // 先于任何登录动作捕获真实宿主窗口（内容区/缩放）：验证助手要先核对窗口身份，
    // 而风控可能由后台 tick 在登录页第一次组合前后触发，不能等验证区域自己组合时才拿。
    val awtWindow = LocalAwtWindow.current
    SideEffect { VerifyHostWindow.capture(awtWindow) }
    if (!NativeUiState.visible) {
        // 关闭后仅保留入口；Box 本身不拦截指针事件，宿主配置页照常可操作。
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(16.dp),
            ) {
                TextButton(onClick = { NativeUiState.visible = true }) {
                    Text("打开网易云音乐")
                }
            }
        }
        return
    }

    Box(Modifier.fillMaxSize().consumeAllPointerEvents()) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NativeUiHeader()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                when (NativeUiState.page) {
                    NativePage.HOME -> NativeHomePage()
                    NativePage.SEARCH -> NativeSearchPage()
                    NativePage.PLAYLIST -> NativePlaylistPage()
                    NativePage.SYNC -> NativeSyncPage()
                    NativePage.LOGIN -> NativeLoginPage()
                    NativePage.ACCOUNT -> NativeAccountPage()
                    NativePage.DEVELOPER -> NativeDeveloperPage()
                }
            }
        }
    }
}

@Composable
private fun NativeUiHeader() {
    val page = NativeUiState.page
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (page != NativePage.HOME) {
            OutlinedButton(
                onClick = {
                    // 离开登录页 = 关闭登录流程：停二维码轮询、关验证窗（恢复语义在 AccountService 内）。
                    if (page == NativePage.LOGIN) {
                        NativeUiSession.closeLogin()
                    }
                    NativeUiState.page = NativePage.HOME
                },
            ) {
                Text("← 返回")
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = "网易云音乐 · " + page.title,
                style = MaterialTheme.typography.headlineSmall,
            )
            // 普通页面只保留用户可见信息：插件版本（宿主版本/密度/字体等诊断在开发者页）。
            Text(
                text = "插件版本 " + NativeUiRuntime.pluginVersion,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        OutlinedButton(
            onClick = {
                if (page == NativePage.LOGIN) {
                    NativeUiSession.closeLogin()
                }
                NativeUiState.visible = false
            },
        ) {
            Text("关闭并返回宿主配置")
        }
    }
}

@Composable
private fun NativeHomePage() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "网易云音乐功能内嵌在宿主主窗口里，不创建独立窗口；" +
                "搜索、歌单同步、登录与账号都在下面的原生页面中完成。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { NativeUiState.page = NativePage.SEARCH; NativeUiSession.requestLocalRefresh() }) {
                Text("搜索歌曲")
            }
            Button(onClick = { NativeUiState.page = NativePage.PLAYLIST; NativeUiSession.requestLocalRefresh() }) {
                Text("歌单与同步")
            }
            Button(onClick = { NativeUiState.page = NativePage.SYNC; NativeUiSession.refreshSync() }) {
                Text("同步状态")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { NativeUiSession.openLogin() }) {
                Text("登录")
            }
            Button(
                onClick = {
                    NativeUiSession.refreshAccount()
                    NativeUiState.page = NativePage.ACCOUNT
                },
            ) {
                Text("账号")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { NativeUiState.visible = false }) {
                Text("关闭并返回宿主配置")
            }
            TextButton(onClick = { NativeUiState.page = NativePage.DEVELOPER }) {
                Text("开发者信息")
            }
        }
        NativeSyncSummary()
    }
}

/**
 * 开发者信息页：只放诊断与验收说明，正式用户页面不再出现这些内容（REVIEW issue6）。
 *
 * <p>「主题来源」一栏显示实际读取到的是宿主 SaltTheme 还是降级到默认 Material3，
 * 便于在真机上确认 issue6 的验收结果，而不是靠注释声称。</p>
 */
@Composable
private fun NativeDeveloperPage() {
    val density = LocalDensity.current
    val theme = HostSaltTheme.read()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("宿主适配诊断", style = MaterialTheme.typography.titleMedium)
        NativeDeveloperRow("挂载状态", NativeUiState.status)
        NativeDeveloperRow("插件版本", NativeUiRuntime.pluginVersion)
        NativeDeveloperRow("宿主版本", NativeUiRuntime.hostVersion)
        NativeDeveloperRow("主题来源", HostSaltTheme.describe(theme))
        NativeDeveloperRow(
            "宿主深浅色",
            if (theme == null) "未知（未读到宿主 SaltTheme）" else if (theme.dark) "深色" else "浅色",
        )
        NativeDeveloperRow("系统深色检测", if (isSystemInDarkTheme()) "深色" else "浅色")
        NativeDeveloperRow("宿主密度", String.format("%.2f×", density.density))
        NativeDeveloperRow("字体缩放", String.format("%.2f×", density.fontScale))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { NativeUiState.clicks += 1 }) {
                Text("状态自检 +1（" + NativeUiState.clicks + "）")
            }
        }
        Text(
            text = "安装/挂载：插件通过宿主工坊的 afterComposable Hook 注入配置页内容区，" +
                "只在当前配置页的 pluginId 精确等于 com.example.netease 时绘制；" +
                "其它插件页、路由缺失或类型不认识时直接返回，不创建入口、不消费指针事件。",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "验收点：关闭后宿主原有配置内容应照常显示与操作；再次进入本插件配置页时，" +
                "右下角会保留「打开网易云音乐」入口，不会重复注册 Hook。" +
                "搜索与歌单页的请求超时仍为 20 秒，播放反馈只回显宿主回报，界面不伪造进度。" +
                "登录页与账号页都消费 AccountService 的同一份快照，不新增 cookie 或账号状态；" +
                "关闭登录页会停掉二维码轮询并走 AccountService 原有的取消/恢复语义。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 开发者页的一行诊断：左列标签、右列值。 */
@Composable
private fun NativeDeveloperRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(0.34f))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.66f))
    }
}

/** 首页的一行同步摘要（详细状态见同步页与歌单页）。 */
@Composable
private fun NativeSyncSummary() {
    val status = NativeUiState.sync
    val text = if (status == null) "尚未取得同步状态" else "同步：" + describeStatus(status)
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
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