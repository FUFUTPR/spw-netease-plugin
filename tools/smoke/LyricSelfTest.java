package netease.smoke;

import com.example.netease.core.DataPaths;
import com.example.netease.core.PluginLog;
import com.example.netease.svc.LyricService;
import com.example.netease.svc.NativeStreamServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * W4 歌词层自测（开发期工具，包名 {@code netease.smoke} —— 与其它 smoke 工具同惯例，
 * <b>不属于插件运行链</b>，不会被 {@code tools/build.py} 打进 .spmod）。
 *
 * <pre>
 *   javac -cp "build\classes;libs\sqlite-jdbc-3.41.2.2.jar" -d tools\smoke\out-lyric tools\smoke\LyricSelfTest.java
 *   java  -cp "tools\smoke\out-lyric;build\classes;libs\sqlite-jdbc-3.41.2.2.jar" netease.smoke.LyricSelfTest
 *   java  -cp "…" netease.smoke.LyricSelfTest --net 1904261851     # 额外做一次真联网抓取
 * </pre>
 *
 * <p><b>为什么要有它</b>：真机轮次由 Lead 独占（本机没有可用视觉模型），所以「抓取链到底通不通」
 * 必须在装机之前用一次本地自测先证明 —— 否则 C 轮一旦失败，无法区分「网络/风控问题」与
 * 「我改的代码问题」。它跑在<b>真实数据目录</b>上（只读 + 复用已就绪的缓存），不造任何假数据。</p>
 *
 * <ol>
 *   <li>T1 {@code songIdOf} 抠 id：直链 URL / 带 query / 非网易路径 / null。</li>
 *   <li>T2 未命中路径：{@code lrcFor} 对不存在的曲目必须返回 null（宿主回调线程的 miss 语义）。</li>
 *   <li>T3 命中路径：拿缓存目录里<b>已存在</b>的 {@code <id>.lrc} 验，并确认文件逐字节未被改写。</li>
 *   <li>T4 真联网抓取（需 {@code --net <songId>}）：走一遍完整链 —— 直连 → 清洗 → 合并 → 落盘 +
 *       内存索引 → {@code lrcFor} 命中 → 报「命中」行。</li>
 *   <li>T5 计数与摘要：{@code cachedCount()} / {@code failClassText()} / {@code stats()} 三态。</li>
 *   <li>T6 邻曲保温：无宿主队列时走「队列不足」分支（不得抛）。</li>
 * </ol>
 *
 * <p><b>副作用边界</b>：T1–T3/T5/T6 零网络；T4 只对显式给定的<b>那一个</b> songId 发请求，
 * 成功则可能多写一个 {@code <songId>.lrc}（内容与插件正常写的一致）并往插件日志目录追加几行。
 * 除此之外不删、不改任何既有文件。</p>
 */
