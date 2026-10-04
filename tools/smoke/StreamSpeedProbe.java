package netease.smoke;

import com.example.netease.core.PluginLog;
import com.example.netease.net.NeteaseApi;
import com.example.netease.svc.CookieVault;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 播放链路吞吐探针（0.10.4 调查用）：判定「卡顿」到底卡在哪一环。
 *
 * <p>用法：</p>
 * <pre>
 *   java -cp tools/smoke/out-webdav netease.smoke.StreamSpeedProbe \
 *        --vault=&lt;插件数据目录&gt; --song=&lt;id&gt; --level=lossless [--mb=8] [--par=4]
 * </pre>
 *
 * <p>做的判定：</p>
 * <ol>
 *   <li>取直链（含逐级降档）+ 直连 CDN 的 TTFB 与持续吞吐；</li>
 *   <li>同一首歌开 {@code --par} 条并发 Range 连接，看总吞吐是否线性增长
 *       —— 增长 ⇒ 单连接被限速（应改为「多连接并行 + 本地拼装」）；不增长 ⇒ CDN 侧总带宽受限。</li>
 *   <li>无 Range / 带 Range 两种请求方式的吞吐差异 —— 判断 CDN 是否按 Range 惩罚。</li>
 * </ol>
 *
 * <p>只打印摘要：不打印直链、不打印凭据。</p>
 */
public final class StreamSpeedProbe {

    public static void main(String[] args) throws Exception {
        Path vault = null;
        long songId = 1868101771L;
        String level = "lossless";
        int mb = 8;
        int par = 4;
        boolean noRange = false;
        for (String a : args) {
            if (a.startsWith("--vault=")) vault = Paths.get(a.substring(8));
            else if (a.startsWith("--song=")) songId = Long.parseLong(a.substring(7));
            else if (a.startsWith("--level=")) level = a.substring(8);
            else if (a.startsWith("--mb=")) mb = Integer.parseInt(a.substring(5));
            else if (a.startsWith("--par=")) par = Integer.parseInt(a.substring("--par=".length()));
            else if (a.equals("--norange")) noRange = true;
        }
        if (vault == null) {
            System.out.println("[SSP] 缺少 --vault=<插件数据目录>");
            System.exit(2);
        }
        CookieVault.init(vault);
        String cookies = CookieVault.load();
        if (cookies == null || cookies.isEmpty()) {
            System.out.println("[SSP] 凭据为空");
            System.exit(2);
        }
        NeteaseApi.importCookies(cookies);

        System.out.println("[SSP] song=" + songId + " level=" + level + " mb=" + mb + " par=" + par
                + " noRange=" + noRange + " 登录=" + NeteaseApi.isLoggedIn());

        long t0 = System.nanoTime();
        String url = NeteaseApi.songUrl(songId, level);
        long tUrl = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("[SSP] 取直链 " + tUrl + " ms  " + NeteaseApi.lastUrlMeta());
        if (url == null || url.isBlank()) {
            System.out.println("[SSP] 没有直链");
            System.exit(1);
        }
        String host = URI.create(url).getHost();
        System.out.println("[SSP] CDN host=" + host);

        // ① 头探测：Content-Length / 是否支持 Range
        Head head = probe(url);
        System.out.println("[SSP] 头探测：HTTP " + head.code + " len=" + head.len
                + " acceptRanges=" + head.acceptRanges + " TTFB=" + head.ttfb + " ms");

        // ② 单连接串行 Range（复刻宿主的播放请求节奏）
        if (!noRange) {
            long got = readSerial(url, 0L, mb, 3);
            System.out.println("[SSP] 串行 Range(524288 B/次)：" + fmt(got) + " 用时 " + (got >> 32)
                    + " ms ≈ " + rate((int) (got & 0xFFFFFFFFL), (int) (got >> 32)) + " MB/s");
        }
        // ③ 单连接顺序流（无 Range，模拟「一口气下整首」）
        long streamGot = streamAll(url, mb);
        System.out.println("[SSP] 单连接无 Range 顺序流：" + fmt(streamGot) + " 用时 " + (streamGot >> 32)
                + " ms ≈ " + rate((int) (streamGot & 0xFFFFFFFFL), (int) (streamGot >> 32)) + " MB/s");

        // ④ 多连接并行（各取不同 Range）
        if (par > 1) {
            long parGot = parallel(url, par, mb);
            System.out.println("[SSP] " + par + " 连接并行 Range：" + fmt(parGot) + " 用时 " + (parGot >> 32)
                    + " ms ≈ " + rate((int) (parGot & 0xFFFFFFFFL), (int) (parGot >> 32)) + " MB/s"
                    + "（单连接均速 ×" + par + " 对照）");
        }
        System.out.println("[SSP] 完成");
        System.exit(0);
    }

    // ------------------------------------------------------------------ 各项测量

