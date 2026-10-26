@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class, androidx.compose.runtime.InternalComposeApi::class)
package com.example.netease.nativeui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.*
import androidx.compose.ui.window.PopupPositionProvider
import com.example.netease.core.PluginLog
import com.example.netease.host.HostSqlBridge
import com.example.netease.net.Dto
import com.example.netease.svc.*
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.reflect.Proxy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Decorates the native DSL, retaining native queue/album/artist/info actions. Local selections pass through. */
internal object HostCloudSongMenu {
    private const val WRAPPER = "com.xuncorp.voxzen.ui.component.\u052c"
    private const val OWNER = "androidx.compose.ui.\u10f5"
    private val params = listOf(PopupPositionProvider::class.java.name, "kotlin.jvm.functions.Function0", "kotlin.jvm.functions.Function1")
    private val method by lazy { Class.forName(WRAPPER).declaredMethods.single {
        it.name == "\u037f" && it.parameterCount == 6 && it.parameterTypes[0] == PopupPositionProvider::class.java
    } }
    private val scope by lazy { HostUiAbi.type("androidx.compose.ui.\u09a8") }
    private var handle: HookHandle? = null
    internal fun popupMethod() = method
    internal fun popupCaller() = HostUiAbi.name(OWNER)
    data class Selection(val songs: List<Dto.Song>, val playlist: Long)
    data class Loaded(val epoch: Long, val uid: Long, val playlists: List<Dto.PlaylistBrief>,
                      val memberships: Map<Long, List<Long>>, val error: String = "")
    data class Removal(val selection: Selection, val target: Dto.PlaylistBrief, val account: Loaded)
    /** 当前展示的移除确认框内容；退场动画期间仍保留，动画结束才清空。 */
    var removal by mutableStateOf<Removal?>(null); private set
    private var removalVisible by mutableStateOf(false)
    var message by mutableStateOf(""); private set
    private var messageVisible by mutableStateOf(false)
    private val lifecycle = java.util.concurrent.atomic.AtomicLong()

    /** RootHook 用它决定是否继续提供宿主材质 local；退场结束前必须保持 true，否则会崩。 */
    fun dialogOpen(): Boolean = removal != null || message.isNotBlank()
    fun dismissRemoval() { removalVisible = false }
    fun dismissMessage() { messageVisible = false }
    private fun clearRemoval() { removal = null; removalVisible = false }
    private fun clearMessage() { message = ""; messageVisible = false }
    private fun postMessage(text: String) { if (text.isNotBlank()) { message = text; messageVisible = true } }
    fun stop() {
        lifecycle.incrementAndGet()
        removal = null; removalVisible = false; message = ""; messageVisible = false
        // 账号切换/卸载时旧一代的在途歌单请求必须失效，不能给新账号复用。
        CloudMenuCache.invalidateAll()
    }
    fun install() {
        check(method.parameterCount == 6 && scope.isInterface)
        if(handle == null) handle = WorkshopApi.hookRegistrar.replaceComposable(WRAPPER, "\u037f", HostUiAbi.name(OWNER), params, 100, Hook)
    }
    fun uninstall() { handle?.unhook(); handle = null; stop() }
    internal fun selection(builder: Any): Selection? = runCatching {
        val values = builder.javaClass.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map {
            it.isAccessible = true; it.get(builder)
        }
        val tracks = values.filterIsInstance<Set<*>>().firstOrNull { it.isNotEmpty() && it.first()?.javaClass?.name == "com.xuncorp.voxzen.data.entity.Track" } ?: return null
        val songs = tracks.map { track ->
            fun get(name: String) = track!!.javaClass.getMethod(name).invoke(track)
            val id = get("getId") as String
            if(!id.startsWith(NativeLibrary.ID_PREFIX) || id.startsWith(NativeLibrary.PLAYLIST_PREFIX)) return null
            val sid = id.removePrefix(NativeLibrary.ID_PREFIX).toLongOrNull() ?: return null
            Dto.Song(sid, get("getTitle") as String, get("getArtist") as String, get("getAlbum") as String,
                get("getDuration") as Long, 0, true, "")
        }
        val properties = values.firstOrNull { it != null && it.javaClass == HostUiAbi.type("androidx.compose.ui.\u0712") }
        val pid = properties?.javaClass?.getMethod("\u037f")?.invoke(properties) as? String
        Selection(songs, pid?.takeIf { it.startsWith(NativeLibrary.PLAYLIST_PREFIX) }
            ?.removePrefix(NativeLibrary.PLAYLIST_PREFIX)?.toLongOrNull() ?: 0L)
    }.getOrElse { PluginLog.w("cloud-menu", "读取宿主选中曲目失败：$it"); null }

