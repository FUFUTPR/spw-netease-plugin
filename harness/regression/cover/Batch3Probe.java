import com.example.netease.core.DataPaths;
import com.example.netease.core.Hashes;
import com.example.netease.core.PluginLog;
import com.example.netease.svc.CoverPrimer;
import com.example.netease.svc.CoverStore;
import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * 0.11.54 批次 3（F6 + F7）离线沙箱探针（不装机、不碰真机数据）。
 *
 * <p>用法：java -cp &lt;probeBuild&gt;;&lt;pluginClasses&gt;;&lt;sqliteJdbc&gt;;&lt;hostApiJars&gt; Batch3Probe &lt;sandboxRoot&gt; &lt;label&gt; &lt;phase&gt;
 * —— sandboxRoot 由启动方写入环境变量 APPDATA（充当 %APPDATA%，隔离真机宿主数据目录）；
 * phase = f6（221 字超长名归一截断对齐）| f7（陈旧键不被 WROTE_KEYS 短路）。</p>
 *
 * <p>假宿主：沙箱内建 SQLite {@code spw.db}（Track 一行）+ coil DiskCache 替身（读写真实文件
 * {@code cache\shared_cover\<sha256(key)>.1}），让 deliverSizes 走完整路径而不碰真机。</p>
 *
 * <p>退出码：0 = 该 phase 全部 PASS；1 = 有 FAIL / 环境不满足。</p>
 */
public final class Batch3Probe {

    private static int pass;
    private static int fail;
    private static Path logsDir;

