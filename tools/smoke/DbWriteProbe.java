import com.example.netease.core.PluginLog;
import com.example.netease.net.Dto;
import com.example.netease.svc.NativeLibrary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * P0 止血探针（0.11.7）：在<b>真机 spw.db 的副本</b>上跑「旧逻辑 SQL」与「新逻辑写入」两条路径，
 * 先复现 0.11.6 的 Track=0 / 孤儿关联行，再证明修好之后 Track=N / 孤儿=0。
 *
 * <p>为什么必须在副本上跑：宿主的 {@code spw.db + -wal + -shm} 三件套缺一不可（真机数据全在 WAL 里，
 * 只拷 .db 会读到空库）；探针全程不碰真机目录。</p>
 *
 * <h2>四个场景（每个都打印原始计数，PASS/FAIL 由期望值判定）</h2>
 * <ol>
 *   <li><b>[A] 旧逻辑（0.11.6 原样 SQL，自带连接 FK=OFF）</b>：{@code INSERT OR IGNORE} 20 列写 Track
 *       ⇒ 宿主 Track 25 列全 NOT NULL、缺的 4 个 REAL + coverRevision 被写成 NULL ⇒ <b>影响行数 0、
 *       不抛异常</b>；随后照旧写 PlaylistTrack ⇒ 孤儿关联行。这正是 0.11.6 真机 Track=0 的复现。</li>
 *   <li><b>[A2] 旧逻辑 + 宿主池连接口径（FK=ON）</b>：同一条链在开外键的连接上必然撞
 *       {@code SQLiteException: Error code: 787, FOREIGN KEY constraint failed}（真机日志第 82/84 行的原话）。</li>
 *   <li><b>[B] 新逻辑（0.11.7 的 {@link NativeLibrary#writeOn}）</b>：schema 自适应 upsert + 补零值 +
 *       影响行数校验 ⇒ Track=N、四个 REAL 列 = 0.0、孤儿=0。</li>
 *   <li><b>[C] 新逻辑 + 人工让 Track 写入必败（BEFORE INSERT 触发器）</b> ⇒ 必须抛错且
 *       <b>一条关联行都不写</b>（0.11.6 是「静默跳过 + 照写关联」）。</li>
 *   <li><b>[D] FK=ON 连接拒收孤儿关联行</b> ⇒ 自带连接 {@code PRAGMA foreign_keys=ON} 这道防线本身有效。</li>
 *   <li><b>[E] 子进程（APPDATA 指向副本）</b>：{@link NativeLibrary#deleteOrphanLinks()} 清掉真机遗留的
 *       5935 条孤儿行，再 {@link NativeLibrary#sync} 端到端写一轮（含 {@code open()} 的 FK=ON 与写入自检）。</li>
 * </ol>
 *
 * <h2>跑法</h2>
 * <pre>
 * javac -encoding UTF-8 -cp build\classes;libs\sqlite-jdbc-3.41.2.2.jar -d build\probe-classes tools\smoke\DbWriteProbe.java
 * java  -cp build\probe-classes;build\classes;libs\sqlite-jdbc-3.41.2.2.jar;tools\.cache\spw-workshop-api-host.jar DbWriteProbe [真机库目录]
 * </pre>
 *
 * <p>缺省真机库目录 = {@code build\probe\real-db}（"spw.db" + "-wal" + "-shm" 三件套）。</p>
 */
public final class DbWriteProbe {

    private static final String PREFIX = "netease-";
    private static final String URL_PREFIX = "http://127.0.0.1:17788/netease/";
    private static final String HOST_DIR = "Salt Player for Windows";
    private static final int SONGS = 5;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final List<String> FAILS = new ArrayList<>();

    private DbWriteProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--phase2".equals(args[0])) {
            phase2(Paths.get(args[1]));
            return;
        }
        if (args.length > 0 && "--phase3".equals(args[0])) {
            phase3(Paths.get(args[1]));
            return;
        }
        Path src = Paths.get(args.length > 0 ? args[0] : "build\\probe\\real-db");
        Path root = Paths.get("build", "probe", "p0-" + LocalDateTime.now().format(TS));
        Path a = root.resolve("A").resolve("appdata");     // 场景 A/A2/B/E 的副本
        Path c = root.resolve("C").resolve("appdata");     // 场景 C 的副本（会被塞触发器）
        Path logs = root.resolve("logs");
        Files.createDirectories(logs);
        PluginLog.init(logs);
        PluginLog.setLevel("debug");

        System.out.println("=== P0 探针（0.11.7）DbWriteProbe ===");
        System.out.println("真机库来源：" + src.toAbsolutePath());
        System.out.println("工作副本：" + root.toAbsolutePath());
        Path dbA = copy(src, a.resolve(HOST_DIR));
        Path dbC = copy(src, c.resolve(HOST_DIR));
        System.out.println("副本 A：" + dbA);
        System.out.println("副本 C：" + dbC);
        System.out.println();

        // ---------------------------------------------------------- [0] before
        try (Connection conn = open(dbA, false)) {
            System.out.println("[0] 副本 A 初始状态（真机 0.11.6 遗留）");
            System.out.println(counts(conn, "    "));
            System.out.println();
        }

        // ---------------------------------------------------------- [A] 旧逻辑
        try (Connection conn = open(dbA, false)) {
            conn.setAutoCommit(false);
            System.out.println("[A] 旧逻辑 SQL（0.11.6 原样）：20 列 INSERT OR IGNORE + INSERT OR REPLACE 关联");
            String pl = PREFIX + "pl-probe-old";
            int[] r = oldLogic(conn, pl, "探针-旧逻辑", SONGS);
            System.out.println("    Playlist 影响行数=" + r[0] + "，Track 影响行数=" + r[1]
                    + " ← 全是 0 也**不会抛异常**（这就是静默失败）");
            System.out.println("    关联行 INSERT OR REPLACE 影响行数合计=" + r[2]);
            conn.commit();
            System.out.println("    提交后（旧逻辑声称写了 " + SONGS + " 首歌）：");
            System.out.println(counts(conn, "    "));
            long newTracks = scalar(conn, "SELECT COUNT(*) FROM Track WHERE id LIKE '" + PREFIX + "%'");
            long pls = scalar(conn, "SELECT COUNT(*) FROM Playlist WHERE id = '" + pl + "'");
            long links = scalar(conn, "SELECT COUNT(*) FROM PlaylistTrack WHERE playlistId = '" + pl + "'");
            long orphan = scalar(conn, "SELECT COUNT(*) FROM PlaylistTrack lt"
                    + " WHERE lt.playlistId = '" + pl + "' AND lt.trackId NOT IN (SELECT id FROM Track)");
            check("A: 旧逻辑写不出 Track 行（真机 Track=0 复现）", newTracks == 0);
            check("A: 旧逻辑写出了歌单行（诚实记账）", pls == 1);
            check("A: 旧逻辑照写了 " + SONGS + " 条关联行", links == SONGS);
            check("A: 这 " + SONGS + " 条关联行 100% 是孤儿", orphan == SONGS);
            System.out.println();
        }

        // ---------------------------------------------------------- [A2] 旧逻辑 + FK=ON
        try (Connection conn = open(dbA, true)) {
            conn.setAutoCommit(false);
            System.out.println("[A2] 旧逻辑 + 宿主池连接口径（foreign_keys=ON）");
            try {
                oldLogic(conn, PREFIX + "pl-probe-old-fk", "探针-旧逻辑-FK", SONGS);
                System.out.println("    意外：开外键的连接上居然写成功了");
                FAILS.add("A2: 期望 787 外键失败，实际却写成功");
            } catch (SQLException e) {
                System.out.println("    预期失败：" + e.getClass().getSimpleName() + ": Error code: "
                        + e.getErrorCode() + ", message: " + e.getMessage() + extended(e));
                check("A2: 宿主池连接口径下撞出外键拒绝（真机日志原话）",
                        e.getMessage() != null && e.getMessage().toLowerCase().contains("foreign key"));
            } finally {
                try {
                    conn.rollback();
                } catch (Throwable ignored) {
                    // 只读回滚，失败无关
                }
            }
            System.out.println();
        }

        // ---------------------------------------------------------- [B] 新逻辑
        try (Connection conn = open(dbA, true)) {
            conn.setAutoCommit(false);
            System.out.println("[B] 新逻辑（0.11.7 NativeLibrary.writeOn，宿主池连接口径 FK=ON）");
            String pl = PREFIX + "pl-900001";   // writeOn 里 id = PLAYLIST_PREFIX + entry.playlistId()
            NativeLibrary.Result res = NativeLibrary.writeOn(new ProbeSql(conn),
                    List.of(new NativeLibrary.Entry(900001L, "探针-新逻辑", songs(), null)),
                    URL_PREFIX, "probe", List.of(), null);
            conn.commit();
            System.out.println("    Result = " + res);
            System.out.println(counts(conn, "    "));
            long tracks = scalar(conn, "SELECT COUNT(*) FROM Track WHERE id LIKE '" + PREFIX + "%'");
            long links = scalar(conn, "SELECT COUNT(*) FROM PlaylistTrack WHERE playlistId = '" + pl + "'");
            long orphan = scalar(conn, "SELECT COUNT(*) FROM PlaylistTrack lt"
                    + " WHERE lt.playlistId = '" + pl + "' AND lt.trackId NOT IN (SELECT id FROM Track)");
            check("B: 新逻辑把 Track 行写进去了（Track=" + tracks + "）", tracks == SONGS);
            check("B: Result 记账一致（tracks=" + res.tracks() + " links=" + res.links() + "）",
                    res.tracks() == SONGS && res.links() == SONGS && res.error() == null);
            check("B: 新歌单的关联行 " + links + " 条、孤儿 " + orphan + " 条", links == SONGS && orphan == 0);
            String real = scalarText(conn, "SELECT trackGain || '/' || trackPeak || '/' || albumGain || '/'"
                    + " || albumPeak || '/' || coverRevision FROM Track WHERE id = '" + PREFIX + "900001'");
            System.out.println("    第一个 REAL 列样本（trackGain/trackPeak/albumGain/albumPeak/coverRevision）=" + real);
            check("B: 四个 REAL NOT NULL 列被补 0.0（不是 NULL）", "0.0/0.0/0.0/0.0/0".equals(real));
            System.out.println();
        }

        // ---------------------------------------------------------- [C] 新逻辑 + 人工必败
        try (Connection conn = open(dbC, true)) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TRIGGER probe_guard BEFORE INSERT ON Track BEGIN"
                        + " SELECT RAISE(ABORT, 'probe: Track 写入被人工拒绝'); END");
            }
            conn.setAutoCommit(false);
            System.out.println("[C] 新逻辑 + 人工让 Track 写入必败（BEFORE INSERT 触发器 ABORT）");
            long linksBefore = scalar(conn, "SELECT COUNT(*) FROM PlaylistTrack");
            String pl = PREFIX + "pl-probe-guard";
            try {
                NativeLibrary.writeOn(new ProbeSql(conn),
                        List.of(new NativeLibrary.Entry(900002L, "探针-必败", songs(), null)),
                        URL_PREFIX, "probe", List.of(), null);
                System.out.println("    意外：Track 写不进去却没抛错");
                FAILS.add("C: 期望抛错，实际却成功返回");
            } catch (Throwable t) {
                System.out.println("    预期抛错：" + t.getClass().getSimpleName() + ": " + t.getMessage());
                check("C: Track 写入失败时抛错中止（不静默）", t.getMessage() != null
                        && (t.getMessage().contains("probe: Track 写入被人工拒绝")
                        || t.getMessage().contains("写库未生效")));
            } finally {
                try {
                    conn.rollback();
                } catch (Throwable ignored) {
                    // 回滚失败不影响判定
                }
            }
            long linksAfter = scalar(conn, "SELECT COUNT(*) FROM PlaylistTrack");
            check("C: 一条关联行都没写（before=" + linksBefore + " after=" + linksAfter + "）",
                    linksAfter == linksBefore);
            System.out.println();
        }

        // ---------------------------------------------------------- [D] FK=ON 拒收孤儿
        try (Connection conn = open(dbA, true)) {
            conn.setAutoCommit(false);
            System.out.println("[D] FK=ON 连接拒收孤儿关联行（自带连接 foreign_keys=ON 这道防线的实测）");
            String pl = PREFIX + "pl-probe-orphan";
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO Playlist"
                    + " (id, title, coverModifiedTime, isUserEditedCover, description, createdTime, `order`,"
                    + " trackSort, trackSortDescending) VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, pl);
                ps.setString(2, "探针-孤儿");
                for (int i = 3; i <= 9; i++) {
                    ps.setLong(i, i == 4 ? 0L : (i == 6 ? System.currentTimeMillis() : 0L));
                }
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO PlaylistTrack"
                    + " (playlistId, trackId, addedTime, `order`) VALUES (?,?,?,?)")) {
                ps.setString(1, pl);
                ps.setString(2, PREFIX + "404404404");
                ps.setLong(3, System.currentTimeMillis());
                ps.setLong(4, 0L);
                ps.executeUpdate();
                System.out.println("    意外：外键开着也把孤儿行写进去了");
                FAILS.add("D: 期望 787 外键失败，实际写成功");
            } catch (SQLException e) {
                System.out.println("    预期失败：Error code: " + e.getErrorCode() + ", message: "
                        + e.getMessage() + extended(e));
                check("D: foreign_keys=ON 拒收孤儿关联行",
                        e.getMessage() != null && e.getMessage().toLowerCase().contains("foreign key"));
            } finally {
                try {
                    conn.rollback();
                } catch (Throwable ignored) {
                    // 同上
                }
            }
            System.out.println();
        }

        // ---------------------------------------------------------- [F] 歌单间重复曲目（A 轮真机误报的回归测试）
        Path dbF = copy(src, root.resolve("F").resolve("appdata").resolve(HOST_DIR));
        try (Connection conn = open(dbF, true)) {
            conn.setAutoCommit(false);
            System.out.println("[F] 两张歌单共享曲目：曲目数按「去重后」算、关联行按「对数」算");
            System.out.println("    （A 轮真机第一版自检拿 5935 对去比 3267 行 ⇒ 把正确写入误判成失败并回滚）");
            long before = scalar(conn, "SELECT COUNT(*) FROM Track");
            List<NativeLibrary.Entry> entries = List.of(
                    new NativeLibrary.Entry(910001L, "探针-共享A", songsOf(900001L, 900002L, 900003L), null),
                    new NativeLibrary.Entry(910002L, "探针-共享B", songsOf(900002L, 900003L, 900004L), null));
            try {
                NativeLibrary.Result r = NativeLibrary.writeOn(new ProbeSql(conn),
                        entries, URL_PREFIX, "probe", List.of(), null);
                conn.commit();
                long tracks = scalar(conn, "SELECT COUNT(*) FROM Track WHERE id LIKE 'netease-%'");
                String scope = " playlistId IN ('" + PREFIX + "pl-910001','" + PREFIX + "pl-910002')";
                long links = scalar(conn, "SELECT COUNT(*) FROM PlaylistTrack WHERE" + scope);
                long orphan = scalar(conn, "SELECT COUNT(*) FROM PlaylistTrack pt WHERE" + scope
                        + " AND NOT EXISTS (SELECT 1 FROM Track t WHERE t.id = pt.trackId)");
                System.out.println("    Result = " + r);
                System.out.println("    Track 行数=" + tracks + "（去重曲目 4）关联行=" + links
                        + "（对数 6）孤儿=" + orphan);
                check("F: 共享曲目下写入不再误报（曲目数=去重 4）", r.tracks() == 4);
                check("F: 关联行 = 对数 6", r.links() == 6 && links == 6);
                check("F: Track 行数 = 去重 4、孤儿 0", tracks == 4 && orphan == 0);
                check("F: 写入行数与基线一致（before=" + before + " after=" + tracks + "）", tracks == before + 4);
            } catch (Throwable t) {
                System.out.println("    未预期抛错：" + t);
                FAILS.add("F: 共享曲目场景抛错（自检又误判了）：" + t);
            } finally {
                try {
                    conn.rollback();
                } catch (Throwable ignored) {
                    // 同上
                }
            }
            System.out.println();
        }

        // ---------------------------------------------------------- [E] 子进程：APPDATA 指向副本
        System.out.println("[E] 子进程跑 plugin 自己的通路（APPDATA → 副本）：清孤儿 + sync 端到端");
        int code = spawnChild(a, "--phase2");
        check("E: 子进程退出码 = 0（清孤儿 + sync 都通过）", code == 0);
        System.out.println();

        // ---------------------------------------------------------- [G] 增量幂等（同库连跑两轮）
        System.out.println("[G] 子进程：同一副本上连跑两轮 NativeLibrary.sync(...)（增量幂等实证）");
        Path g = root.resolve("G").resolve("appdata");
        copy(src, g.resolve(HOST_DIR));
        int codeG = spawnChild(g, "--phase3");
        check("G: 子进程退出码 = 0（两轮行数不变、行标识不变）", codeG == 0);
        System.out.println();

        // ---------------------------------------------------------- 汇总
        System.out.println("=== 汇总 ===");
        if (FAILS.isEmpty()) {
            System.out.println("PASS：6 个场景全部符合预期（旧逻辑复现 Track=0/孤儿，新逻辑 Track=" + SONGS
                    + "/孤儿=0，共享曲目不再误报，两轮 sync 行数不变）");
            System.out.println("日志（含 PluginLog 的 ERROR/WARN 原文）：" + logs.toAbsolutePath()
                    + "\\plugin-*.log");
        } else {
            System.out.println("FAIL：");
            for (String f : FAILS) {
                System.out.println("  - " + f);
            }
        }
        System.exit(FAILS.isEmpty() ? 0 : 1);
    }

    /** 子进程：APPDATA 指向副本 ⇒ NativeLibrary 的 dbPath()/open()/backupOnce 全落在副本上。 */
    private static void phase2(Path appdata) throws Exception {
        System.out.println("    [E] 子进程 APPDATA=" + appdata);
        PluginLog.init(appdata.resolve(HOST_DIR).resolve("workshop").resolve("data")
                .resolve("com.example.netease").resolve("logs"));
        PluginLog.setLevel("debug");
        System.out.println("    [E] dbPath = " + NativeLibrary.dbPath());
        try (Connection conn = open(NativeLibrary.dbPath(), false)) {
            System.out.println("    [E] 清孤儿前：" + counts(conn, "        "));
        }
        int n = NativeLibrary.deleteOrphanLinks();
        System.out.println("    [E] deleteOrphanLinks() 返回 " + n + " 行");
        try (Connection conn = open(NativeLibrary.dbPath(), false)) {
            System.out.println("    [E] 清孤儿后：" + counts(conn, "        "));
        }
        NativeLibrary.Result r = NativeLibrary.sync(
                List.of(new NativeLibrary.Entry(900003L, "探针-新逻辑-自带连接", songs(), null)),
                URL_PREFIX, List.of(), null);
        System.out.println("    [E] NativeLibrary.sync(...) = " + r);
        try (Connection conn = open(NativeLibrary.dbPath(), false)) {
            System.out.println("    [E] sync 后：" + counts(conn, "        "));
            long orphan = scalar(conn, "SELECT COUNT(*) FROM PlaylistTrack lt"
                    + " WHERE lt.playlistId LIKE '" + PREFIX + "%' AND lt.trackId NOT IN (SELECT id FROM Track)");
            long tracks = scalar(conn, "SELECT COUNT(*) FROM Track WHERE id LIKE '" + PREFIX + "%'");
            boolean ok = r.error() == null && orphan == 0 && tracks >= SONGS;
            System.out.println("    [E] 判定：error=" + r.error() + " 插件 Track=" + tracks + " 孤儿=" + orphan
                    + " → " + (ok ? "PASS" : "FAIL"));
            if (!ok) {
                System.exit(1);
            }
        }
    }

    /**
     * 子进程：增量幂等实证 —— 同一副本上连跑两轮 {@code NativeLibrary.sync(...)}，要求
     * ① 两轮都不报错且声明行数一致；② 第二轮结束后 Track / Playlist / 关联行计数与第一轮**完全相同**；
     * ③ 行标识不变（Track.addedTime、Playlist.createdTime 不被重写）⇒ 证明走的是
     * {@code ON CONFLICT DO UPDATE}，不是 {@code INSERT OR REPLACE}（REPLACE = DELETE+INSERT，
     * 会连带 CASCADE 掉其它歌单的关联行，且把宿主用户数据 addedTime/isFavorite 清零）。
     */
    private static void phase3(Path appdata) throws Exception {
        System.out.println("    [G] 子进程 APPDATA=" + appdata);
        PluginLog.init(appdata.resolve(HOST_DIR).resolve("workshop").resolve("data")
                .resolve("com.example.netease").resolve("logs"));
        PluginLog.setLevel("debug");
        System.out.println("    [G] dbPath = " + NativeLibrary.dbPath());
        List<NativeLibrary.Entry> entries = List.of(
                new NativeLibrary.Entry(920001L, "探针-幂等A", songsOf(900001L, 900002L, 900003L), null),
                new NativeLibrary.Entry(920002L, "探针-幂等B", songsOf(900002L, 900003L, 900004L), null));
        String scope = " playlistId IN ('" + PREFIX + "pl-920001','" + PREFIX + "pl-920002')";
        String trackId = PREFIX + "900001";
        String plId = PREFIX + "pl-920001";

        // ---- 第一轮
        NativeLibrary.Result r1 = NativeLibrary.sync(entries, URL_PREFIX, List.of(), null);
        System.out.println("    [G] 第 1 轮 sync = " + r1);
        long[] c1 = idemCounts(scope);
        String tr1 = rowStamp("SELECT addedTime || '/' || modifiedTime FROM Track WHERE id = '" + trackId + "'");
        String pl1 = rowStamp("SELECT createdTime || '/' || coverModifiedTime FROM Playlist WHERE id = '" + plId + "'");
        System.out.println("    [G] 第 1 轮后：插件 Track=" + c1[0] + " 插件 Playlist=" + c1[1]
                + " 探针歌单关联行=" + c1[2] + " 孤儿=" + c1[3]);
        System.out.println("    [G] 第 1 轮后行标识：Track(" + trackId + ").addedTime/modifiedTime=" + tr1
                + "；Playlist(" + plId + ").createdTime/coverModifiedTime=" + pl1);

        // ---- 第二轮（同一份 entries，完全重复的一轮）
        NativeLibrary.Result r2 = NativeLibrary.sync(entries, URL_PREFIX, List.of(), null);
        System.out.println("    [G] 第 2 轮 sync = " + r2);
        long[] c2 = idemCounts(scope);
        String tr2 = rowStamp("SELECT addedTime || '/' || modifiedTime FROM Track WHERE id = '" + trackId + "'");
        String pl2 = rowStamp("SELECT createdTime || '/' || coverModifiedTime FROM Playlist WHERE id = '" + plId + "'");
        System.out.println("    [G] 第 2 轮后：插件 Track=" + c2[0] + " 插件 Playlist=" + c2[1]
                + " 探针歌单关联行=" + c2[2] + " 孤儿=" + c2[3]);
        System.out.println("    [G] 第 2 轮后行标识：Track(" + trackId + ").addedTime/modifiedTime=" + tr2
                + "；Playlist(" + plId + ").createdTime/coverModifiedTime=" + pl2);

        check("G: 第 1 轮成功（error=null，曲目 4 / 关联 6）",
                r1.error() == null && r1.tracks() == 4 && r1.links() == 6);
        check("G: 第 2 轮成功（error=null，曲目 4 / 关联 6）",
                r2.error() == null && r2.tracks() == 4 && r2.links() == 6);
        check("G: 两轮后 Track 行数不变（" + c1[0] + "）", c1[0] == c2[0]);
        check("G: 两轮后 Playlist 行数不变（" + c1[1] + "）", c1[1] == c2[1]);
        check("G: 两轮后关联行数不变（" + c1[2] + "）", c1[2] == c2[2]);
        check("G: 两轮后孤儿仍为 0", c2[3] == 0);
        check("G: Track.addedTime 未被重写（" + tr1 + "）", tr1.equals(tr2));
        check("G: Playlist.createdTime 未被重写（" + pl1 + "）", pl1.equals(pl2));
        boolean ok = FAILS.isEmpty();
        System.out.println("    [G] 判定：" + (ok ? "PASS（增量幂等：行数不变 + 行标识不变）" : "FAIL"));
        if (!ok) {
            System.exit(1);
        }
    }

    /** [G] 用的四个计数：插件曲目 / 插件歌单 / 探针两歌单的关联行 / 其中的孤儿行。 */
    private static long[] idemCounts(String scope) {
        try (Connection conn = open(NativeLibrary.dbPath(), false)) {
            return new long[]{
                    scalar(conn, "SELECT COUNT(*) FROM Track WHERE id LIKE '" + PREFIX + "%'"),
                    scalar(conn, "SELECT COUNT(*) FROM Playlist WHERE id LIKE '" + PREFIX + "%'"),
                    scalar(conn, "SELECT COUNT(*) FROM PlaylistTrack WHERE" + scope),
                    scalar(conn, "SELECT COUNT(*) FROM PlaylistTrack pt WHERE" + scope
                            + " AND NOT EXISTS (SELECT 1 FROM Track t WHERE t.id = pt.trackId)")};
        } catch (Throwable t) {
            throw new IllegalStateException("[G] 计数失败：" + t, t);
        }
    }

    private static String rowStamp(String sql) {
        try (Connection conn = open(NativeLibrary.dbPath(), false)) {
            String v = scalarText(conn, sql);
            return v == null ? "(行不存在)" : v;
        } catch (Throwable t) {
            throw new IllegalStateException("[G] 读行标识失败：" + sql + "：" + t, t);
        }
    }

    private static int spawnChild(Path appdata, String phase) throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString();
        List<String> cmd = new ArrayList<>();
        cmd.add(java);
        // 子进程的 stdout 会被 inheritIO 接到父进程的输出（常被重定向进日志文件），
        // 不显式指定编码时按平台默认（本机 cp936）写，日志里那段中文会乱码 ⇒ 钉死 UTF-8。
        cmd.add("-Dstdout.encoding=UTF-8");
        cmd.add("-Dstderr.encoding=UTF-8");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-cp");
        cmd.add(absoluteClasspath());
        cmd.add("DbWriteProbe");
        cmd.add(phase);
        cmd.add(appdata.toAbsolutePath().toString());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("APPDATA", appdata.toAbsolutePath().toString());
        // 不设工作目录：Classpath 里的相对路径（build\classes 之类）要按调用者的 cwd 解析
        pb.inheritIO();
        Process p = pb.start();
        return p.waitFor();
    }

    /** 子进程继承的 classpath 必须是绝对路径（否则换个 cwd 就 ClassNotFoundException）。 */
    private static String absoluteClasspath() {
        StringBuilder sb = new StringBuilder();
        for (String part : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(java.io.File.pathSeparator);
            }
            sb.append(Paths.get(part).toAbsolutePath());
        }
        return sb.toString();
    }

    /** sqlite-jdbc 的扩展错误码（19 = SQLITE_CONSTRAINT 的扩展码 787 = SQLITE_CONSTRAINT_FOREIGNKEY）。 */
    private static String extended(SQLException e) {
        try {
            Object rc = e.getClass().getMethod("getResultCode").invoke(e);
            if (rc == null) {
                return "";
            }
            String name = String.valueOf(rc);
            try {
                java.lang.reflect.Field f;
                try {
                    f = rc.getClass().getField("code");
                } catch (NoSuchFieldException nsf) {
                    f = rc.getClass().getDeclaredField("code");
                    f.setAccessible(true);
                }
                return "（扩展码 " + f.get(rc) + " = " + name + "；宿主 Room 日志里写作 Error code: 787）";
            } catch (Throwable ignored) {
                return "（" + name + "）";
            }
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ------------------------------------------------------------------ 旧逻辑（0.11.6 原样 SQL）

    /** @return {Playlist 影响行数, Track 影响行数, 关联行影响行数} */
    private static int[] oldLogic(Connection c, String plId, String plName, int songs) throws SQLException {
        int pl;
        try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO Playlist (id, title,"
                + " coverModifiedTime, isUserEditedCover, description, createdTime, `order`, trackSort,"
                + " trackSortDescending) VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, plId);
            ps.setString(2, plName);
            ps.setLong(3, 0L);
            ps.setLong(4, 0L);
            ps.setString(5, "探针");
            ps.setLong(6, System.currentTimeMillis());
            ps.setLong(7, 0L);
            ps.setLong(8, 0L);
            ps.setLong(9, 0L);
            pl = ps.executeUpdate();
        }
        int tr = 0;
        int ln = 0;
        int order = 0;
        for (Dto.Song s : songs()) {
            String id = PREFIX + s.id();
            try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO Track"
                    + " (id, title, artist, album, albumArtist, genre, year, number, isFavorite, playCount,"
                    + " `order`, path, readable, size, addedTime, modifiedTime, duration, bitsPerSample,"
                    + " sampleRate, bitrate) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, s.name());
                ps.setString(3, s.artists());
                ps.setString(4, s.album());
                ps.setString(5, s.artists());
                ps.setString(6, "");
                ps.setLong(7, 0L);
                ps.setLong(8, order + 1);
                ps.setLong(9, 0L);
                ps.setLong(10, 0L);
                ps.setLong(11, order);
                ps.setString(12, URL_PREFIX + s.id());
                ps.setLong(13, 1L);
                ps.setLong(14, 0L);
                ps.setLong(15, System.currentTimeMillis());
                ps.setLong(16, NativeLibrary.PINNED_MODIFIED_TIME);
                ps.setLong(17, s.durationMs());
                ps.setLong(18, 0L);
                ps.setLong(19, 0L);
                ps.setDouble(20, 0.0d);
                tr += ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO PlaylistTrack"
                    + " (playlistId, trackId, addedTime, `order`) VALUES (?,?,?,?)")) {
                ps.setString(1, plId);
                ps.setString(2, id);
                ps.setLong(3, System.currentTimeMillis());
                ps.setLong(4, order);
                ln += ps.executeUpdate();
            }
            order++;
        }
        return new int[]{pl, tr, ln};
    }

    private static List<Dto.Song> songs() {
        List<Dto.Song> out = new ArrayList<>();
        for (int i = 0; i < SONGS; i++) {
            long id = 900001L + i;
            out.add(song(id, i + 1));
        }
        return out;
    }

    private static Dto.Song song(long id, int n) {
        return new Dto.Song(id, "探针曲目 " + n, "探针歌手", "探针专辑", 180000L + n, 0, true, null);
    }

    private static List<Dto.Song> songsOf(long... ids) {
        List<Dto.Song> out = new ArrayList<>();
        for (long id : ids) {
            out.add(song(id, (int) (id - 900000L)));
        }
        return out;
    }

    // ------------------------------------------------------------------ 连接与计数

    /** {@code foreign_keys} 显式设置：false 模拟 0.11.6 的自带连接，true 模拟宿主池连接。 */
    private static Connection open(Path db, boolean foreignKeys) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA foreign_keys=" + (foreignKeys ? "ON" : "OFF"));
        }
        return c;
    }

    private static String counts(Connection c, String indent) throws SQLException {
        long track = scalar(c, "SELECT COUNT(*) FROM Track");
        long trackPl = scalar(c, "SELECT COUNT(*) FROM Track WHERE id LIKE '" + PREFIX + "%'");
        long pl = scalar(c, "SELECT COUNT(*) FROM Playlist");
        long plPl = scalar(c, "SELECT COUNT(*) FROM Playlist WHERE id LIKE '" + PREFIX + "%'");
        long link = scalar(c, "SELECT COUNT(*) FROM PlaylistTrack");
        long linkPl = scalar(c, "SELECT COUNT(*) FROM PlaylistTrack WHERE playlistId LIKE '" + PREFIX + "%'");
        long orphan = scalar(c, "SELECT COUNT(*) FROM PlaylistTrack lt"
                + " WHERE lt.trackId NOT IN (SELECT id FROM Track)");
        long orphanPl = scalar(c, "SELECT COUNT(*) FROM PlaylistTrack lt"
                + " WHERE lt.playlistId LIKE '" + PREFIX + "%' AND lt.trackId NOT IN (SELECT id FROM Track)");
        long album = scalar(c, "SELECT COUNT(*) FROM Album");
        long artist = scalar(c, "SELECT COUNT(*) FROM Artist");
        return "Track 总计=" + track + "（插件 " + trackPl + "）/ Playlist=" + pl + "（插件 " + plPl + "）"
                + " / PlaylistTrack=" + link + "（插件 " + linkPl + "，孤儿 " + orphanPl + "，全库孤儿 " + orphan + "）"
                + " / Album=" + album + " Artist=" + artist;
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static String scalarText(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** 拷三件套：spw.db + -wal + -shm（少一个就读到空库）。 */
    private static Path copy(Path src, Path dstDir) throws Exception {
        Files.createDirectories(dstDir);
        Path db = dstDir.resolve("spw.db");
        int n = 0;
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Path from = Paths.get(src.toAbsolutePath().toString(), "spw.db" + suffix);
            if (Files.isRegularFile(from)) {
                Files.copy(from, Paths.get(db.toAbsolutePath().toString() + suffix),
                        StandardCopyOption.REPLACE_EXISTING);
                n++;
            }
        }
        if (n == 0 || !Files.isRegularFile(db)) {
            throw new IllegalStateException("副本没拷成：来源 " + src + " 里没有 spw.db（三件套要齐全）");
        }
        return db;
    }

    private static void check(String what, boolean ok) {
        System.out.println("    " + (ok ? "[OK] " : "[!!] ") + what);
        if (!ok) {
            FAILS.add(what);
        }
    }

    /** 探针自己的 {@link NativeLibrary.SqlSchema} 适配器（真机上是 HostSqlBridge.HostSql 干这活）。 */
    private record ProbeSql(Connection conn) implements NativeLibrary.SqlSchema {

        @Override
        public void exec(String sql, Object... args) throws Exception {
            execUpdate(sql, args);
        }

        @Override
        public int execUpdate(String sql, Object... args) throws Exception {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, args);
                return ps.executeUpdate();
            }
        }

        @Override
        public List<Object[]> rows(String sql, Object... args) throws Exception {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    int n = rs.getMetaData().getColumnCount();
                    List<Object[]> out = new ArrayList<>();
                    while (rs.next()) {
                        Object[] row = new Object[n];
                        for (int i = 0; i < n; i++) {
                            row[i] = rs.getObject(i + 1);
                        }
                        out.add(row);
                    }
                    return out;
                }
            }
        }

        private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
            if (args == null) {
                return;
            }
            for (int i = 0; i < args.length; i++) {
                Object v = args[i];
                int idx = i + 1;
                if (v == null) {
                    ps.setObject(idx, null);
                } else if (v instanceof Integer) {
                    ps.setInt(idx, (Integer) v);
                } else if (v instanceof Long) {
                    ps.setLong(idx, (Long) v);
                } else if (v instanceof Double) {
                    ps.setDouble(idx, (Double) v);
                } else if (v instanceof Float) {
                    ps.setDouble(idx, (Float) v);
                } else if (v instanceof byte[]) {
                    ps.setBytes(idx, (byte[]) v);
                } else {
                    ps.setString(idx, String.valueOf(v));
                }
            }
        }
    }
}