    private record Head(int code, long len, String acceptRanges, long ttfb) {
    }

    private static Head probe(String url) {
        HttpURLConnection c = null;
        try {
            c = open(url, "bytes=0-0");
            long t = System.nanoTime();
            int code = c.getResponseCode();
            long ttfb = (System.nanoTime() - t) / 1_000_000L;
            long len = c.getContentLengthLong();
            String ar = c.getHeaderField("Accept-Ranges");
            String cr = c.getHeaderField("Content-Range");
            if (cr != null) {
                int slash = cr.lastIndexOf('/');
                if (slash > 0) {
                    try {
                        len = Long.parseLong(cr.substring(slash + 1).trim());
                    } catch (Throwable ignored) { }
                }
            }
            return new Head(code, len, String.valueOf(ar), ttfb);
        } catch (Throwable t) {
            return new Head(-1, -1L, "?", -1L);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 串行 Range：每次 512 KB（宿主的粒度），返回 [高32位=ms][低32位=字节]。 */
    private static long readSerial(String url, long start, int mb, int chunkKb) {
        long want = (long) mb * 1024L * 1024L;
        long got = 0L;
        long t = System.nanoTime();
        long pos = start;
        while (got < want) {
            long len = Math.min(512L * 1024L, want - got);
            HttpURLConnection c = null;
            try {
                c = open(url, "bytes=" + pos + "-" + (pos + len - 1));
                if (c.getResponseCode() / 100 != 2) break;
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[128 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) got += n;
                }
            } catch (Throwable e) {
                System.out.println("[SSP]   serial 中断：" + e);
                break;
            } finally {
                if (c != null) c.disconnect();
            }
            pos += len;
        }
        long ms = (System.nanoTime() - t) / 1_000_000L;
        return ((ms & 0xFFFFFFFFL) << 32) | (got & 0xFFFFFFFFL);
    }

    /** 单连接无 Range 顺序流：读到 mb 上限即停；返回同 readSerial 的编码。 */
    private static long streamAll(String url, int mb) {
        long want = (long) mb * 1024L * 1024L;
        long got = 0L;
        long t = System.nanoTime();
        HttpURLConnection c = null;
        try {
            c = open(url, null);
            int code = c.getResponseCode();
            if (code / 100 != 2) return 0L;
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while (got < want && (n = in.read(buf)) > 0) got += n;
            }
        } catch (Throwable e) {
            System.out.println("[SSP]   stream 中断：" + e);
        } finally {
            if (c != null) c.disconnect();
        }
        long ms = (System.nanoTime() - t) / 1_000_000L;
        return ((ms & 0xFFFFFFFFL) << 32) | (got & 0xFFFFFFFFL);
    }

    /** par 条连接各拉 mb 字节（不同起始偏移，避开缓存与限速窗口重叠）。 */
    private static long parallel(String url, int par, int mb) throws Exception {
        CountDownLatch done = new CountDownLatch(par);
        AtomicLong total = new AtomicLong();
        List<Thread> ts = new ArrayList<>();
        long t = System.nanoTime();
        for (int i = 0; i < par; i++) {
            final long off = (long) i * 64L * 1024L * 1024L;             // 每条错开 64 MB
            Thread th = new Thread(() -> {
                long got = 0L;
                HttpURLConnection c = null;
                try {
                    c = open(url, "bytes=" + off + "-" + (off + (long) mb * 1024L * 1024L - 1L));
                    if (c.getResponseCode() / 100 != 2) return;
                    try (InputStream in = c.getInputStream()) {
                        byte[] buf = new byte[256 * 1024];
                        int n;
                        long want = (long) mb * 1024L * 1024L;
                        while (got < want && (n = in.read(buf)) > 0) got += n;
                    }
                } catch (Throwable ignored) {
                } finally {
                    total.addAndGet(got);
                    if (c != null) c.disconnect();
                }
            }, "ssp-" + i);
            th.setDaemon(true);
            ts.add(th);
            th.start();
        }
        for (Thread th : ts) {
            th.join(180_000L);
        }
        done.countDown();
        long ms = (System.nanoTime() - t) / 1_000_000L;
        long got = total.get();
        return ((ms & 0xFFFFFFFFL) << 32) | (got & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------------------ 工具

    private static HttpURLConnection open(String url, String range) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestMethod("GET");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(60_000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        c.setRequestProperty("Referer", "https://music.163.com");
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Encoding", "identity");
        if (range != null) {
            c.setRequestProperty("Range", range);
        }
        return c;
    }

    private static String fmt(long packed) {
        return (packed & 0xFFFFFFFFL) + " B";
    }

    private static String rate(int bytes, int ms) {
        if (ms <= 0) ms = 1;
        double mbps = bytes / 1024.0 / 1024.0 / (ms / 1000.0);
        return String.format("%.2f", mbps);
    }

}
