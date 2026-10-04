package com.example.netease.net;

import com.example.netease.core.PluginLog;
import com.example.netease.svc.CookieVault;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 真机联调探针（0.2.5）：用**本机插件已登录的凭据**跑真实接口，钉死三件事。
 *
 * <ol>
 *   <li>歌单能否取全（用户「2600 首只显示 1000 首」）；</li>
 *   <li>lossless / hires 到底给不给（用户「只能下几 MB」）；</li>
 *   <li>拿到的直链能否被**不带 cookie、不带 Referer** 的普通客户端拉流
 *       —— 这是「宿主 BASS 能否直接播网络流」的唯一证据。</li>
 * </ol>
 *
 * <p>安全边界：凭据从 {@code --vault=<拷贝目录>} 读（脚本会把真盘 account.json/account.key
 * 复制到 build\\live-probe\\vault 再传进来，**绝不触碰真盘**）；输出只含摘要与解析后的
 * URL 特征（host / 扩展名 / query 参数名），**从不打印 cookie、完整直链或验证码**。
 * 需要完整直链的试验（m3u）只写进 {@code --m3u=<本机文件>}，不进对话。</p>
 *
 * <p>用法：
 * {@code java -cp <out> com.example.netease.net.LiveProbe --vault=<dir> [--playlist=<id>] [--levels=lossless,hires] [--m3u=<path>] [--quiet]}</p>
 */
public final class LiveProbe {

    private static final long DEFAULT_PLAYLIST = 8689913835L;
    private static int failures;

