package com.example.netease.svc;

import com.example.netease.core.PluginLog;
import com.example.netease.core.PluginVersions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 0.11.19「邻曲保温」：把「下一首 / 上一首」的<b>头部若干 MB</b> 提前拉进内存，
 * 让用户点「下一首 / 上一首」时首字节不碰网络。
 *
 * <p><b>为什么需要它</b>：0.11.18 之前，「下一首」在用户路径上<b>从来没有被预加载过</b> ——
 * {@link NextTrack} 算得出下一首是谁，但全树没有任何调用点；{@link StreamPrefetcher} 只在
 * <b>当前曲已经开流之后</b>才开工（{@code NativeStreamServer.proxy} 里调
 * {@code onPlaybackStart}），做的是「边听边下整首」。于是每点一次下一首，都要现做两件联网的事：
 * ① 解析直链（一次网易接口往返）；② 连 CDN 取首块。真机实测：换曲到出声 1.75–2.0 s。</p>
 *
 * <p><b>为什么走内存、不落盘</b>：本机 0 GB 缓存（ephemeral）时，
 * {@code StreamingAudioCache.sink()} 对「非当前曲」有明确闸门（0.11.0 的生产决策：
 * 0 GB 时宿主也可能并发预读下一首，那不是「正在听」的曲目，不能留下临时片段）。
 * 邻曲保温不该推翻这条策略，所以头部字节只驻留内存（最多 {@link #SLOTS} 首 ×
 * {@link #HEAD_BYTES}），<b>一个字节都不落盘</b>。</p>
 *
 * <p><b>谁来直出</b>：{@link NativeStreamServer#proxy} 在「本地直出」失败之后、解析直链之前问一次
 * {@link #serve}：宿主这一段的字节是否全在保温头部里？是则直接写出（微秒级）。真机实证宿主的请求
 * 形状是 <b>512 KB 定长窗</b>（换曲后第一枪 {@code Range: bytes=0-524287}，开流前还有一发
 * {@code bytes=0-0} 探测），所以 6 MB 头部 = 12 次请求的余量，足够撑到「当前曲的全量预取」接手。</p>
 *
 * <p><b>线程纪律</b>：{@link #kick} 只在宿主流服务线程上登记（立刻返回，不联网、不读库）；
 * 「下一首/上一首是谁」的解析（队列反射 / 歌单兜底读库）与联网拉取都在自建守护线程
 * {@code netease-warm-1} 上做，串行、一次一首。</p>
 */
final class NeighborWarm {

    private static final String TAG = "warm";

    /**
     * 每首保留的头部字节数。<b>必须是 512 KB 的整数倍</b>（宿主一次要 512 KB）：
     * 6 MB = 12 次请求的余量，jymaster 档也够 10 s 以上的音频。
     */
    static final int HEAD_BYTES = 6 * 1024 * 1024;

    /**
     * 同时最多保温几首。<b>6 = 两代</b>：本代「下一首 / 上一首 / 本曲」+ 上一代（用户连跳两首后
     * 再点「上一首」回头时，目标头部仍在）。真机 0.11.19 的 3 格会在换曲瞬间把上一代挤掉 ⇒
     * 同一首 6 MB 被反复重拉（实测 8 s 内两次）。
     */
    private static final int SLOTS = 6;

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 30000;

    /** 同一首（同档位）保温失败后的冷却：无版权 / 断网时不要反复试。 */
    private static final long FAIL_BACKOFF_MS = 10L * 60L * 1000L;

    private static final Object MON = new Object();

    /** 保温头部（LRU，按<b>插入顺序</b>淘汰：{@link #serve} 只读不搬动顺序，免得把「下一首」挤掉）。 */
    private static final LinkedHashMap<String, Head> HEADS = new LinkedHashMap<>(8, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Head> eldest) {
            return size() > SLOTS;
        }
    };

    /** 试过且失败的目标：key → 时间戳（成功会从表里移除）。 */
    private static final Map<String, Long> FAILED = new LinkedHashMap<>();

    /** 直出计数（同一个键前两次 + 每 10 次打一行日志，避免刷屏）。 */
    private static final Map<String, Integer> SERVED = new LinkedHashMap<>();

    /** 待保温的当前曲（0 = 无）；{@link #kick} 写、保温线程取走。 */
    private static volatile long wantSong = 0L;
    private static volatile String wantLevel = "";

    /**
     * 已经排过一轮的当前曲。<b>只由 {@link #kick} 写，保温线程不清空</b>——真机 0.11.19 的教训：
     * 「待办槽」（{@code wantSong}）会被保温线程取走清空，若拿它当去重判据，宿主每来一枪 512 KB
     * 就会重排一轮（真机实测同一首刷出 ~120 行「开工」，并因反复拉 6 MB 头部把邻里互相挤出缓存）。
     * 故去重判据必须是「这首排过没有」，且<b>不看档位</b>：歌词钩子与开流路径给的档位字符串同源，
     * 按档位去重等于把同一首排两轮（详见 {@code docs\00} §11 坑 27）。
     */
    private static volatile long lastKickSong = 0L;
    private static volatile Thread worker;

    private NeighborWarm() {
    }

    // ------------------------------------------------------------------ 受理（宿主线程，立刻返回）

    /**
     * 换曲时调用：让保温线程去算并拉「下一首 / 上一首 / 本曲」的头部。
     *
     * @param songId 刚成为当前曲的裸 songId（&gt; 0）
     * @param level  该曲的实得档位（{@code NativeStreamServer.proxy} 归一化后的值）
     */
    static void kick(long songId, String level) {
        if (songId <= 0L) {
            return;
        }
        String lv = level == null || level.isBlank() ? "standard" : level;
        synchronized (MON) {
            if (songId == lastKickSong) {
                return;                     // 同一首只排一轮（判据是「排过没有」，不是「待办槽空没空」）
            }
            lastKickSong = songId;
            wantSong = songId;
            wantLevel = lv;
            MON.notifyAll();
        }
        ensureWorker();
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
            Thread nt = new Thread(NeighborWarm::loop, "netease-warm-1");
            nt.setDaemon(true);
            nt.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            worker = nt;
            nt.start();
        }
    }

    // ------------------------------------------------------------------ 直出（宿主流服务线程）

    /**
     * 宿主刚来要的这一段，全部字节都在保温头部里吗？是则直接写出并返回 {@code true}。
     *
     * <p>只认<b>定长窗口</b>（{@code bytes=a-b}）：开区间 {@code bytes=a-} 要的是「一直到文件末尾」，
     * 头部给不了，如实返回 {@code false} 交给原路径（绝不半途而废地少发字节）。</p>
     */
    static boolean serve(long songId, String level, String method, String range, OutputStream out)
            throws IOException {
        if (songId <= 0L || "HEAD".equals(method) || range == null || range.isBlank()) {
            return false;
        }
        String key = songId + ":" + (level == null ? "" : level);
        Head h;
        synchronized (MON) {
            h = HEADS.get(key);
        }
        if (h == null || h.total <= 0L) {
            return false;
        }
        long[] win = window(range, h.total, h.bytes.length);
        if (win == null) {
            return false;
        }
        long from = win[0];
        long to = win[1];
        int len = (int) (to - from + 1L);
        NativeStreamServer.writeHead(out, 206, "Partial Content", h.type, len,
                "bytes " + from + "-" + to + "/" + h.total, true);
        out.write(h.bytes, (int) from, len);
        out.flush();
        int n;
        synchronized (MON) {
            n = SERVED.merge(key, 1, Integer::sum);
        }
        if (n <= 2 || n % 10 == 0) {
            PluginLog.i(TAG, "邻曲保温直出 #" + n + "：" + songId + " Range=" + range + " bytes=" + len
                    + "（内存头部 " + h.bytes.length + " B；未碰网络）");
        }
        return true;
    }

    /** 把 {@code Range} 解析成「落在保温头部之内」的闭区间；不满足则 {@code null}。 */
    private static long[] window(String range, long total, long headLen) {
        String r = range.trim().toLowerCase(Locale.ROOT);
        if (!r.startsWith("bytes=")) {
            return null;
        }
        r = r.substring("bytes=".length()).trim();
        if (r.indexOf(',') >= 0) {
            return null;                    // 多段：原路径处理
        }
        int dash = r.indexOf('-');
        if (dash < 0) {
            return null;
        }
        long from;
        long to;
        try {
            String a = r.substring(0, dash).trim();
            String b = r.substring(dash + 1).trim();
            if (b.isEmpty()) {
                return null;                // 开区间：要到文件末尾，头部给不了
            }
            if (a.isEmpty()) {
                long n = Long.parseLong(b);
                if (n <= 0L) {
                    return null;
                }
                from = Math.max(0L, total - n);
                to = total - 1L;
            } else {
                from = Long.parseLong(a);
                to = Long.parseLong(b);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        if (from < 0L || to < from) {
            return null;
        }
        to = Math.min(to, total - 1L);
        if (to >= headLen) {
            return null;                    // 超出头部：不半途而废，走原路径
        }
        return new long[]{from, to};
    }

    // ------------------------------------------------------------------ 保温线程

    private static void loop() {
        while (true) {
            long cur;
            String lv;
            synchronized (MON) {
                while (wantSong <= 0L) {
                    try {
                        MON.wait(1000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                cur = wantSong;
                lv = wantLevel;
                wantSong = 0L;              // 取走：一轮只做一次
            }
            try {
                long t0 = System.currentTimeMillis();
                // 顺序即优先级：「下一首」最要紧（用户最常按）；「上一首」次之；
                // 本曲的头部留给「再点一次上一首」用（切走之后它的 .part 会被 0 GB 策略删掉）。
                long nxt = NextTrack.next(cur);
                long prv = NextTrack.prev(cur);
                PluginLog.i(TAG, "邻曲保温开工：当前曲 #" + cur + " → 下一首 " + brief(nxt)
                        + " / 上一首 " + brief(prv) + "（读目标用时 " + (System.currentTimeMillis() - t0)
                        + " ms；" + NextTrack.why() + "）");
                warmOne(nxt, lv, "下一首");
                warmOne(prv, lv, "上一首");
                warmOne(cur, lv, "本曲");
            } catch (Throwable t) {
                PluginLog.w(TAG, "邻曲保温异常（忽略）：" + t);
            }
        }
    }

    private static void warmOne(long songId, String level, String why) {
        if (songId <= 0L) {
            return;
        }
        String key = songId + ":" + level;
        synchronized (MON) {
            Head have = HEADS.get(key);
            if (have != null && have.bytes.length >= HEAD_BYTES) {
                return;                     // 已满额在手
            }
            Long failAt = FAILED.get(key);
            if (failAt != null && System.currentTimeMillis() - failAt < FAIL_BACKOFF_MS) {
                return;                     // 刚失败过：冷却中
            }
        }
        long t0 = System.currentTimeMillis();
        try {
            String url = NativeStreamServer.directUrl(songId, level);
            long tUrl = System.currentTimeMillis();
            if (url == null) {
                fail(key, why, songId, "直链解析失败");
                return;
            }
            Head h = pull(songId, url);
            long now = System.currentTimeMillis();
            synchronized (MON) {
                HEADS.put(key, h);
                FAILED.remove(key);
            }
            PluginLog.i(TAG, "邻曲保温：" + why + " #" + songId + " 头部 " + mb(h.bytes.length)
                    + "/" + mb(h.total) + " MB 用时 " + (now - t0) + " ms（直链 " + (tUrl - t0)
                    + " ms；内存驻留，不落盘）");
        } catch (Throwable t) {
            fail(key, why, songId, String.valueOf(t));
        }
    }

    private static void fail(String key, String why, long songId, String reason) {
        synchronized (MON) {
            FAILED.put(key, System.currentTimeMillis());
        }
        PluginLog.w(TAG, "邻曲保温失败：" + why + " #" + songId + "：" + reason);
    }

    /** 拉头部（一次 {@code Range: bytes=0-(HEAD_BYTES-1)} 请求；顺带把整首长度与 MIME 带回来）。 */
    private static Head pull(long songId, String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", PluginVersions.userAgent());
            conn.setRequestProperty("Referer", "https://music.163.com");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Encoding", "identity");   // 音频流不做 gzip
            conn.setRequestProperty("Range", "bytes=0-" + (HEAD_BYTES - 1));
            int code = conn.getResponseCode();
            if (code >= 400) {
                throw new IOException("上游 HTTP " + code);
            }
            long total = totalOf(conn.getHeaderField("Content-Range"), conn.getContentLengthLong(), code);
            String type = conn.getHeaderField("Content-Type");
            byte[] buf = new byte[HEAD_BYTES];
            int n = 0;
            try (InputStream in = conn.getInputStream()) {
                while (n < buf.length) {
                    int r = in.read(buf, n, buf.length - n);
                    if (r < 0) {
                        break;
                    }
                    n += r;
                }
            }
            if (n <= 0) {
                throw new IOException("没读到字节");
            }
            byte[] head = n == buf.length ? buf : Arrays.copyOf(buf, n);
            return new Head(songId, total, type == null || type.isBlank() ? "audio/mpeg" : type, head);
        } finally {
            try {
                conn.disconnect();
            } catch (Throwable ignored) {
                // 尽力断
            }
        }
    }

    /** 从 {@code Content-Range: bytes 0-6291455/149445576} 取整首长度；取不到返回 -1。 */
    private static long totalOf(String contentRange, long contentLength, int code) {
        if (contentRange != null && !contentRange.isBlank()) {
            int slash = contentRange.lastIndexOf('/');
            if (slash > 0) {
                String s = contentRange.substring(slash + 1).trim();
                if (!"*".equals(s)) {
                    try {
                        return Long.parseLong(s);
                    } catch (NumberFormatException ignored) {
                        // 落到下面
                    }
                }
            }
        }
        return code == 200 && contentLength > 0L ? contentLength : -1L;
    }

    // ------------------------------------------------------------------ 查询 / 清理

    /** 凭据变化 / 清理：丢掉全部保温头部（{@code NativeStreamServer.credentialsChanged} 调用）。 */
    static void clearAll() {
        synchronized (MON) {
            HEADS.clear();
            FAILED.clear();
            SERVED.clear();
            wantSong = 0L;
            lastKickSong = 0L;
        }
    }

    /** 一行摘要（配置页 / 日志用）。 */
    static String stats() {
        synchronized (MON) {
            if (HEADS.isEmpty()) {
                return "邻曲保温：空闲";
            }
            StringBuilder sb = new StringBuilder("邻曲保温：");
            long sum = 0L;
            for (Map.Entry<String, Head> e : HEADS.entrySet()) {
                Head h = e.getValue();
                sum += h.bytes.length;
                sb.append('#').append(h.songId).append(' ')
                        .append(mb(h.bytes.length)).append('/').append(mb(h.total)).append(" MB  ");
            }
            sb.append("（合计 ").append(mb(sum)).append(" MB，内存）");
            return sb.toString();
        }
    }

    private static String brief(long songId) {
        return songId > 0L ? "#" + songId : "未知";
    }

    private static long mb(long bytes) {
        return bytes / 1024L / 1024L;
    }

    /** 保温头部（不可变；{@code bytes} 长度可能小于 {@link #HEAD_BYTES}，即只拿到一部分）。 */
    private record Head(long songId, long total, String type, byte[] bytes) {
    }
}
