package com.example.netease.ui;

import com.example.netease.core.DataPaths;
import com.example.netease.core.PluginLog;
import com.example.netease.net.Dto;
import com.example.netease.svc.CoverArt;
import com.example.netease.svc.CoverStore;
import com.example.netease.svc.NativeLibrary;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 0.11.55 批次 4（F8）离线沙箱探针（不装机、不碰真机数据）。
 *
 * <p>用法：java -cp &lt;probeBuild&gt;;&lt;pluginClasses&gt;;&lt;sqliteJdbc&gt;;&lt;hostApiJars&gt;
 * com.example.netease.ui.Batch4Probe &lt;sandboxRoot&gt; &lt;label&gt; &lt;phase&gt;
 * —— sandboxRoot 由启动方写入环境变量 APPDATA（充当 %APPDATA%，隔离真机宿主数据目录）；
 * phase = f8a（NO_SRC 无人读记账已删 + 无图源占位终态回归）| f8b（面板失败负缓存自愈）。</p>
 *
 * <p>探针放在 {@code com.example.netease.ui} 包内：{@code CoverCache} 是包私有类，同包才能直呼
 * {@code get/onLoaded}；其余（CoverStore 私有字段、INDEX、pending、sink）走反射。</p>
 *
 * <p>退出码：0 = 该 phase 全部 PASS；1 = 有 FAIL / 环境不满足。</p>
 */
public final class Batch4Probe {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        Path sandbox = Path.of(args[0]).toAbsolutePath();
        String label = args.length > 1 ? args[1] : "unknown";
        String phase = args.length > 2 ? args[2] : "all";
        String appdata = System.getenv("APPDATA");
        System.out.println("=== Batch4Probe（F8）phase=" + phase + " 被测=" + label + " 沙箱=" + sandbox + " ===");
        if (appdata == null || !Path.of(appdata).toAbsolutePath().equals(sandbox)) {
            System.out.println("[FAIL] 沙箱隔离不成立：APPDATA=" + appdata + "（必须等于 " + sandbox + "，拒绝污染真机）");
            System.exit(1);
        }
        DataPaths.init("com.example.netease");
        PluginLog.init(DataPaths.logs());

        switch (phase) {
            case "f8a" -> f8a();
            case "f8b" -> f8b();
            default -> {
                System.out.println("[FAIL] 未知 phase：" + phase);
                fail++;
            }
        }

        System.out.println();
        System.out.println("结果：" + pass + " PASS / " + fail + " FAIL");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ F8a

    /** F8a：无人读的 NO_SRC 记账删除（反射判据）+ 无图源专辑仍走「占位即终态」的回归。 */
    private static void f8a() throws Exception {
        boolean fieldGone;
        String fieldNote;
        try {
            Field f = CoverStore.class.getDeclaredField("NO_SRC");
            fieldGone = false;
            fieldNote = "字段仍在（" + f.getType().getSimpleName() + "）；0.11.54 对照应如此";
        } catch (NoSuchFieldException e) {
            fieldGone = true;
            fieldNote = "NoSuchFieldException（已删）";
        }
        check("F8a-1 CoverStore 已删无人读的 NO_SRC 字段（0.11.54 仍在 ⇒ FAIL）", fieldGone, fieldNote);

        String album = "探针1155无图源专辑";
        String artist = "探针1155歌手";
        CoverStore.plan(List.of(new NativeLibrary.Entry(1155L, "探针1155歌单", List.of(
                new Dto.Song(115501L, "无图源曲", artist, album, 1000L, 0, true, "")))));
        String refKey = CoverStore.refKey(album, artist);
        check("F8a-2 plan 登记无图源引用（picUrl 空）", refsContains(refKey), "refs=" + refsContains(refKey));

        String uri = CoverStore.ensure(album, artist, "");
        Path stub = uri == null || !uri.startsWith("file:")
                ? null : CoverStore.stubDir().resolve(uri.substring(uri.lastIndexOf('/') + 1));
        check("F8a-3 无图源走占位终态（桩落盘 + 引用命中 + 索引记 placeholder-）",
                stub != null && Files.isRegularFile(stub) && CoverStore.has(refKey) && placeholderOfStub(stub),
                "uri=" + uri);

        int pending = pendingSize();
        check("F8a-4 占位后 pending() 不再列出该专辑（终态、省一轮索引计算）", pending == 0, "pending=" + pending);
    }

    // ------------------------------------------------------------------ F8b

