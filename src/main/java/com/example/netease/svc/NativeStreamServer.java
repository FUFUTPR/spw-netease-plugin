package com.example.netease.svc;

import com.example.netease.cfg.PluginConfig;
import com.example.netease.core.Levels;
import com.example.netease.core.PluginLog;
import com.example.netease.core.PluginVersions;
import com.example.netease.net.NeteaseApi;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 0.7.0 原生集成的<b>本地音频流服务</b>：把网易云曲目以「稳定的本地 URL」暴露给宿主自己的取流器。
 *
 * <p>为什么需要它：宿主原生 UI 播放曲目走的是它自带的 {@code HttpRangeBassStream}
 * （HTTP Range + 后台预取 + 断线重连）。我们往宿主库里写曲目行时，{@code Track.path} 必须是一个
 * <b>长期有效</b>的 URL；网易云 CDN 直链带 {@code authSecret} 会过期，写进库后过几小时就失效。
 * 因此这里自建一个只监听 {@code 127.0.0.1} 的小服务：</p>
 *
 * <pre>
 *   GET http://127.0.0.1:17788/netease/&lt;songId&gt;
 *       → 解析该曲目的最新 CDN 直链（带缓存与 403 重取）
 *       → 原样转发 Range 请求，返回 200/206 + Accept-Ranges
 * </pre>
 *
 * <p><b>为什么是裸 ServerSocket</b>：宿主是 jlink 裁剪运行时，
 * {@code java.net.http} 与 {@code jdk.httpserver} <b>都不在 MODULES 清单里</b>
 * （实测 runtime/release: {@code java.base … java.sql jdk.unsupported}），
 * 用 {@code HttpClient}/{@code HttpServer} 会直接抛 {@code NoClassDefFoundError}。
 * 所以这里只用 {@code java.base}：{@link ServerSocket} 收连接、手动解析请求行/头、
 * 用 {@link HttpURLConnection} 向上游取流。</p>
 *
 * <p><b>失败姿态</b>：端口被占 → 换随机端口（并把实际端口记在 {@link #baseUrl()} 里，写库时按当前值写）；
 * 曲目取不到直链 → 返回 404 并落一行日志，绝不影响宿主其它功能。</p>
 */
public final class NativeStreamServer {

    private static final String TAG = "stream";

    /** 首选端口；被占则退化为随机端口。写库时一律用 {@link #baseUrl()} 的实际值。 */
    public static final int PREFERRED_PORT = 17788;

    /** 直链缓存时长：网易云直链通常几十分钟内有效，取 20 分钟并在 403 时立即重取。 */
    private static final long URL_TTL_MS = 20 * 60 * 1000L;

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int BUF = 64 * 1024;

    private static volatile NativeStreamServer instance;
    private static volatile ServerSocket server;
    private static volatile ExecutorService pool;
    private static volatile int port = -1;

    private final Map<String, Cached> cache = new LinkedHashMap<>();
    private final AtomicLong served = new AtomicLong();
    /** 0.11.19 首字节打点的节流表（songId → 上次打点毫秒）：同一首一秒内只报一次，防宿主重试刷屏。 */
    private final Map<Long, Long> firstByteAt = new LinkedHashMap<>();

    private record Cached(String url, long at) {
    }

    private NativeStreamServer() {
    }

    // ------------------------------------------------------------------ 生命周期

    /** 启动服务（幂等）。返回实际监听端口；失败返回 -1。 */
    public static synchronized int start() {
        if (server != null) {
            return port;
        }
        for (int candidate : new int[]{PREFERRED_PORT, 0}) {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), candidate), 32);
                NativeStreamServer self = new NativeStreamServer();
                StreamingAudioCache.initialize();
                StreamPrefetcher.start();
                final ServerSocket bound = ss;
                ExecutorService p = Executors.newFixedThreadPool(4, r -> {
                    Thread t = new Thread(r, "netease-stream");
                    t.setDaemon(true);
                    return t;
                });
                instance = self;
                server = ss;
                pool = p;
                port = ss.getLocalPort();
                Thread acceptor = new Thread(() -> self.acceptLoop(bound), "netease-stream-accept");
                acceptor.setDaemon(true);
                acceptor.start();
                PluginLog.i(TAG, "本地流服务已启动：" + baseUrl() + "（首选端口 " + PREFERRED_PORT
                        + (port == PREFERRED_PORT ? " 命中" : " 被占，已退化为随机端口") + "）");
                return port;
            } catch (Throwable t) {
                PluginLog.w(TAG, "本地流服务端口 " + candidate + " 启动失败：" + t);
                closeQuietly(ss);
            }
        }
        PluginLog.w(TAG, "本地流服务启动失败：宿主仍可播放，但写库的曲目 path 将不可用");
        return -1;
    }

    public static synchronized void stop() {
        try {
            // 先收预取线程（可中断、有界 join），再关服务端 —— 避免插件停用后留下 netease-* 线程。
            StreamPrefetcher.shutdown();
            NeighborWarm.clearAll();
            ServerSocket ss = server;
            server = null;
            port = -1;
            closeQuietly(ss);
            ExecutorService p = pool;
            pool = null;
            if (p != null) {
                p.shutdownNow();
            }
            PluginLog.i(TAG, "本地流服务已停止（累计响应 "
                    + (instance == null ? 0 : instance.served.get()) + " 次）");
            instance = null;
        } catch (Throwable t) {
            PluginLog.d(TAG, "停止本地流服务异常（忽略）：" + t);
        }
    }

    /** 服务根地址（未启动时为按首选端口拼的地址，便于日志直读）。 */
    public static String baseUrl() {
        int p = port > 0 ? port : PREFERRED_PORT;
        return "http://127.0.0.1:" + p;
    }

    /** 某首歌在本地服务上的 URL（写进宿主库的就是它）。 */
    public static String urlFor(long songId) {
        return baseUrl() + "/netease/" + songId;
    }

    /** 带本次播放音质的稳定 URL（搜索/歌单窗口的手动档位使用）。 */
    public static String urlFor(long songId, String level) {
        return urlFor(songId) + "?level=" + normalizeLevel(level, PluginConfig.audioLevel());
    }

    /**
     * 把调用方已经解析好的直链放进短期缓存，避免播放器开流时再请求一次。
     * 直链本身从不进日志。
     */
    public static void rememberResolved(long songId, String level, String url) {
        NativeStreamServer self = instance;
        if (self == null || songId <= 0L || url == null || url.isBlank()) {
            return;
        }
        String key = self.urlKey(songId, normalizeLevel(level, PluginConfig.audioLevel()));
        synchronized (self.cache) {
            self.cache.put(key, new Cached(url, System.currentTimeMillis()));
            self.trimUrlCache();
        }
    }

    /** 配置变更：旧直链立即失效，新容量在后台按 LRU 应用。 */
    public static void onConfigChanged() {
        clearResolvedUrls();
        StreamingAudioCache.reconfigureAsync();
    }

    /**
     * 0.11.0：解析直链的<b>只读入口</b>（全量预取线程用，与播放路径共享同一份短效缓存）。
     * 直链本身绝不进日志。
     */
    public static String directUrl(long songId, String level) {
        NativeStreamServer self = instance;
        if (self == null || songId <= 0L) {
            return null;
        }
        return self.resolve(songId, normalizeLevel(level, PluginConfig.audioLevel()));
    }

    /** 直链失效（HTTP 401/403/404）：丢掉缓存项，下一位调用者重新取。 */
    public static void forgetUrl(long songId, String level) {
        NativeStreamServer self = instance;
        if (self == null || songId <= 0L) {
            return;
        }
        synchronized (self.cache) {
            self.cache.remove(self.urlKey(songId, level));
        }
    }

    /** 登录凭据变化：清掉匿名/旧会员权限下取到的直链与音频（含 0.11.19 的邻曲保温头部）。 */
    public static void credentialsChanged() {
        clearResolvedUrls();
        StreamingAudioCache.purgeAll();
        NeighborWarm.clearAll();
    }

    /** 手动清理入口；正在写的文件会在该请求结束后删除。 */
    public static int purgePlaybackCache() {
        return StreamingAudioCache.purgeAll();
    }

    public static String cacheStats() {
        return StreamingAudioCache.stats() + "；" + NeighborWarm.stats();
    }

    private static void clearResolvedUrls() {
        NativeStreamServer self = instance;
        if (self != null) {
            synchronized (self.cache) {
                self.cache.clear();
            }
        }
    }

    public static boolean running() {
        return server != null;
    }

    // ------------------------------------------------------------------ 连接循环

    private void acceptLoop(ServerSocket ss) {
        while (true) {
            Socket sock;
            try {
                sock = ss.accept();
            } catch (Throwable t) {
                if (server == null) {
                    return;          // 正常停止
                }
                PluginLog.d(TAG, "accept 异常（继续）：" + t);
                continue;
            }
            ExecutorService p = pool;
            if (p == null || p.isShutdown()) {
                closeQuietly(sock);
                return;
            }
            try {
                p.submit(() -> handle(sock));
            } catch (Throwable t) {
                closeQuietly(sock);
            }
        }
    }

    private void handle(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(READ_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            OutputStream raw = s.getOutputStream();
            OutputStream out = new BufferedOutputStream(raw, BUF);

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 0; i < 64; i++) {
                String line = readLine(in);
                if (line == null || line.isEmpty()) {
                    break;
                }
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            line.substring(colon + 1).trim());
                }
            }

            String requestedLevel = queryLevel(target);
            String path = target;
            int q = path.indexOf('?');
            if (q >= 0) {
                path = path.substring(0, q);
            }
            if (path.startsWith("http://") || path.startsWith("https://")) {
                try {
                    path = URI.create(path).getPath();
                } catch (Throwable ignored) {
                    // 保持原样
                }
            }

            if (path.equals("/health") || path.equals("/health/")) {
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                writeHead(out, 200, "OK", "text/plain; charset=utf-8", body.length, null, true);
                if (!"HEAD".equals(method)) {
                    out.write(body);
                }
                out.flush();
                return;
            }
            if (!path.startsWith("/netease/")) {
                writeHead(out, 404, "Not Found", "text/plain; charset=utf-8", 0, null, true);
                out.flush();
                return;
            }

            long songId;
            try {
                songId = Long.parseLong(path.substring("/netease/".length()).trim());
            } catch (Throwable t) {
                writeHead(out, 400, "Bad Request", "text/plain; charset=utf-8", 0, null, true);
                out.flush();
                return;
            }
            proxy(songId, method, headers, out, requestedLevel);
        } catch (Throwable t) {
            PluginLog.d(TAG, "连接处理异常（忽略）：" + t);
        }
    }

    /** 逐字节读一行（只读到 LF），不越过请求头 —— 避免 BufferedReader 预读走 body。 */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(96);
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') {
                    sb.setLength(len - 1);
                }
                return sb.toString();
            }
            sb.append((char) b);
            if (sb.length() > 8192) {
                throw new IOException("请求行/头过长");
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ------------------------------------------------------------------ 转发

    private void proxy(long songId, String method, Map<String, String> headers, OutputStream out,
                       String requestedLevel) throws IOException {
        long t0 = System.currentTimeMillis();
        String range = headers.get("range");
        String ua = String.valueOf(headers.get("user-agent"));
        String level = normalizeLevel(requestedLevel, PluginConfig.audioLevel());
        StreamingAudioCache.select(songId, startsAtBeginning(range));
        // 0.11.0：宿主一开口播（这首歌真的成了当前曲目）就开工「全量预取」。只在宿主流服务线程上
        // 登记（立即返回、不联网），重活在自建守护线程 netease-prefetch-1 上 —— 不拖首包。
        if (StreamingAudioCache.current() == songId) {
            StreamPrefetcher.onPlaybackStart(songId, level);
            // 0.11.19（邻曲保温）：换曲时顺手把「下一首 / 上一首」的头部拉进内存 ——
            // 用户点它们时首字节就不必再等「解析直链 + 连 CDN」。同样是「登记即返回」。
            NeighborWarm.kick(songId, level);
            // 0.11.15（起播对齐）：宿主**真的来要这首歌的音频字节**了 —— 这只是「哪一首是当前曲」的
            // 定位信号（宿主那个「当前曲」反射字段读不到值），**不在这里投封面**：
            // 真机时序已证它比出声早 3–4 秒（0.11.11「宿主清完才投」= 慢；0.11.12/0.11.14「这一刻就投」
            // = 封面先出、音乐后到）。投递时刻只认「播放态 Ready / isPlaying / 首个位置拍」。
            PlaybarCover.onStreamServe(songId, "开流");
        }
        Path cachedFile = StreamingAudioCache.complete(songId, level);
        if (cachedFile != null) {
            noteFirstByte(songId, t0, range, "完整缓存直出");
            serveFile(cachedFile, method, range, out);
            return;
        }
        // 0.11.19：宿主要的这一段是否整段都在「邻曲保温」的内存头部里？是则直接写出（不碰网络）。
        // 放在 servePrefetched 之前：那条路径在「有缺口且缺口不大」时会等最多 2 s（GAP_WAIT_MS），
        // 而内存头部是**确定已经拿到**的字节，没有任何理由让它排队。
        if (NeighborWarm.serve(songId, level, method, range, out)) {
            noteFirstByte(songId, t0, range, "邻曲保温内存直出");
            return;
        }
        // 0.11.0：整首已在本地落盘（或已覆盖宿主要的这段）→ 直接从 .part 直出，不碰网络。
        if (servePrefetched(songId, level, method, range, out)) {
            noteFirstByte(songId, t0, range, "本地直出（预取 .part）");
            return;
        }
        long tResolve0 = System.currentTimeMillis();
        String url = resolve(songId, level);
        long resolveMs = System.currentTimeMillis() - tResolve0;
        if (url == null) {
            String why = resolveFailure(songId);
            noteLevelUnknown(songId, level, why == null ? "直链解析失败" : why);
            PluginLog.w(TAG, "取直链失败：#" + songId + "（Range=" + range + "）");
            writeHead(out, 404, "Not Found", "text/plain; charset=utf-8", 0, null, true);
            out.flush();
            return;
        }

        HttpURLConnection conn = open(url, range);
        int code = conn.getResponseCode();
        if (code == 403 || code == 401 || code == 404) {
            // 直链过期：强制重取一次
            PluginLog.d(TAG, "直链可能过期（HTTP " + code + "），重取 #" + songId);
            disconnect(conn);
            synchronized (cache) {
                cache.remove(urlKey(songId, level));
            }
            url = resolve(songId, level);
            if (url == null) {
                writeHead(out, 404, "Not Found", "text/plain; charset=utf-8", 0, null, true);
                out.flush();
                return;
            }
            conn = open(url, range);
            code = conn.getResponseCode();
        }

        if (code >= 400) {
            PluginLog.w(TAG, "上游返回 HTTP " + code + "：#" + songId);
            disconnect(conn);
            writeHead(out, 502, "Bad Gateway", "text/plain; charset=utf-8", 0, null, true);
            out.flush();
            return;
        }

        String type = conn.getHeaderField("Content-Type");
        String contentRange = conn.getHeaderField("Content-Range");
        long len = conn.getContentLengthLong();
        boolean close = true;

        writeHead(out, code == 206 ? 206 : 200, code == 206 ? "Partial Content" : "OK",
                type == null ? "audio/mpeg" : type, len, contentRange, close);
        noteFirstByte(songId, t0, range, "转发上游（解析直链 " + resolveMs + " ms）");

        long sent = 0L;
        // 上游可能忽略 Range 而回 200：这时响应从 0 开始，绝不能按请求偏移写盘。
        long rangeStart = code == 200 ? 0L : responseRangeStart(contentRange, range);
        long total = code == 200 ? len : totalLength(contentRange, len, rangeStart);
        StreamingAudioCache.Sink sink = "HEAD".equals(method) ? null
                : StreamingAudioCache.sink(songId, level, rangeStart, total);
        try {
            if (!"HEAD".equals(method)) {
                try (InputStream body = conn.getInputStream()) {
                    byte[] buf = new byte[BUF];
                    int n;
                    while ((n = body.read(buf)) >= 0) {
                        // 播放优先：先写 socket；缓存写失败会在 Sink 内部静默降级。
                        out.write(buf, 0, n);
                        if (sink != null) {
                            sink.write(buf, 0, n);
                        }
                        sent += n;
                        if (sent % (4L * 1024 * 1024) < BUF) { // 大块传输时分批 flush
                            out.flush();
                        }
                    }
                }
            }
            out.flush();
        } finally {
            if (sink != null) {
                sink.close();
            }
            disconnect(conn);
        }

        long n = served.incrementAndGet();
        if (n <= 12 || n % 10 == 0) {
            PluginLog.i(TAG, "转发 #" + n + "：" + songId + " HTTP " + code + " Range=" + range
                    + " bytes=" + sent + "/" + len + " UA=" + ua);
        }
    }

    private HttpURLConnection open(String url, String range) throws IOException {
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
        conn.setRequestProperty("Accept-Encoding", "identity");   // 音频流不做 gzip
        if (range != null && !range.isBlank()) {
            conn.setRequestProperty("Range", range);
        }
        return conn;
    }

    private static void disconnect(HttpURLConnection conn) {
        try {
            conn.disconnect();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 写响应头（HTTP/1.1 + Connection: close）。{@code NeighborWarm} 直出保温头部时复用。 */
    static void writeHead(OutputStream out, int code, String reason, String type,
                          long len, String contentRange, boolean close) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        if (len >= 0) {
            sb.append("Content-Length: ").append(len).append("\r\n");
        }
        sb.append("Accept-Ranges: bytes\r\n");
        if (contentRange != null && !contentRange.isBlank()) {
            sb.append("Content-Range: ").append(contentRange).append("\r\n");
        }
        sb.append("Cache-Control: no-store\r\n");
        if (close) {
            sb.append("Connection: close\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /**
     * 0.11.19：打一行「宿主来要这首歌的头一段 → 我们把响应写出去了」花了多久。
     *
     * <p>为什么要它：换曲到出声的 1.75–2.0 s 里，「插件这一侧（解析直链 + 上游往返）」与
     * 「宿主自己的换曲管线」各占多少，此前只能靠起播时序反推。只在「从头要」的请求上打点
     * （{@code bytes=0-0} 探测与 {@code bytes=0-524287} 第一窗），同一首一秒内只报一次。</p>
     */
    private void noteFirstByte(long songId, long t0, String range, String how) {
        if (!startsAtBeginning(range)) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (firstByteAt) {
            Long last = firstByteAt.get(songId);
            if (last != null && now - last < 1000L) {
                return;
            }
            firstByteAt.put(songId, now);
            while (firstByteAt.size() > 64) {
                firstByteAt.remove(firstByteAt.keySet().iterator().next());
            }
        }
        PluginLog.i(TAG, "首字节：#" + songId + " " + (now - t0) + " ms（" + how + "；Range=" + range + "）");
    }

    // ---------------------------------------------------------------- 档位协商（D7 / A15）

    /**
     * 一次「档位协商」的结论（诊断/界面标注用，非契约面）。
     *
     * @param actual    实得档位；{@code null} = <b>未知</b>（协商没完成，绝不拿请求档位充数）
     * @param downgrade 降档数（{@link Levels#stepsDown}）；未知为 {@code -1}
     */
    public record LevelNote(long songId, String requested, String actual, int downgrade, String source) {
        /** 实得档位是否已知。 */
        public boolean known() {
            return actual != null && !actual.isBlank();
        }
    }

    /** 每首歌的协商结论（诊断面；到量就整表重来，不做 LRU —— 它只是给界面/报告看的）。 */
    private static final Map<Long, LevelNote> LEVELS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 已打过「档位」日志的 {@code songId|请求|实得} 组合：宿主的每个 Range 请求都会走到这里，必须去重。 */
    private static final java.util.Set<String> LEVEL_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 直链解析失败原因（每首歌一条；解析成功即清除）—— 失败分类的原料。 */
    private static final Map<Long, String> RESOLVE_FAILS = new java.util.concurrent.ConcurrentHashMap<>();

    private static volatile LevelNote lastLevelNote;

    /** 最近一次协商结论（配置页 / 验证报告用）。 */
    public static LevelNote lastLevelNote() {
        return lastLevelNote;
    }

    /** 某首已协商出的**实得**档位；未知（或与传入请求档位不是同一次协商）返回 {@code null}。 */
    public static LevelNote negotiatedLevel(long songId, String requested) {
        LevelNote n = LEVELS.get(songId);
        if (n == null || !n.known()) {
            return null;
        }
        return requested == null || requested.isBlank() || n.requested().equals(requested) ? n : null;
    }

    /** 某首歌最近一次直链解析失败的原因（无则 {@code null}）。 */
    static String resolveFailure(long songId) {
        return RESOLVE_FAILS.get(songId);
    }

    /**
     * 登记协商结论并按<b>冻结词形</b>打一行日志（A15/D7 机器判据）：
     * {@code [stream] 档位 请求=<level> 实得=<level> songId=<id> 降级=<n>档}。
     *
     * <p>降档时另打一行 WARN（界面标注与报告的证据面）；一次协商只打一遍（宿主每个 Range 请求都会经过这里）。</p>
     */
    static void noteNegotiated(long songId, String requested, String actual, String source) {
        if (songId <= 0L || actual == null || actual.isBlank()) {
            return;
        }
        String req = requested == null || requested.isBlank() ? "?" : requested;
        String act = actual.trim().toLowerCase(Locale.ROOT);
        int dg = Levels.stepsDown(req, act);
        LevelNote n = new LevelNote(songId, req, act, dg, source == null ? "" : source);
        if (LEVELS.size() > 128) {
            LEVELS.clear();                     // 诊断面：不为了它做 LRU，超量就重来
        }
        LEVELS.put(songId, n);
        lastLevelNote = n;
        RESOLVE_FAILS.remove(songId);
        if (!LEVEL_LOGGED.add(songId + "|" + req + "|" + act)) {
            return;
        }
        if (LEVEL_LOGGED.size() > 512) {
            LEVEL_LOGGED.clear();
        }
        PluginLog.i(TAG, "档位 请求=" + req + " 实得=" + act + " songId=" + songId
                + " 降级=" + (dg < 0 ? "?" : dg + "档"));
        if (dg > 0) {
            PluginLog.w(TAG, "音质降档：songId=" + songId + " 请求=" + req + " 实得=" + act
                    + " 降" + dg + "档（服务端只给到这一档；界面需标注实得档位）");
            if (dg > 3) {
                PluginLog.w(TAG, "降档超过 3 档（D7 上限）：songId=" + songId + " 请求=" + req
                        + " 实得=" + act + " —— 账号权限或曲源限制，建议在配置页下调请求档位");
            }
        }
    }

    /**
     * 协商失败/未知：仍然按冻结词形留一行（{@code 实得=未知 降级=?}），把原因写在行尾。
     *
     * <p>不允许因为「没协商出来」就一行都不写 —— 那正是「失败不可见」（A15 失败路径判据）。</p>
     */
    static void noteLevelUnknown(long songId, String requested, String why) {
        String req = requested == null || requested.isBlank() ? "?" : requested;
        lastLevelNote = new LevelNote(songId, req, null, -1, why == null ? "" : why);
        if (!LEVEL_LOGGED.add(songId + "|" + req + "|未知")) {
            return;
        }
        PluginLog.i(TAG, "档位 请求=" + req + " 实得=未知 songId=" + songId + " 降级=?（"
                + (why == null || why.isBlank() ? "直链协商未完成" : why) + "）");
    }

    /**
     * 从 net 层诊断面（{@link NeteaseApi#lastUrlMeta()}）解析**实得**档位并登记。
     *
     * <p>只在刚做完一次真实解析（{@link #resolve}）之后调用。{@code lastUrlMeta} 是「最近一次解析」的
     * 全局快照，所以只有当它的 {@code 请求=} 与我这次请求逐字一致时才采信 —— 否则宁可记「未知」，
     * 也不能把别的线程/别的歌的档位安到这首歌头上（撒谎比未知更糟）。</p>
     */
    static void noteFromMeta(long songId, String requested) {
        String meta = NeteaseApi.lastUrlMeta();
        if (meta == null || meta.isBlank()) {
            return;
        }
        String req = metaField(meta, "请求=");
        String act = metaField(meta, "实际档位=");
        if (act == null || act.isBlank() || "?".equals(act)) {
            return;
        }
        if (req != null && requested != null && !req.equals(requested)) {
            return;                             // 快照来自别的请求：不采信
        }
        noteNegotiated(songId, requested, act, "meta:" + metaField(meta, "来源="));
    }

    /** 从 {@code 键=值} 形式的诊断串里取一个字段（值到下一个空格为止）。 */
    private static String metaField(String meta, String key) {
        int i = meta.indexOf(key);
        if (i < 0) {
            return null;
        }
        int s = i + key.length();
        int e = meta.indexOf(' ', s);
        return (e < 0 ? meta.substring(s) : meta.substring(s, e)).trim();
    }

    /** 取（带缓存的）CDN 直链。 */
    private String resolve(long songId, String level) {
        String key = urlKey(songId, level);
        synchronized (cache) {
            Cached c = cache.get(key);
            if (c != null && System.currentTimeMillis() - c.at() < URL_TTL_MS) {
                return c.url();
            }
        }
        try {
            String url = NeteaseApi.songUrl(songId, level);
            if (url == null || url.isBlank()) {
                RESOLVE_FAILS.put(songId, "直链接口未返回可播地址（无版权 / 未登录 / 风控，见 net 层日志）");
                return null;
            }
            synchronized (cache) {
                cache.put(key, new Cached(url, System.currentTimeMillis()));
                trimUrlCache();
            }
            // 协商结论（实得档位）在这里落地：刚刚这次 songUrl 就是真实协商。
            noteFromMeta(songId, level);
            return url;
        } catch (Throwable t) {
            String why = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : "：" + t.getMessage());
            RESOLVE_FAILS.put(songId, why);
            PluginLog.w(TAG, "解析直链失败 #" + songId + "：" + why);
            return null;
        }
    }

    private String urlKey(long songId, String level) {
        return songId + ":" + normalizeLevel(level, PluginConfig.audioLevel());
    }

    private void trimUrlCache() {
        while (cache.size() > 64) {
            cache.remove(cache.keySet().iterator().next());
        }
    }

    /**
     * 0.11.0：宿主要的这段音频是否已经落在本地了？是则直接从 {@code .part} 直出（GB/s 级，不碰网络）。
     *
     * <p>预取线程按 512 KB 顺序补 {@code <songId>-<level>.part}，只有「已经 close 并并入区间表」的
     * 字节才算数（{@link StreamingAudioCache#coveredOf}），所以绝不会把半截数据发出去。
     * 缺口小（≤ {@link StreamPrefetcher#WAIT_AHEAD_BYTES}）时最多等 {@link StreamPrefetcher#GAP_WAIT_MS}
     * 毫秒让预取追上来；等不到就走原来的转发路径 —— 绝不把宿主卡在这里。</p>
     *
     * @return {@code true} 表示响应已完整写出（调用方直接返回）
     */
    private boolean servePrefetched(long songId, String level, String method, String range, OutputStream out)
            throws IOException {
        if ("HEAD".equals(method)) {
            return false;
        }
        long total = StreamPrefetcher.totalOf(songId, level);
        if (total <= 0L) {
            return false;                  // 还没探到整首长度：交给原路径
        }
        ByteRange wanted = parseRange(range, total);
        if (wanted == null) {
            return false;                  // 多段/非法 Range：交给原路径
        }
        long need = wanted.end() + 1L;
        long covered = StreamPrefetcher.coveredOf(songId, level);
        if (covered < need) {
            StreamPrefetcher.noteGap(songId, level, covered, need);
            if (covered <= 0L || need - covered > StreamPrefetcher.WAIT_AHEAD_BYTES) {
                return false;              // 差太远：等也等不来，直接转发
            }
            if (!StreamPrefetcher.awaitCovered(songId, level, need, StreamPrefetcher.GAP_WAIT_MS)) {
                return false;
            }
            covered = StreamPrefetcher.coveredOf(songId, level);
            if (covered < need) {
                return false;
            }
        }
        Path part = StreamingAudioCache.partOf(songId, level);
        if (!Files.isRegularFile(part) || Files.size(part) < need) {
            // 刚好在这一刻写完整首（.part 已改名 .cache）：按完整文件直出。
            Path done = StreamingAudioCache.complete(songId, level);
            if (done == null) {
                return false;
            }
            serveFile(done, method, range, out);
            return true;
        }
        long start = wanted.start();
        long end = wanted.end();
        long len = wanted.length();
        boolean partial = range != null && !range.isBlank();
        writeHead(out, partial ? 206 : 200, partial ? "Partial Content" : "OK",
                mediaType(part), len, partial ? "bytes " + start + "-" + end + "/" + total : null, true);
        long left = len;
        try (RandomAccessFile in = new RandomAccessFile(part.toFile(), "r")) {
            in.seek(start);
            byte[] buf = new byte[BUF];
            while (left > 0L) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) {
                    break;
                }
                out.write(buf, 0, n);
                left -= n;
            }
        }
        out.flush();
        long n = served.incrementAndGet();
        if (n <= 12 || n % 10 == 0) {
            PluginLog.i(TAG, "本地直出 #" + n + "：" + songId + " Range=" + range + " bytes=" + (len - left)
                    + "/" + len + " 水位=" + covered + "/" + total);
        }
        return true;
    }

    private static void serveFile(Path file, String method, String range, OutputStream out) throws IOException {
        long total = Files.size(file);
        boolean partial = range != null && !range.isBlank();
        ByteRange wanted = parseRange(range, total);
        if (wanted == null) {
            writeHead(out, 416, "Range Not Satisfiable", "text/plain; charset=utf-8",
                    0L, "bytes */" + total, true);
            out.flush();
            return;
        }
        long start = wanted.start();
        long end = wanted.end();
        long len = wanted.length();
        writeHead(out, partial ? 206 : 200, partial ? "Partial Content" : "OK",
                mediaType(file), len, partial ? "bytes " + start + "-" + end + "/" + total : null, true);
        if (!"HEAD".equals(method)) {
            try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
                in.seek(start);
                byte[] buf = new byte[BUF];
                long left = len;
                while (left > 0L) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    left -= n;
                }
            }
        }
        out.flush();
    }

    private record ByteRange(long start, long end) {
        long length() {
            return end - start + 1L;
        }
    }

    /** 解析单段 HTTP Range；空头表示整段，非法/多段返回 null。 */
    private static ByteRange parseRange(String value, long total) {
        if (total <= 0L) {
            return null;
        }
        if (value == null || value.isBlank()) {
            return new ByteRange(0L, total - 1L);
        }
        try {
            String s = value.trim().toLowerCase(Locale.ROOT);
            if (!s.startsWith("bytes=") || s.indexOf(',') >= 0) {
                return null;
            }
            String spec = s.substring("bytes=".length()).trim();
            int dash = spec.indexOf('-');
            if (dash < 0) {
                return null;
            }
            String left = spec.substring(0, dash).trim();
            String right = spec.substring(dash + 1).trim();
            long start;
            long end;
            if (left.isEmpty()) {
                long suffix = Long.parseLong(right);
                if (suffix <= 0L) {
                    return null;
                }
                start = Math.max(0L, total - suffix);
                end = total - 1L;
            } else {
                start = Long.parseLong(left);
                if (start < 0L || start >= total) {
                    return null;
                }
                end = right.isEmpty() ? total - 1L : Math.min(Long.parseLong(right), total - 1L);
                if (end < start) {
                    return null;
                }
            }
            return new ByteRange(start, end);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 206 响应在原文件中的起点；无法确定时返回 -1（不缓存）。 */
    private static long responseRangeStart(String contentRange, String requestRange) {
        try {
            if (contentRange != null) {
                String s = contentRange.trim();
                int space = s.indexOf(' ');
                int dash = s.indexOf('-', space + 1);
                return Long.parseLong(s.substring(space + 1, dash).trim());
            }
            if (requestRange != null) {
                String s = requestRange.trim().toLowerCase(Locale.ROOT);
                if (s.startsWith("bytes=") && s.indexOf(',') < 0) {
                    String left = s.substring("bytes=".length(), s.indexOf('-')).trim();
                    return left.isEmpty() ? -1L : Long.parseLong(left);
                }
            }
        } catch (Throwable ignored) {
            // 下方返回 -1
        }
        return -1L;
    }

    private static long totalLength(String contentRange, long len, long start) {
        try {
            if (contentRange != null) {
                int slash = contentRange.lastIndexOf('/');
                if (slash >= 0) return Long.parseLong(contentRange.substring(slash + 1).trim());
            }
        } catch (Throwable ignored) { }
        return len < 0L || start < 0L ? -1L : start + len;
    }

    private static boolean startsAtBeginning(String range) {
        if (range == null || range.isBlank()) {
            return true;
        }
        try {
            String s = range.trim().toLowerCase(Locale.ROOT);
            return s.startsWith("bytes=0-");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String queryLevel(String target) {
        if (target == null) {
            return null;
        }
        int q = target.indexOf('?');
        if (q < 0 || q == target.length() - 1) {
            return null;
        }
        String query = target.substring(q + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) {
            query = query.substring(0, hash);
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "level".equalsIgnoreCase(pair.substring(0, eq).trim())) {
                return pair.substring(eq + 1).trim();
            }
        }
        return null;
    }

    private static String normalizeLevel(String value, String fallback) {
        String v = (value == null || value.isBlank()) ? fallback : value;
        String canonical = Levels.canonical(v);
        return canonical == null ? Levels.DEFAULT : canonical;
    }

    private static String mediaType(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] b = in.readNBytes(12);
            if (b.length >= 4 && b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'C') {
                return "audio/flac";
            }
            if (b.length >= 3 && b[0] == 'I' && b[1] == 'D' && b[2] == '3') {
                return "audio/mpeg";
            }
            if (b.length >= 2 && (b[0] & 0xff) == 0xff && (b[1] & 0xe0) == 0xe0) {
                return "audio/mpeg";
            }
            if (b.length >= 4 && b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') {
                return "audio/ogg";
            }
            if (b.length >= 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
                return "audio/mp4";
            }
        } catch (Throwable ignored) {
            // 宿主的解码器仍可自行嗅探
        }
        return "application/octet-stream";
    }

    private static void closeQuietly(ServerSocket ss) {
        try {
            if (ss != null) {
                ss.close();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            if (s != null) {
                s.close();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
