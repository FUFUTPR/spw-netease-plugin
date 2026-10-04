package netease.smoke;

import com.example.netease.net.NeteaseApi;
import com.example.netease.svc.CookieVault;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CDN 深挖探针：逐次 Range 的耗时分布 + 整首全量下载吞吐。
 *
 * <pre>
 *   java -cp ... netease.smoke.StreamDeepProbe --vault=&lt;插件数据&gt; --song=&lt;id&gt; --level=&lt;档&gt;
 *        [--times=12] [--rangeKb=512] [--full=1] [--concurrent=3]
 * </pre>
 *
 * 用途：判定「宿主每 512 KB 一次、间隔数十秒」到底是 CDN 慢，还是宿主取流器节奏。
 */
public final class StreamDeepProbe {

    public static void main(String[] args) throws Exception {
        Path vault = null;
        long songId = 1868101771L;
        String level = "lossless";
        int times = 12;
        int rangeKb = 512;
        boolean full = false;
        int concurrent = 0;
        for (String a : args) {
            if (a.startsWith("--vault=")) vault = Paths.get(a.substring("--vault=".length()));
            else if (a.startsWith("--song=")) songId = Long.parseLong(a.substring("--song=".length()));
            else if (a.startsWith("--level=")) level = a.substring("--level=".length());
            else if (a.startsWith("--times=")) times = Integer.parseInt(a.substring("--times=".length()));
            else if (a.startsWith("--rangeKb=")) rangeKb = Integer.parseInt(a.substring("--rangeKb=".length()));
            else if (a.startsWith("--concurrent=")) concurrent = Integer.parseInt(a.substring("--concurrent=".length()));
            else if (a.equals("--full")) full = true;
        }
        if (vault == null) {
            System.out.println("[SDP] 缺少 --vault=");
            System.exit(2);
        }
        CookieVault.init(vault);
        String cookies = CookieVault.load();
        if (cookies == null || cookies.isEmpty()) {
            System.out.println("[SDP] 凭据为空");
            System.exit(2);
        }
        NeteaseApi.importCookies(cookies);
        String url = NeteaseApi.songUrl(songId, level);
        if (url == null || url.isBlank()) {
            System.out.println("[SDP] 没有直链");
            System.exit(1);
        }
        System.out.println("[SDP] song=" + songId + " level=" + level + " host="
                + URI.create(url).getHost() + "  " + NeteaseApi.lastUrlMeta());

        long rangeStart = 4L * 1024L * 1024L;      // 从 4 MB 处开始，避开开头与文件尾
        System.out.println("[SDP] 逐次 Range（" + rangeKb + " KB × " + times + "）从偏移 " + (rangeStart / 1024 / 1024) + " MB：");
        long pos = rangeStart;
        long totalBytes = 0L;
        long totalMs = 0L;
        long worst = 0L;
        for (int i = 0; i < times; i++) {
            long len = (long) rangeKb * 1024L;
            long t = System.nanoTime();
            long got = range(url, pos, len);
            long ms = (System.nanoTime() - t) / 1_000_000L;
            totalBytes += got;
            totalMs += ms;
            worst = Math.max(worst, ms);
            System.out.printf("[SDP]   #%02d offset=%d MB  %d B  %d ms  %.2f MB/s%n",
                    i + 1, pos / 1024 / 1024, got, ms, mbps(got, ms));
            pos += len;
        }
        System.out.printf("[SDP] 合计 %d B / %d ms ≈ %.2f MB/s，最慢一次 %d ms%n",
                totalBytes, totalMs, mbps(totalBytes, totalMs), worst);

        if (full) {
            long t = System.nanoTime();
            Path tmp = Files.createTempFile("sdp-", ".bin");
            long got;
            try {
                got = download(url, tmp);
            } finally {
                Files.deleteIfExists(tmp);
            }
            long ms = (System.nanoTime() - t) / 1_000_000L;
            System.out.printf("[SDP] 全量下载（单连接，无 Range）：%d B / %d ms ≈ %.2f MB/s%n",
                    got, ms, mbps(got, ms));
        }
        if (concurrent > 1) {
            System.out.println("[SDP] " + concurrent + " 条连接各拉 4 MB（不同偏移）：");
            long t = System.nanoTime();
            long[] got = new long[concurrent];
            Thread[] ts = new Thread[concurrent];
            for (int i = 0; i < concurrent; i++) {
                final int k = i;
                ts[i] = new Thread(() -> got[k] = range(url, rangeStart + (long) k * 64L * 1024L * 1024L, 4L * 1024L * 1024L));
                ts[i].setDaemon(true);
                ts[i].start();
            }
            for (Thread th : ts) th.join(120_000L);
            long ms = (System.nanoTime() - t) / 1_000_000L;
            long sum = 0L;
            for (long g : got) sum += g;
            System.out.printf("[SDP] 并发合计 %d B / %d ms ≈ %.2f MB/s%n", sum, ms, mbps(sum, ms));
        }
        System.out.println("[SDP] 完成");
        System.exit(0);
    }

    private static long range(String url, long start, long len) {
        HttpURLConnection c = null;
        try {
            c = open(url, "bytes=" + start + "-" + (start + len - 1L));
            if (c.getResponseCode() / 100 != 2) return 0L;
            long got = 0L;
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) got += n;
            }
            return got;
        } catch (Throwable e) {
            System.out.println("[SDP]   range 失败：" + e);
            return 0L;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static long download(String url, Path out) throws Exception {
        HttpURLConnection c = open(url, null);
        try (InputStream in = c.getInputStream(); OutputStream os = Files.newOutputStream(out)) {
            byte[] buf = new byte[256 * 1024];
            long got = 0L;
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                got += n;
            }
            return got;
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String range) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestMethod("GET");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(60_000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        c.setRequestProperty("Referer", "https://music.163.com");
        c.setRequestProperty("Accept-Encoding", "identity");
        if (range != null) c.setRequestProperty("Range", range);
        return c;
    }

    private static double mbps(long bytes, long ms) {
        return bytes / 1024.0 / 1024.0 / Math.max(ms, 1L) * 1000.0;
    }
}
