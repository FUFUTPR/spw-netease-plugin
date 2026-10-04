package com.example.netease.svc;

import com.example.netease.core.DataPaths;
import com.example.netease.core.PluginLog;
import com.example.netease.core.PluginVersions;
import com.example.netease.net.Dto;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 0.8.0：给宿主原生 UI <b>造封面</b> —— 把网易云的专辑图装进一个本地 FLAC「封面桩」。
 *
 * <p><b>为什么是「桩」</b>（真机对照实验，2026-09-29，证据见 {@code docs/08 §31}）：宿主的封面只认
 * <b>本地媒体文件里的内嵌图</b>——</p>
 * <ul>
 *   <li>{@code Album.cover}/{@code Artist.cover} 指向 {@code file:///…/x.flac}（内含 PICTURE 块）⇒ 封面立刻显示；</li>
 *   <li>指向纯图片文件（{@code .jpg}/{@code .png}）⇒ 不显示；</li>
 *   <li>指向 {@code http(s)} 源（哪怕源里真有 PICTURE 块）⇒ 不显示；</li>
 *   <li>{@code Album.cover} 归属「曲目 path 所指文件」——我们的曲目 path 是 http 流，流里没有 PICTURE，
 *       所以之前宿主给我们的专辑派生出的 cover 永远取不到图（这就是「接入网易云的歌曲都没封面」的根因）。</li>
 * </ul>
 *
 * <p>于是本类把专辑图下载下来，写成 {@code fLaC + STREAMINFO(合成) + PICTURE(last)} 的最小 FLAC——
 * 真机实测：<b>不需要任何音频帧</b>（36 KB 的桩即可被宿主读成专辑/艺术家封面），且宿主
 * <b>运行期</b>改库即刷新，无需重启。</p>
 *
 * <p><b>两个易踩的坑</b>：</p>
 * <ol>
 *   <li>宿主写库用的是<b>未转义</b>的 file URI（{@code file:///C:/Users/…/秋裳 - 好想玩原神(…）.mp3}
 *       ——空格与中文都是原样）。这里照它的写法来：{@link #fileUri(Path)} 不做百分号转义。</li>
 *   <li>宿主运行时是 jlink 裁剪镜像，<b>没有 {@code java.net.http} 模块</b>（{@code HttpClient} 抛
 *       {@code NoClassDefFoundError}），只能用 {@link HttpURLConnection}。</li>
 * </ol>
 */
public final class CoverArt {

    private static final String TAG = "cover";

    /**
     * 单张封面允许的最大字节数（超过即视为异常响应，丢弃）。
     *
     * <p>0.11.8 从 3 MB 抬到 8 MB：口径改成单尺寸 {@link #SIZE}² 后，少数噪点密集的
     * 1200² JPEG 会超过 3 MB —— 那不是坏数据，正是我们要的大图。</p>
     */
    public static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    /** 小于此字节数的桩文件视为坏文件，重新生成。 */
    private static final int STUB_MIN_BYTES = 1024;

    /** 桩文件名的固定前缀（便于人眼辨认与清理）。 */
    public static final String STUB_PREFIX = "cover-stub-";

    private CoverArt() {
    }

    // ------------------------------------------------------------------ 目录与命名

    /** 封面根目录：{@code <插件数据>/cover/}（0.11.0 起真正的桩在 {@code cover/stub/}，交给 CoverStore）。 */
    public static Path dir() {
        return CoverStore.dir();
    }

    /**
     * 宿主风格的 {@code file://} URI：<b>不做百分号转义</b>（宿主自己写进库的值就是原样路径）。
     */
    public static String fileUri(Path p) {
        String s = p.toAbsolutePath().normalize().toString().replace('\\', '/');
        return s.startsWith("/") ? "file://" + s : "file:///" + s;
    }

    /** 桩文件路径（按封面原始 URL 命名：同一张图复用同一个桩，换图自然换文件）。 */
    public static Path stubPath(String picUrl) {
        return CoverStore.stubPath("未知专辑", picUrl);
    }

    // ------------------------------------------------------------------ 主入口

    /**
     * 取（必要时造）某个封面 URL 的本地桩。
     *
     * @return {@code file:///…/cover-stub-<md5>.flac}；下载失败/非图片/写盘失败一律返回 {@code null}
     */
    public static String stub(String picUrl) {
        if (picUrl == null || picUrl.isBlank()) {
            return null;
        }
        return CoverStore.ensure("未知专辑", null, picUrl);
    }

    /** 目录里已有的桩数量（统计用）。 */
    public static int countStubs() {
        return CoverStore.countStubs();
    }

    // ------------------------------------------------------------------ 为一批曲目备封面

    /** 一个专辑要写的封面：专辑名 + 专辑艺术家名 + 本地桩的 file URI。 */
    public record Cover(String album, String artist, String fileUri) {
    }

    /**
     * 从待同步曲目里按专辑收集<b>已经有桩</b>的封面（0.11.1：纯离线、瞬间返回，一张都不下）。
     *
     * <p><b>为什么改成离线</b>：0.11.0 在这里当场新造最多 {@code NATIVE_COVER_MAX=200} 张，
     * 而这 200 张是<b>串行 + 300 ms 节流</b>下完才轮到写库 ⇒ 用户看到的是「等几分钟才出封面」。
     * 现在同步轮只做「把已有封面随曲库行一起写」这件事；缺的交给
     * {@link CoverStore#startFill} 的下载池（{@code NET_THREADS} 路并行、不限量、造一张写一张）。</p>
     *
     * <p>专辑名/歌手名必须与 {@link NativeLibrary#writeOn} 写进 {@code Track} 的值一字不差
     * （宿主正是按这两个值派生 {@code Album}/{@code Artist} 行的），所以这里复用同一套 trim 规则；
     * 0.11.49 起同时用作封面引用键（{@link CoverStore#refKey}），同名专辑不同艺人互不串图。</p>
     *
     * @param entries 待同步歌单
     * @return 专辑 → 封面 的列表（保持首次出现顺序）
     */
    public static List<Cover> collectReady(List<NativeLibrary.Entry> entries) {
        List<Cover> out = new ArrayList<>();
        if (entries == null || entries.isEmpty()) {
            return out;
        }
        Map<String, Dto.Song> firstByKey = new LinkedHashMap<>();
        for (NativeLibrary.Entry e : entries) {
            for (Dto.Song s : e.songs()) {
                // 0.11.49：按（专辑+歌手）收集 —— 同名专辑不同艺人各有各的桩
                firstByKey.putIfAbsent(CoverStore.refKey(NativeLibrary.text(s.album(), "未知专辑"),
                        NativeLibrary.text(s.artists(), "未知歌手")), s);
            }
        }
        int miss = 0;
        for (Map.Entry<String, Dto.Song> kv : firstByKey.entrySet()) {
            String album = NativeLibrary.text(kv.getValue().album(), "未知专辑");
            String artist = NativeLibrary.text(kv.getValue().artists(), "未知歌手");
            String uri = CoverStore.readyUri(kv.getKey());
            if (uri == null) {
                miss++;
                continue;
            }
            out.add(new Cover(album, artist, uri));
        }
        PluginLog.i(TAG, "封面（离线复用）：专辑 " + firstByKey.size() + " 个 → 已有桩 " + out.size()
                + " 张随库写，缺 " + miss + " 张（下载池接着铺，见封面实时投递日志）");
        return out;
    }

    // ------------------------------------------------------------------ 下载

    /**
     * 唯一尺寸（Q1，2026-09-30 定）：<b>所有</b>封面取图一律 {@code ?param=1200y1200}。
     *
     * <p>只落盘这一个尺寸：2000+ 张 1200² 约 1 GB 级，列表行的 96/192 由宿主自己的
     * coil 缓存与内存缓存去缩，不在插件侧再存一套小图（旧版 500 与 240 两套并存 ⇒
     * 同一张图下两次、`cover\` 体积翻倍，见任务书 Q1）。</p>
     */
    public static final int SIZE = 1200;

    /** URL 里已有的尺寸参数（宿主/第三方可能塞过），形如 {@code ?param=500y500} / {@code &param=240y240}。 */
    private static final java.util.regex.Pattern SIZE_PARAM =
            java.util.regex.Pattern.compile("(?i)(^|[?&])param=([0-9]+)y([0-9]+)");

    /**
     * 把取图 URL 规范成 {@link #SIZE}×{@link #SIZE}（幂等）。
     *
     * <p>无查询串 ⇒ 追加 {@code ?param=1200y1200}；已有 {@code param=NyM} ⇒ 只改数值；
     * 已有其它查询参数（时效签名等）⇒ 追加 {@code &param=1200y1200}。</p>
     */
    public static String withSize(String picUrl) {
        return withSize(picUrl, SIZE);
    }

    /** 同上，可指定尺寸（只有「往音频文件里嵌图」那条老路还用得到非 1200 的值）。 */
    public static String withSize(String picUrl, int size) {
        String u = picUrl == null ? "" : picUrl.trim();
        if (u.isEmpty()) {
            return u;
        }
        String want = size + "y" + size;
        var m = SIZE_PARAM.matcher(u);
        if (m.find()) {
            return u.substring(0, m.start(2)) + size + "y" + size + u.substring(m.end(3));
        }
        return u + (u.indexOf('?') >= 0 ? "&" : "?") + "param=" + want;
    }

    /** 下载封面原图：默认单尺寸 {@link #SIZE}（=1200²）。失败返回 null。 */
    static byte[] download(String picUrl) {
        return downloadSized(picUrl, SIZE);
    }

    /**
     * 下载封面原图（按指定尺寸）。失败返回 null。
     *
     * <p>尺寸参数一律走 {@link #withSize(String, int)} —— 「一个尺寸」的口径集中在那一处，
     * 任何调用方都不该自己拼 {@code ?param=}（旧版每个模块各拼一次 ⇒ 500/240 两套并存）。</p>
     */
    static byte[] downloadSized(String picUrl, int size) {
        return fetchSized(picUrl, size).bytes();
    }

    /**
     * 一次取图的结果：<b>带 HTTP 码</b>（封面层要按码分类：403/412/429 = 风控、404/410 = URL 过期）。
     *
     * @param bytes 响应体（失败为 {@code null}）
     * @param code  HTTP 状态码；{@code <= 0} 表示连请求都没发出去（超时/DNS/断网）
     * @param error 传输层异常（可为 {@code null}）
     */
    record Fetch(byte[] bytes, int code, Throwable error) {
        boolean ok() {
            return bytes != null && bytes.length > 0;
        }
    }

    /**
     * 取图（带 HTTP 码）。<b>所有封面网络请求的唯一出口</b> —— 超时/UA/Referer/上限只在这里定义一次。
     *
     * <p>超时与并发口径走 {@link com.example.netease.core.RiskControl}（任务书 §13：全插件一套常量，
     * 不在各模块各写一份）。</p>
     */
    static Fetch fetchSized(String picUrl, int size) {
        Fetch f = get(withSize(picUrl, size), picUrl);
        if (f.ok() && isWebp(f.bytes())) {
            // WebP 兜底（真机 2026-10-01，A13 收口）：图床偶尔只给 WebP（Content-Type 还谎报 image/jpg），
            // 宿主精简 JRE 的 ImageIO 解不了 ⇒ 原先必落「格式异常」占位图。换 imageView 形式能拿到 JPEG。
            Fetch j = get(imageViewUrl(picUrl, size), picUrl);
            if (j.ok() && !isWebp(j.bytes())) {
                PluginLog.i(TAG, "封面 WebP 源改取 JPEG 渲染成功：" + brief(picUrl));
                return j;
            }
            PluginLog.w(TAG, "封面 WebP 源换 imageView 仍不是 JPEG（交上层分类）：" + brief(picUrl));
        }
        return f;
    }

    /**
     * 图床的 {@code imageView} 形式：把任意 picUrl 规范成 {@code size}² 的 <b>JPEG</b>。
     *
     * <p>真机 2026-10-01 实测（同一张只提供 WebP 的图）：{@code ?param=1200y1200} → {@code RIFF….WEBP}；
     * {@code ?param=1200y1200&type=jpg} 仍是 WebP（{@code type} 只在 imageView 形式下生效）；
     * {@code ?imageView&thumbnail=1200y1200&type=jpg} → {@code FF D8 FF E0}（JPEG，47 KB）。</p>
     */
    static String imageViewUrl(String picUrl, int size) {
        String base = picUrl;
        int q = base.indexOf('?');
        if (q > 0) {
            base = base.substring(0, q);
        }
        return base + "?imageView&thumbnail=" + size + "y" + size + "&type=jpg";
    }

    /** 是不是 WebP 容器（{@code RIFF????WEBP}）—— Java ImageIO 默认解不了。 */
    static boolean isWebp(byte[] b) {
        return b != null && b.length > 12
                && (b[0] & 0xFF) == 0x52 && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    /**
     * 一次 HTTP GET（封面网络请求的<b>唯一实现</b>；{@link #fetchSized} 与 WebP 兜底都走这里）。
     *
     * <p>超时与并发口径走 {@link com.example.netease.core.RiskControl}（任务书 §13：全插件一套常量）。</p>
     */
    private static Fetch get(String url, String forLog) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(com.example.netease.core.RiskControl.CONNECT_TIMEOUT_MS);
            c.setReadTimeout(com.example.netease.core.RiskControl.READ_TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", PluginVersions.userAgent());
            c.setRequestProperty("Referer", "https://music.163.com");
            c.setRequestProperty("Accept", "image/*");
            c.setRequestProperty("Accept-Encoding", "identity");
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                PluginLog.w(TAG, "封面 HTTP " + code + "：" + brief(forLog));
                return new Fetch(null, code, null);
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (bos.size() + n > MAX_IMAGE_BYTES) {
                        PluginLog.w(TAG, "封面超过上限 " + MAX_IMAGE_BYTES + " B，丢弃：" + brief(forLog));
                        return new Fetch(null, code, null);
                    }
                    bos.write(buf, 0, n);
                }
                return new Fetch(bos.toByteArray(), code, null);
            }
        } catch (Throwable t) {
            PluginLog.w(TAG, "封面下载失败：" + t + "（" + brief(forLog) + "）");
            return new Fetch(null, 0, t);
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** 封面 CDN 的换域正则：{@code p1.music.126.net} / {@code p2.music.126.net} / {@code p3.music.126.net}。 */
    private static final java.util.regex.Pattern HOST =
            java.util.regex.Pattern.compile("^(https?://)(p\\d+)\\.music\\.126\\.net", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * 同一个封面 URL 的<b>换域候选</b>（任务书第 6 条「403 ⇒ 换域 + Referer/UA」）。
     *
     * <p>网易云图床有 {@code p1}/{@code p2}/{@code p3} 三组等价域名；某组被风控拦时换一组常常就通了。
     * 返回列表第一个恒为原 URL（顺序即尝试顺序）。</p>
     */
    static List<String> candidates(String picUrl) {
        List<String> out = new ArrayList<>(3);
        String first = withSize(picUrl);
        if (first.isEmpty()) {
            return out;
        }
        out.add(first);
        var m = HOST.matcher(first);
        if (!m.find()) {
            return out;
        }
        for (String host : new String[]{"p1", "p2", "p3", "p4"}) {
            if (host.equalsIgnoreCase(m.group(2))) {
                continue;
            }
            String alt = first.substring(0, m.start(2)) + host + first.substring(m.end(2));
            if (!out.contains(alt)) {
                out.add(alt);
            }
            if (out.size() >= 2) {          // 原域 + 一个备域足够；三个全试会把失败耗时×3
                break;
            }
        }
        return out;
    }

    /** 认图：JPEG / PNG，其余返回 null。 */
    static String sniff(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        return null;
    }

    // ------------------------------------------------------------------ FLAC 封面桩

    /**
     * 造「最小 FLAC」：{@code fLaC} + {@code STREAMINFO} + {@code PICTURE}(last)。
     *
     * <p>STREAMINFO 是合成的（44.1 kHz / 双声道 / 16 bit / 总样本 0、无帧）——真机实测宿主只走元数据块，
     * 不解码音频，所以桩里一个音频帧都不需要。</p>
     */
    static byte[] flac(byte[] image, String mime) {
        byte[] streamInfo = streamInfoBlock();
        byte[] picture = pictureBlock(image, mime);
        byte[] out = new byte[4 + streamInfo.length + picture.length];
        int p = 0;
        out[p++] = 'f';
        out[p++] = 'L';
        out[p++] = 'a';
        out[p++] = 'C';
        System.arraycopy(streamInfo, 0, out, p, streamInfo.length);
        p += streamInfo.length;
        System.arraycopy(picture, 0, out, p, picture.length);
        return out;
    }

    private static byte[] streamInfoBlock() {
        byte[] body = new byte[34];
        putU16(body, 0, 4096);          // minBlockSize
        putU16(body, 2, 4096);          // maxBlockSize
        // minFrameSize/maxFrameSize = 0（3+3 字节，已是 0）
        long packed = ((long) 44100 << 44) | (1L << 41) | (15L << 36);   // 44.1k / 2ch / 16bit / 总样本 0
        for (int i = 0; i < 8; i++) {
            body[10 + i] = (byte) (packed >>> (56 - 8 * i));
        }
        // 其后 16 字节为 MD5，保持全 0
        return block(0, body, false);
    }

    /** FLAC PICTURE 块（type 3，last）；包内可见：{@link AudioCache} 往真音频文件里插图时也用它。 */
    static byte[] pictureBlock(byte[] image, String mime) {
        byte[] m = mime.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int len = 4 + 4 + m.length + 4 + 4 * 4 + 4 + image.length;
        byte[] body = new byte[len];
        int p = 0;
        p = putU32(body, p, 3);              // picture type = Cover (front)
        p = putU32(body, p, m.length);
        System.arraycopy(m, 0, body, p, m.length);
        p += m.length;
        p = putU32(body, p, 0);              // description 长度 0
        p = putU32(body, p, 0);              // width：0 = 未知（宿主不看）
        p = putU32(body, p, 0);              // height
        p = putU32(body, p, 0);              // color depth
        p = putU32(body, p, 0);              // indexed colors
        p = putU32(body, p, image.length);
        System.arraycopy(image, 0, body, p, image.length);
        return block(6, body, true);
    }

    private static byte[] block(int type, byte[] body, boolean last) {
        byte[] out = new byte[4 + body.length];
        out[0] = (byte) ((last ? 0x80 : 0) | (type & 0x7F));
        out[1] = (byte) (body.length >>> 16);
        out[2] = (byte) (body.length >>> 8);
        out[3] = (byte) body.length;
        System.arraycopy(body, 0, out, 4, body.length);
        return out;
    }

    private static void putU16(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    private static int putU32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
        return off + 4;
    }

    // ------------------------------------------------------------------ 小工具

    /** 桩是否可用（存在且不小于 {@link #STUB_MIN_BYTES}）；CoverStore 也走这一份判定。 */
    static boolean usable(Path p) {
        try {
            return Files.isRegularFile(p) && Files.size(p) >= STUB_MIN_BYTES;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** 日志里只露「去参数」的 URL 前缀（封面 CDN 的查询串很长，且含时效签名）。 */
    static String brief(String url) {
        if (url == null) {
            return "null";
        }
        String u = url.toLowerCase(Locale.ROOT);
        int q = u.indexOf('?');
        String cut = q > 0 ? url.substring(0, q) : url;
        return cut.length() > 120 ? cut.substring(0, 120) + "…" : cut;
    }
}