    internal fun labelKey(label: Any?): Int? = runCatching {
        label?.javaClass?.getDeclaredField("key")?.apply { isAccessible = true }?.getInt(label)
    }.getOrNull()
    internal fun suppress(key: Int?) = key in setOf(1605270770, 1419648159, -1468264589, -1687823975)
    private object Hook : ComposableHook() {
        @Composable override fun Content(call: UiHookCall) {
            @Suppress("UNCHECKED_CAST")
            val original = call.arguments[2] as (Any) -> Unit
            val selected = remember(original) { selection(original) }
            if(selected == null) { draw(call, original); return }
            var loaded by remember(selected) { mutableStateOf<Loaded?>(null) }
            val epoch = AccountService.sessionEpoch()
            LaunchedEffect(selected, epoch) {
                loaded = null
                val uid = AccountService.status().uid()
                try {
                    // 缓存只挂 whenComplete，不在调用线程 get；成员关系仍每次读宿主库。
                    val mine = awaitPlaylists(epoch, uid)
                    val memberships = withContext(Dispatchers.IO) {
                        HostSqlBridge.onHostConnection { sql ->
                            NativeLibrary.membershipsForSongsOn(sql, selected.songs.map { it.id() })
                        } ?: emptyMap()
                    }
                    loaded = Loaded(epoch, uid, mine, memberships)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    loaded = Loaded(epoch, uid, emptyList(), emptyMap(), "歌单读取失败，请重新打开菜单")
                }
            }
            @Suppress("UNCHECKED_CAST")
            val dismiss = call.arguments[1] as () -> Unit
            val decorated = remember(original, selected, loaded) {
                { target: Any -> decorate(original, target, selected, loaded, dismiss) }
            }
            draw(call, decorated)
        }
        @Composable private fun draw(call: UiHookCall, builder: (Any) -> Unit) {
            method.invoke(null, call.arguments[0], call.arguments[1], builder, currentComposer, 0, 0)
        }
    }
    private suspend fun awaitPlaylists(epoch: Long, uid: Long): List<Dto.PlaylistBrief> =
        suspendCancellableCoroutine { continuation ->
            CloudMenuCache.playlists(epoch, uid) { playlists, error ->
                if (error != null) continuation.resumeWithException(error)
                else continuation.resume(playlists.orEmpty())
            }
        }

