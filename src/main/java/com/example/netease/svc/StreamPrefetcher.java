package com.example.netease.svc;

import com.example.netease.cfg.PluginConfig;
import com.example.netease.core.PluginLog;
import com.example.netease.core.PluginVersions;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 0.11.0：<b>播放即全量预取</b> —— 解决宿主「每 24~49 秒才要下一个 512 KB」造成的听歌卡顿。
 *
 * <p><b>问题</b>（真机实证，证据见 {@code docs/08 §39}）：宿主自己的取流器是「挤牙膏」式的——
 * 每 24~49 秒才发出一个 {@code Range: bytes=<n>-<n+512K>} 请求，折算 ≈49 KB/s；同一首歌从
 * CDN 直接整首拉只有 ≈2 秒（≈18 MB/s）。于是「播放」比「下载」慢了两个数量级，一旦网络抖动、
 * 或者宿主把缓冲读干，听感就是卡顿与断流。</p>
 *
 * <p><b>修法</b>：宿主一开口播（{@code Range} 从头开始的请求），插件就在<b>自己的守护线程</b>上
 * 把整首按 512 KB 一块顺序拉完，边拉边按原文件偏移写进 {@link StreamingAudioCache} 的
 * {@code <songId>-<level>.part}；宿主后续的 Range 请求只要落在已落盘的区间内，
 * {@link NativeStreamServer} 就直接从本地文件直出（GB/s 级，与网络无关）。
 * 拉完整首后 {@code .part} 原子改名 {@code .cache}，同一首歌再播就是纯本地读取。</p>
 *
 * <p><b>两种模式</b>（与配置键 {@code audio_cache_gb} 同源）：</p>
 * <ul>
 *   <li>{@link Mode#PERSISTENT}（容量 &gt; 0）：预取产物进 LRU 缓存，跨曲、跨会话留着；</li>
 *   <li>{@link Mode#EPHEMERAL}（容量 = 0，即「不缓存」）：照样<b>立刻拉完整首</b>（保证本次播放不卡），
 *       但切歌时上一首立即删除 —— 与用户确认的口径一致。</li>
 * </ul>
 *
 * <p><b>纪律</b>（docs/00 §4/§5）：本类只在自建守护线程 {@code netease-prefetch-1} 与宿主自己的
 * 流服务线程上跑，<b>绝不</b>出现在宿主回调线程（{@code NeteasePlaybackExtension} 三条歌词钩子）
 * 里；任何异常都被 {@code catch (Throwable)} 兜住，只记一行日志、绝不外抛；
 * 切歌时旧工作项立刻 {@code cancelled}，读循环在下一次 {@code read()} 返回后即退出。</p>
 */
public final class StreamPrefetcher {

    private static final String TAG = "prefetch";

    /** 预取模式：由配置键 {@code audio_cache_gb} 推导（0 = 不缓存）。 */
    public enum Mode {
        /** 容量 0：本次播放拉完整首，切歌即删。 */
        EPHEMERAL,
        /** 容量 &gt; 0：预取产物进 LRU 缓存。 */
        PERSISTENT
    }

    /** 预取连接数（默认 1 条顺序连接：不抢播放带宽，也最贴近上游的风控容忍度）。 */
    static final int PREFETCH_SEGMENTS = 1;

    /** 宿主请求的区间还没拉到时最多等多久（毫秒）——超时即退回「原转发路径」，绝不阻塞播放。 */
    static final long GAP_WAIT_MS = 2000L;

    /** 缺口距离已落盘水位超过这个字节数就不等了（等也等不来，直接转发）。 */
    static final long WAIT_AHEAD_BYTES = 12L * 1024L * 1024L;

    /** 不缓存模式（EPHEMERAL）下的磁盘余量下限——比缓存模式更保守，避免把系统盘写满。 */
    static final long EPHEMERAL_MIN_FREE_BYTES = 512L * 1024L * 1024L;

    /** 缓存模式下的磁盘余量下限（与 {@code StreamingAudioCache.MIN_FREE_BYTES} 对齐）。 */
    static final long PERSISTENT_MIN_FREE_BYTES = 256L * 1024L * 1024L;

    /** 单块失败后的重试次数与退避。 */
    static final int RETRY_MAX = 3;
    private static final long[] RETRY_BACKOFF_MS = {500L, 1000L, 2000L};

    /**
     * 每块的字节数。0.11.0 首轮用 512 KB（对齐宿主自己的请求尺寸），真机实测顺序预取只有 1–10 MB/s
     * —— 病因是当时每块都 {@code disconnect()}（每块一次 TCP+TLS）。现在连接走 keep-alive 复用，
     * 顺手把块放大到 4 MB：请求数降到 1/8，单块取消的粒度对「切歌即刻停」仍然够用。
     */
    private static final long CHUNK = 4L * 1024L * 1024L;

    private static final int BUF = 64 * 1024;
    private static final long MAX_ENTRY_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final long STOP_JOIN_MS = 1500L;

    /** 工作项锁（只护队列交接，不护网络与写盘）。 */
    private static final Object MON = new Object();

    private static volatile Job pending;
    private static volatile Job recent;
    private static volatile Thread worker;
    private static volatile boolean shutdown;

    private StreamPrefetcher() {
    }

    // ------------------------------------------------------------------ 生命周期

    /** 流服务启动时调用：允许重新受理工作项（宿主可被反复启停）。 */
    static void start() {
        shutdown = false;
        pending = null;
        recent = null;
    }

    /** 流服务停止时调用：取消工作项、可中断地收掉预取线程（不回抛、不无界等待）。 */
    static void shutdown() {
        shutdown = true;
        Job j = recent;
        if (j != null) {
            j.cancelled = true;
        }
        pending = null;
        Thread t = worker;
        synchronized (MON) {
            MON.notifyAll();
        }
        if (t != null) {
            try {
                t.join(STOP_JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) {
                PluginLog.d(TAG, "预取线程未在 " + STOP_JOIN_MS + " ms 内退出（守护线程，不阻塞停止）");
            }
        }
    }

    /** 当前模式。 */
    public static Mode mode() {
        return PluginConfig.audioCacheEnabled() ? Mode.PERSISTENT : Mode.EPHEMERAL;
    }

    // ------------------------------------------------------------------ 受理 / 取消

    /**
     * 开工入口：{@link NativeStreamServer#proxy} 在确认「这首歌已成为当前播放曲目」后调用，
     * 只在宿主自己的流服务线程上跑，<b>立刻返回</b>（重活在 {@code netease-prefetch-1} 上）。
     */
    static void onPlaybackStart(long songId, String level) {
        if (songId <= 0L || shutdown) {
            return;
        }
        String lv = level == null || level.isBlank() ? "standard" : level;
        Job r = recent;
        if (r != null && r.songId == songId && r.level.equals(lv)
                && !r.cancelled && !r.failed && !r.stopped) {
            if (!r.done) {
                return;                     // 同一首同一档位还在跑：不重复投递
            }
            // 已判完成，但「完成」必须此刻仍然成立：缓存可能在播放途中被清掉
            // （配额回收 / 另一实例的启动清理 / 用户手删目录）。真机实测：那时宿主
            // 只能按自己的请求节奏回源，512 KB 一次、掉到 64 KB/s —— 必须重新开工。
            long cov = StreamingAudioCache.coveredOf(songId, lv);
            if (r.total <= 0L || cov >= r.total) {
                return;                     // 真的还在手里，什么都不用做
            }
            PluginLog.i(TAG, "播放中发现缓存已丢，重新开工 #" + songId
                    + "（水位 " + (cov / 1024L / 1024L) + "/" + (r.total / 1024L / 1024L) + " MB）");
        }
        synchronized (MON) {
            Job cur = recent;
            if (cur != null && cur != r && cur.songId == songId && cur.level.equals(lv)
                    && !cur.cancelled && !cur.failed && !cur.stopped && !cur.done) {
                return;                     // 别的线程刚投过同一首，别重复
            }
            Job j = new Job(songId, lv);
            if (cur != null) {
                cur.cancelled = true;       // 换歌/换档位：旧工作项就地作废
            }
            recent = j;
            pending = j;
            MON.notifyAll();
        }
        ensureWorker();
    }

    /** 切歌：只保留 {@code songId} 的工作项，其余立刻作废（{@code StreamingAudioCache.select} 调用）。 */
    static void cancelExcept(long songId) {
        Job r = recent;
        if (r == null || r.songId == songId) {
            return;
        }
        r.cancelled = true;
        pending = null;
    }

    /** 手动清理 / 凭据变化：所有工作项作废（下一次播放请求会重新开工）。 */
    static void cancelAll() {
        Job r = recent;
        if (r != null) {
            r.cancelled = true;
        }
        pending = null;
    }

    private static void ensureWorker() {
        Thread t = worker;
        if (t != null && t.isAlive()) {
            return;
        }
        synchronized (MON) {
            if (worker != null && worker.isAlive()) {
                return;
            }
            Thread nt = new Thread(StreamPrefetcher::loop, "netease-prefetch-1");
            nt.setDaemon(true);
            nt.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            worker = nt;
            nt.start();
        }
    }

    // ------------------------------------------------------------------ 供流服务查询

    /** 整首字节数（探测所得）；未知返回 -1。 */
    static long totalOf(long songId, String level) {
        Job j = recent;
        if (j != null && j.songId == songId && j.level.equals(level == null ? "" : level) && j.total > 0L) {
            return j.total;
        }
        return StreamingAudioCache.totalOf(songId, level);
    }

    /** 已落盘且连续的字节水位（[0, n) 全部在手）；由 {@link StreamingAudioCache} 的区间表合并得出。 */
    static long coveredOf(long songId, String level) {
        return StreamingAudioCache.coveredOf(songId, level);
    }

    /** 宿主请求的区间还没落盘：记一笔缺口，让预取线程优先补它。 */
    static void noteGap(long songId, String level, long from, long to) {
        Job j = recent;
        if (j != null && j.songId == songId && j.level.equals(level == null ? "" : level)) {
            j.gapStart = Math.max(0L, from);
            j.gapEnd = to;
        }
    }

    /** 等预取把 {@code need} 之前的字节补齐（最多 {@code timeoutMs}）；宿主流服务线程上跑，有界。 */
    static boolean awaitCovered(long songId, String level, long need, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (true) {
            if (StreamingAudioCache.coveredOf(songId, level) >= need) {
                return true;
            }
            Job j = recent;
            if (shutdown || j == null || !j.level.equals(level == null ? "" : level)
                    || j.songId != songId || j.cancelled || j.failed || j.stopped) {
                return false;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** 一行摘要（配置页/日志用）。 */
    public static String stats() {
        Job j = recent;
        if (j == null) {
            return "预取：空闲";
        }
        String st = j.cancelled ? "已取消" : j.failed ? "失败" : j.stopped ? "已让路" : "进行中";
        long cov = StreamingAudioCache.coveredOf(j.songId, j.level);
        return "预取：" + st + " #" + j.songId + " " + j.level + " "
                + (cov / 1024L / 1024L) + "/" + (j.total > 0L ? j.total / 1024L / 1024L : -1L) + " MB";
    }

    // ------------------------------------------------------------------ 工作项与线程

    private static final class Job {

        final long songId;
        final String level;
        final long startedAt = System.currentTimeMillis();

        volatile boolean cancelled;
        volatile boolean failed;
        volatile boolean stopped;      // 环境不允许（空间不足 / 非当前曲目 / 超过单曲上限）
        volatile boolean done;         // 整首确实落盘（覆盖水位 >= total）；「缓存丢了」判定靠它
        volatile long total = -1L;
        volatile long gapStart = -1L;
        volatile long gapEnd = -1L;
        /** 最近一次失败的<b>分类</b>（断网 / 无版权 / 上游 5xx / 磁盘 …）：失败日志必须能看出是哪一类。 */
        volatile String failWhy = "";

        Job(long songId, String level) {
            this.songId = songId;
            this.level = level;
        }
    }

    private static void loop() {
        while (!shutdown) {
            Job j;
            synchronized (MON) {
                while (!shutdown && pending == null) {
                    try {
                        MON.wait(1000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (shutdown) {
                    return;
                }
                j = pending;
                pending = null;
            }
            try {
                runJob(j);
            } catch (Throwable t) {
                j.failed = true;
                PluginLog.w(TAG, "预取异常（已忽略）：" + t);
            }
        }
    }

    /**
     * 开工时的旁路工作（0.11.0）：本曲歌词 + 本曲专辑封面。
     *
     * <p>都在同一条 {@code netease-prefetch-1} 线程上、同一个工作项里 —— 这是本版
     * 「歌词与音频共享缓存生命周期」的落点：宿主<b>只在换曲那一刻</b>问一次歌词钩子，
     * 所以歌词必须和音频一起在那一刻就绪（docs/42 §4）。歌词抓取先于音频块
     * （一次 HTTP，冷启动几百毫秒；磁盘命中近乎零开销），封面通常已由
     * {@code netease-cover-fill} 补齐线程造好，这里只兜底「清单外的冷门曲目」。</p>
     *
     * <p>两件事都绝不影响音频预取：各自 catch，失败只记一行日志。</p>
     */
    private static void sideWork(Job j) {
        try {
            LyricService.prefetchOne(j.songId, NativeStreamServer.urlFor(j.songId), null, null);
        } catch (Throwable t) {
            PluginLog.d(TAG, "歌词旁路失败（已忽略）：" + t);
        }
        try {
            CoverArt.Cover c = CoverStore.ensurePlaying(j.songId);
            if (c != null) {
                CoverStore.deliverNow(c);
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "封面旁路失败（已忽略）：" + t);
        }
    }

    private static void runJob(Job j) {
        long total = probe(j);
        if (j.cancelled || shutdown) {
            return;
        }
        if (total <= 0L) {
            j.failed = true;
            // 失败必须能分类（W6 实验室判据）：只写「取不到总长」分不出断网 / 无版权 / 上游 5xx。
            PluginLog.w(TAG, "预取放弃 #" + j.songId + "：取不到总长（失败分类="
                    + (j.failWhy == null || j.failWhy.isBlank() ? "原因未记录" : j.failWhy) + "）");
            return;
        }
        if (total > MAX_ENTRY_BYTES) {
            j.stopped = true;
            PluginLog.i(TAG, "预取跳过 #" + j.songId + "：整首 " + (total / 1024L / 1024L)
                    + " MB 超过单曲上限，只转发不落盘");
            return;
        }
        Mode m = mode();
        long need = m == Mode.EPHEMERAL ? EPHEMERAL_MIN_FREE_BYTES : PERSISTENT_MIN_FREE_BYTES;
        long free = freeBytes();
        if (free < need) {
            j.stopped = true;
            PluginLog.w(TAG, "预取让路 #" + j.songId + "：剩余空间 " + (free / 1024L / 1024L)
                    + " MB < " + (need / 1024L / 1024L) + " MB，本次只转发");
            return;
        }
        j.total = total;
        PluginLog.i(TAG, "开工 #" + j.songId + " freq=" + j.level + " total=" + total
                + " mode=" + (m == Mode.EPHEMERAL ? "ephemeral" : "persistent")
                + " segments=" + PREFETCH_SEGMENTS);
        sideWork(j);
        long lastLog = System.currentTimeMillis();
        int attempts = 0;
        while (!j.cancelled && !shutdown) {
            long before = StreamingAudioCache.coveredOf(j.songId, j.level);
            if (before >= total) {
                break;
            }
            // 顺序补齐最低空洞；若前面的字节已被「转发路径」写到盘上，就直接补宿主要的那个缺口。
            long from = before;
            long gap = j.gapStart;
            if (gap > from && StreamingAudioCache.coversRange(j.songId, j.level, from, gap)) {
                from = gap;
            }
            long to = Math.min(total, from + CHUNK);
            boolean ok = fetchChunk(j, from, to);
            if (j.cancelled || shutdown) {
                break;
            }
            if (j.stopped) {
                PluginLog.i(TAG, "预取让路 #" + j.songId + "：缓存不再接收写入，只转发");
                return;
            }
            long after = StreamingAudioCache.coveredOf(j.songId, j.level);
            if (ok && after > before) {
                attempts = 0;
            } else {
                attempts++;
                if (attempts > RETRY_MAX) {
                    j.failed = true;
                    PluginLog.w(TAG, "预取失败 #" + j.songId + "：连续 " + RETRY_MAX + " 次没长进（已落盘 "
                            + (after / 1024L / 1024L) + "/" + (total / 1024L / 1024L) + " MB；失败分类="
                            + (j.failWhy == null || j.failWhy.isBlank() ? "原因未记录" : j.failWhy) + "）");
                    return;
                }
                PluginLog.d(TAG, "预取重试 #" + j.songId + " 第 " + attempts + " 次（已落盘 "
                        + (after / 1024L / 1024L) + " MB）");
                sleep(RETRY_BACKOFF_MS[Math.min(attempts - 1, RETRY_BACKOFF_MS.length - 1)]);
                NativeStreamServer.forgetUrl(j.songId, j.level);
            }
            long now = System.currentTimeMillis();
            if (now - lastLog >= 5000L && !j.cancelled && !shutdown) {
                lastLog = now;
                PluginLog.d(TAG, "进度 #" + j.songId + " " + (after / 1024L / 1024L) + "/"
                        + (total / 1024L / 1024L) + " MB（"
                        + (now - j.startedAt) / 1000L + "s）");
            }
        }
        if (j.cancelled) {
            return;
        }
        long cov = StreamingAudioCache.coveredOf(j.songId, j.level);
        j.done = cov >= total;
        long ms = Math.max(1L, System.currentTimeMillis() - j.startedAt);
        PluginLog.i(TAG, "完成 #" + j.songId + " " + (cov / 1024L / 1024L) + "/"
                + (total / 1024L / 1024L) + " MB 用时 " + ms + " ms（"
                + (cov * 1000L / ms / 1024L / 1024L) + " MB/s）");
        if (j.done) {
            // 0.11.2：整首已在本地 → 交给「行封面」把封面注进去并翻 Track.path（零额外下载）
            try {
                RowCover.onPrefetchDone(j.songId);
            } catch (Throwable t) {
                PluginLog.d(TAG, "行封面投递失败（忽略）：" + t);
            }
        }
    }

    // ------------------------------------------------------------------ 网络

    /** 探测整首字节数：{@code Range: bytes=0-0}，支持 206 的 {@code Content-Range} 与 200 的 {@code Content-Length}。 */
    private static long probe(Job j) {
        String url = NativeStreamServer.directUrl(j.songId, j.level);
        if (url == null) {
            j.failWhy = classify(NativeStreamServer.resolveFailure(j.songId), "取直链失败");
            PluginLog.w(TAG, "预取探测：取直链失败 #" + j.songId + "（失败分类=" + j.failWhy + "）");
            return -1L;
        }
        HttpURLConnection conn = null;
        try {
            conn = open(url, "bytes=0-0");
            int code = conn.getResponseCode();
            if (code == 206) {
                long t = totalOf(conn.getHeaderField("Content-Range"));
                if (t > 0L) {
                    return t;
                }
                j.failWhy = "上游响应异常（Content-Range 无法解析）";
                PluginLog.w(TAG, "预取探测：Content-Range 无法解析 #" + j.songId + "（失败分类=" + j.failWhy + "）");
                return -1L;
            }
            if (code == 200) {
                long len = conn.getContentLengthLong();
                if (len > 0L) {
                    return len;
                }
                j.failWhy = "上游响应异常（没有 Content-Length）";
                PluginLog.w(TAG, "预取探测：上游没给 Content-Length #" + j.songId + "（失败分类=" + j.failWhy + "）");
                return -1L;
            }
            if (code == 403 || code == 401 || code == 404) {
                NativeStreamServer.forgetUrl(j.songId, j.level);
            }
            j.failWhy = httpWhy(code);
            PluginLog.w(TAG, "预取探测：上游 HTTP " + code + " #" + j.songId + "（失败分类=" + j.failWhy + "）");
            return -1L;
        } catch (Throwable t) {
            j.failWhy = classify(brief(t), "探测异常");
            PluginLog.w(TAG, "预取探测失败 #" + j.songId + "（失败分类=" + j.failWhy + "）");
            return -1L;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 拉一块 [from, to) 并按原文件偏移写进缓存；返回 true 表示这一块都拿到了。 */
    private static boolean fetchChunk(Job j, long from, long to) {
        String url = NativeStreamServer.directUrl(j.songId, j.level);
        if (url == null) {
            j.failWhy = classify(NativeStreamServer.resolveFailure(j.songId), "取直链失败");
            return false;
        }
        HttpURLConnection conn = null;
        long writeFrom = from;
        long limit = to;
        boolean ok = false;
        try {
            conn = open(url, "bytes=" + from + "-" + (to - 1L));
            int code = conn.getResponseCode();
            if (code == 403 || code == 401 || code == 404) {
                NativeStreamServer.forgetUrl(j.songId, j.level);
                j.failWhy = httpWhy(code);
                PluginLog.w(TAG, "预取直链已失效（HTTP " + code + "），丢弃重取 #" + j.songId
                        + "（失败分类=" + j.failWhy + "）");
                return false;
            }
            if (code >= 400) {
                j.failWhy = httpWhy(code);
                PluginLog.w(TAG, "预取上游 HTTP " + code + "：#" + j.songId + "（失败分类=" + j.failWhy + "）");
                return false;
            }
            if (code != 206) {
                // 上游忽略了 Range 而回整段：必须从 0 顺序写，绝不能按请求偏移写盘。
                writeFrom = 0L;
                limit = j.total;
            }
            StreamingAudioCache.Sink sink = StreamingAudioCache.sink(j.songId, j.level, writeFrom, j.total);
            if (sink == null) {
                j.stopped = true;
                return false;
            }
            long got = 0L;
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[BUF];
                int n;
                while (!j.cancelled && !shutdown && (n = in.read(buf)) >= 0) {
                    long room = limit - (writeFrom + got);
                    if (room > 0L) {
                        sink.write(buf, 0, (int) Math.min((long) n, room));
                    }
                    got += n;
                    if (writeFrom + got >= limit && code != 206) {
                        break;              // 200 回整段：够本块就走，别把整首读进来
                    }
                    // 206：继续读到本段流尾 —— 连接才会回到 keep-alive 池，下一块复用同一条 TCP/TLS。
                }
            } finally {
                sink.close();
            }
            ok = got > 0L && writeFrom + got >= limit;
            return ok;
        } catch (Throwable t) {
            j.failWhy = classify(brief(t), "取块异常");
            PluginLog.w(TAG, "预取取块失败 #" + j.songId + " [" + from + "," + to + ")（失败分类="
                    + j.failWhy + "）");
            return false;
        } finally {
            // 正常读完就别 disconnect()：交给 HttpURLConnection 的 keep-alive 池复用。
            // 真机对照（0.11.0 首轮）：每块新建一次连接时顺序预取只有 1–10 MB/s，远低于 CDN 单连接实测 18 MB/s。
            if (conn != null && !ok) {
                conn.disconnect();
            }
        }
    }

    private static HttpURLConnection open(String url, String range) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setUseCaches(false);
        conn.setDoInput(true);
        conn.setRequestProperty("User-Agent", PluginVersions.userAgent());
        conn.setRequestProperty("Referer", "https://music.163.com");
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Encoding", "identity");
        if (range != null && !range.isBlank()) {
            conn.setRequestProperty("Range", range);
        }
        return conn;
    }

    /** {@code bytes 0-0/37440283} → 37440283。 */
    // ------------------------------------------------------------ 失败分类（W6 判据）

    /**
     * 把底层异常 / 原始诊断串归一成<b>可 grep 的失败分类词</b>。
     *
     * <p>W6 失败实验室判据 {@code [WARN|ERROR] [prefetch|stream].*(断网|网络|连接|超时|Connection)}
     * 要求「断网 / 无版权 / 上游 5xx / 磁盘」可分：只写「取不到总长」看不出是哪一类。分类<b>只影响日志与
     * 诊断串</b>，不改任何控制流（预取失败永远只是降级为纯转发，绝不打断播放）。</p>
     */
    private static String classify(String raw, String fallback) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return fallback;
        }
        String l = s.toLowerCase(java.util.Locale.ROOT);
        if (l.contains("unknownhost") || l.contains("unknown host") || l.contains("nodename")
                || l.contains("域名解析")) {
            return "断网（域名解析失败）：" + s;
        }
        if (l.contains("noroutetohost") || l.contains("no route") || l.contains("unreachable")
                || l.contains("无路由") || l.contains("网络不可达")) {
            return "断网（网络不可达）：" + s;
        }
        if (l.contains("connectionrefused") || l.contains("connectexception") || l.contains("连接被拒绝")) {
            return "断网（连接被拒绝）：" + s;
        }
        if (l.contains("sockettimeout") || l.contains("timed out") || l.contains("超时")) {
            return "网络超时：" + s;
        }
        if (l.contains("ssl") || l.contains("certificate") || l.contains("tls")) {
            return "网络 TLS 失败：" + s;
        }
        if (l.contains("可播地址") || l.contains("无版权") || l.contains("未登录") || l.contains("风控")) {
            return "无版权或未登录（直链接口没给可播地址）：" + s;
        }
        if (l.contains("eof") || l.contains("reset") || l.contains("socket") || l.contains("io")
                || l.contains("broken pipe")) {
            return "网络 IO 失败：" + s;
        }
        return "其它（" + s + "）";
    }

    /** 上游 HTTP 状态码 → 分类词（401/403 无版权、404 已下架、5xx 上游故障）。 */
    private static String httpWhy(int code) {
        if (code == 401 || code == 403) {
            return "无版权（HTTP " + code + "）";
        }
        if (code == 404) {
            return "曲目不存在或已下架（HTTP 404）";
        }
        if (code >= 500) {
            return "上游服务异常（HTTP " + code + "）";
        }
        return "上游 HTTP " + code;
    }

    /** 异常 → 一行诊断（类名 + 根因消息）；不打堆栈，日志按行采集。 */
    private static String brief(Throwable t) {
        if (t == null) {
            return "null";
        }
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        return root.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : "：" + msg);
    }

    private static long totalOf(String contentRange) {
        if (contentRange == null) {
            return -1L;
        }
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0 || slash == contentRange.length() - 1) {
            return -1L;
        }
        try {
            long v = Long.parseLong(contentRange.substring(slash + 1).trim());
            return v > 0L ? v : -1L;
        } catch (Throwable t) {
            return -1L;
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static long freeBytes() {
        try {
            return StreamingAudioCache.dir().toFile().getUsableSpace();
        } catch (Throwable t) {
            return Long.MAX_VALUE;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 供离线探针调用的一次性预取（不等待）。 */
    public static void prefetchNow(long songId, String level) {
        onPlaybackStart(songId, level);
    }

    /** 供离线探针调用：等某首歌整首落盘（最多 {@code timeoutMs}）。 */
    public static boolean awaitComplete(long songId, String level, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            long total = totalOf(songId, level);
            if (total > 0L && StreamingAudioCache.coveredOf(songId, level) >= total) {
                return true;
            }
            Job j = recent;
            if (j != null && j.songId == songId && (j.failed || j.stopped)) {
                return false;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 校验缓存目录可写（探针/自检用）。 */
    public static boolean storeReady() {
        try {
            Path d = StreamingAudioCache.dir();
            Files.createDirectories(d);
            return Files.isWritable(d);
        } catch (Throwable t) {
            return false;
        }
    }
}