    public static void main(String[] args) throws Exception {
        Path sandbox = Path.of(args[0]).toAbsolutePath();
        String label = args.length > 1 ? args[1] : "unknown";
        String phase = args.length > 2 ? args[2] : "all";
        String appdata = System.getenv("APPDATA");
        System.out.println("=== Batch3Probe（F6 + F7）phase=" + phase + " 被测=" + label + " 沙箱=" + sandbox + " ===");
        if (appdata == null || !Path.of(appdata).toAbsolutePath().equals(sandbox)) {
            System.out.println("[FAIL] 沙箱隔离不成立：APPDATA=" + appdata + "（必须等于 " + sandbox + "，拒绝污染真机）");
            System.exit(1);
        }
        DataPaths.init("com.example.netease");
        PluginLog.init(DataPaths.logs());
        logsDir = DataPaths.logs();

        Path hostDir = sandbox.resolve("Salt Player for Windows");
        Path db = hostDir.resolve("spw.db");
        Path cache = hostDir.resolve("cache").resolve("shared_cover");
        Files.createDirectories(cache);

        switch (phase) {
            case "f6" -> f6Truncation(db, cache);
            case "f7" -> f7StaleRewrite(db, cache);
            default -> {
                System.out.println("[FAIL] 未知 phase：" + phase);
                fail++;
            }
        }

        System.out.println();
        System.out.println("结果：" + pass + " PASS / " + fail + " FAIL");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ F6

    /** F6：221 字超长名 —— 宿主库写的是 220 截断名，引用键口径对齐后仍要命中、可投递。 */
    private static void f6Truncation(Path db, Path cache) throws Exception {
        String album221 = "L" + "甲".repeat(219) + "尾";
        String artist221 = "歌" + "乙".repeat(219) + "尾";
        String albumDb = album221.substring(0, 220);
        String artistDb = artist221.substring(0, 220);

        Method text = CoverStore.class.getDeclaredMethod("text", String.class, String.class);
        text.setAccessible(true);
        check("F6a text 保留 trim（\"  x  \" → \"x\"）",
                "x".equals(text.invoke(null, "  x  ", "F")), "text=「" + text.invoke(null, "  x  ", "F") + "」");
        check("F6b text 超 220 截断（221 → 220；0.11.53 = 221）",
                String.valueOf(text.invoke(null, album221, "F")).length() == 220,
                "len=" + String.valueOf(text.invoke(null, album221, "F")).length());
        check("F6c text 边界：220 不动、null/空白走兜底",
                String.valueOf(text.invoke(null, "F".repeat(220), "Z")).length() == 220
                        && "F".equals(text.invoke(null, null, "F"))
                        && "F".equals(text.invoke(null, "   ", "F")),
                "220 边界长度=" + String.valueOf(text.invoke(null, "F".repeat(220), "Z")).length());

        String keyFull = CoverStore.refKey(album221, artist221);
        String keyDb = CoverStore.refKey(albumDb, artistDb);
        int sep = keyFull.indexOf('\u0001');
        check("F6d refKey 的专辑/歌手项都截断 220（全长 443 → 441；0.11.53 = 443）",
                keyFull.equals(keyDb) && keyFull.length() == 441 && sep == 220
                        && keyFull.substring(sep + 1).length() == 220,
                "keyLen=" + keyFull.length() + " sep=" + sep);

        HttpServer server = serve("/f6-1154.jpg", imageBytes(1200, 1200, new Color(0x3366AA), "jpg"), "image/jpeg");
        try {
            String uri = CoverStore.ensure(album221, artist221, serverUrl(server, "/f6-1154.jpg"));
            check("F6e 221 字名下载路径造桩成功", uri != null && uri.startsWith("file:"), "uri=" + uri);
            check("F6f refKeyOfTrack 侧命中引用（has(截断名键)；0.11.53 = false）",
                    CoverStore.has(keyDb), "has=" + CoverStore.has(keyDb));
            installFakeHost(db, cache, 115401L, albumDb, artistDb, "http://127.0.0.1:1/f6.flac", 2222L, 1);
            int n = deliver(115401L, 96);
            String log = logText();
            check("F6g 行封面可投递（写键 ≥1 +「档键就位」；0.11.53 = 0 + 取不到封面图）",
                    n >= 1 && log.contains("档键就位") && !log.contains("取不到封面图"),
                    "n=" + n + " ｜ " + snippet(logLine(log, "封面投递 #115401")));
        } finally {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------ F7

    /** F7：会话中途换图 —— 第二次投递必须判陈旧并按新图重写，不被「本会话已写」短路。 */
    private static void f7StaleRewrite(Path db, Path cache) throws Exception {
        String album = "探针1154专辑F7";
        String artist = "探针1154歌手F7";

        byte[] imageA = imageBytes(1200, 1200, new Color(0x3366AA), "jpg");
        HttpServer server = serve("/f7-1154.jpg", imageA, "image/jpeg");
        String uri;
        List<Path> before = listFiles(CoverStore.imgDir());
        try {
            uri = CoverStore.ensure(album, artist, serverUrl(server, "/f7-1154.jpg"));
        } finally {
            server.stop(0);
        }
        Path img = diffNew(before, CoverStore.imgDir());
        check("F7a 造桩成功且定位到原图", uri != null && uri.startsWith("file:") && img != null,
                "uri=" + uri + " img=" + img);

        installFakeHost(db, cache, 115402L, album, artist, "http://127.0.0.1:1/f7.flac", 3333L, 2);
        addTrack(db, 115403L, album, artist, "http://127.0.0.1:1/f7.flac", 3333L, 2);
        addTrack(db, 115404L, album, artist, "http://127.0.0.1:1/f7.flac", 3333L, 2);
        int n1 = deliver(115402L, 96);
        Path data = only(cache, ".1");
        byte[] oldBytes = data == null ? null : Files.readAllBytes(data);
        byte[] wantOld = thumb(imageA, 96);
        check("F7b 第一次投递写键（新写；0.11.53 同）",
                n1 >= 1 && data != null && Arrays.equals(oldBytes, wantOld) && logText().contains("新写"),
                "n=" + n1 + " 字节=" + (oldBytes == null ? -1 : oldBytes.length) + " 期望=" + wantOld.length);

        // 会话中途换图：原图内容换成 B、mtime 抬到「现在」；已写键的 mtime 压回 60 s 前
        byte[] imageB = patternedImage(1200, 1200);
        Files.write(img, imageB);
        Files.setLastModifiedTime(img, FileTime.fromMillis(System.currentTimeMillis()));
        Files.setLastModifiedTime(data, FileTime.fromMillis(System.currentTimeMillis() - 60_000L));

        int beforeLen = logText().length();
        int n2 = deliver(115403L, 96);            // 同专辑同键，只是换了个曲目 id（避开日志指纹去重）
        String log2 = safeSubstring(logText(), beforeLen);
        byte[] newBytes = Files.readAllBytes(data);
        byte[] wantNew = thumb(imageB, 96);
        check("F7c 陈旧 ⇒ 重写（日志「⇒ 重写（忽略本会话已写兜底）」；0.11.53 = 本会话已写（跳过））",
                n2 >= 1 && log2.contains("陈旧（图 mtime ") && log2.contains("⇒ 重写（忽略本会话已写兜底）")
                        && log2.contains("新写") && !log2.contains("本会话已写（跳过）"),
                "n=" + n2 + " ｜ " + snippet(logLine(log2, "封面投递 #115403")));
        check("F7d 宿主缓存字节 = 新图缩略图（不是旧图；0.11.53 = 旧图残留）",
                Arrays.equals(newBytes, wantNew) && !Arrays.equals(newBytes, oldBytes),
                "now=" + newBytes.length + " 期望=" + wantNew.length + " 旧=" + oldBytes.length);

        int beforeLen3 = logText().length();
        long mtime3 = Files.getLastModifiedTime(data).toMillis();
        int n3 = deliver(115404L, 96);
        String log3 = safeSubstring(logText(), beforeLen3);
        check("F7e 非陈旧重复投递仍命中跳过（幂等短路保留，重写不成为常态）",
                n3 >= 1 && log3.contains("命中（跳过") && !log3.contains("⇒ 重写")
                        && Files.getLastModifiedTime(data).toMillis() == mtime3,
                "n=" + n3 + " ｜ " + snippet(logLine(log3, "封面投递 #115404")));
    }

    // ------------------------------------------------------------------ 假宿主

    /** 沙箱假宿主：SQLite 曲库一行 + coil DiskCache 替身（真实文件落 cacheDir）。 */
    private static void installFakeHost(Path db, Path cache, long songId, String album, String artist,
                                        String path, long size, int rev) throws Exception {
        addTrack(db, songId, album, artist, path, size, rev);
        Path hostRow = CoverPrimer.hostDb();
        check("假宿主曲库就位且 hostDb() 解析到沙箱（防真机污染）",
                Files.isRegularFile(db) && hostRow != null && hostRow.toAbsolutePath().equals(db.toAbsolutePath()),
                "resolved=" + hostRow);
        Path cacheDir = CoverPrimer.cacheDir();
        check("cacheDir() 解析到沙箱缓存（陈旧判据有落点）",
                cacheDir != null && cacheDir.toAbsolutePath().equals(cache.toAbsolutePath()),
                "cacheDir=" + cacheDir);
        Class<?> cd = Class.forName("com.example.netease.svc.CoverDelivery");
        setStatic(cd, "cachedDisk", new FakeDisk(cache));
        setStatic(cd, "mOpenEditor", FakeDisk.class.getMethod("openEditor", String.class));
        setStatic(cd, "mOpenSnapshot", FakeDisk.class.getMethod("openSnapshot", String.class));
        setStatic(cd, "mGetData", FakeEditor.class.getMethod("getData"));
        setStatic(cd, "mCommit", FakeEditor.class.getMethod("commit"));
    }

    /** 往假曲库插一行 Track（同专辑多曲目 id ⇒ 同键，用来避开日志指纹去重）。 */
    private static void addTrack(Path db, long songId, String album, String artist,
                                 String path, long size, int rev) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (var s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS Track (id TEXT PRIMARY KEY, album TEXT, artist TEXT, "
                        + "path TEXT, size INTEGER, coverRevision INTEGER)");
            }
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT OR REPLACE INTO Track(id, album, artist, path, size, coverRevision) VALUES(?,?,?,?,?,?)")) {
                p.setString(1, "netease-" + songId);
                p.setString(2, album);
                p.setString(3, artist);
                p.setString(4, path);
                p.setLong(5, size);
                p.setInt(6, rev);
                p.executeUpdate();
            }
        }
    }