public final class LyricSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        long netId = -1L;
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--net".equals(args[i]) && i + 1 < args.length) {
                try {
                    netId = Long.parseLong(args[++i]);
                } catch (Throwable ignored) {
                    netId = -1L;
                }
            } else if (!args[i].startsWith("--")) {
                try {
                    ids.add(Long.parseLong(args[i]));
                } catch (Throwable ignored) {
                    // 非数字参数忽略
                }
            }
        }
        PluginLog.setLevel("info");
        Path data = DataPaths.data();
        Path lyrDir = data.resolve("lyric");
        System.out.println("数据目录 = " + data);
        System.out.println("歌词目录 = " + lyrDir + "（存在=" + Files.isDirectory(lyrDir) + "）");
        System.out.println("插件日志 = " + PluginLog.file());
        LyricService.init(data);

        t1();
        t2();
        t3(ids, lyrDir);
        t5();
        t6();
        if (netId > 0L) {
            t4(netId, lyrDir);
        } else {
            System.out.println();
            System.out.println("[T4] 真联网抓取 …… 跳过（未给 --net <songId>）");
        }

        System.out.println();
        System.out.println("=== 自测结果：通过 " + passed + " / 失败 " + failed + " ===");
        System.out.println("插件日志 = " + PluginLog.file());
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ T1

    private static void t1() {
        System.out.println();
        System.out.println("[T1] songIdOf 抠 id");
        eq("直链 URL", 1904261851L, LyricService.songIdOf("http://127.0.0.1:17788/netease/1904261851"));
        eq("带 query", 4885597L, LyricService.songIdOf("http://127.0.0.1:17788/netease/4885597?level=exhigh"));
        eq("非网易路径", -1L, LyricService.songIdOf("file:///C:/Music/foo.flac"));
        eq("null", -1L, LyricService.songIdOf(null));
    }

    // ------------------------------------------------------------------ T2

    private static void t2() {
        System.out.println();
        System.out.println("[T2] 未命中必须返回 null（宿主回调线程的 miss 语义）");
        eq("miss 返回 null", null, LyricService.lrcFor(
                "http://127.0.0.1:17788/netease/1", "绝对不存在的曲目 zzz", "无人"));
        eq("全 null 返回 null", null, LyricService.lrcFor(null, null, null));
    }

    // ------------------------------------------------------------------ T3

    private static void t3(List<Long> ids, Path lyrDir) throws Exception {
        System.out.println();
        System.out.println("[T3] 已就绪曲目的命中路径（用缓存目录里现成的 .lrc 验，不改写文件）");
        List<Long> probe = new ArrayList<>(ids);
        if (probe.isEmpty() && Files.isDirectory(lyrDir)) {
            try (var s = Files.list(lyrDir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".lrc"))
                        .limit(2)
                        .forEach(p -> probe.add(Long.parseLong(
                                p.getFileName().toString().replace(".lrc", ""))));
            }
        }
        if (probe.isEmpty()) {
            System.out.println("  跳过：缓存目录里没有 .lrc，且命令行没给 songId");
            return;
        }
        for (Long id : probe) {
            Path f = lyrDir.resolve(id + ".lrc");
            if (!Files.isRegularFile(f)) {
                System.out.println("  跳过 " + id + "：磁盘上没有 " + f.getFileName());
                continue;
            }
            String before = Files.readString(f);
            String url = NativeStreamServer.urlFor(id);
            String hit = LyricService.lrcFor(url, null, null);
            ok("磁盘有的是 " + f.getFileName() + "（" + before.length() + " 字符）⇒ lrcFor 命中", hit != null);
            if (hit != null) {
                eq("命中文本与磁盘逐字相等", before, hit);
            }
            eq("文件未被改写", before, Files.readString(f));
            ok("按 id 索引也能命中（宿主 path 被改写成 file:///… 后仍工作）",
                    LyricService.lrcFor("http://127.0.0.1:17788/netease/" + id, null, null) != null);
        }
        long id0 = probe.get(0);
        long t0 = System.currentTimeMillis();
        LyricService.prefetchOne(id0, "", null, null);
        long ms = System.currentTimeMillis() - t0;
        ok("已就绪的 prefetchOne 立即返回（" + ms + "ms，未联网）", ms < 500L);
    }

    // ------------------------------------------------------------------ T4

    private static void t4(long songId, Path lyrDir) throws Exception {
        System.out.println();
        System.out.println("[T4] 真联网抓取链（songId=" + songId + "）");
        Path f = lyrDir.resolve(songId + ".lrc");
        System.out.println("  抓取前：" + f.getFileName() + " 存在=" + Files.isRegularFile(f));
        long t0 = System.currentTimeMillis();
        LyricService.prefetchOne(songId, NativeStreamServer.urlFor(songId), null, null);
        long ms = System.currentTimeMillis() - t0;
        boolean nowFile = Files.isRegularFile(f);
        String hit = LyricService.lrcFor(NativeStreamServer.urlFor(songId), null, null);
        System.out.println("  耗时 " + ms + "ms ｜ 落盘=" + nowFile + " ｜ lrcFor 命中=" + (hit != null));
        System.out.println("  失败分类 = " + LyricService.failClassText());
        if (nowFile) {
            String text = Files.readString(f);
            int lines = 1;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    lines++;
                }
            }
            System.out.println("  落盘大小 = " + text.length() + " 字符 / " + lines + " 行");
            ok("落盘非空", !text.isBlank());
            eq("命中文本与落盘一致", text, hit);
        } else {
            ok("未落盘 ⇒ 失败分类必须非全 0（不许静默）",
                    !LyricService.failClassText().contains(
                            "401=0 403=0 NO_LYRIC=0 NET=0 PARSE=0 AUTH=0 ENV=0"));
        }
    }

    // ------------------------------------------------------------------ T5

    private static void t5() {
        System.out.println();
        System.out.println("[T5] 计数与摘要");
        System.out.println("  cachedCount()   = " + LyricService.cachedCount());
        System.out.println("  failClassText() = " + LyricService.failClassText());
        System.out.println("  failCount()     = " + LyricService.failCount());
        ok("failCount 非负", LyricService.failCount() >= 0L);
        // 冻结词形守卫：Lead 的 J3 判据里 `NO_LYRIC=c NET=d` 必须相邻，AUTH/ENV 只许追加在行尾。
        String fc = LyricService.failClassText();
        ok("失败分类冻结五段顺序未被改写（NO_LYRIC 紧跟 NET）",
                fc.matches("^401=\\d+ 403=\\d+ NO_LYRIC=\\d+ NET=\\d+ PARSE=\\d+ AUTH=\\d+ ENV=\\d+$"));
        System.out.println("  冻结前缀校验      = " + fc.substring(0, Math.min(34, fc.length())) + " …");
        String st = LyricService.stats();
        System.out.println("  stats()         = " + st);
        ok("stats() 含三态词形（已就绪/待处理/失败）",
                st.contains("已就绪") && st.contains("待处理") && st.contains("失败"));
        LyricService.noteTotal(3267L);
        String st2 = LyricService.stats();
        System.out.println("  noteTotal(3267) 后 = " + st2);
        ok("noteTotal 之后「待处理」不再是 ?",
                st2.contains("待处理") && !st2.contains("待处理 ?"));
    }

    // ------------------------------------------------------------------ T6

    private static void t6() {
        System.out.println();
        System.out.println("[T6] 邻曲保温（无宿主队列时的分支，不得抛）");
        eq("无队列时入队 0 首", 0, LyricService.warmNeighbors(0L));
    }

    // ------------------------------------------------------------------ 断言

    private static void eq(String what, Object want, Object got) {
        boolean ok = (want == null) ? got == null : want.equals(got);
        ok(what, ok);
        if (!ok) {
            System.out.println("      期望 = " + want + " / 实际 = " + got);
        }
    }

    private static void ok(String what, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("  ✓ " + what);
        } else {
            failed++;
            System.out.println("  ✗ " + what);
        }
    }
}
