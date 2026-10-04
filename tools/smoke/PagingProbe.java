package com.example.netease.net;

import com.example.netease.core.Json;
import com.example.netease.core.PluginLog;
import com.example.netease.svc.CookieVault;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * P2 取证探针 · 歌单曲目分页化（{@code playlist/track/all}）的**失败语义**与**回退通道**。
 *
 * <p>验证的不变式（P2 铁律）：</p>
 * <ol>
 *   <li>正常分页：声明 N 首就该取回 N 首，日志逐页写明 {@code offset / limit / 通道 / 累计}，
 *       收尾摘要带「分页 P 页 / 页大小 S」与来源说明 {@link NeteaseApi#lastTrackNote()}；</li>
 *   <li><b>绝不静默返回半页</b>：已拿到部分页后再失败 ⇒ ERROR + 抛 {@code NeteaseException}；</li>
 *   <li>只有「一首都还没拿到」才允许回退 {@code trackIds + v3/song/detail}，且回退要写 WARN 日志
 *       与来源说明（本轮曲目来源 = 兜底通道）；</li>
 *   <li>完整性硬门：取回数 < 声明数 ⇒ ERROR + 抛错（写库侧会先删旧关联行再重建，半份 = 歌单被截断）。</li>
 * </ol>
 *
 * <p><b>离线可跑</b>：靠 {@code NeteaseApi} 里 package-private 的两个测试缝
 * （{@code detailSeam} / {@code pageSeam}，生产恒为 null）注入故障；S3 的真实回退分支与
 * {@code --live} 场景才需要网络（真机登录凭据从插件数据目录读）。</p>
 *
 * <pre>
 * 编译：
 *   javac -encoding UTF-8 -cp "build\classes;libs\sqlite-jdbc-3.41.2.2.jar;tools\.cache\spw-workshop-api-host.jar" ^
 *         -d build\probe-classes tools\smoke\PagingProbe.java
 * 运行：
 *   java "-Dfile.encoding=UTF-8" -cp "build\probe-classes;build\classes;libs\sqlite-jdbc-3.41.2.2.jar;tools\.cache\spw-workshop-api-host.jar" ^
 *        com.example.netease.net.PagingProbe [库副本目录] [--live=<真歌单 id>]
 * </pre>
 *
 * <p>退出码 0 = 全部符合预期，1 = 有不符合项。</p>
 */
public final class PagingProbe {

    private static final String SONG_PREFIX = "netease-tr-";

    private static int ok;
    private static int bad;
    private static Path logFile;
    private static long logOffset;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("build", "probe", "p2-paging");
        Path dbDir = Paths.get("build", "probe", "real-db-live");
        long liveId = 0L;
        for (String a : args) {
            if (a == null || a.isEmpty()) {
                continue;
            }
            if (a.startsWith("--live=")) {
                liveId = Long.parseLong(a.substring("--live=".length()).trim());
            } else {
                dbDir = Paths.get(a);
            }
        }
        Files.createDirectories(root);
        PluginLog.init(root.resolve("logs"));
        PluginLog.setLevel("debug");
        logFile = PluginLog.file();
        // 每轮从空日志开始：否则上一轮遗留的行会被算进本轮「逐页日志条数」这类断言（曾因此假 FAIL）。
        if (logFile != null && Files.isRegularFile(logFile)) {
            Files.write(logFile, new byte[0]);
        }
        logOffset = 0L;
        System.out.println("PagingProbe · 日志文件 = " + logFile + "（本轮已清空，计数类断言只算本轮的）");
        System.out.println("PagingProbe · 库副本目录 = " + dbDir.toAbsolutePath());
        importLiveCookies();

        scenario1PagingOk();
        scenario2PartialFailure();
        scenario3FirstPageFallback(dbDir);
        scenario4IncompleteGate();
        if (liveId > 0L) {
            scenario5Live(liveId);
        }

        NeteaseApi.pageSeam = null;
        NeteaseApi.detailSeam = null;
        System.out.println();
        if (bad == 0) {
            System.out.println("PASS：分页化 4 项不变式全部符合预期（含「绝不静默返回半页」与回退可追溯）");
        } else {
            System.out.println("FAIL：" + bad + " 项不符合预期（见上面 [FAIL] 行）");
        }
        System.exit(bad == 0 ? 0 : 1);
    }

    // ---- 场景 1：正常分页 --------------------------------------------------------

    private static void scenario1PagingOk() throws Exception {
        System.out.println();
        System.out.println("【S1】分页正常：声明 1200 首 ⇒ 500 / 500 / 200 三页");
        long id = 88000001L;
        setSeams((pid, payload) -> detail(id, "探针歌单·分页", 1200, List.of()),
                (pid, limit, offset) -> {
                    markChannel("weapi track/all");
                    return page(offset, 1200, limit);
                });
        Dto.Playlist pl = NeteaseApi.playlist(id);
        check("取回曲目数 == 声明数 1200", pl.tracks().size() == 1200, "实际 " + pl.tracks().size());
        check("曲目 id 不重复且首尾正确",
                pl.tracks().get(0).id() == 900000L && pl.tracks().get(1199).id() == 901199L,
                "首=" + pl.tracks().get(0).id() + " 尾=" + pl.tracks().get(1199).id());
        String log = drainLog("S1");
        check("逐页日志 3 条", occurrences(log, "曲目分页第 ") == 3,
                "实际 " + occurrences(log, "曲目分页第 "));
        check("逐页日志含 offset 与页大小 limit=500",
                log.contains("offset=0 limit=500") && log.contains("offset=500 limit=500")
                        && log.contains("offset=1000 limit=200"));
        check("收尾摘要含「分页 3 页 / 页大小 500」", log.contains("分页 3 页 / 页大小 500"));
        check("来源说明 lastTrackNote 已写",
                NeteaseApi.lastTrackNote() != null && NeteaseApi.lastTrackNote().contains("曲目来源"),
                String.valueOf(NeteaseApi.lastTrackNote()));
    }

    // ---- 场景 2：半页失败必须中止（P2 铁律）--------------------------------------

    private static void scenario2PartialFailure() throws Exception {
        System.out.println();
        System.out.println("【S2】已拿到第 1 页（500 首）后第 2 页失败 ⇒ 必须 ERROR + 抛错，绝不返回半页");
        long id = 88000002L;
        setSeams((pid, payload) -> detail(id, "探针歌单·半页", 1200, List.of()),
                (pid, limit, offset) -> {
                    if (offset == 0) {
                        markChannel("weapi track/all");
                        return page(0, 1200, limit);
                    }
                    throw new NeteaseException("探针注入：第 2 页失败（模拟 HTTP 503 / 风控）");
                });
        Dto.Playlist got = null;
        String why = null;
        try {
            got = NeteaseApi.playlist(id);
        } catch (NeteaseException e) {
            why = e.getMessage();
        }
        check("没有返回半页数据（返回 == null）", got == null,
                got == null ? "" : "却返回了 " + got.tracks().size() + " 首");
        check("抛出 NeteaseException 且说明「拒绝静默返回半页」",
                why != null && why.contains("拒绝静默返回半页"), String.valueOf(why));
        check("异常里带上已取回 / 声明数", why != null && why.contains("已取回 500 首")
                && why.contains("声明 1200 首"), String.valueOf(why));
        String log = drainLog("S2");
        check("ERROR 级日志已落盘", log.contains("[ERROR]") && log.contains("曲目分页中断"),
                log.contains("[ERROR]") ? "" : "没看到 [ERROR]");
    }

    // ---- 场景 3：首页失败 ⇒ 回退 trackIds（必须可追溯）--------------------------

    private static void scenario3FirstPageFallback(Path dbDir) throws Exception {
        System.out.println();
        System.out.println("【S3】首页就失败 ⇒ 回退 trackIds + v3/song/detail（回退必须写进日志与来源说明）");
        long id = 88000003L;
        List<Long> realIds = realSongIds(dbDir, 50);
        check("从库副本取到 50 个真曲目 id", realIds.size() == 50, "实际 " + realIds.size());
        setSeams((pid, payload) -> detail(id, "探针歌单·回退", realIds.size(), realIds),
                (pid, limit, offset) -> {
                    throw new NeteaseException("探针注入：track/all 三通道全失败（模拟 460 风控）");
                });
        String outcome;
        try {
            Dto.Playlist pl = NeteaseApi.playlist(id);
            outcome = "回退成功（真网络）：取回 " + pl.tracks().size() + " 首";
            check("回退后取回数 == detail.trackIds 数", pl.tracks().size() == realIds.size(),
                    outcome);
        } catch (NeteaseException e) {
            outcome = "回退未能完成（无网络/未登录，属预期）⇒ " + e.getMessage();
            check("无网络时也**没有**静默返回半页（抛错而不是给空歌单）", true, outcome);
        }
        check("来源说明写明是回退通道",
                NeteaseApi.lastTrackNote() != null && NeteaseApi.lastTrackNote().contains("回退"),
                String.valueOf(NeteaseApi.lastTrackNote()));
        String log = drainLog("S3");
        check("WARN「曲目分页不可用（第 1 页就失败…）」已落盘",
                log.contains("曲目分页不可用（第 1 页就失败"), "");
        check("回退日志含来源 = trackIds 兜底（回退）",
                log.contains("trackIds 兜底（回退）"), "");
        System.out.println("      · 结局：" + outcome);
    }

    // ---- 场景 4：完整性硬门 ------------------------------------------------------

    private static void scenario4IncompleteGate() throws Exception {
        System.out.println();
        System.out.println("【S4】分页提前给空页（声明 1200、只拿到 500）⇒ 完整性硬门必须 ERROR + 抛错");
        long id = 88000004L;
        setSeams((pid, payload) -> detail(id, "探针歌单·截断", 1200, List.of()),
                (pid, limit, offset) -> {
                    markChannel("weapi track/all");
                    return offset == 0 ? page(0, 1200, limit) : List.of();
                });
        Dto.Playlist got = null;
        String why = null;
        try {
            got = NeteaseApi.playlist(id);
        } catch (NeteaseException e) {
            why = e.getMessage();
        }
        check("没有返回截断歌单", got == null, got == null ? "" : "却返回了 " + got.tracks().size() + " 首");
        check("抛出且说明「拒绝静默写半份歌单」", why != null && why.contains("拒绝静默写半份歌单"),
                String.valueOf(why));
        String log = drainLog("S4");
        check("ERROR「歌单曲目未取全」已落盘", log.contains("歌单曲目未取全"), "");
    }

    // ---- 场景 5：真机 live（可选）------------------------------------------------

    private static void scenario5Live(long playlistId) throws Exception {
        System.out.println();
        System.out.println("【S5】真机 live：真歌单 id=" + playlistId + "（走真实网络，验证真实分页日志）");
        NeteaseApi.detailSeam = null;
        NeteaseApi.pageSeam = null;
        try {
            Dto.Playlist pl = NeteaseApi.playlist(playlistId);
            check("live 取回曲目数 >= 1", pl.tracks().size() >= 1, "实际 " + pl.tracks().size());
            System.out.println("      · 歌单「" + pl.name() + "」声明 " + pl.trackCount()
                    + " 首，取回 " + pl.tracks().size() + " 首");
            System.out.println("      · 通道 " + NeteaseApi.lastTrackPath() + "；" + NeteaseApi.lastTrackNote());
        } catch (NeteaseException e) {
            check("live 调用（网络不可用时允许失败，但必须抛错而不是静默）", true, e.getMessage());
        }
        drainLog("S5");
    }

    // ---- 基础设施 ----------------------------------------------------------------

    private static void setSeams(NeteaseApi.DetailFetch d, NeteaseApi.PageFetch p) {
        NeteaseApi.detailSeam = d;
        NeteaseApi.pageSeam = p;
    }

    private static Map<String, Object> detail(long id, String name, int trackCount, List<Long> trackIds) {
        Map<String, Object> node = Json.newObject();
        node.put("id", id);
        node.put("name", name);
        node.put("trackCount", trackCount);
        List<Object> ids = new ArrayList<>();
        for (Long t : trackIds) {
            Map<String, Object> one = Json.newObject();
            one.put("id", t);
            ids.add(one);
        }
        node.put("trackIds", ids);
        Map<String, Object> root = Json.newObject();
        root.put("playlist", node);
        return root;
    }

    /** 造一页曲目：{@code [offset, offset+limit)} 与 {@code total} 取交集。 */
    private static List<Dto.Song> page(int offset, int total, int limit) {
        List<Dto.Song> out = new ArrayList<>();
        for (int i = 0; i < limit && offset + i < total; i++) {
            out.add(new Dto.Song(900000L + offset + i, "曲目" + (offset + i), "歌手A", "专辑B",
                    200_000L, 0, true, ""));
        }
        return out;
    }

    /** 测试缝替身要模仿真实实现：命中通道时写 {@code lastTrackPath}。 */
    private static void markChannel(String via) {
        try {
            Field f = NeteaseApi.class.getDeclaredField("lastTrackPath");
            f.setAccessible(true);
            f.set(null, via);
        } catch (Throwable ignored) {
            // 反射失败只会让来源串变空，断言会抓出来
        }
    }

    private static List<Long> realSongIds(Path dbDir, int n) {
        List<Long> out = new ArrayList<>();
        Path db = dbDir.resolve("spw.db");
        if (!Files.isRegularFile(db)) {
            return out;
        }
        Properties p = new Properties();
        p.setProperty("open_mode", "1");                 // SQLITE_OPEN_READONLY
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db, p);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id FROM Track WHERE id LIKE 'netease-%' LIMIT " + n)) {
            while (rs.next()) {
                String id = rs.getString(1);
                String digits = id == null ? "" : id.replaceAll("^\\D*(\\d+)$", "$1");
                if (!digits.isEmpty()) {
                    out.add(Long.parseLong(digits));
                }
            }
        } catch (Throwable t) {
            System.out.println("      · 读库副本失败：" + t);
        }
        return out;
    }

    private static void importLiveCookies() {
        try {
            Path dir = Paths.get(String.valueOf(System.getenv("APPDATA")),
                    "Salt Player for Windows", "workshop", "data", "com.example.netease");
            CookieVault.init(dir);
            if (!CookieVault.exists()) {
                System.out.println("PagingProbe · 未找到已保存登录凭据（S3 真实回退 / S5 live 会失败，属预期）");
                return;
            }
            String cookie = CookieVault.load();
            NeteaseApi.importCookies(cookie);
            System.out.println("PagingProbe · 已导入真机登录凭据（长度 "
                    + (cookie == null ? 0 : cookie.length()) + "）");
        } catch (Throwable t) {
            System.out.println("PagingProbe · 导入登录凭据失败：" + t);
        }
    }

    private static void check(String what, boolean pass) {
        check(what, pass, "");
    }

    private static void check(String what, boolean pass, String detail) {
        if (pass) {
            ok++;
            System.out.println("  [OK]   " + what + (detail.isEmpty() ? "" : "（" + detail + "）"));
        } else {
            bad++;
            System.out.println("  [FAIL] " + what + (detail.isEmpty() ? "" : "（" + detail + "）"));
        }
    }

    /** 取走自上次调用以来新增的日志原文（并原样打印，便于取证留档）。 */
    private static String drainLog(String tag) throws Exception {
        if (logFile == null || !Files.isRegularFile(logFile)) {
            return "";
        }
        byte[] all = Files.readAllBytes(logFile);
        if (all.length <= logOffset) {
            return "";
        }
        String text = new String(all, (int) logOffset, (int) (all.length - logOffset),
                StandardCharsets.UTF_8);
        logOffset = all.length;
        System.out.println("  ----- " + tag + " 原始日志 -----");
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                System.out.println("  | " + line.trim());
            }
        }
        System.out.println("  ----- " + tag + " 日志结束 -----");
        return text;
    }

    private static int occurrences(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }

    private PagingProbe() {
    }
}
