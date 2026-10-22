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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.netease.net.Dto
import com.example.netease.svc.LocalPlaylists
import com.example.netease.svc.SyncCoordinator
import com.example.netease.ui.controller.PlaylistController
import com.example.netease.ui.controller.SearchController

/**
 * 深浅色适配：宿主 1.19.0 的界面是自绘配色，没有使用 Material3 的 MaterialTheme，
 * 因此 Hook 点继承不到宿主配色。这里用 Compose 提供的系统深色检测（与宿主
 * com.xuncorp.voxzen.util.AppConfig 使用的是同一个 isSystemInDarkTheme）决定用
 * Material3 深色还是浅色配色，至少让「深色/浅色模式」与宿主保持一致；
 * 具体品牌色等宿主专属颜色仍无法从宿主读取（记在 ITERATION 的已知偏差）。
 */
@Composable
private fun NativeUiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

/** Hook 注册结果，供页面显示与真机排查（只写内存，不落盘）。 */
internal object NativeUiRuntime {
    var hostVersion: String = "unknown"
    var pluginVersion: String = "unknown"
}

/** 原生页面内的路由（不涉及宿主导航，纯插件内部页面切换）。 */
internal enum class NativePage(val title: String) {
    HOME("原生 UI 测试区域"),
    SEARCH("搜索歌曲"),
    PLAYLIST("歌单与同步"),
    SYNC("同步状态"),
}

/**
 * 页面状态：visible=false 时只留右下角入口，宿主原有配置内容照常可见可用。
 *
 * <p>控制器状态（search/playlist）由 [NativeUiSession] 的后台 tick 线程写回，
 * 读取时是 Compose snapshot 状态，切页不丢。</p>
 */
internal object NativeUiState {
    var visible by mutableStateOf(true)
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

    /** 打开歌单页时按需刷新一次本地歌单列表（读盘在后台线程）。 */
    var localRefreshRequested by mutableStateOf(false)
}

/**
 * 插件在宿主「插件配置」页内容区之上的原生页面（docs/PLAN.md 批次 1/2）。
 *
 * <p>约束：不创建独立窗口；主题跟随宿主深浅色；缩放跟随宿主 CompositionLocals；
 * 关闭后只撤掉本层，宿主内容不动；页面打开时吞掉指针事件，避免点穿到宿主配置项。</p>
 */
@Composable
internal fun NativeUiPanel() = NativeUiTheme { NativeUiShell() }

@Composable
private fun NativeUiShell() {
    // 宿主提供的密度/字体缩放：页面不加任何自己的 Density 提供者，直接用宿主的 CompositionLocals。
    val density = LocalDensity.current
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
                    Text("打开原生 UI 测试区域")
                }
            }
        }
        return
    }

    Box(Modifier.fillMaxSize().consumeAllPointerEvents()) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NativeUiHeader(density.density, density.fontScale)
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
                }
            }
        }
    }
}

@Composable
private fun NativeUiHeader(density: Float, fontScale: Float) {
    val page = NativeUiState.page
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (page != NativePage.HOME) {
            OutlinedButton(onClick = { NativeUiState.page = NativePage.HOME }) {
                Text("← 返回")
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = "网易云音乐 · " + page.title,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "插件 " + NativeUiRuntime.pluginVersion + " · 宿主 " + NativeUiRuntime.hostVersion +
                    " · 密度 " + String.format("%.2f×", density) +
                    " · 字体 " + String.format("%.2f×", fontScale) +
                    " · " + (if (isSystemInDarkTheme()) "深色" else "浅色"),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        OutlinedButton(onClick = { NativeUiState.visible = false }) {
            Text("关闭并返回宿主配置")
        }
    }
}

@Composable
private fun NativeHomePage() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "这一区域由插件在宿主主窗口内用 Compose 渲染，不创建任何独立窗口。" +
                "挂载状态：" + NativeUiState.status,
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
            OutlinedButton(onClick = { NativeUiState.clicks += 1 }) {
                Text("状态自检 +1（" + NativeUiState.clicks + "）")
            }
            OutlinedButton(onClick = { NativeUiState.visible = false }) {
                Text("关闭并返回宿主配置")
            }
        }
        NativeSyncSummary()
        Text(
            text = "验证点：关闭后宿主原有配置内容应照常显示与操作；再次进入配置页时，" +
                "右下角会保留「打开原生 UI 测试区域」入口，不会重复注册 Hook。" +
                "搜索与歌单页的请求超时仍为 20 秒，播放反馈只回显宿主回报，界面不伪造进度。",
            style = MaterialTheme.typography.bodySmall,
        )
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