    /** coil RealDiskCache 的替身：键 → {@code <cacheDir>\<sha256(key)>.1}（与 staleKey 同址）。 */
    public static final class FakeDisk {
        private final Path dir;

        FakeDisk(Path dir) {
            this.dir = dir;
        }

        Path dataFile(String key) {
            return dir.resolve(Hashes.sha256Hex(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".1");
        }

        public Object openEditor(String key) {
            return new FakeEditor(dataFile(key));
        }

        public Object openSnapshot(String key) {
            Path f = dataFile(key);
            return Files.isRegularFile(f) ? new FakeSnap(f) : null;
        }
    }

    public static final class FakeEditor {
        private final Path file;

        FakeEditor(Path file) {
            this.file = file;
        }

        public Path getData() {
            return file;
        }

        public Object commit() {
            return new FakeSnap(file);
        }
    }

    public static final class FakeSnap {
        private final Path file;

        FakeSnap(Path file) {
            this.file = file;
        }

        public Path getData() {
            return file;
        }
    }

    private static int deliver(long songId, int size) throws Exception {
        Class<?> cd = Class.forName("com.example.netease.svc.CoverDelivery");
        Method m = cd.getDeclaredMethod("deliverSizes", long.class, int[].class);
        m.setAccessible(true);
        return (Integer) m.invoke(null, songId, new int[]{size});
    }

