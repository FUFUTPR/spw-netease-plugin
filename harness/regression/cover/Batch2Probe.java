import com.example.netease.core.DataPaths;
import com.example.netease.core.Json;
import com.example.netease.core.PluginLog;
import com.example.netease.svc.CoverArt;
import com.example.netease.svc.CoverStore;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 0.11.53 批次 2（F3 + F4 + F5）离线沙箱探针（不装机、不碰真机数据）。
 *
 * <p>用法：java -cp &lt;probeBuild&gt;;&lt;pluginClasses&gt;;&lt;hostApiJars&gt; Batch2Probe &lt;sandboxRoot&gt; &lt;label&gt; &lt;phase&gt;
 * —— sandboxRoot 由启动方写入环境变量 APPDATA（充当 %APPDATA%，隔离真机宿主数据目录）；
 * phase = f3（writeStub 带歌手）| f4（巡检按索引作废）| f5（补挂账本复合键）。</p>
 *
 * <p>退出码：0 = 该 phase 全部 PASS；1 = 有 FAIL / 环境不满足。</p>
 */
public final class Batch2Probe {

    private static int pass;
    private static int fail;
    private static Path logsDir;

    public static void main(String[] args) throws Exception {
        Path sandbox = Path.of(args[0]).toAbsolutePath();
        String label = args.length > 1 ? args[1] : "unknown";
        String phase = args.length > 2 ? args[2] : "all";
        String appdata = System.getenv("APPDATA");
        System.out.println("=== Batch2Probe（F3 + F4 + F5）phase=" + phase + " 被测=" + label + " 沙箱=" + sandbox + " ===");
        if (appdata == null || !Path.of(appdata).toAbsolutePath().equals(sandbox)) {
            System.out.println("[FAIL] 沙箱隔离不成立：APPDATA=" + appdata + "（必须等于 " + sandbox + "，拒绝污染真机）");
            System.exit(1);
        }

        DataPaths.init("com.example.netease");
        PluginLog.init(DataPaths.logs());
        logsDir = DataPaths.logs();

        switch (phase) {
            case "f3" -> f3WriteStubArtist();
            case "f4" -> f4SweepByIndex();
            case "f5" -> f5ReassertCompositeKey();
            default -> {
                System.out.println("[FAIL] 未知 phase：" + phase);
                fail++;
            }
        }

        System.out.println();
        System.out.println("结果：" + pass + " PASS / " + fail + " FAIL");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ F3

    /** F3：writeStub 带歌手 —— 下载路径与占位路径的引用都记 refKey(专辑, 歌手)，不再新增「专辑 + 空歌手」键。 */
    private static void f3WriteStubArtist() throws Exception {
        String albumA = "探针1153专辑下载";
        String artistA = "探针1153歌手下载";
        byte[] jpeg = imageBytes(1200, 1200, "jpg");
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cover1153.jpg", ex -> {
            ex.getResponseHeaders().add("Content-Type", "image/jpeg");
            ex.sendResponseHeaders(200, jpeg.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(jpeg);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/cover1153.jpg";
            String uri = CoverStore.ensure(albumA, artistA, url);
            check("F3a-1 下载路径造桩成功（file:// uri，0.11.52 同）", uri != null && uri.startsWith("file:"), "uri=" + uri);
            Map<String, Object> refs = refsMap();
            String k = CoverStore.refKey(albumA, artistA);
            Object rec = refs.get(k);
            check("F3a-2 引用记为 refKey(专辑,歌手) 且 b=歌手、带桩名",
                    rec instanceof Map<?, ?> m && artistA.equals(String.valueOf(m.get("b")))
                            && m.get("s") != null && String.valueOf(m.get("s")).startsWith("cover-stub-"),
                    "key 在=" + refs.containsKey(k));
            check("F3a-3 未新增「专辑 + 空歌手」歧义键（0.11.52 会新增）",
                    !refs.containsKey(CoverStore.refKey(albumA, "")),
                    "空歌手键存在=" + refs.containsKey(CoverStore.refKey(albumA, "")));
        } finally {
            server.stop(0);
        }

        String albumB = "探针1153专辑占位";
        String artistB = "探针1153歌手占位";
        String uri2 = CoverStore.ensure(albumB, artistB, "");
        check("F3b-1 占位路径造桩成功（无图 URL ⇒ 占位）（0.11.52 同）",
                uri2 != null && uri2.startsWith("file:"), "uri=" + uri2);
        Map<String, Object> refs2 = refsMap();
        String k2 = CoverStore.refKey(albumB, artistB);
        Object rec2 = refs2.get(k2);
        check("F3b-2 占位引用记为 refKey(专辑,歌手) 且 b=歌手",
                rec2 instanceof Map<?, ?> m && artistB.equals(String.valueOf(m.get("b"))),
                "key 在=" + refs2.containsKey(k2));
        check("F3b-3 占位路径也未新增「专辑 + 空歌手」歧义键（0.11.52 会新增）",
                !refs2.containsKey(CoverStore.refKey(albumB, "")),
                "空歌手键存在=" + refs2.containsKey(CoverStore.refKey(albumB, "")));
    }

    // ------------------------------------------------------------------ F4

    /**
     * F4：巡检按索引键作废 —— 沙箱预置 2 张 500² 坏图（各带索引；一张有引用、一张无引用 ⇒ 孤儿桩兜底）
     * + 1 张 1200² 好图（同名专辑另一歌手），启动触发 sweepWrongSize 后核对「索引/引用/桩/图」四本账。
     */
    private static void f4SweepByIndex() throws Exception {
        String albumA = "探针1153专辑甲";
        String albumB = "探针1153专辑乙";
        String artistBad = "探针1153歌手坏";
        String artistSib = "探针1153歌手好";
        String urlBad = "http://127.0.0.1:9/bad1153.jpg?param=1200y1200";
        String urlSib = "http://127.0.0.1:9/sib1153.jpg?param=1200y1200";
        String urlOrphan = "http://127.0.0.1:9/orphan1153.jpg?param=1200y1200";

        Path img = CoverStore.imgDir();
        Path stubDir = CoverStore.stubDir();
        Files.createDirectories(img);
        Files.createDirectories(stubDir);
        Files.createDirectories(CoverStore.placeholderDir());
        Path bad = img.resolve("bad-1153.png");
        Path good = img.resolve("good-1153.png");
        Path orphan = img.resolve("orphan-1153.jpg");
        Files.write(bad, imageBytes(500, 500, "png"));
        Files.write(good, imageBytes(1200, 1200, "png"));
        Files.write(orphan, imageBytes(500, 500, "jpg"));

        Method idxOf = CoverStore.class.getDeclaredMethod("idxOf", String.class, String.class);
        idxOf.setAccessible(true);
        String idxBad = (String) idxOf.invoke(null, albumA, urlBad);
        String idxSib = (String) idxOf.invoke(null, albumA, urlSib);
        String idxOrphan = (String) idxOf.invoke(null, albumB, urlOrphan);
        String stubBad = "cover-stub-" + idxBad + ".flac";
        String stubSib = "cover-stub-" + idxSib + ".flac";
        String stubOrphan = "cover-stub-" + idxOrphan + ".flac";
        Files.writeString(stubDir.resolve(stubBad), "stub-bad");
        Files.writeString(stubDir.resolve(stubSib), "stub-sib");
        Files.writeString(stubDir.resolve(stubOrphan), "stub-orphan");

        Map<String, Object> index = new LinkedHashMap<>();
        index.put(idxBad, indexRec(albumA, urlBad, "bad-1153.png"));
        index.put(idxSib, indexRec(albumA, urlSib, "good-1153.png"));
        index.put(idxOrphan, indexRec(albumB, urlOrphan, "orphan-1153.jpg"));
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put(CoverStore.refKey(albumA, artistBad), refRec(albumA, artistBad, urlBad, stubBad));
        refs.put(CoverStore.refKey(albumA, artistSib), refRec(albumA, artistSib, urlSib, stubSib));
        Path idxPath = privatePath("indexPath");
        Path refsPath = privatePath("refsPath");
        Json.writeObject(idxPath, index);
        Json.writeObject(refsPath, refs);

        CoverStore.init();                       // migrateLegacy + load + sweepWrongSize（一次性）

        String log = logText();
        check("F4a 巡检日志「摘除索引 2 条 / 作废引用 1 条 / 删除桩 2 张」（0.11.52 = 作废桩 0 张）",
                log.contains("摘除索引 2 条 / 作废引用 1 条 / 删除桩 2 张"), snippet(logLine(log, "封面尺寸巡检")));
        Map<String, Object> indexNow = mapField("INDEX");
        Map<String, Object> refsNow = mapField("REFS");
        check("F4b 坏索引两条都摘除（有引用的 + 孤儿），兄弟索引保留",
                !indexNow.containsKey(idxBad) && !indexNow.containsKey(idxOrphan) && indexNow.containsKey(idxSib),
                "bad=" + indexNow.containsKey(idxBad) + " orphan=" + indexNow.containsKey(idxOrphan)
                        + " sib=" + indexNow.containsKey(idxSib));
        check("F4c 复合键引用被摘除（0.11.52 按纯专辑名摘 ⇒ 永远打不中、仍在）",
                !refsNow.containsKey(CoverStore.refKey(albumA, artistBad)),
                "key 在=" + refsNow.containsKey(CoverStore.refKey(albumA, artistBad)));
        check("F4d 同名专辑另一歌手的引用与桩不受牵连（误伤 = 0）",
                refsNow.containsKey(CoverStore.refKey(albumA, artistSib)) && Files.exists(stubDir.resolve(stubSib)),
                "ref 在=" + refsNow.containsKey(CoverStore.refKey(albumA, artistSib)) + " 桩在=" + Files.exists(stubDir.resolve(stubSib)));
        check("F4e 坏桩 + 孤儿桩都删除（孤儿兜底），兄弟桩保留",
                !Files.exists(stubDir.resolve(stubBad)) && !Files.exists(stubDir.resolve(stubOrphan))
                        && Files.exists(stubDir.resolve(stubSib)),
                "bad=" + Files.exists(stubDir.resolve(stubBad)) + " orphan=" + Files.exists(stubDir.resolve(stubOrphan))
                        + " sib=" + Files.exists(stubDir.resolve(stubSib)));
        check("F4f 坏图清除、好图保留（既有行为不回归）",
                !Files.exists(bad) && !Files.exists(orphan) && Files.exists(good),
                "bad=" + Files.exists(bad) + " orphan=" + Files.exists(orphan) + " good=" + Files.exists(good));
        Map<String, Object> refsDisk = Json.readObject(refsPath);
        Map<String, Object> indexDisk = Json.readObject(idxPath);
        check("F4g 摘除结果已落盘（cover-refs.json / cover-index.json）",
                !refsDisk.containsKey(CoverStore.refKey(albumA, artistBad))
                        && refsDisk.containsKey(CoverStore.refKey(albumA, artistSib))
                        && !indexDisk.containsKey(idxBad) && indexDisk.containsKey(idxSib),
                "refs 磁盘键数=" + refsDisk.size() + " index 磁盘键数=" + indexDisk.size());
    }

    // ------------------------------------------------------------------ F5

    /** F5：补挂账本按 refKey(专辑,歌手) 作键 —— 同名专辑两个歌手并存，各自映射自己的图。 */
    @SuppressWarnings("unchecked")
    private static void f5ReassertCompositeKey() throws Exception {
        Class<?> np = Class.forName("com.example.netease.NeteasePlugin");
        Field f = np.getDeclaredField("REASSERT");
        f.setAccessible(true);
        Map<String, Object> reassert = (Map<String, Object>) f.get(null);
        reassert.clear();
        Method merge = np.getDeclaredMethod("mergeReassert", List.class);
        merge.setAccessible(true);

        String album = "探针1153同名专辑";
        String artist1 = "探针1153歌手甲";
        String artist2 = "探针1153歌手乙";
        CoverArt.Cover c1 = new CoverArt.Cover(album, artist1, "file:///probe1153-a.flac");
        CoverArt.Cover c2 = new CoverArt.Cover(album, artist2, "file:///probe1153-b.flac");
        merge.invoke(null, new ArrayList<>(List.of(c1, c2)));

        String k1 = CoverStore.refKey(album, artist1);
        String k2 = CoverStore.refKey(album, artist2);
        check("F5a 同名专辑两个歌手各自入账（0.11.52 按专辑名去重 ⇒ size=1）",
                reassert.size() == 2, "size=" + reassert.size());
        check("F5b 两条账的键都是 refKey(专辑,歌手)",
                reassert.containsKey(k1) && reassert.containsKey(k2), "键=" + reassert.keySet());
        Object v1 = reassert.get(k1);
        Object v2 = reassert.get(k2);
        check("F5c 各自映射自己的图（不互相覆盖）",
                v1 instanceof CoverArt.Cover a && v2 instanceof CoverArt.Cover b
                        && a.fileUri().endsWith("-a.flac") && b.fileUri().endsWith("-b.flac"),
                "a=" + (v1 instanceof CoverArt.Cover a ? a.fileUri() : "null")
                        + " b=" + (v2 instanceof CoverArt.Cover b ? b.fileUri() : "null"));
        reassert.clear();
    }

    // ------------------------------------------------------------------ 工具

    private static byte[] imageBytes(int w, int h, String fmt) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x3366AA));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, fmt, bos);
        return bos.toByteArray();
    }

    private static Map<String, Object> indexRec(String album, String url, String imgName) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("a", album);
        rec.put("u", url);
        rec.put("i", imgName);
        rec.put("n", 0L);
        rec.put("p", false);
        rec.put("t", System.currentTimeMillis());
        return rec;
    }

    private static Map<String, Object> refRec(String album, String artist, String url, String stub) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("b", artist);
        rec.put("a", album);
        rec.put("u", url);
        rec.put("s", stub);
        rec.put("t", System.currentTimeMillis());
        return rec;
    }

    private static Path privatePath(String name) throws Exception {
        Method m = CoverStore.class.getDeclaredMethod(name);
        m.setAccessible(true);
        return (Path) m.invoke(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapField(String name) throws Exception {
        Field f = CoverStore.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Map<String, Object>) f.get(null);
    }

    private static Map<String, Object> refsMap() throws Exception {
        return mapField("REFS");
    }

    private static String logText() throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> s = Files.list(logsDir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                sb.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return sb.toString();
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
        return s.length() <= 150 ? s : s.substring(0, 150) + "…";
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
