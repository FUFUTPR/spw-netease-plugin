package netease.smoke;

import com.example.netease.core.PluginLog;
import com.example.netease.net.NeteaseApi;
import com.example.netease.svc.CookieVault;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 音源直链吞吐测量：用真机凭据取一首歌的直链，量「首字节时间」与「持续吞吐」。
 * 目的：判断 WebDAV 桥开播等待（Windows 重定向器会整文件拉取）到底是被 CDN 拖慢，
 * 还是被宿主/重定向器拖慢。
 *
 * 用法：java netease.smoke.SpeedProbe --vault=<目录> --song=<id> --level=lossless [--mb=20]
 * 只打印摘要，绝不打印直链与凭据。
 */
public final class SpeedProbe {

    public static void main(String[] args) {
        Path vault = null;
        long songId = 3417346246L;
        String level = "lossless";
        int mb = 20;
        for (String a : args) {
            if (a.startsWith("--vault=")) {
                vault = Paths.get(a.substring(8));
            } else if (a.startsWith("--song=")) {
                songId = Long.parseLong(a.substring(7));
            } else if (a.startsWith("--level=")) {
                level = a.substring(8);
            } else if (a.startsWith("--mb=")) {
                mb = Integer.parseInt(a.substring(5));
            }
        }
        if (vault == null) {
            System.out.println("[SP] 缺少 --vault=<目录>");
            System.exit(2);
        }
        try {
            CookieVault.init(vault);
            String cookies = CookieVault.load();
            if (cookies == null || cookies.isEmpty()) {
                System.out.println("[SP] 凭据为空");
                System.exit(2);
            }
            NeteaseApi.importCookies(cookies);
            System.out.println("[SP] 已登录=" + NeteaseApi.isLoggedIn() + " song=" + songId + " level=" + level
                    + " 目标=" + mb + " MB");
            long t0 = System.nanoTime();
            String url = NeteaseApi.songUrl(songId, level);
            long tUrl = (System.nanoTime() - t0) / 1_000_000L;
            System.out.println("[SP] 取直链用时=" + tUrl + " ms  " + NeteaseApi.lastUrlMeta());
            if (url == null || url.isEmpty()) {
                System.out.println("[SP] 没有拿到直链");
                System.exit(1);
            }
            URI uri = URI.create(url);
            System.out.println("[SP] host=" + uri.getHost() + " 扩展名=" + ext(url));

            HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (SpeedProbe)");
            c.setConnectTimeout(15_000);
            c.setReadTimeout(60_000);
            long t1 = System.nanoTime();
            int code = c.getResponseCode();
            long ttfb = (System.nanoTime() - t1) / 1_000_000L;
            System.out.println("[SP] HTTP " + code + " 首字节=" + ttfb + " ms  声明长度=" + c.getContentLengthLong());

            long want = mb * 1024L * 1024L;
            long got = 0;
            byte[] buf = new byte[262_144];
            long t2 = System.nanoTime();
            try (InputStream in = c.getInputStream()) {
                int r;
                while (got < want && (r = in.read(buf)) > 0) {
                    got += r;
                }
            }
            long dt = System.nanoTime() - t2;
            double sec = dt / 1e9;
            double mbps = got / 1024.0 / 1024.0 / Math.max(sec, 1e-6);
            System.out.println("[SP] 下载 " + got + " B / " + (dt / 1_000_000L) + " ms ≈ "
                    + String.format("%.2f", mbps) + " MB/s");
            PluginLog.i("speed", "CDN 吞吐：" + got + " B / " + (dt / 1_000_000L) + " ms");
            System.exit(0);
        } catch (Exception e) {
            System.out.println("[SP] 失败：" + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        }
    }

    private static String ext(String url) {
        try {
            String path = URI.create(url).getPath();
            int i = path.lastIndexOf('.');
            return i < 0 ? "?" : path.substring(i + 1);
        } catch (RuntimeException e) {
            return "?";
        }
    }
}
