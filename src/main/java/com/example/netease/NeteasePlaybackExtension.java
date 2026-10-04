package com.example.netease;

import com.example.netease.core.EventBus;
import com.example.netease.core.HostBridgeWorker;
import com.example.netease.core.PluginLog;
import com.example.netease.host.VoxzenBridge;
import com.example.netease.svc.LyricService;
import com.example.netease.svc.PlaybarCover;
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint;
import org.pf4j.Extension;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 播放扩展点实现 —— **本类只做「拷贝参数 + 投递队列 + 立刻返回」**。
 *
 * <p>宿主回调线程上绝对禁止：网络请求、写盘、锁等待、再调用宿主 API。
 * 这些回调都处在宿主播放流程的关键路径上，一旦阻塞就会造成卡顿。</p>
 *
 * <p>所有方法都必须「永不抛异常」：任何异常都必须被 {@code catch (Throwable)} 吞掉，
 * 否则会影响宿主播放流程。</p>
 *
 * <p><b>0.6.0：歌词钩子恢复</b>（0.5.0 瘦身时连代码删除，docs/00 §6.3）。宿主在取歌词时会分别问
 * {@link #updateLyrics} / {@link #onBeforeLoadLyrics} / {@link #onAfterLoadLyrics} 三个钩子，
 * 三个都返回<b>同一份</b>内存缓存文本（谁先被问到谁先给，效果一致）。</p>
 *
 * <p><b>为什么钩子里只能调 {@link LyricService#lrcFor}</b>：这三个方法跑在宿主回调线程上。
 * {@code lrcFor} 被刻意实现成纯内存查表（不联网、不碰磁盘、不记日志、不取锁），
 * 所以在这里调用是安全的；换成任何「会去抓一次」的写法都会把宿主播放线程堵在网络上。
 * 抓取提前由 {@code host.VoxzenBridge} 在拿到直链时投递给 {@code netease-lyric} 线程做完了。</p>
 *
 * <p><b>为什么日志要按曲子去重</b>：{@code core.PluginLog} 是<b>同步写盘</b>的，而宿主回调线程
 * 禁止写盘。所以每个钩子对每首歌最多只放行一行日志（{@link #logged}），既留下真机实验判据
 * （能看出哪条钩子被宿主调过、命中还是未命中），又不至于把换曲变成几十次 fsync。</p>
 */
@Extension
public final class NeteasePlaybackExtension implements PlaybackExtensionPoint {

    private static final String TAG = "ext";

    /**
     * 「本进程里已经为哪首歌记录过哪个钩子」——去重表。
     *
     * <p>用 {@code ConcurrentHashMap} 的键集：读/写都不需要我们自己拿监视器锁，
     * 符合「宿主回调线程不许锁等待」。容量上限 {@link #LOGGED_MAX}，超了就整体清空
     * （真机一次听歌远达不到这个量，清空只是防无界增长）。</p>
     */
    private static final Set<String> logged = ConcurrentHashMap.newKeySet();

    /** 去重表容量上限。 */
    private static final int LOGGED_MAX = 512;

    private static volatile boolean playing;

    @Override
    public void onStateChanged(State state) {
        try {
            String name = state == null ? "" : state.name();
            EventBus.get().offer(new EventBus.PlaybackState(name, playing));
            PlaybarCover.trace("播放态 " + name);     // 时序黑匣子（宿主回调线程上一次入队，零 IO）
            PlaybarCover.onHostState(name);            // 只存名字：Idle/Ended = 显示态可立即出图
            // 0.11.15：封面与音乐同时出场 —— 宿主报 Ready（这一曲装载完成、马上/正在出声）就是投递时刻。
            // 其余状态（Buffering/Idle/Ended）不投：Buffering 投下去就是用户实测过的「封面先切、音乐后到」。
            if ("Ready".equals(name)) {
                alignPlaybarWithMusic("播放态 Ready");
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "onStateChanged 异常（已忽略）:: " + t);
        }
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        try {
            playing = isPlaying;
            // ⚠️ 严禁在此回调里同步调用 playback().changeExclusive(...)
            EventBus.get().offer(new EventBus.PlaybackState("", isPlaying));
            PlaybarCover.trace("isPlaying=" + isPlaying);   // 时序黑匣子（一次入队，零 IO）
            PlaybarCover.noteHostPlaying(isPlaying);      // 播放态镜像（一次 volatile 写，零 IO；只作观测）
            // 0.11.15 起 isPlaying=true 就是「音乐真的在放」；0.11.18 起它是唯一的投递时刻之一
            // （另一条是播放态 Ready / 新鲜位置拍；一首只投一次，见 PlaybarCover 类注释的 0.11.18 一节）。
            if (isPlaying) {
                alignPlaybarWithMusic("播放态 isPlaying");
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "onIsPlayingChanged 异常（已忽略）:: " + t);
        }
    }

    /**
     * 播放态钩子里的封面对齐（0.11.15）：只做一次 {@code submitFast}，宿主回调线程零 IO。
     *
     * <p>当前曲 id 不在这里解析 —— 宿主那个「当前曲」反射字段取不到值（0.11.13 诊断已证），
     * 改由「正在供流的那一首」记在 {@link PlaybarCover} 里；这里只负责通知「音乐起来了」。</p>
     */
    private static void alignPlaybarWithMusic(String why) {
        try {
            HostBridgeWorker.get().submitFast(() -> PlaybarCover.onMusicStarted(why));
        } catch (Throwable t) {
            PluginLog.d(TAG, "播放态封面同步失败（忽略）:: " + t);
        }
    }

    @Override
    public void onSeekTo(long positionMs) {
        try {
            EventBus.get().offer(new EventBus.PositionTick(positionMs));
        } catch (Throwable t) {
            PluginLog.d(TAG, "onSeekTo 异常（已忽略）:: " + t);
        }
    }

    @Override
    public void onPositionUpdated(long positionMs) {
        try {
            // 约每秒一次：不做任何日志，只投递位置
            EventBus.get().offer(new EventBus.PositionTick(positionMs));
            // 0.11.15：位置真的在推进（> 0）= 音频在放 —— 换曲场景最贴近「耳朵听见」的起播信号。
            // 这一路在宿主回调线程上只做几次内存读（快闸不过就返回），不做反射、不写盘。
            PlaybarCover.onPositionTick(positionMs);
        } catch (Throwable ignored) {
            // 位置更新失败不值得记录
        }
    }

    // ---------------------------------------------------------------- 歌词钩子（0.6.0 恢复）

    /**
     * 宿主「更新歌词」钩子：返回内存缓存里的歌词文本。
     *
     * <p>线程：宿主回调线程 —— 纯内存查表，绝不联网/写盘。
     * 每首歌最多记一行日志（真机实验判据：确认宿主真的调了这条钩子）。</p>
     *
     * @param item 宿主当前曲目项
     * @return 标准 LRC 文本；未命中返回 {@code null}（宿主退回自己的歌词来源）
     */
    @Override
    public String updateLyrics(MediaItem item) {
        try {
            return handle("updateLyrics", item);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 宿主「加载歌词前」钩子：与 {@link #updateLyrics} 同一份文本。
     *
     * @param item 宿主当前曲目项
     * @return 标准 LRC 文本；未命中返回 {@code null}
     */
    @Override
    public String onBeforeLoadLyrics(MediaItem item) {
        try {
            return handle("onBeforeLoadLyrics", item);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 宿主「加载歌词后」钩子：与 {@link #updateLyrics} 同一份文本。
     *
     * <p>宿主把这条放在「已经自己找过一遍」之后，所以它命中时往往意味着
     * 宿主自己的歌词源没找到、由我们补上——日志里区分开这三个名字就是为了
     * 真机上判断到底哪条路径在起作用。</p>
     *
     * @param item 宿主当前曲目项
     * @return 标准 LRC 文本；未命中返回 {@code null}
     */
    @Override
    public String onAfterLoadLyrics(MediaItem item) {
        try {
            return handle("onAfterLoadLyrics", item);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 三条歌词钩子的公共实现：查缓存 + 未命中补抓 + 一次性日志。
     *
     * <p>只允许调用三个外部方法，且三个都是「不阻塞宿主回调线程」的：
     * {@link LyricService#lrcFor(String, String, String)}（纯内存查表）、
     * {@link LyricService#ensureAsync(long, String, String, String)}（只投一次线程池，不联网不写盘）
     * 与 {@link LyricService#kickWarmNeighbors(long)}（同曲去重 + 起一条短命后台线程，见 0.11.8 R14 接线）。
     * 0.7.0 起 {@link MediaItem#getPath()} 可能是本地流 URL
     * （{@code http://127.0.0.1:<port>/netease/<songId>}），因此从 path 抠 songId 就能定位曲目——
     * 这比「标题 + 歌手」可靠得多。</p>
     */
    private static String handle(String hook, MediaItem item) {
        if (item == null) {
            return null;
        }
        String path = item.getPath();
        String title = item.getTitle();
        String artist = item.getArtist();
        String text = LyricService.lrcFor(path, title, artist);
        long sid = LyricService.songIdOf(path);
        if (text == null && sid > 0L) {
            LyricService.ensureAsync(sid, path, title, artist);
        }
        // 0.11.8（接线修复）：换曲时刻给宿主队列里的「下一首」保温（R14 / 判据行 J5）。
        // 此前 `LyricService.warmNeighbors(...)` 全树无调用点 ⇒ 该判据结构上打不出来。
        // `kickWarmNeighbors` 只做「同曲去重 + 起一条短命后台线程」（不读队列、不联网、不写盘），
        // 所以仍然满足 A10 的「宿主回调线程零 IO」。
        LyricService.kickWarmNeighbors(sid);
        // 0.11.11（播放条封面）：换曲时把「该放哪一首」记给封面通道（D4 路线 ② 的生产化）。
        // 播放条/播放页那一处不走封面加载器、也不查 shared_cover（它只认「本地音频文件内嵌图」），
        // 所以只能由我们直投成品位图。noteCurrent() 只做「一次判重 + 一次记曲」：宿主回调线程零 IO。
        // 0.11.15 起「在播时只记不投」；0.11.18 起**无论在不在地只记不投**（用户规格：封面与音乐同一拍进场、
        // 且只能进场一次）—— 投递时刻只由「播放态 Ready / isPlaying=true / 新鲜位置拍」触发。
        PlaybarCover.noteCurrent(sid, hook, playing);
        logOnce(hook, item, title, artist, path, text != null);
        return text;
    }

    /** 每首歌每个钩子最多记一行（{@code PluginLog} 同步写盘，宿主回调线程上必须克制）。 */
    private static void logOnce(String hook, MediaItem item, String title, String artist,
                                String path, boolean hit) {
        try {
            String id = item.getPath();
            if (id == null || id.isEmpty()) {
                id = (title == null ? "" : title) + '|' + (artist == null ? "" : artist);
            }
            if (logged.size() >= LOGGED_MAX) {
                logged.clear();
            }
            if (!logged.add(hook + '|' + id)) {
                return;
            }
            String tail = path == null ? "" : (path.length() > 48 ? "…" + path.substring(path.length() - 48) : path);
            PluginLog.i(TAG, "歌词钩子 " + hook + (hit ? " 命中" : " 未命中")
                    + " title=" + title + " artist=" + artist + " path=" + tail);
        } catch (Throwable ignored) {
            // 记日志本身失败绝不能影响宿主播放流程
        }
    }
}