    /** F8b：面板失败负缓存自愈 —— 同纪元压制；有新批次（epoch 前进）或超 TTL 立即放行重试。 */
    private static void f8b() throws Exception {
        byte[] jpeg = imageBytes(1200, 1200, new Color(0x226644));
        byte[] notFound = "not found".getBytes(StandardCharsets.UTF_8);
        AtomicInteger n1 = new AtomicInteger();
        AtomicInteger n2 = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/flaky-1155.jpg", ex -> {
            int n = n1.incrementAndGet();
            if (n == 1) {
                ex.sendResponseHeaders(404, notFound.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(notFound);
                }
            } else {
                ex.getResponseHeaders().add("Content-Type", "image/jpeg");
                ex.sendResponseHeaders(200, jpeg.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(jpeg);
                }
            }
        });
        server.createContext("/flaky2-1155.jpg", ex -> {
            n2.incrementAndGet();
            ex.sendResponseHeaders(404, notFound.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(notFound);
            }
        });
        server.start();
        String u1 = "http://127.0.0.1:" + server.getAddress().getPort() + "/flaky-1155.jpg";
        String u2 = "http://127.0.0.1:" + server.getAddress().getPort() + "/flaky2-1155.jpg";
        try {
            CoverCache.get(u1, 96);
            boolean failed = await(() -> failedContains(96 + "|" + u1), 8000);
            check("F8b-1 首取失败记负缓存（1 次请求）", failed && n1.get() == 1,
                    "failed=" + failed + " 请求=" + n1.get());

            CoverCache.get(u1, 96);
            Thread.sleep(300L);
            check("F8b-2 同纪元内负缓存压制重试（请求数不变）", n1.get() == 1, "请求=" + n1.get());

            long e0 = doneEpochOrMinus();
            installSink();
            CoverStore.deliverNow(new CoverArt.Cover("探针1155甲", "探针1155乙", "file:///probe-1155"));
            long e1 = doneEpochOrMinus();
            check("F8b-3 deliver 一批桩 ⇒ doneEpoch 前进（0.11.54 = 无此 API）",
                    e0 >= 0 && e1 == e0 + 1, "e0=" + e0 + " e1=" + e1);

            CoverCache.get(u1, 96);
            boolean retried = await(() -> n1.get() == 2, 8000);
            check("F8b-4 新批次落地后自愈重试（请求 1→2）", retried, "请求=" + n1.get());
            boolean icon = await(() -> CoverCache.get(u1, 96) != null, 8000);
            check("F8b-5 先失败后成功的 URL 面板直接拿到图（无需重启面板）", icon, "icon=" + (CoverCache.get(u1, 96) != null));
            check("F8b-6 成功后负缓存已清", !failedContains(96 + "|" + u1), "");

            boolean ttlHooked = setFailTtl(150L);
            check("F8b-7 failTtlMs 可设（探针改小做时间路径；0.11.54 = 无此字段）", ttlHooked, "");
            if (ttlHooked) {
                CoverCache.get(u2, 96);
                boolean f2 = await(() -> failedContains(96 + "|" + u2), 8000);
                check("F8b-8 恒 404 首取失败记负缓存（1 次请求）", f2 && n2.get() == 1,
                        "failed=" + f2 + " 请求=" + n2.get());
                CoverCache.get(u2, 96);
                Thread.sleep(200L);
                check("F8b-9 TTL 未到不重试", n2.get() == 1, "请求=" + n2.get());
                Thread.sleep(400L);
                CoverCache.get(u2, 96);
                boolean retried2 = await(() -> n2.get() == 2, 8000);
                check("F8b-10 超过 TTL 自动重试（请求 1→2）", retried2, "请求=" + n2.get());
            }
        } finally {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------ 辅助

    /** 探针 Sink（只需存在；deliverNow 用它触发 doneEpoch，不真写宿主）。 */
    private static final class ProbeSink implements CoverStore.Sink {
        @Override
        public void onBatch(List<CoverArt.Cover> covers) {
        }
    }

    private static void installSink() throws Exception {
        Field f = CoverStore.class.getDeclaredField("sink");
        f.setAccessible(true);
        f.set(null, new ProbeSink());
    }

    private static long doneEpochOrMinus() {
        try {
            Method m = CoverStore.class.getDeclaredMethod("doneEpoch");
            m.setAccessible(true);
            return (Long) m.invoke(null);
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** CoverCache.FAILED 兼容两种形态：0.11.55 = Map（key→{epoch,at}）、0.11.54 = Set。 */
    private static boolean failedContains(String key) {
        try {
            Field f = CoverCache.class.getDeclaredField("FAILED");
            f.setAccessible(true);
            Object v = f.get(null);
            if (v instanceof Map<?, ?> m) {
                return m.containsKey(key);
            }
            return v instanceof Set<?> s && s.contains(key);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean setFailTtl(long ms) {
        try {
            Field f = CoverCache.class.getDeclaredField("failTtlMs");
            f.setAccessible(true);
            f.set(null, ms);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int pendingSize() throws Exception {
        Method m = CoverStore.class.getDeclaredMethod("pending");
        m.setAccessible(true);
        return ((List<?>) m.invoke(null)).size();
    }

    private static boolean refsContains(String key) {
        try {
            Field f = CoverStore.class.getDeclaredField("REFS");
            f.setAccessible(true);
            Object v = f.get(null);
            return v instanceof Map<?, ?> m && m.containsKey(key);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean placeholderOfStub(Path stub) throws Exception {
        String name = stub.getFileName().toString();
        if (!name.startsWith("cover-stub-") || !name.endsWith(".flac")) {
            return false;
        }
        String idx = name.substring("cover-stub-".length(), name.length() - ".flac".length());
        Field f = CoverStore.class.getDeclaredField("INDEX");
        f.setAccessible(true);
        Object v = f.get(null);
        if (!(v instanceof Map<?, ?> map)) {
            return false;
        }
        Object rec = map.get(idx);
        return rec instanceof Map<?, ?> m && String.valueOf(m.get("i")).startsWith("placeholder-");
    }

    private static boolean await(BooleanSupplier cond, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            try {
                if (cond.getAsBoolean()) {
                    return true;
                }
                Thread.sleep(50L);
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    private static byte[] imageBytes(int w, int h, Color color) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, w, h);
            g.setColor(Color.WHITE);
            g.fillOval(w / 4, h / 4, w / 2, h / 2);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128 * 1024);
        ImageIO.write(img, "jpg", bos);
        return bos.toByteArray();
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
