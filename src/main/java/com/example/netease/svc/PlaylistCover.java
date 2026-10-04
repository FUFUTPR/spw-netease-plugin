package com.example.netease.svc;

import com.example.netease.core.DataPaths;
import com.example.netease.core.PluginLog;
import com.example.netease.core.RiskControl;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 0.10.0：宿主原生「歌单封面」。
 *
 * <p><b>机制（字节码实证）</b>：宿主 1.18.5 的 {@code Playlist} 表没有 cover 列 —— 歌单封面是一个
 * <b>文件</b>：{@code %APPDATA%\Salt Player for Windows\data\playlist_cover\<playlistId>}
 * （无扩展名，宿主用 Coil 按内容解码）。宿主 UI 的取图链是
 * {@code androidx.compose.ui.ஜ → ఌ.ԫ()(data/playlist_cover) .resolve(playlist.getId()) → ImageRequest}，
 * 且 {@code Playlist.coverModifiedTime} 参与图片内存缓存键 —— 所以换了图要把它一起更新
 * （写库侧见 {@code NativeLibrary#insertPlaylist}）。</p>
 *
 * <p><b>与 {@link CoverArt} 的差别</b>：专辑/艺术家磁贴只认「本地媒体文件的内嵌图」，所以要造 FLAC 桩；
 * 歌单封面就是普通图片文件本身 —— 下载原图直接落盘即可（宿主自己给歌单换封面也是这么存的）。</p>
 *
 * <p><b>幂等</b>：插件数据目录留一份 {@code <歌单行 id>.src} 边车（封面 URL 的 md5）。URL 没变且
 * 宿主侧文件还在就跳过下载，避免每轮同步重复拉图。</p>
 */
public final class PlaylistCover {

    private static final String TAG = "pl-cover";

    /** 小于此字节数的文件视为坏文件，重新生成。 */
    private static final int MIN_BYTES = 1024;

    /** 单张歌单封面的最大尝试次数（超过归「失败」终态，只保留本地旧图，不再发起请求）。 */
    private static final int MAX_ATTEMPTS = 5;

    /** 失败分类计数（label → 次数）。词表与 {@link CoverStore} **同一套**（{@code CoverStore.kindOf}）。 */
    private static final Map<String, Integer> FAIL_KINDS = new ConcurrentHashMap<>();

    /** 已尝试次数：宿主歌单行 id → n（成功即清零）。 */
    private static final Map<String, Integer> ATTEMPTS = new ConcurrentHashMap<>();

    /** 长退避到点时间：宿主歌单行 id → epoch ms（窗口内不再发起请求，避免每轮同步重复拉同一张）。 */
    private static final Map<String, Long> RETRY_AT = new ConcurrentHashMap<>();

    /** 「歌单自己没有封面图」的终态集合（不算失败，不进网络 —— A8 口径）。 */
    private static final Set<String> NO_SRC = ConcurrentHashMap.newKeySet();

    private static final AtomicInteger READY = new AtomicInteger();
    private static final AtomicInteger FAILED = new AtomicInteger();

    private PlaylistCover() {
    }

    /** 宿主歌单封面目录：{@code <宿主数据>\data\playlist_cover}。 */
    public static Path hostDir() {
        Path data = NativeLibrary.hostDataDir();
        return data == null ? null : data.resolve("data").resolve("playlist_cover");
    }

    /** 某条宿主歌单行的封面文件（文件名 = 宿主 {@code Playlist.id}，一字不差；宿主就是这样 resolve 的）。 */
    public static Path hostFile(String hostPlaylistId) {
        Path dir = hostDir();
        return dir == null ? null : dir.resolve(hostPlaylistId);
    }

    /** 边车目录：{@code <插件数据>\playlist-cover\}（只存 URL 指纹，不存图）。 */
    private static Path sidecar(String hostPlaylistId) {
        return DataPaths.data().resolve("playlist-cover").resolve(hostPlaylistId + ".src");
    }

    /**
     * 为一批待同步歌单准备封面文件（串行下载；宿主运行期写文件即可被取图链读到）。
     *
     * @return 宿主歌单行 id → coverModifiedTime（毫秒）；拿不到图的歌单不在表里（写库侧保持 0）
     */
    public static Map<String, Long> prepare(List<NativeLibrary.Entry> entries) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (entries == null || entries.isEmpty()) {
            return out;
        }
        int ready = 0;
        int miss = 0;
        for (NativeLibrary.Entry e : entries) {
            String hostId = NativeLibrary.PLAYLIST_PREFIX + e.playlistId();
            long mt = ensure(hostId, e.coverUrl());
            if (mt > 0) {
                out.put(hostId, mt);
                ready++;
            } else {
                miss++;
            }
        }
        PluginLog.i(TAG, "歌单封面：" + entries.size() + " 个歌单 → 备好 " + ready
                + " 张，无图/失败 " + miss + " 张 ｜ " + stats());
        return out;
    }

    /**
     * 确保某个宿主歌单行的封面文件存在且与 {@code coverUrl} 一致。
     *
     * @return 可用的封面文件 mtime（毫秒）；无图/失败且本地也没有旧文件时返回 0
     */
    public static long ensure(String hostPlaylistId, String coverUrl) {
        Path out = hostFile(hostPlaylistId);
        if (out == null) {
            return 0L;
        }
        Path src = sidecar(hostPlaylistId);
        String want = md5Hex(coverUrl == null ? "" : coverUrl);
        try {
            if (isUsable(out) && want.equals(readText(src))) {
                return Files.getLastModifiedTime(out).toMillis();
            }
        } catch (Throwable ignored) {
            // 读边车失败按「需要重取」处理
        }
        if (coverUrl == null || coverUrl.isBlank()) {
            NO_SRC.add(hostPlaylistId);
            return fallback(out);
        }
        Long until = RETRY_AT.get(hostPlaylistId);
        if (until != null && System.currentTimeMillis() < until) {
            return fallback(out);                       // 长退避窗口内：不再发起请求（首轮已打过分类日志）
        }
        try {
            // Q1：全插件单尺寸 1200（走 CoverArt 唯一出口）；用 fetchSized 是为了拿到状态码做失败分类。
            CoverArt.Fetch f = CoverArt.fetchSized(coverUrl, CoverArt.SIZE);
            String kind = CoverStore.kindOf(f.code(), f.bytes());
            if (kind != null) {
                return noteFail(hostPlaylistId, kind, out, coverUrl, f.error());
            }
            byte[] img = f.bytes();
            Files.createDirectories(out.getParent());
            Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
            Files.write(tmp, img);
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            Files.createDirectories(src.getParent());
            Files.writeString(src, want, StandardCharsets.UTF_8);
            ATTEMPTS.remove(hostPlaylistId);
            RETRY_AT.remove(hostPlaylistId);
            READY.incrementAndGet();
            PluginLog.i(TAG, "歌单封面 " + hostPlaylistId + "（图 " + img.length + " B "
                    + CoverArt.sniff(img) + "）");
            return Files.getLastModifiedTime(out).toMillis();
        } catch (Throwable t) {
            return noteFail(hostPlaylistId, CoverStore.kindOf(0, null), out, coverUrl, t);
        }
    }

    /**
     * 记一次失败：分类计数 + 长退避（梯度走 {@code CoverStore.backoffMs}，与专辑封面共用一套）。
     *
     * <p><b>不静默</b>：每次失败都打一行 {@code 歌单封面失败[分类,n/5]}；到上限打 {@code 歌单封面放弃}
     * 并转终态（本地有旧图就继续用，没有就交回写库侧保持 0）。</p>
     */
    private static long noteFail(String hostPlaylistId, String kind, Path out, String coverUrl, Throwable err) {
        int n = ATTEMPTS.merge(hostPlaylistId, 1, Integer::sum);
        FAIL_KINDS.merge(kind, 1, Integer::sum);
        FAILED.incrementAndGet();
        long wait = Math.max(1000L, CoverStore.backoffMs(kind, n));
        if (n >= MAX_ATTEMPTS) {
            RETRY_AT.remove(hostPlaylistId);
            PluginLog.e(TAG, "歌单封面放弃 " + hostPlaylistId + "（" + n + "/" + MAX_ATTEMPTS
                    + " 次，分类 " + kind + "）：" + brief(coverUrl) + (err == null ? "" : " ← " + err));
        } else {
            RETRY_AT.put(hostPlaylistId, System.currentTimeMillis() + wait);
            PluginLog.w(TAG, "歌单封面失败[" + kind + "," + n + "/" + MAX_ATTEMPTS + "] " + hostPlaylistId
                    + "：" + brief(coverUrl) + " ⇒ " + RiskControl.human(wait) + " 后重试"
                    + (err == null ? "" : " ← " + err));
        }
        return fallback(out);
    }

    /** 三态完成度（Q6：已就绪 / 待处理 / 失败）+ 分类明细；供状态行与验证报告引用。 */
    public static String stats() {
        StringBuilder sb = new StringBuilder("歌单封面：已就绪 ").append(READY.get())
                .append(" / 待处理 ").append(RETRY_AT.size())
                .append(" / 失败 ").append(FAILED.get());
        if (!FAIL_KINDS.isEmpty()) {
            sb.append("（分类 ");
            new TreeMap<>(FAIL_KINDS).forEach((k, v) -> sb.append(k).append('=').append(v).append(' '));
            sb.setLength(sb.length() - 1);
            sb.append('）');
        }
        if (!NO_SRC.isEmpty()) {
            sb.append(" ｜ 无图源 ").append(NO_SRC.size());
        }
        return sb.toString();
    }

    /** 下载失败时：本地还有旧封面就继续用（返回其 mtime），没有就 0。 */
    private static long fallback(Path out) {
        try {
            return isUsable(out) ? Files.getLastModifiedTime(out).toMillis() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static boolean isUsable(Path p) {
        try {
            return p != null && Files.isRegularFile(p) && Files.size(p) >= MIN_BYTES;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readText(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String brief(String url) {
        if (url == null) {
            return "null";
        }
        String u = url.toLowerCase(Locale.ROOT);
        int q = u.indexOf('?');
        String cut = q > 0 ? url.substring(0, q) : url;
        return cut.length() > 120 ? cut.substring(0, 120) + "…" : cut;
    }

    private static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