    public static void main(String[] args) {
        String vaultDir = arg(args, "--vault=", null);
        long playlistId = Long.parseLong(arg(args, "--playlist=", String.valueOf(DEFAULT_PLAYLIST)));
        String[] levels = arg(args, "--levels=", "hires,lossless,exhigh").split(",");
        int songCount = Integer.parseInt(arg(args, "--count=", "3"));
        String m3uOut = arg(args, "--m3u=", null);
        int m3uLimit = Integer.parseInt(arg(args, "--m3u-limit=", "50"));
        String logDir = arg(args, "--log=", null);

        if (logDir != null) {
            PluginLog.init(Path.of(logDir));   // 让 net 层自己的诊断（分页通道/降档）也落盘
            PluginLog.setLevel("debug");
            println("[LP] 插件日志（net 层自述）→ " + PluginLog.file());
        }

        if (vaultDir == null) {
            System.out.println("[LP] state=BAD_ARGS 缺少 --vault=<含 account.json/account.key 的目录>");
            System.exit(1);
        }

        // ---- 1. 凭据（只读拷贝） ----
        CookieVault.init(Path.of(vaultDir));
        String cookie = CookieVault.load();
        if (cookie == null || cookie.isBlank()) {
            System.out.println("[LP] state=NO_CREDENTIALS（account.json 缺失或解不开；先在本机插件里登录一次）");
            System.exit(2);
        }
        println("[LP] 凭据已解密：明文长度 " + cookie.length() + " 字符，键名数 "
                + new LinkedHashSet<>(List.of(cookie.split(";"))).size() + "（内容不打印）");
        NeteaseApi.importCookies(cookie);
        println("[LP] isLoggedIn=" + NeteaseApi.isLoggedIn()
                + "（" + (NeteaseApi.isLoggedIn() ? "OK" : "FAIL") + "：需 MUSIC_U 生效）");
        check(NeteaseApi.isLoggedIn(), "凭据生效（MUSIC_U）");

        // ---- 2. 歌单取全 ----
        Dto.Playlist pl = null;
        try {
            long t0 = System.currentTimeMillis();
            pl = NeteaseApi.playlist(playlistId);
            long ms = System.currentTimeMillis() - t0;
            int got = pl.tracks() == null ? 0 : pl.tracks().size();
            println("[LP] 歌单 id=" + playlistId + "「" + pl.name() + "」声明 " + pl.trackCount()
                    + " 首，实取 " + got + " 首，来源=" + NeteaseApi.lastTrackPath() + "，耗时 " + ms + " ms");
            check(pl.trackCount() <= 0 || got >= pl.trackCount(),
                    "歌单取全（声明 " + pl.trackCount() + " / 实取 " + got + "）");
            List<Dto.Song> first = pl.tracks() == null ? List.of() : pl.tracks();
            for (int i = 0; i < Math.min(3, first.size()); i++) {
                Dto.Song s = first.get(i);
                println("[LP]   样本[" + i + "] id=" + s.id() + " 《" + s.name() + "》 " + s.artists()
                        + " fee=" + s.fee() + " playable=" + s.playable() + " 时长=" + s.durationMs() + "ms");
            }
        } catch (Throwable t) {
            println("[LP] 歌单取证异常：" + t.getClass().getSimpleName() + ": " + headless(t.getMessage()));
            failures++;
        }

        // ---- 3. 各档位直链 + CDN 可流式性 ----
        List<Dto.Song> songs = pl != null && pl.tracks() != null ? pl.tracks() : List.of();
        List<Dto.Song> probeSongs = songs.isEmpty()
                ? List.of(new Dto.Song(186016L, "（内置样本）", "", "", 0L, 0, true, ""))
                : songs.subList(0, Math.min(Math.max(1, songCount), songs.size()));
        List<String> m3uLines = new ArrayList<>();
        int m3uCount = 0;
        Set<String> probedUrls = new LinkedHashSet<>();

        for (Dto.Song s : probeSongs) {
            println("[LP] -- 歌曲 id=" + s.id() + " 《" + s.name() + "》 --");
            for (String lv : levels) {
                String level = lv.trim().toLowerCase(java.util.Locale.ROOT);
                if (level.isEmpty()) {
                    continue;
                }
                String url = null;
                String err = null;
                try {
                    url = NeteaseApi.songUrl(s.id(), level);
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + headless(t.getMessage());
                }
                String meta = NeteaseApi.lastUrlMeta();
                if (url == null || url.isBlank()) {
                    println("[LP]   请求=" + level + " → 无直链（" + (err == null ? "伺服端未下发" : err) + "）"
                            + (meta == null || meta.isBlank() ? "" : " | " + meta));
                    failures++;
                    continue;
                }
                println("[LP]   请求=" + level + " → " + describeUrl(url) + " | " + meta);
                if (!meta.contains("实际档位=" + level)) {
                    println("[LP]     注意：实际档位未达请求档位（降级）");
                }
                if (!probedUrls.add(url)) {
                    println("[LP]     （同一 URL，跳过重复拉流试验）");
                } else {
                    println("[LP]     CDN 无凭据拉流试验（Range 1KB，无 cookie / 无 Referer）：" + cdnRange(url));
                }
                if (m3uOut != null && m3uCount < m3uLimit) {
                    m3uLines.add("#EXTINF:" + Math.max(0, s.durationMs() / 1000) + "," + s.name()
                            + " - " + s.artists());
                    m3uLines.add(url);
                    m3uCount++;
                    break; // 每首歌只放一个档位进 m3u
                }
            }
        }

        // ---- 4. 可选：写 m3u 试验文件 ----
        if (m3uOut != null) {
            try {
                List<String> out = new ArrayList<>();
                out.add("#EXTM3U");
                out.addAll(m3uLines);
                Path p = Path.of(m3uOut);
                if (p.getParent() != null) {
                    Files.createDirectories(p.getParent());
                }
                Files.writeString(p, String.join("\r\n", out) + "\r\n", StandardCharsets.UTF_8);
                println("[LP] m3u 已写出：" + p.toAbsolutePath() + "（" + m3uCount + " 首，"
                        + Files.size(p) + " 字节；直链含临时令牌，仅本地试验用，勿外传）");
            } catch (Throwable t) {
                println("[LP] m3u 写出失败：" + t.getClass().getSimpleName() + ": " + headless(t.getMessage()));
                failures++;
            }
        }

        println("[LP] emitted=" + probeSongs.size() * levels.length + " failures=" + failures);

        // ---- 5. 回放 net 层自述（通道、降档、解析失败原因全在这里） ----
        if (logDir != null && PluginLog.file() != null) {
            try {
                List<String> lines = Files.readAllLines(PluginLog.file(), StandardCharsets.UTF_8);
                int from = Math.max(0, lines.size() - 40);
                println("[LP] ---- net 层日志尾部（" + (lines.size() - from) + "/" + lines.size() + " 行）----");
                for (int i = from; i < lines.size(); i++) {
                    println("[PLOG] " + lines.get(i));
                }
            } catch (Throwable t) {
                println("[LP] 日志回放失败：" + headless(t.getMessage()));
            }
        }

        System.exit(failures == 0 ? 0 : 1);
    }

