package com.example.netease.svc;

import com.example.netease.core.DataPaths;
import com.example.netease.core.PluginLog;
import com.example.netease.core.PluginVersions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地音频缓存（0.9.0）：把曲目的音频落到本地文件、并<b>把封面塞进文件自己的标签里</b>，再把
 * {@code Track.path} 指过去。
 *
 * <h2>为什么非得这么做</h2>
 * 宿主的「歌曲」列表行封面只有一条来源：{@code Track.path} 指向的**本地媒体文件的内嵌图**
 * （宿主用 jAudioTagger 读 {@code Artwork}/{@code MetadataBlockDataPicture}，AOT 字符串实证见 docs/08 §32）。
 * 我们的曲目 path 本来是 {@code http://127.0.0.1:<port>/netease/<id>} 的流：
 * <ul>
 *   <li>HTTP 源读不出标签 ⇒ 行内永远只有 ♪（真机对照：本地 FLAC 带 PICTURE 的行出图 ✓，
 *       http 指向同一个 FLAC 的行不出图 ✗）；</li>
 *   <li>宿主的封面缓存 {@code cache/shared_cover/<sha256>.<0|1>} 无法预置——key 公式反推不成立
 *       （试过 md5/sha1/sha256 × 路径/URI/时间戳/尺寸/图片字节/整文件字节，0 命中）；</li>
 *   <li>{@code Track} 表没有 cover 列，也没有任何按曲目存封面的表。</li>
 * </ul>
 * 所以「每首歌都有封面」= 每首歌都得有一份**本地带图音频文件**。这份文件同时解决了播放
 * （本地文件是真音频，不必再依赖流服务）与封面两件事。
 *
 * <h2>怎么做</h2>
 * 从插件自己的流服务（{@link NativeStreamServer#urlFor(long)}）把音频整段拉下来，
 * **边拉边改头**（不整段进内存，无损档单曲可达几十 MB）：
 * <ul>
 *   <li>FLAC：解析元数据块链，若没有 PICTURE(6) 就插入一个（插在末尾并置 last 位），音频帧原样跟在后面；</li>
 *   <li>MP3：丢掉原有 ID3v2 标签（保留音频），前置一个自带 ID3v2.3 标签：{@code TIT2/TPE1/TALB/APIC}；</li>
 *   <li>M4A/OGG：照落不误（行封面需另解，暂不注入）。</li>
 * </ul>
 * 落盘走 {@code <id>.<ext>.part} → 原子改名 {@code <id>.<ext>}，半截文件不会被当成可用缓存。
 *
 * <h2>代价与边界</h2>
 * 这是**磁盘换封面**：每首歌一份音频（默认按 {@code audio_level}，无损档约 20–60 MB/首）。
 * 有总容量上限与剩余空间下限（见常量），超了就停止填充并记日志；{@code native_audio_cache=false}
 * 可整体关掉（关掉后行封面回到 ♪，专辑/艺术家磁贴不受影响）。
 */
public final class AudioCache {

    private static final String TAG = "audio";

    /** 单曲音频上限：无损/母带档留足余量，超过视为异常响应。 */
    /**
     * 单曲音频字节上限（0.11.2：120 MB → <b>512 MB</b>）。
     *
     * <p>0.9.0 设 120 MB 是为了挡住「从 CDN 下载整首」的意外大头；现在这条路上唯一的消费者是
     * {@code svc.RowCover} 的<b>本地搬运</b>（源就是本机已预取完成的缓存文件），而 jymaster 母带单曲
     * 实测 146 MB、更大的到 200 MB+ —— 120 MB 会把母带整批挡在行封面之外
     * （真机日志：{@code 行封面失败 #38689097：java.io.IOException: 音频超过单曲上限 125829120 B}）。</p>
     */
    public static final long MAX_AUDIO_BYTES = 512L * 1024 * 1024;

    /** 小于这个大小的「音频」必然是错误响应（HTML 错误页等），不当作缓存。 */
    private static final long MIN_AUDIO_BYTES = 32L * 1024;

    /** 元数据头（FLAC 块链 / ID3 标签）读取上限，防畸形文件吃内存。 */
    private static final long MAX_HEAD_BYTES = 4L * 1024 * 1024;

    /** 缓存总容量上限；超过后不再下载（已缓存的继续用）。 */
    public static final long MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024;

    /** 剩余磁盘下限；低于它就停止填充。 */
    private static final long FREE_SPACE_FLOOR = 512L * 1024 * 1024;

    /** 认识的容器后缀（顺序即探测顺序）。 */
    private static final String[] EXTS = {".flac", ".mp3", ".m4a", ".ogg"};

    private static volatile boolean stopLogged;

    /**
     * 「改完头」的结果：容器名 + 已拷进输出流的音频正文字节数。
     *
     * <p>{@code audio} 是 {@code copyRest} 的累计值（含 FLAC/MP3 分支内部那次拷贝），
     * 调用方不能再拷一遍——所以这个计数必须由容器分支自己带回来，见 {@link #copyWithArt}。</p>
     */
    record Head(String kind, long audio) {
    }

    private AudioCache() {
    }

    // ------------------------------------------------------------------ 目录与查询

    /** 缓存目录：{@code <插件数据目录>/audio}（随插件数据目录一起被卸载/清理）。 */
    public static Path dir() {
        Path p = DataPaths.data().resolve("audio");
        try {
            Files.createDirectories(p);
        } catch (Throwable ignored) {
            // 建不出来时后面每个文件操作都会自己失败，不必在这里抛
        }
        return p;
    }

    /** 已缓存的本地文件；没有或不完整返回 null。 */
    public static Path existing(long songId) {
        for (String ext : EXTS) {
            Path p = dir().resolve(songId + ext);
            try {
                if (Files.isRegularFile(p) && Files.size(p) >= MIN_AUDIO_BYTES) {
                    return p;
                }
            } catch (Throwable ignored) {
                // 探测失败当作不存在
            }
        }
        return null;
    }

    /** 这首歌是否已有本地缓存。 */
    public static boolean has(long songId) {
        return existing(songId) != null;
    }

    /** 已缓存文件的 file URI（宿主用的原样路径，见 {@link CoverArt#fileUri(Path)}）；没有返回 null。 */
    public static String localUri(long songId) {
        Path p = existing(songId);
        return p == null ? null : CoverArt.fileUri(p);
    }

    /** 已缓存文件大小；没有返回 0。 */
    public static long localSize(long songId) {
        Path p = existing(songId);
        if (p == null) {
            return 0L;
        }
        try {
            return Files.size(p);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 已缓存曲目数。 */
    public static int cached() {
        return files().size();
    }

    /** 已缓存总字节数。 */
    public static long bytes() {
        long total = 0L;
        for (Path p : files()) {
            try {
                total += Files.size(p);
            } catch (Throwable ignored) {
                // 统计用，忽略
            }
        }
        return total;
    }

    /** 一句话统计（日志/诊断用）。 */
    public static String stats() {
        return "本地音频缓存：" + cached() + " 首 / " + (bytes() / (1024L * 1024L)) + " MB（" + dir() + "）";
    }

    /** 清空缓存（返回删掉的文件数）。 */
    public static int purge() {
        int n = 0;
        for (Path p : files()) {
            try {
                Files.deleteIfExists(p);
                n++;
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        return n;
    }

    private static List<Path> files() {
        List<Path> out = new ArrayList<>();
        try (var s = Files.list(dir())) {
            s.filter(Files::isRegularFile).forEach(out::add);
        } catch (Throwable ignored) {
            // 目录不存在等
        }
        return out;
    }

    // ------------------------------------------------------------------ 填充

    /**
     * 下载一首歌的音频、注入封面并落盘。返回本地文件 URI；失败返回 null（只记日志，不抛）。
     *
     * @param songId  曲目 id
     * @param picUrl  封面原图地址（可空：没有图也照样缓存音频，行封面这轮就没有）
     * @param title   写进 MP3 标签的标题（可空）
     * @param artist  写进 MP3 标签的歌手（可空）
     * @param album   写进 MP3 标签的专辑（可空）
     */
    public static String ensure(long songId, String picUrl, String title, String artist, String album) {
        Path have = existing(songId);
        if (have != null) {
            return CoverArt.fileUri(have);
        }
        if (bytes() > MAX_TOTAL_BYTES) {
            logOnce("缓存已到上限 " + (MAX_TOTAL_BYTES / (1024L * 1024L * 1024L)) + " GB，停止填充");
            return null;
        }
        if (freeSpace() < FREE_SPACE_FLOOR) {
            logOnce("剩余磁盘不足 " + (FREE_SPACE_FLOOR / (1024L * 1024L)) + " MB，停止填充");
            return null;
        }
        byte[] image = null;
        String mime = null;
        if (picUrl != null && !picUrl.isBlank()) {
            image = CoverArt.download(picUrl);
            if (image != null) {
                mime = CoverArt.sniff(image);
                if (mime == null) {
                    image = null;                       // 不是 JPEG/PNG 就当没有图
                }
            }
        }
        String streamUrl = NativeStreamServer.urlFor(songId);
        if (streamUrl == null || streamUrl.isBlank()) {
            PluginLog.w(TAG, "音频缓存失败 #" + songId + "：拿不到流地址");
            return null;
        }
        Path tmp = dir().resolve(songId + ".part");
        java.net.HttpURLConnection c = null;
        try {
            c = open(streamUrl);
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                PluginLog.w(TAG, "音频缓存失败 #" + songId + "：HTTP " + code);
                return null;
            }
            long declared = c.getContentLengthLong();
            if (declared > MAX_AUDIO_BYTES) {
                PluginLog.w(TAG, "音频缓存跳过 #" + songId + "：Content-Length " + declared + " 超上限");
                return null;
            }
            String kind;
            long audio;
            try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
                Head head = copyWithArt(in, out, image, mime, title, artist, album);
                if (head == null) {
                    PluginLog.w(TAG, "音频缓存失败 #" + songId + "：认不出容器（不是 FLAC/MP3/M4A/OGG）");
                    return null;
                }
                kind = head.kind();
                audio = head.audio();                        // 音频正文由 copyWithArt 内部拷完，这里不再二次拷
            }
            long size = Files.size(tmp);
            if (size < MIN_AUDIO_BYTES) {
                PluginLog.w(TAG, "音频缓存失败 #" + songId + "：只有 " + size + " B，丢弃");
                return null;
            }
            Path dst = dir().resolve(songId + "." + kind);
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
            PluginLog.i(TAG, "音频缓存 +1 #" + songId + "：" + kind.toUpperCase()
                    + " / 音频 " + (audio / 1024L) + " KB / 文件 " + (size / 1024L) + " KB / "
                    + artSummary(dst, kind));
            return CoverArt.fileUri(dst);
        } catch (Throwable t) {
            PluginLog.w(TAG, "音频缓存失败 #" + songId + "：" + t);
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
            try {
                Files.deleteIfExists(tmp);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    private static void logOnce(String why) {
        if (!stopLogged) {
            stopLogged = true;
            PluginLog.w(TAG, "音频缓存停止：" + why);
        }
    }

    private static long freeSpace() {
        try {
            return dir().toFile().getUsableSpace();
        } catch (Throwable t) {
            return Long.MAX_VALUE;
        }
    }

    private static java.net.HttpURLConnection open(String url) throws IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", PluginVersions.userAgent());
        c.setRequestProperty("Referer", "https://music.163.com");
        c.setRequestProperty("Accept-Encoding", "identity");
        return c;
    }

    // ------------------------------------------------------------------ 边下边改头

    /**
     * 读入容器头（必要时改写：插 FLAC PICTURE / 前置 MP3 ID3v2），把「音频正文」拷进 {@code out} 并计数。
     *
     * @return 容器名 + 音频字节数（{@code flac}/{@code mp3}/{@code m4a}/{@code ogg}）；认不出返回 null
     */
    /** 认容器 + 注入内嵌图（包内可见：{@code svc.RowCover} 复用它给「行封面」造带图文件）。 */
    static Head copyWithArt(InputStream raw, OutputStream out, byte[] image, String mime,
                                    String title, String artist, String album) throws IOException {
        PushbackInputStream in = new PushbackInputStream(raw, 32);
        byte[] sig = new byte[16];
        int n = readFully(in, sig);
        if (n < 4) {
            return null;
        }
        in.unread(sig, 0, n);
        if (sig[0] == 'f' && sig[1] == 'L' && sig[2] == 'a' && sig[3] == 'C') {
            return flacWithArt(in, out, image, mime);
        }
        if (sig[0] == 'I' && sig[1] == 'D' && sig[2] == '3') {
            return mp3WithArt(in, out, image, mime, title, artist, album);
        }
        if ((sig[0] & 0xFF) == 0xFF && (sig[1] & 0xE0) == 0xE0) {
            // 无 ID3 的裸 MP3 帧：直接补一个我们自己的标签
            writeId3(out, image, mime, title, artist, album);
            return new Head("mp3", copyRest(in, out));
        }
        if (n >= 8 && sig[4] == 'f' && sig[5] == 't' && sig[6] == 'y' && sig[7] == 'p') {
            return new Head("m4a", copyRest(in, out));  // M4A：照落不误，暂不注入封面
        }
        if (sig[0] == 'O' && sig[1] == 'g' && sig[2] == 'g' && sig[3] == 'S') {
            return new Head("ogg", copyRest(in, out));  // OGG：同上
        }
        return null;
    }

    /**
     * FLAC：解析元数据块链。没有 PICTURE 块就插一个（新块置 last、原 last 块清位），音频帧原样流过去。
     *
     * <p>必须让新块成为链里最后一个：FLAC 的块链靠 last 位终止，插在中间会让后面的块被当成音频。</p>
     */
    private static Head flacWithArt(InputStream in, OutputStream out, byte[] image, String mime) throws IOException {
        byte[] magic = new byte[4];
        if (readFully(in, magic) != 4 || magic[0] != 'f' || magic[1] != 'L' || magic[2] != 'a' || magic[3] != 'C') {
            return null;
        }
        List<byte[]> blocks = new ArrayList<>();
        boolean hasPicture = false;
        boolean last = false;
        int lastIndex = 0;
        long total = 4;
        while (!last) {
            byte[] hdr = new byte[4];
            if (readFully(in, hdr) != 4) {
                return null;
            }
            last = (hdr[0] & 0x80) != 0;
            int type = hdr[0] & 0x7F;
            int len = ((hdr[1] & 0xFF) << 16) | ((hdr[2] & 0xFF) << 8) | (hdr[3] & 0xFF);
            byte[] body = new byte[len];
            if (readFully(in, body) != len) {
                return null;
            }
            if (type == 6) {
                hasPicture = true;
            }
            hdr[0] = (byte) type;                        // 先清掉 last 位，写完再按需补
            byte[] whole = new byte[4 + len];
            System.arraycopy(hdr, 0, whole, 0, 4);
            System.arraycopy(body, 0, whole, 4, len);
            blocks.add(whole);
            lastIndex = blocks.size() - 1;
            total += 4 + len;
            if (total > MAX_HEAD_BYTES) {
                return null;
            }
        }
        out.write(magic);
        boolean inject = !hasPicture && image != null && mime != null;
        for (int i = 0; i < blocks.size(); i++) {
            byte[] b = blocks.get(i);
            if (!inject && i == lastIndex) {
                b[0] = (byte) (b[0] | 0x80);             // 原样：原来的 last 块还是 last
            }
            out.write(b);
        }
        if (inject) {
            out.write(CoverArt.pictureBlock(image, mime));
        }
        return new Head("flac", copyRest(in, out));
    }

    /**
     * MP3：丢掉原有 ID3v2 标签（保留音频正文），前置一个自带 {@code TIT2/TPE1/TALB/APIC} 的 v2.3 标签。
     *
     * <p>丢掉旧标签是有意的：改标签要重排帧、还得改标签长度字段，收益只是保住文件里的元数据；
     * 而曲目的标题/歌手/专辑本来就在 {@code Track} 行里（我们再写回 TIT2 等，避免宿主从文件回读时变空）。</p>
     */
    private static Head mp3WithArt(InputStream in, OutputStream out, byte[] image, String mime,
                                   String title, String artist, String album) throws IOException {
        byte[] h = new byte[10];
        if (readFully(in, h) != 10) {
            return null;
        }
        if (h[0] != 'I' || h[1] != 'D' || h[2] != '3') {
            return null;
        }
        int flags = h[5] & 0xFF;
        long size = ((long) (h[6] & 0x7F) << 21) | ((long) (h[7] & 0x7F) << 14)
                | ((long) (h[8] & 0x7F) << 7) | (long) (h[9] & 0x7F);
        long skip = size + (((flags & 0x10) != 0) ? 10L : 0L);          // v2.4 的 footer
        if (skip > MAX_HEAD_BYTES) {
            return null;
        }
        skipFully(in, skip);
        writeId3(out, image, mime, title, artist, album);
        return new Head("mp3", copyRest(in, out));
    }

    /** 造 ID3v2.3 标签：TIT2/TPE1/TALB（UTF-16）+ APIC（有图时）。 */
    private static void writeId3(OutputStream out, byte[] image, String mime,
                                 String title, String artist, String album) throws IOException {
        ByteArrayOutputStream frames = new ByteArrayOutputStream(64 * 1024);
        frames.write(textFrame("TIT2", title));
        frames.write(textFrame("TPE1", artist));
        frames.write(textFrame("TALB", album));
        if (image != null && mime != null) {
            frames.write(apicFrame(image, mime));
        }
        byte[] body = frames.toByteArray();
        byte[] tag = new byte[10 + body.length];
        tag[0] = 'I';
        tag[1] = 'D';
        tag[2] = '3';
        tag[3] = 0x03;                                    // v2.3.0
        tag[4] = 0x00;                                    // revision
        tag[5] = 0x00;                                    // flags
        int sz = body.length;
        tag[6] = (byte) ((sz >>> 21) & 0x7F);             // 同步安全整数
        tag[7] = (byte) ((sz >>> 14) & 0x7F);
        tag[8] = (byte) ((sz >>> 7) & 0x7F);
        tag[9] = (byte) (sz & 0x7F);
        System.arraycopy(body, 0, tag, 10, body.length);
        out.write(tag);
    }

    private static byte[] textFrame(String id, String value) throws IOException {
        if (value == null || value.isBlank()) {
            return new byte[0];
        }
        byte[] t = value.getBytes(StandardCharsets.UTF_16);              // 带 BOM
        byte[] f = new byte[10 + 1 + t.length];
        frameHeader(f, id, 1 + t.length);
        f[10] = 0x01;                                                     // UTF-16 with BOM
        System.arraycopy(t, 0, f, 11, t.length);
        return f;
    }

    private static byte[] apicFrame(byte[] image, String mime) throws IOException {
        byte[] m = mime.getBytes(StandardCharsets.US_ASCII);
        byte[] f = new byte[10 + 1 + m.length + 1 + 1 + 1 + image.length];
        frameHeader(f, "APIC", f.length - 10);
        int p = 10;
        f[p++] = 0x00;                                                    // ISO-8859-1
        System.arraycopy(m, 0, f, p, m.length);
        p += m.length;
        f[p++] = 0x00;                                                    // MIME 结束
        f[p++] = 0x03;                                                    // Cover (front)
        f[p++] = 0x00;                                                    // 空 description
        System.arraycopy(image, 0, f, p, image.length);
        return f;
    }

    private static void frameHeader(byte[] b, String id, int len) {
        b[0] = (byte) id.charAt(0);
        b[1] = (byte) id.charAt(1);
        b[2] = (byte) id.charAt(2);
        b[3] = (byte) id.charAt(3);
        b[4] = (byte) (len >>> 24);                                       // v2.3：普通大端
        b[5] = (byte) (len >>> 16);
        b[6] = (byte) (len >>> 8);
        b[7] = (byte) len;
        b[8] = 0x00;                                                      // flags
        b[9] = 0x00;
    }

    // ------------------------------------------------------------------ 自检与流工具

    /**
     * 落盘后的自检（日志证据用）：读文件头，报出内嵌图情况。
     *
     * <p>刻意只看头部（≤4 MB）：既快又足以覆盖 ID3v2 标签与 FLAC 元数据块链。</p>
     */
    public static String artSummary(Path p, String kind) {
        try (InputStream in = Files.newInputStream(p)) {
            byte[] head = new byte[(int) Math.min(Files.size(p), MAX_HEAD_BYTES)];
            int n = readFully(in, head);
            if ("flac".equals(kind)) {
                if (n < 8) {
                    return "自检：头太短";
                }
                int off = 4;
                while (off + 4 <= n) {
                    int type = head[off] & 0x7F;
                    boolean last = (head[off] & 0x80) != 0;
                    int len = ((head[off + 1] & 0xFF) << 16) | ((head[off + 2] & 0xFF) << 8)
                            | (head[off + 3] & 0xFF);
                    if (type == 6 && off + 4 + len <= n && len > 32) {
                        int pos = off + 4 + 4;                  // 跳 pictureType(4)
                        int mlen = readU32(head, pos);
                        pos += 4 + mlen;
                        pos += 4;                               // description 长度
                        pos += 16;                              // w/h/depth/colors
                        int dlen = readU32(head, pos);
                        return "自检：FLAC PICTURE 内嵌图 " + dlen + " B";
                    }
                    if (last) {
                        break;
                    }
                    off += 4 + len;
                }
                return "自检：FLAC 无 PICTURE";
            }
            if ("mp3".equals(kind)) {
                if (n < 10 || head[0] != 'I' || head[1] != 'D' || head[2] != '3') {
                    return "自检：无 ID3v2 标签";
                }
                long size = ((long) (head[6] & 0x7F) << 21) | ((long) (head[7] & 0x7F) << 14)
                        | ((long) (head[8] & 0x7F) << 7) | (long) (head[9] & 0x7F);
                int off = 10;
                long end = Math.min(10 + size, n);
                while (off + 10 <= end) {
                    String id = new String(head, off, 4, StandardCharsets.US_ASCII);
                    int len = ((head[off + 4] & 0xFF) << 24) | ((head[off + 5] & 0xFF) << 16)
                            | ((head[off + 6] & 0xFF) << 8) | (head[off + 7] & 0xFF);
                    if (len <= 0) {
                        break;
                    }
                    if ("APIC".equals(id)) {
                        return "自检：ID3v2 APIC 内嵌图 " + Math.max(0, len - 4) + " B（标签 " + size + " B）";
                    }
                    off += 10 + len;
                }
                return "自检：标签内无 APIC（标签 " + size + " B）";
            }
            return "自检：容器 " + kind + " 不注入封面";
        } catch (Throwable t) {
            return "自检失败：" + t;
        }
    }

    private static int readU32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) {
                break;
            }
            off += r;
        }
        return off;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        byte[] tmp = new byte[16 * 1024];
        long left = n;
        while (left > 0) {
            int r = in.read(tmp, 0, (int) Math.min(tmp.length, left));
            if (r < 0) {
                break;
            }
            left -= r;
        }
    }

    private static long copyRest(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0L;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_AUDIO_BYTES) {
                throw new IOException("音频超过单曲上限 " + MAX_AUDIO_BYTES + " B");
            }
            out.write(buf, 0, n);
        }
        return total;
    }
}