    internal fun decorate(original: (Any) -> Unit, target: Any, selected: Selection, loaded: Loaded?, dismiss: () -> Unit) {
        val proxy = Proxy.newProxyInstance(scope.classLoader, arrayOf(scope)) { _, method, args ->
            val key = args?.lastOrNull()?.let(::labelKey)
            when {
                suppress(key) -> null
                key == -1734620327 || key == -581413720 || key == -967620052 -> null // Replace native local-only writes below.
                else -> method.invoke(target, *(args ?: emptyArray()))
            }
        }
        original(proxy)
        HostSongPopup.divider(target)
        HostSongPopup.item(target, "添加到我喜欢的音乐", loaded != null && loaded.uid > 0 && loaded.error.isEmpty()) {
            dismiss(); edit(selected, loaded!!, 0, "我喜欢的音乐", true, false)
        }
        if(loaded == null) HostSongPopup.item(target, "正在读取网易云歌单…", false) {}
        else if(loaded.error.isNotBlank()) HostSongPopup.item(target, loaded.error, false) {}
        else {
            HostSongPopup.submenu(target, "添加到其它网易云歌单", loaded.playlists.isNotEmpty()) { nested ->
                loaded.playlists.filter { it.id() != selected.playlist }.forEach { p ->
                    HostSongPopup.item(nested, p.name(), true) { dismiss(); edit(selected, loaded, p.id(), p.name(), false, false) }
                }
            }
            val targets = loaded.playlists.filter { p -> if(selected.playlist > 0) p.id() == selected.playlist
                else selected.songs.any { p.id() in loaded.memberships[it.id()].orEmpty() } }
            fun ask(p: Dto.PlaylistBrief) { dismiss(); removal = Removal(selected, p, loaded); removalVisible = true }
            if(selected.playlist > 0) HostSongPopup.item(target, "从此歌单移除", targets.isNotEmpty()) { ask(targets.first()) }
            else HostSongPopup.submenu(target, "从网易云歌单移除", targets.isNotEmpty()) { nested ->
                targets.forEach { p -> HostSongPopup.item(nested, p.name(), true) { ask(p) } }
            }
        }
    }
    private fun edit(selected: Selection, account: Loaded, pid: Long, title: String, like: Boolean, remove: Boolean) {
        val token = lifecycle.get()
        val songs = if(remove && selected.playlist == 0L) selected.songs.filter { pid in account.memberships[it.id()].orEmpty() } else selected.songs
        CloudPlaylistEdits.edit(account.epoch, account.uid, songs, pid, title, like, remove) { error ->
            // 成功或失败都让下一次右键重新读云端：成功必须看到新状态，失败也要允许重试。
            if (error.isBlank()) CloudMenuCache.invalidateAll()
            else if (token == lifecycle.get() && account.epoch == AccountService.sessionEpoch() &&
                account.uid == AccountService.status().uid()) postMessage(error)
        }
    }
    fun confirmRemoval() {
        val request = removal ?: return
        // 业务立即生效：先不可交互，视觉退场由 DialogSlot 收尾。
        removalVisible = false
        edit(request.selection, request.account, request.target.id(), request.target.name(), false, true)
    }

    /** 保留最后内容直到退场结束；visible=false 期间卡片不可交互（interactive=false）。 */
    @Composable private fun DialogSlot(visible: Boolean, onExited: () -> Unit, content: @Composable () -> Unit) {
        val state = remember { MutableTransitionState(false) }
        SideEffect { state.targetState = visible }
        LaunchedEffect(state.isIdle, visible) {
            if (!visible && state.isIdle && !state.currentState) onExited()
        }
        AnimatedVisibility(
            visibleState = state,
            // 只淡入淡出：卡片自身的 0.96→1 缩放在 EmbeddedSaltDialog 内部完成，
            // 这里再缩放会把全屏遮罩一起缩小、露出发丝边。
            enter = fadeIn(Motion.dialogEnter()),
            exit = fadeOut(Motion.dialogExit()),
        ) { content() }
    }

    @Composable fun Dialogs() {
        // 兜底：宿主材质/provider 被撤时整个 Dialogs 会离开组合，退场回调没机会跑；
        // 若仍留着 removal/message，dialogOpen() 会永久为 true，焦点与 Esc 就再也回不来。
        DisposableEffect(Unit) { onDispose { clearRemoval(); clearMessage() } }
        removal?.let { request ->
            DialogSlot(removalVisible, ::clearRemoval) {
                EmbeddedSaltDialog("从此歌单移除", ::dismissRemoval, ::confirmRemoval, true, "取消", "移除",
                    interactive = removalVisible) {
                    val count = if(request.selection.playlist > 0) request.selection.songs.size else request.selection.songs.count {
                        request.target.id() in request.account.memberships[it.id()].orEmpty()
                    }
                    SaltText("确定将 $count 首歌曲从「${request.target.name()}」移除吗？网易云中的该歌单也会同步移除。")
                }
            }
        }
        if(message.isNotBlank()) {
            DialogSlot(messageVisible, ::clearMessage) {
                EmbeddedSaltDialog("歌单修改结果", ::dismissMessage, interactive = messageVisible) { SaltText(message) }
            }
        }
    }
}