    /** 只暴露 URL 特征：host、扩展名、query 参数名（无值）——绝不打完整直链。 */
    private static String describeUrl(String url) {
        try {
            URI u = URI.create(url);
            String path = u.getPath() == null ? "" : u.getPath();
            int dot = path.lastIndexOf('.');
            String ext = dot < 0 ? "?" : path.substring(dot + 1);
            Set<String> names = new LinkedHashSet<>();
            String q = u.getQuery();
            if (q != null) {
                for (String kv : q.split("&")) {
                    int eq = kv.indexOf('=');
                    names.add(eq < 0 ? kv : kv.substring(0, eq));
                }
            }
            return "host=" + u.getHost() + " ext=" + ext + " 长度=" + url.length()
                    + " query参数=" + names;
        } catch (Throwable t) {
            return "URL 解析失败（长度 " + url.length() + "）";
        }
    }

    /** 不带 cookie / Referer，只取前 1KB —— 模拟 BASS 这类裸客户端。 */
    private static String cdnRange(String url) {
        long t0 = System.currentTimeMillis();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (LiveProbe)");
            c.setRequestProperty("Range", "bytes=0-1023");
            int status = c.getResponseCode();
            int read = 0;
            byte[] head = new byte[1024];
            try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
                if (in != null) {
                    int n;
                    while (read < 1024 && (n = in.read(head, read, 1024 - read)) > 0) {
                        read += n;
                    }
                }
            }
            c.disconnect();
            return "HTTP " + status + " 首块=" + read + "B Content-Type=" + c.getContentType()
                    + " Content-Range=" + c.getHeaderField("Content-Range")
                    + " 音频头=" + describeAudioHead(head, read)
                    + " 耗时=" + (System.currentTimeMillis() - t0) + "ms"
                    + (status == 200 || status == 206 ? "（可裸拉）" : "（不可裸拉）");
        } catch (Throwable t) {
            return "拉流失败 " + t.getClass().getSimpleName() + ": " + headless(t.getMessage());
        }
    }

    /**
     * 从首块字节识别容器并解出 FLAC 的采样率/位深/时长 —— 「音质到底是多少」的直接证据。
     *
     * <p>判据不是接口自报的 {@code 码率}，而是文件自己的 STREAMINFO：客户端下载下来看到的
     * 也是这个值（用同一把尺子比，才能判「插件给的是不是和客户端一样的那一档」）。</p>
     */
    private static String describeAudioHead(byte[] b, int len) {
        if (len >= 42 && b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'C') {
            int btype = b[4] & 0x7F;
            if (btype != 0) {
                return "FLAC（首块不是 STREAMINFO：type=" + btype + "）";
            }
            int rate = ((b[18] & 0xFF) << 12) | ((b[19] & 0xFF) << 4) | ((b[20] & 0xFF) >> 4);
            int ch = (((b[20] & 0xFF) >> 1) & 0x07) + 1;
            int bps = (((b[20] & 0x01) << 4) | ((b[21] & 0xFF) >> 4)) + 1;
            long total = (((long) (b[21] & 0x0F)) << 32)
                    | (((long) (b[22] & 0xFF)) << 24) | (((long) (b[23] & 0xFF)) << 16)
                    | (((long) (b[24] & 0xFF)) << 8) | (b[25] & 0xFF);
            long dur = rate > 0 ? total / rate : 0L;
            return "FLAC " + rate + "Hz/" + bps + "bit/" + ch + "ch 时长=" + dur + "s";
        }
        if (len >= 3 && b[0] == 'I' && b[1] == 'D' && b[2] == '3') {
            return "MP3（ID3v2 头）";
        }
        if (len >= 2 && (b[0] & 0xFF) == 0xFF && ((b[1] & 0xE0) == 0xE0)) {
            return "MP3（裸帧）";
        }
        if (len >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            return "M4A/MP4";
        }
        if (len >= 4 && b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') {
            return "OGG";
        }
        return "未知容器";
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            println("[LP]   FAIL " + what);
        }
    }

    private static void println(String s) {
        System.out.println(s);
        System.out.flush();
    }

    private static String arg(String[] args, String key, String def) {
        for (String a : args) {
            if (a != null && a.startsWith(key)) {
                return a.substring(key.length());
            }
        }
        return def;
    }

    private static String headless(String s) {
        if (s == null) {
            return "";
        }
        String out = s.replace('\r', ' ').replace('\n', ' ').trim();
        return out.length() > 160 ? out.substring(0, 160) + "…" : out;
    }
}
