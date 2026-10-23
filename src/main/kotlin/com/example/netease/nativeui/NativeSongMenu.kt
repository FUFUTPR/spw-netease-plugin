@file:OptIn(androidx.compose.runtime.InternalComposeApi::class)
package com.example.netease.nativeui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.PopupPositionProvider
import com.example.netease.net.Dto
import com.example.netease.svc.AccountService
import com.example.netease.ui.controller.PlaylistMenuController
import java.util.concurrent.*
import java.util.function.Consumer

/** Uses the host's SPC PopupMenu and its actual menu-item DSL. */
internal object HostSongPopup {
    private val scope by lazy { HostUiAbi.type("androidx.compose.ui.\u09a8") }
    private val popup by lazy { HostUiAbi.type("androidx.compose.ui.\u0587").declaredMethods.single {
        it.name == "\u037f" && it.parameterCount == 7 && it.parameterTypes[0] == PopupPositionProvider::class.java
    } }
    private val item by lazy { scope.methods.single {
        it.name == "\u037f" && it.parameterCount == 3 && it.parameterTypes[0].name == "kotlin.jvm.functions.Function0"
    } }
    private val divider by lazy { scope.methods.single { it.name == "\u037f" && it.parameterCount == 0 } }
    private val submenu by lazy { scope.methods.single {
        it.name == "\u037f" && it.parameterCount == 3 && it.parameterTypes[0].name == "kotlin.jvm.functions.Function1"
    } }
    fun submenu(scope: Any, text: String, enabled: Boolean, builder: (Any) -> Unit) {
        val label: @Composable () -> Unit = { SaltText(text, modifier = Modifier.widthIn(min = 250.dp, max = 480.dp)) }
        submenu.invoke(scope, builder, enabled, label)
    }
    fun validate() { check(popup.parameterCount == 7 && item.parameterCount == 3) }
    fun item(scope: Any, text: String, enabled: Boolean, action: () -> Unit) {
        val label: @Composable () -> Unit = { SaltText(text, modifier = Modifier.widthIn(min = 250.dp, max = 480.dp)) }
        item.invoke(scope, action, enabled, label)
    }
    fun divider(scope: Any) { divider.invoke(scope) }
    @Composable fun Menu(state: PlaylistMenuController.State, dismiss: () -> Unit, add: (Long, Boolean) -> Unit) {
        val anchor = NativeSongMenu.anchor
        val position = remember(anchor) { object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                                           layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset(
                anchor.x.toInt().coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                anchor.y.toInt().coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
        } }
        val builder: (Any) -> Unit = { scope ->
            val loggedIn = AccountService.status().state() == AccountService.State.LOGGED_IN
            item(scope, "添加到我喜欢的音乐", loggedIn && !state.busy()) { add(0, true) }
            divider(scope)
            when {
                state.busy() -> item(scope, "正在添加…", false) {}
                state.loading() -> item(scope, "正在读取歌单…", false) {}
                state.playlists().isEmpty() -> item(scope, state.message().ifBlank { "暂无自己创建的歌单" }, false) {}
                else -> state.playlists().forEach { playlist ->
                    item(scope, playlist.name(), loggedIn) { add(playlist.id(), false) }
                }
            }
        }
        // Default container (bit 2) preserves native sizing, scrolling, animation and theme.
        popup.invoke(null, position, dismiss, null, builder, currentComposer, 0, 4)
    }
}

internal object NativeSongMenu {
    var state by mutableStateOf<PlaylistMenuController.State?>(null)
        private set
    var visible by mutableStateOf(false)
        private set
    var anchor by mutableStateOf(Offset.Zero)
        private set
    private var controller: PlaylistMenuController? = null
    private var executor: ExecutorService? = null
    private val lifecycle = java.util.concurrent.atomic.AtomicLong()
    @Synchronized private fun start() {
        if (controller != null) return
        HostSongPopup.validate()
        val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2),
            ThreadFactory { task -> Thread(task, "netease-playlist-menu").apply { isDaemon = true } })
        executor = worker
        controller = PlaylistMenuController({
            val account = AccountService.status()
            PlaylistMenuController.Identity(AccountService.sessionEpoch(),
                if (account.state() == AccountService.State.LOGGED_IN) account.uid() else 0L)
        }, object : PlaylistMenuController.Cloud {
            override fun playlists(uid: Long) = AccountService.menuPlaylists(AccountService.sessionEpoch(), uid)
                .get(25, TimeUnit.SECONDS)
            override fun add(account: PlaylistMenuController.Identity, song: Long, playlist: Long,
                             like: Boolean, done: Consumer<String>) {
                val current = controller?.state() ?: return done.accept("请重新打开菜单")
                val metadata = current.song() ?: return done.accept("没有选中歌曲")
                val title = if (like) "我喜欢的音乐" else current.playlists().firstOrNull { it.id() == playlist }?.name().orEmpty()
                com.example.netease.svc.CloudPlaylistEdits.edit(account.epoch(), account.uid(), listOf(metadata),
                    playlist, title, like, false, done)
            }
        }, worker, { state = it }, {}, System::currentTimeMillis)
    }
    fun open(song: Dto.Song, position: Offset) {
        start()
        if (controller?.state()?.busy() == true) return
        anchor = position; controller?.open(song); visible = true
    }
    fun dismiss() { visible = false }
    fun add(playlist: Long, like: Boolean) { visible = false; controller?.add(playlist, like) }
    @Synchronized fun stop() {
        lifecycle.incrementAndGet()
        com.example.netease.svc.CloudPlaylistEdits.stop()
        HostCloudSongMenu.stop()
        controller?.close(); controller = null
        executor?.shutdownNow(); executor = null
        visible = false; state = null
    }
}
