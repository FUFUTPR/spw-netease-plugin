import com.example.netease.core.DataPaths;
import com.example.netease.core.PluginLog;
import com.example.netease.net.Dto;
import com.example.netease.net.NeteaseApi;
import com.example.netease.net.SmokeHooks;
import com.example.netease.svc.CoverStore;
import com.example.netease.svc.LyricService;
import com.example.netease.svc.NativeLibrary;
import com.example.netease.svc.StreamPrefetcher;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * W6「禁止静默失败」失败可见性实证 —— 离线故障实验室（只读真机数据，绝不写宿主）。
 *
 * <p>每个场景一个独立 JVM：{@code APPDATA} 指向本次沙箱，日志落在沙箱里；不连宿主、不起宿主、
 * 不读本机网易云客户端、不下载音乐。真库只被<b>只读</b>复制成副本（由 {@code failure-lab.py} 完成），
 * 所有写入只发生在副本上。</p>
 *
 * <p>用法：{@code java -cp ... FailureLab <scene> [k=v ...]}；场景列表见 {@link #main} 的 switch。</p>
 *
 * <p>输出契约：每个场景最后一行是机器可读的
 * {@code [LAB] scene=<name> status=<ok|fail|threw|bad-success|skip> ...}，
 * 由 {@code tools/smoke/failure-lab.py} 解析并与沙箱日志交叉核对。</p>
 */
public final class FailureLab {

    /** 沙箱内假歌单/曲目 id 段：远离真机 id，且一律走 netease- 前缀（红线：不写非 netease- 前缀的行）。 */
    private static final long LAB_PLAYLIST_BASE = 900000L;
    private static final String LAB_URL_PREFIX = "http://127.0.0.1:17788/netease/";

    public static void main(String[] args) {
        String scene = args.length == 0 ? "" : args[0];
        Map<String, String> a = parse(args);
        System.out.println("[LAB] scene=" + scene + " java=" + System.getProperty("java.version")
                + " appdata=" + System.getenv("APPDATA"));
        int code;
        try {
            code = switch (scene) {
                case "sync-ok" -> syncOk(a);
                case "sync-silent-write" -> syncSilent(a, true);
                case "sync-silent-count" -> syncSilent(a, false);
                case "net-dead" -> netDead();
                case "net-probe" -> netProbe(a);
                case "lyric" -> lyric(a);
                case "cover" -> cover(a);
                case "stream" -> stream(a);
                default -> {
                    System.out.println("[LAB] status=skip detail=unknown scene " + scene);
                    yield 3;
                }
            };
        } catch (Throwable t) {
            System.out.println("[LAB] UNCAUGHT " + t.getClass().getName() + ": " + oneLine(t.getMessage()));
            t.printStackTrace(System.out);
            code = 4;
        }
        System.out.flush();
        System.exit(code);
    }

    // ------------------------------------------------------------------ S1 同步（写库侧）

    /**
     * 正对照：真 JDBC 连接（指向真库的<b>只读副本</b>）跑整轮写入 ⇒ 必须 ok，且库里必须真有行。
     */
    private static int syncOk(Map<String, String> a) throws Exception {
        bootLog();
        String db = need(a, "db");
        try (Connection c = connect(db)) {
            NativeLibrary.Result r = NativeLibrary.writeOn(new JdbcSchema(c), entries(), LAB_URL_PREFIX);
            long tracks = scalar(c, "SELECT COUNT(*) FROM Track WHERE id LIKE 'netease-9%'");
            long links = scalar(c, "SELECT COUNT(*) FROM PlaylistTrack WHERE playlistId LIKE 'netease-pl-9%'");
            long pls = scalar(c, "SELECT COUNT(*) FROM Playlist WHERE id LIKE 'netease-pl-9%'");
            boolean ok = r.ok() && tracks == 6 && links == 6 && pls == 2;
            System.out.println("[LAB] status=" + (ok ? "ok" : "fail")
                    + " result=" + r + " dbTracks=" + tracks + " dbLinks=" + links + " dbPlaylists=" + pls);
            return ok ? 0 : 1;
        }
    }

    /**
     * 伪造「写进去却不留痕」的通道（复刻 0.11.6 病灶：宿主那个没有结果反馈的连接）。
     *
     * @param existsLiesEmpty true = 连「曲目行存不存在」都答「不存在」（较早的防线抓住）；
     *                        false = 存在性撒谎成功、只有行数自检能抓住（最晚的防线）
     */
    private static int syncSilent(Map<String, String> a, boolean existsLiesEmpty) throws Exception {
        bootLog();
        String db = need(a, "db");
        try (Connection c = connect(db)) {
            SilentSql fake = new SilentSql(c, existsLiesEmpty);
            try {
                NativeLibrary.Result r = NativeLibrary.writeOn(fake, entries(), LAB_URL_PREFIX);
                System.out.println("[LAB] status=bad-success result=" + r
                        + " detail=写入自检没拦住「静默丢弃」");
                return 1;
            } catch (Throwable t) {
                String msg = oneLine(t.getMessage());
                System.out.println("[LAB] status=threw class=" + t.getClass().getName() + " msg=" + msg);
                return 0;
            }
        }
    }

    // ------------------------------------------------------------------ S2 同步（网络侧）

    private static int netDead() {
        try {
            Dto.Playlist p = NeteaseApi.playlist(1L);
            System.out.println("[LAB] status=bad-success tracks=" + p.tracks().size()
                    + " detail=断网下取歌单竟然成功");
            return 1;
        } catch (Throwable t) {
            System.out.println("[LAB] status=threw class=" + t.getClass().getName() + " msg=" + oneLine(t.getMessage())
                    + " cause=" + causeChain(t));
            return 0;
        }
    }

    /** HTTP 层正对照：同一段分类逻辑在不同失败原因下给出不同结论（证明实验室能区分「失败」与「成功」）。 */
    private static int netProbe(Map<String, String> a) {
        String url = a.getOrDefault("url", "http://127.0.0.1:9/w6lab-probe");
        String out;
        try {
            out = SmokeHooks.httpProbe(url);
        } catch (Throwable t) {
            out = "THREW " + t.getClass().getSimpleName() + ": " + oneLine(t.getMessage());
        }
        System.out.println("[LAB] status=ok url=" + url + " probe=" + oneLine(out));
        return 0;
    }

    // ------------------------------------------------------------------ S4 歌词

    private static int lyric(Map<String, String> a) throws Exception {
        boot(a);
        long id = Long.parseLong(a.getOrDefault("id", "999999999"));
        String path = LAB_URL_PREFIX + id;
        System.out.println("[LAB] before stats=" + LyricService.stats()
                + " cached=" + LyricService.cachedCount());
        long t0 = System.currentTimeMillis();
        LyricService.ensureAsync(id, path, "W6LAB 曲目", "W6LAB 歌手");
        Thread.sleep(Long.parseLong(a.getOrDefault("wait", "3000")));
        String lrc = LyricService.lrcFor(path, "W6LAB 曲目", "W6LAB 歌手");
        // 换曲那一刻必须命中（R14）：这里只记录 miss，不判失败（\u8be5句由 W4 的预热自证）
        System.out.println("[LAB] status=ok waited=" + (System.currentTimeMillis() - t0) + "ms lrcFor=" + (lrc == null ? "null" : lrc.length() + "字")
                + " after stats=" + LyricService.stats() + " cached=" + LyricService.cachedCount());
        return 0;
    }

    // ------------------------------------------------------------------ S3 封面

    private static int cover(Map<String, String> a) throws Exception {
        boot(a);
        String album = a.getOrDefault("album", "W6LAB-专辑-存在");
        String artist = a.getOrDefault("artist", "W6LAB 歌手");
        String url = a.getOrDefault("url", "http://127.0.0.1:9/w6lab-missing.jpg");
        String uri = CoverStore.ensure(album, artist, url);
        Thread.sleep(Long.parseLong(a.getOrDefault("wait", "1500")));
        System.out.println("[LAB] status=ok ensure=" + (uri == null ? "null" : uri)
                + " stats=" + oneLine(CoverStore.stats()) + " stubs=" + CoverStore.countStubs());
        return 0;
    }

    // ------------------------------------------------------------------ S5 播放（预取）

    private static int stream(Map<String, String> a) throws Exception {
        boot(a);
        long id = Long.parseLong(a.getOrDefault("id", "999999999"));
        String level = a.getOrDefault("quality", "lossless");
        StreamPrefetcher.prefetchNow(id, level);
        boolean done = StreamPrefetcher.awaitComplete(id, level,
                Long.parseLong(a.getOrDefault("wait", "6000")));
        System.out.println("[LAB] status=ok awaitComplete=" + done
                + " storeReady=" + StreamPrefetcher.storeReady()
                + " stats=" + oneLine(StreamPrefetcher.stats()));
        return 0;
    }

    // ------------------------------------------------------------------ 公共

    /** 起沙箱环境（APPDATA 由启动器设好）：数据目录 + 日志目录都在沙箱里。 */
    private static void boot(Map<String, String> a) throws Exception {
        bootLog();
        PluginLog.setLevel(a.getOrDefault("level", "INFO"));
    }

    /** 只把日志/数据目录指到沙箱（写库类场景不需要配置项，也就不碰配置层）。 */
    private static void bootLog() throws Exception {
        DataPaths.init("com.example.netease");
        PluginLog.init(DataPaths.logs());
        System.out.println("[LAB] boot data=" + DataPaths.data() + " logs=" + DataPaths.logs()
                + " level=" + PluginLog.currentLevel());
    }

    private static List<NativeLibrary.Entry> entries() {
        List<NativeLibrary.Entry> list = new ArrayList<>();
        for (long pl = LAB_PLAYLIST_BASE + 1; pl <= LAB_PLAYLIST_BASE + 2; pl++) {
            List<Dto.Song> songs = new ArrayList<>();
            for (long i = 1; i <= 3; i++) {
                long id = LAB_PLAYLIST_BASE * 10 + pl % 100 * 10 + i;
                songs.add(new Dto.Song(id, "W6LAB 曲目 " + id, "W6LAB 歌手", "W6LAB 专辑",
                        210000L, 0, true, ""));
            }
            list.add(new NativeLibrary.Entry(pl, "W6LAB 歌单 " + pl, songs, ""));
        }
        return list;
    }

    private static Connection connect(String db) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Properties p = new Properties();
        p.setProperty("busy_timeout", "5000");
        p.setProperty("foreign_keys", "true");
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + db, p);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys=ON");
        }
        c.setAutoCommit(true);
        return c;
    }

    private static long scalar(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /** 真 JDBC 通道的 {@link NativeLibrary.SqlSchema} 适配（插件自己的 JdbcSql 是包内私有）。 */
    private static final class JdbcSchema implements NativeLibrary.SqlSchema {

        private final Connection c;

        JdbcSchema(Connection c) {
            this.c = c;
        }

        @Override
        public void exec(String sql, Object... args) throws Exception {
            execUpdate(sql, args);
        }

        @Override
        public List<Object[]> rows(String sql, Object... args) throws Exception {
            try (var ps = c.prepareStatement(sql)) {
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

        @Override
        public int execUpdate(String sql, Object... args) throws Exception {
            try (var ps = c.prepareStatement(sql)) {
                bind(ps, args);
                return ps.executeUpdate();
            }
        }

        private static void bind(java.sql.PreparedStatement ps, Object[] args) throws Exception {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
        }
    }

    /**
     * 0.11.6 病灶的复刻：<b>写语句「成功」、但库里什么都没有</b>。
     *
     * <p>schema 探测（{@code PRAGMA table_info}）走真副本，所以列集合是真的 —— 只有「写」和
     * 「数行数」这两个动作在撒谎，正如当年那条没有结果反馈的连接。</p>
     */
    private static final class SilentSql implements NativeLibrary.SqlSchema {

        private final JdbcSchema real;
        private final boolean existsLiesEmpty;
        private int writes;

        SilentSql(Connection c, boolean existsLiesEmpty) {
            this.real = new JdbcSchema(c);
            this.existsLiesEmpty = existsLiesEmpty;
        }

        @Override
        public void exec(String sql, Object... args) {
            writes++;
        }

        @Override
        public int execUpdate(String sql, Object... args) {
            writes++;
            return 1;                       // 谎报「写成功」
        }

        @Override
        public List<Object[]> rows(String sql, Object... args) throws Exception {
            String s = sql.trim().toUpperCase(java.util.Locale.ROOT);
            if (s.startsWith("PRAGMA")) {
                return real.rows(sql, args);      // schema 探测照实回答
            }
            if (s.startsWith("SELECT 1 FROM TRACK")) {
                return existsLiesEmpty ? List.<Object[]>of() : List.<Object[]>of(new Object[]{1L});
            }
            if (s.contains("COUNT(*)")) {
                return List.<Object[]>of(new Object[]{0L}); // 谎报「库里 0 行」
            }
            System.out.println("[LAB] SILENT-SQL 未分类查询（按空结果答）：" + oneLine(sql));
            return List.of();
        }

        @Override
        public String toString() {
            return "SilentSql{writes=" + writes + ", existsLiesEmpty=" + existsLiesEmpty + "}";
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq > 0) {
                m.put(args[i].substring(0, eq), args[i].substring(eq + 1));
            }
        }
        return m;
    }

    private static String need(Map<String, String> a, String key) {
        String v = a.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("缺少参数 " + key + "=");
        }
        return v;
    }

    private static String causeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t.getCause(); c != null && sb.length() < 300; c = c.getCause()) {
            sb.append(" <- ").append(c.getClass().getSimpleName()).append(": ").append(oneLine(c.getMessage()));
        }
        return sb.toString();
    }

    static String oneLine(String s) {
        return s == null ? "null" : s.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
