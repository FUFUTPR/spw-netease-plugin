package com.example.netease.nativeui

import com.example.netease.core.PluginLog
import com.example.netease.net.Dto
import com.example.netease.svc.AccountService
import java.util.concurrent.CompletableFuture

/**
 * 云歌单菜单的短期缓存：右键菜单每次打开都会拉一次「我的歌单」，同一账号 30 秒内重复打开
 * 不应重复走网络；并发打开共享同一个在途请求。成员关系仍每次读宿主库（“已在本歌单”要立刻
 * 反映本机播放状态），因此缓存里只存歌单列表本身。
 *
 * <p><b>失效隔离</b>：每次 invalidateAll 递增 generation 并清空 cache 与 inflight。旧请求完成时
 * 即使结果成功也只投递给自己的等待者，不写缓存、不按 key 删除新一代的 inflight；失效之后的新调用
 * 一定发起新请求，不会 join 旧 future。TTL 用 System.nanoTime 的单调时钟，不受系统时间调整影响。</p>
 *
 * <p><b>不阻塞</b>：menuPlaylists 本身就是 CompletableFuture；本缓存只挂 whenComplete，不在调用线程
 * 上 get。界面用 [playlists] 的回调版本，避免右键菜单在 IO 线程上同步等待。</p>
 */
internal object CloudMenuCache {
    private const val TTL_NANOS = 30_000_000_000L
    private data class Key(val epoch: Long, val uid: Long)
    private data class Entry(val playlists: List<Dto.PlaylistBrief>, val atNanos: Long)

    private class Request(val key: Key, val generation: Long) {
        val callbacks = ArrayList<(List<Dto.PlaylistBrief>?, Throwable?) -> Unit>(2)
        var done = false
    }

    private val cache = HashMap<Key, Entry>()
    private val inflight = HashMap<Key, Request>()
    private val lock = Any()

    /** 失效代次：旧请求完成时用它判断自己是否还属于当前一代。 */
    private var generation = 0L

    /**
     * 异步读取当前账号可见歌单。回调只会被调用一次，可能同步（缓存命中）也可能在
     * AccountService 的完成线程上；实现方负责切回 UI 线程。
     */
    fun playlists(epoch: Long, uid: Long, callback: (List<Dto.PlaylistBrief>?, Throwable?) -> Unit) {
        val key = Key(epoch, uid)
        val cached: List<Dto.PlaylistBrief>?
        val toStart: Request?
        synchronized(lock) {
            val entry = cache[key]
            if (entry != null && System.nanoTime() - entry.atNanos <= TTL_NANOS) {
                cached = entry.playlists
                toStart = null
            } else {
                cached = null
                val running = inflight[key]
                if (running != null) {
                    running.callbacks.add(callback)
                    return
                }
                val request = Request(key, generation)
                request.callbacks.add(callback)
                inflight[key] = request
                toStart = request
            }
        }
        if (cached != null) {
            deliver(listOf(callback), cached, null)
            return
        }
        start(toStart!!)
    }

    /**
     * 兼容 CompletableFuture 调用方（探针/旧调用点）：缓存与请求仍是异步的，本方法不阻塞。
     * 新界面代码请优先用回调版本，避免在外部线程上 get。
     */
    fun playlists(epoch: Long, uid: Long): CompletableFuture<List<Dto.PlaylistBrief>> {
        val future = CompletableFuture<List<Dto.PlaylistBrief>>()
        playlists(epoch, uid) { list, error ->
            if (error != null) future.completeExceptionally(error) else future.complete(list.orEmpty())
        }
        return future
    }

    private fun start(request: Request) {
        val future = try {
            AccountService.menuPlaylists(request.key.epoch, request.key.uid)
        } catch (t: Throwable) {
            complete(request, null, t)
            return
        }
        future.whenComplete { list, error ->
            if (error != null) {
                complete(request, null, unwrap(error))
            } else {
                complete(request, list.orEmpty().filter { it.mine() }, null)
            }
        }
    }

    private fun complete(request: Request, playlists: List<Dto.PlaylistBrief>?, error: Throwable?) {
        val callbacks: List<(List<Dto.PlaylistBrief>?, Throwable?) -> Unit>
        synchronized(lock) {
            if (request.done) return
            request.done = true
            // 只删除自己那一代：失效后同 key 已经有新的 Request，绝不能被旧结果删掉。
            if (inflight[request.key] === request) inflight.remove(request.key)
            if (error == null && playlists != null && request.generation == generation) {
                cache[request.key] = Entry(playlists, System.nanoTime())
            }
            callbacks = request.callbacks.toList()
            request.callbacks.clear()
        }
        if (error != null) {
            PluginLog.d("cloud-menu", "读取云歌单失败（下次打开菜单会重试）：$error")
        }
        deliver(callbacks, playlists, error)
    }

    private fun deliver(
        callbacks: List<(List<Dto.PlaylistBrief>?, Throwable?) -> Unit>,
        playlists: List<Dto.PlaylistBrief>?,
        error: Throwable?,
    ) {
        callbacks.forEach { callback ->
            runCatching { callback(playlists, error) }
        }
    }

    private fun unwrap(error: Throwable): Throwable =
        if (error is java.util.concurrent.CompletionException && error.cause != null) error.cause!! else error

    fun invalidateAll() {
        synchronized(lock) {
            generation++
            cache.clear()
            // 旧请求仍会完成并通知自己的等待者，但它们不再属于当前一代；新调用必须新起请求。
            inflight.clear()
        }
    }
}
