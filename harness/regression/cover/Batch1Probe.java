import com.example.netease.core.DataPaths;
import com.example.netease.core.HostBridgeWorker;
import com.example.netease.core.PluginLog;
import com.example.netease.host.VoxzenBridge;
import com.example.netease.svc.CoverStore;
import com.example.netease.svc.PlaybarCover;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * 0.11.52 批次 1（F1 + F2）离线沙箱探针（不装机、不碰真机数据）。
 *
 * <p>用法：java -Djava.awt.headless=true -cp &lt;probeBuild&gt;;&lt;pluginClasses&gt; Batch1Probe &lt;sandboxRoot&gt; &lt;label&gt;
 * —— sandboxRoot 由启动方写入环境变量 APPDATA（充当 %APPDATA%，隔离真机宿主数据目录）。</p>
 *
 * <p>退出码：0 = 全部 PASS；1 = 有 FAIL / 环境不满足。</p>
 */
public final class Batch1Probe {

    private static int pass;
    private static int fail;
    private static Path logsDir;

    public static void main(String[] args) throws Exception {
        Path sandbox = Path.of(args[0]).toAbsolutePath();
        String label = args.length > 1 ? args[1] : "unknown";
        String appdata = System.getenv("APPDATA");
        System.out.println("=== Batch1Probe（F1 + F2） 被测版本=" + label + " 沙箱=" + sandbox + " ===");
        if (appdata == null || !Path.of(appdata).toAbsolutePath().equals(sandbox)) {
            System.out.println("[FAIL] 沙箱隔离不成立：APPDATA=" + appdata + "（必须等于 " + sandbox + "，拒绝污染真机）");
            System.exit(1);
        }

        DataPaths.init("com.example.netease");
        PluginLog.init(DataPaths.logs());
        logsDir = DataPaths.logs();
        CoverStore.init();

        f1BuildReleasesInflight();
        f1SubmitPendingCycle();
        f2AuthUnknownGuard();
        f2WarnObservability();

        System.out.println();
        System.out.println("结果：" + pass + " PASS / " + fail + " FAIL");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** F1a：build() 结束时必须用「加入时的同一复合键」把 INFLIGHT 移除（0.11.51 用纯专辑名 → 恒不归零）。 */
    private static void f1BuildReleasesInflight() throws Exception {
        Set<String> inflight = inflight();
        Method refKey = CoverStore.class.getMethod("refKey", String.class, String.class);
        String album = "探针1152专辑A";
        String artist = "探针1152歌手A";
        String key = (String) refKey.invoke(null, album, artist);
        inflight.add(key);
        Method build = CoverStore.class.getDeclaredMethod("build", String.class, String.class, String.class);
        build.setAccessible(true);
        long t0 = System.currentTimeMillis();
        build.invoke(null, album, artist, "http://127.0.0.1:9/probe1152.jpg");
        check("F1a build 结束「在下载」归零（复合键移除；0.11.51 恒 1）", !inflight.contains(key),
                "耗时 " + (System.currentTimeMillis() - t0) + " ms，在下载=" + inflight.size());
    }

    /** F1b：整条 submitPending → 归零 → 退避到点再入池 的循环（真机「在下载」口径的行为级对照）。 */
    @SuppressWarnings("unchecked")
    private static void f1SubmitPendingCycle() throws Exception {
        Set<String> inflight = inflight();
        Method refKey = CoverStore.class.getMethod("refKey", String.class, String.class);
        Field refsF = CoverStore.class.getDeclaredField("REFS");
        refsF.setAccessible(true);
        Map<String, Object> refs = (Map<String, Object>) refsF.get(null);
        String album = "探针1152专辑B";
        String artist = "探针1152歌手B";
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("a", album);
        rec.put("b", artist);
        rec.put("u", "http://127.0.0.1:9/probe1152-b.jpg");
        rec.put("t", System.currentTimeMillis());
        refs.put((String) refKey.invoke(null, album, artist), rec);

        Field poolF = CoverStore.class.getDeclaredField("netPool");
        poolF.setAccessible(true);
        ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "probe1152-net");
            t.setDaemon(true);
            return t;
        });
        poolF.set(null, pool);

        Method submitPending = CoverStore.class.getDeclaredMethod("submitPending");
        submitPending.setAccessible(true);
        int n1 = (Integer) submitPending.invoke(null);
        check("F1b-1 submitPending 投出（加入键=refKey 复合键）", n1 >= 1, "n=" + n1);
        boolean emptied = waitUntil(() -> inflight.isEmpty(), 20_000L);
        check("F1b-2 本轮结束「在下载」归零（0.11.51 超时不为 0）", emptied, "在下载=" + inflight.size());

        String st = CoverStore.stats();
        check("F1b-3 stats() 报「在下载 0」", st.contains("在下载 0"), snippet(st));

        Field retryF = CoverStore.class.getDeclaredField("RETRY_AT");
        retryF.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Long> retry = (Map<String, Long>) retryF.get(null);
        retry.replaceAll((k, v) -> 1L);              // 把退避时刻拨回过去 = 模拟「退避到点」
        int n2 = (Integer) submitPending.invoke(null);
        check("F1b-4 退避到点后同专辑重新入池（1→0→1 循环）", n2 >= 1 || !inflight.isEmpty(),
                "n2=" + n2 + "，在下载=" + inflight.size());
        waitUntil(() -> inflight.isEmpty(), 20_000L);
        pool.shutdownNow();
    }

    /** F2a-e：onStreamServe 在权威读失败时「只记不发」，且既有语义（不夺位 / 对齐）不回归。 */
    private static void f2AuthUnknownGuard() throws Exception {
        Field authF = PlaybarCover.class.getDeclaredField("playingIdCache");
        authF.setAccessible(true);
        Field curF = PlaybarCover.class.getDeclaredField("currentSongId");
        curF.setAccessible(true);
        Method serve = PlaybarCover.class.getMethod("onStreamServe", long.class, String.class);
        Method flush = PlaybarCover.class.getMethod("flushTrace", String.class);

        authF.setLong(null, 0L);
        curF.setLong(null, 111L);
        serve.invoke(null, 222L, "探针");
        check("F2a 权威未知 + 供流曲≠当前曲：封面目标不变（0.11.51 会被带偏成 222）",
                curF.getLong(null) == 111L, "currentSongId=" + curF.getLong(null));
        flush.invoke(null, "探针");
        String log = logText();
        check("F2b 黑匣子落盘含「权威未知…只记不发」", log.contains("权威未知") && log.contains("只记不发"), "");

        authF.setLong(null, 0L);
        curF.setLong(null, 111L);
        serve.invoke(null, 111L, "探针");
        check("F2c 权威未知但供流曲=当前曲：仍不变", curF.getLong(null) == 111L,
                "currentSongId=" + curF.getLong(null));

        authF.setLong(null, 333L);
        curF.setLong(null, 111L);
        serve.invoke(null, 222L, "探针");
        check("F2d 权威可用且≠供流曲：不夺位（既有语义不回归）", curF.getLong(null) == 111L,
                "currentSongId=" + curF.getLong(null));

        authF.setLong(null, 333L);
        curF.setLong(null, 111L);
        serve.invoke(null, 333L, "探针");
        check("F2e 权威可用且=供流曲：对齐仍生效", curF.getLong(null) == 333L,
                "currentSongId=" + curF.getLong(null));
    }

    /** F2f-g：权威读失败的降级点提升为一次性 WARN（0.11.51 全程静默 / 只有 DEBUG，数不到）。 */
    private static void f2WarnObservability() throws Exception {
        HostBridgeWorker.get().start();
        try {
            long id = VoxzenBridge.playingTrackId();
            int c1 = countLog("宿主权威当前曲读不到");
            long id2 = VoxzenBridge.playingTrackId();
            int c2 = countLog("宿主权威当前曲读不到");
            check("F2f 权威读失败提升为 WARN（0.11.51 = 0 条）", id == 0L && c1 >= 1,
                    "id=" + id + "，WARN=" + c1);
            check("F2g 同签名一次性去重（第二次不重复落）", c2 == c1, "第一=" + c1 + " 第二=" + c2);
        } finally {
            HostBridgeWorker.get().stop();
        }
    }

    // ------------------------------------------------------------------ 工具

    @SuppressWarnings("unchecked")
    private static Set<String> inflight() throws Exception {
        Field f = CoverStore.class.getDeclaredField("INFLIGHT");
        f.setAccessible(true);
        return (Set<String>) f.get(null);
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier cond, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100L);
        }
        return cond.getAsBoolean();
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

    private static int countLog(String needle) throws Exception {
        String t = logText();
        int n = 0;
        for (int i = t.indexOf(needle); i >= 0; i = t.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static String snippet(String s) {
        return s.length() <= 70 ? s : s.substring(0, 70) + "…";
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