    private static void setStatic(Class<?> c, String name, Object v) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, v);
    }

    private static byte[] thumb(byte[] src, int size) throws Exception {
        Method m = CoverPrimer.class.getDeclaredMethod("thumb", byte[].class, int.class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, src, size);
    }

    // ------------------------------------------------------------------ 工具

    private static HttpServer serve(String ctx, byte[] body, String mime) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ctx, ex -> {
            ex.getResponseHeaders().add("Content-Type", mime);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return server;
    }

    private static String serverUrl(HttpServer server, String ctx) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + ctx;
    }

    private static byte[] imageBytes(int w, int h, Color color, String fmt) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, fmt, bos);
        return bos.toByteArray();
    }

    /** 换图用图案（条纹）——与实色图 A 的缩略图字节必然不同（防「同色同字节」巧合）。 */
    private static byte[] patternedImage(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0xCC3344));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.WHITE);
        for (int x = 0; x < w; x += 120) {
            g.fillRect(x, 0, 48, h);
        }
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private static List<Path> listFiles(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> out = new ArrayList<>(s.filter(Files::isRegularFile).toList());
            out.sort(java.util.Comparator.comparing(Path::toString));
            return out;
        }
    }

    private static Path diffNew(List<Path> before, Path dir) throws Exception {
        List<Path> now = listFiles(dir);
        now.removeAll(before);
        return now.isEmpty() ? null : now.get(0);
    }

    private static Path only(Path dir, String suffix) throws Exception {
        List<Path> all = new ArrayList<>();
        for (Path p : listFiles(dir)) {
            if (p.getFileName().toString().endsWith(suffix)) {
                all.add(p);
            }
        }
        return all.size() == 1 ? all.get(0) : null;
    }

    private static String logText() throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> s = Files.list(logsDir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                sb.append(Files.readString(p, java.nio.charset.StandardCharsets.UTF_8)).append('\n');
            }
        }
        return sb.toString();
    }

    private static String safeSubstring(String s, int from) {
        return from >= 0 && from <= s.length() ? s.substring(from) : s;
    }

    private static String logLine(String log, String needle) {
        for (String line : log.split("\n")) {
            if (line.contains(needle)) {
                return line;
            }
        }
        return "（日志里没有「" + needle + "」）";
    }

    private static String snippet(String s) {
        return s.length() <= 170 ? s : s.substring(0, 170) + "…";
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name + (detail.isEmpty() ? "" : "  | " + detail));
        } else {
            fail++;
            System.out.println("[FAIL] " + name + (detail.isEmpty() ? "" : "  | " + detail));
        }
    }
}
