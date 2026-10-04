package netease.smoke;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * 本地流服务探针：按宿主的真实请求节奏（0-0 → 512 KB 块）打自己插件的
 * {@code http://127.0.0.1:17788/netease/<id>}，逐次量「耗时 / 吞吐」。
 *
 * <pre>
 *   java -cp ... netease.smoke.LocalStreamProbe --port=17788 --song=&lt;id&gt; [--times=8] [--level=lossless]
 * </pre>
 *
 * 判定：本地服务返回一次 512 KB 若耗时 ≈24 s，则瓶颈在插件侧（或上游取流）；
 * 若耗时 &lt;1 s，则瓶颈在宿主的请求节奏，插件只需把「全量预取」提前做掉。
 */
public final class LocalStreamProbe {

    public static void main(String[] args) throws Exception {
        int port = 17788;
        long songId = 3417346246L;
        int times = 8;
        String level = null;
        for (String a : args) {
            if (a.startsWith("--port=")) port = Integer.parseInt(a.substring("--port=".length()));
            else if (a.startsWith("--song=")) songId = Long.parseLong(a.substring("--song=".length()));
            else if (a.startsWith("--times=")) times = Integer.parseInt(a.substring("--times=".length()));
            else if (a.startsWith("--level=")) level = a.substring("--level=".length());
        }
        String base = "http://127.0.0.1:" + port + "/netease/" + songId + (level == null ? "" : "?level=" + level);
        System.out.println("[LSP] 目标 " + base);

        long t0 = System.nanoTime();
        long n = read(base, "bytes=0-0", 16);
        System.out.println("[LSP] 0-0 探测：" + n + " B / " + ms(t0) + " ms");

        long t1 = System.nanoTime();
        long total = 0L;
        long pos = 0L;
        for (int i = 0; i < times; i++) {
            long len = 512L * 1024L;
            long t = System.nanoTime();
            long got = read(base, "bytes=" + pos + "-" + (pos + len - 1L), 512 * 1024);
            long dt = ms(t);
            total += got;
            System.out.printf("[LSP]   #%02d offset=%d MB %d B %d ms %.2f MB/s%n",
                    i + 1, pos / 1024 / 1024, got, dt, mbps(got, dt));
            pos += len;
            if (got == 0L) {
                System.out.println("[LSP] 服务端返回空，停止");
                break;
            }
        }
        System.out.printf("[LSP] 合计首块起 %d B（含首块 %d B）总耗时 %d ms%n", total, n, ms(t1));
        System.exit(0);
    }

    private static long read(String url, String range, int cap) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(5_000);
            c.setReadTimeout(120_000);
            c.setRequestProperty("Range", range);
            c.setRequestProperty("User-Agent", "Java/25.0.3");     // 复刻宿主
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                System.out.println("[LSP]   HTTP " + code);
                return 0L;
            }
            long got = 0L;
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    got += r;
                    if (got >= cap) break;
                }
            }
            return got;
        } catch (Throwable e) {
            System.out.println("[LSP]   异常：" + e);
            return 0L;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    private static double mbps(long bytes, long ms) {
        return bytes / 1024.0 / 1024.0 / Math.max(ms, 1L) * 1000.0;
    }
}
