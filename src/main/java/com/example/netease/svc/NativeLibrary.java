package com.example.netease.svc;

import com.example.netease.core.DataPaths;
import com.example.netease.core.Notifier;
import com.example.netease.core.PluginLog;
import com.example.netease.core.Sql;
import com.example.netease.net.Dto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 0.7.0 原生集成的<b>宿主曲库写入器</b>：把网易云歌单写成宿主 Salt Player 自己的库行，
 * 让它们直接出现在宿主原生 UI 的侧栏与「歌曲」页里。
 *
 * <p><b>为什么必须写库</b>：宿主的工作坊 API（1.18.5）里 {@code WorkshopApi$Ui} 只有一个
 * {@code toast()}，没有任何「向原生侧栏/列表注入条目」的接口；而宿主界面完全由它自己的 Room 库
 * （{@code %APPDATA%\Salt Player for Windows\spw.db}）驱动。真机实证（2026-09-29）：
 * 往 {@code Track}/{@code Playlist}/{@code PlaylistTrack} 插入外部行，宿主启动后照常显示，
 * 且在原生 UI 里点一下就能播放（{@code path} 为 http URL 时由宿主自带的
 * {@code HttpRangeBassStream} 拉流）。</p>
 *
 * <p><b>写入契约（受控写入）</b>：</p>
 * <ul>
 *   <li>只写 {@code Playlist} / {@code Track} / {@code PlaylistTrack} 三张表的<b>行</b>；
 *       0.8.0 起额外<b>只更新</b> {@code Album}/{@code Artist} 两表的 {@code cover} 三列
 *       （封面字段，值指向插件自己造的本地封面桩；不插入行、不碰宿主其它字段，见 {@link CoverArt}）；</li>
 *   <li>只碰 {@code id} 以 {@link #ID_PREFIX} 开头的行 —— 宿主原有行一个字节都不动；</li>
 *   <li>写前自动备份 {@code spw.db}(+{@code -wal}/-{@code shm}) 到插件数据目录的
 *       {@code db-backup/} 下；{@link #purgeAll()} 可一键撤销全部插件写入；</li>
 *   <li>任何异常都只降级为「本次同步失败」，绝不影响插件其余功能与宿主运行。</li>
 * </ul>
 *
 * <p>SQLite 驱动走插件自带的 {@code lib/sqlite-jdbc-*.jar}：直接 {@code new org.sqlite.JDBC()}
 * 再 {@code connect(url, props)}，绕开 {@code DriverManager} 的类加载器可见性限制。</p>
 */
public final class NativeLibrary {

    private static final String TAG = "native-lib";

    /** 插件写入行的统一前缀：宿主原有 id 是 UUID，绝不会撞。 */
    public static final String ID_PREFIX = "netease-";

    /**
     * 本插件曲目 {@code Track.modifiedTime} 的**钉住值**（0.11.4）。
     *
     * <p>为什么必须钉：宿主「歌曲行 / 播放条」的封面缓存键逐字为
     * {@code "{Track.path}?v=1&t={modifiedTime}&s={size}&r={coverRevision}&w=&h="}（见 docs/46 §3）。
     * 原先同步每轮都把 {@code modifiedTime} 写成「当前时间」⇒ 键漂移：行重绘会按<b>新键</b>请求，
     * 而缓存里只有<b>旧键</b>的图 ⇒ 宿主写一条 0 字节负缓存 ⇒ 封面当场消失（真机逐条实证：那些
     * 负条目的 t 值与每轮同步时刻一一对应）。写库时直接用常量，键就跨同步、跨会话稳定。</p>
     */
    public static final long PINNED_MODIFIED_TIME = 1735689600000L;

    /** 歌单 id 前缀（与曲目前缀区分，便于日志与人眼辨认）。 */
    public static final String PLAYLIST_PREFIX = ID_PREFIX + "pl-";

    private static final String HOST_DIR_NAME = "Salt Player for Windows";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 本次进程内是否已经备份过（每次启动最多备份一次，避免刷爆磁盘）。 */
    private static volatile String lastBackup;

    private NativeLibrary() {
    }

    // ------------------------------------------------------------------ 路径

    /** 宿主数据目录：{@code %APPDATA%\Salt Player for Windows}。 */
    public static Path hostDataDir() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            return null;
        }
        return Paths.get(appData, HOST_DIR_NAME);
    }

    /** 宿主曲库文件。 */
    public static Path dbPath() {
        Path dir = hostDataDir();
        return dir == null ? null : dir.resolve("spw.db");
    }

    /** 宿主曲库是否可写（存在且非只读）。 */
    public static boolean available() {
        Path db = dbPath();
        return db != null && Files.isRegularFile(db) && Files.isWritable(db);
    }

    /** 宿主曲库是否正在被运行中的宿主打开（进程内看：文件被锁则视为打开）。 */
    public static String describe() {
        Path db = dbPath();
        if (db == null) {
            return "找不到 APPDATA";
        }
        if (!Files.isRegularFile(db)) {
            return "曲库不存在：" + db;
        }
        try {
            return db + "（" + Math.round(Files.size(db) / 1024.0) + " KB）";
        } catch (Throwable t) {
            return String.valueOf(db);
        }
    }

    // ------------------------------------------------------------------ 备份

    /**
     * 备份宿主曲库（{@code spw.db} + WAL/SHM）到 {@code <插件数据>/db-backup/<时间戳>-<原因>/}。
     *
     * @return 备份目录名；失败或本次已备份返回最近一次的备份名（可能为 null）
     */
    public static synchronized String backupOnce(String why) {
        if (lastBackup != null) {
            return lastBackup;
        }
        Path db = dbPath();
        if (db == null || !Files.isRegularFile(db)) {
            return null;
        }
        try {
            Path root = DataPaths.ensure(DataPaths.data().resolve("db-backup"));
            String name = LocalDateTime.now().format(STAMP) + "-" + why;
            Path dir = Files.createDirectories(root.resolve(name));
            int n = 0;
            for (String suffix : new String[]{"", "-wal", "-shm"}) {
                Path src = db.resolveSibling(db.getFileName() + suffix);
                if (Files.isRegularFile(src)) {
                    Files.copy(src, dir.resolve(src.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                    n++;
                }
            }
            lastBackup = name;
            PluginLog.i(TAG, "已备份宿主曲库 " + n + " 个文件 → " + dir);
            return name;
        } catch (Throwable t) {
            PluginLog.w(TAG, "备份宿主曲库失败（继续写入）：" + t);
            return null;
        }
    }

    /** 最近一次备份目录名（未备份过为 null）。 */
    public static String lastBackup() {
        return lastBackup;
    }

    /** 列出所有备份目录名（新→旧）。 */
    public static List<String> listBackups() {
        List<String> out = new ArrayList<>();
        try {
            Path root = DataPaths.data().resolve("db-backup");
            if (!Files.isDirectory(root)) {
                return out;
            }
            try (var s = Files.list(root)) {
                s.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .sorted((a, b) -> b.compareTo(a))
                        .forEach(out::add);
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "列备份失败（忽略）：" + t);
        }
        return out;
    }

    // ------------------------------------------------------------------ 同步

    /** 一个待同步的歌单。 */
    public record Entry(long playlistId, String name, List<Dto.Song> songs, String coverUrl) {

        /** 3 参兼容构造器（0.10.0 加 {@code coverUrl} 时补）：没有封面 URL（空串）。 */
        public Entry(long playlistId, String name, List<Dto.Song> songs) {
            this(playlistId, name, songs, "");
        }
    }

    /** 同步结果摘要。 */
    public record Result(int playlists, int tracks, int links, int covers, String backup, String error) {

        /** 无封面计数的便捷构造（0.7.0 及以前的调用点照旧）。 */
        public Result(int playlists, int tracks, int links, String backup, String error) {
            this(playlists, tracks, links, 0, backup, error);
        }

        public boolean ok() {
            return error == null;
        }

        @Override
        public String toString() {
            return ok()
                    ? "歌单 " + playlists + " 个 / 曲目 " + tracks + " 首 / 关联 " + links + " 行"
                    + (covers > 0 ? " / 封面 " + covers + " 张" : "")
                    + "（备份 " + backup + "）"
                    : "失败：" + error;
        }
    }

    /**
     * 能读结果、能拿影响行数的 {@link Sql} 扩展（0.11.7 / P0）。
     *
     * <p>{@code core/Sql} 只有「发一条语句」一个动作、而且不带返回值。P0 要它做不到的三件事：
     * ① {@code PRAGMA table_info} 探测表的<b>真实列集合</b>；② {@code SELECT 1} 断言目标行存在；
     * ③ 校验 {@code executeUpdate()} 的<b>影响行数</b>（{@code 0} = 没写进去，必须当失败看）。</p>
     *
     * <p>两条通路（插件自带 {@code sqlite-jdbc} / 宿主 Room 连接）都必须实现它。实现不了的一律
     * <b>直接抛错</b>，不许悄悄退回「发语句不管结果」的老写法 —— 0.11.6 真机正是那条老写法
     * 把 5935 条孤儿关联行当成功写进了宿主曲库。</p>
     */
    public interface SqlSchema extends Sql {

        /** 只读查询：每行一个 {@code Object[]}（按 SELECT 的列顺序，驱动给的原生类型）。 */
        List<Object[]> rows(String sql, Object... args) throws Exception;

        /** 执行写语句并返回影响行数（调用方负责校验 {@code 0}）。 */
        int execUpdate(String sql, Object... args) throws Exception;
    }

    /**
     * 把若干歌单整体同步进宿主曲库：先删掉这些歌单在插件前缀下的旧行，再写入新行（单事务）。
     *
     * @param entries  待同步歌单
     * @param urlPrefix 曲目 path 前缀（通常是 {@link NativeStreamServer#baseUrl()}+"/netease/"）
     */
    public static Result sync(List<Entry> entries, String urlPrefix) {
        return sync(entries, urlPrefix, null, null);
    }

    /**
     * 同 {@link #sync(List, String)}，但额外把封面桩挂到 {@code Album}/{@code Artist} 行上（0.8.0 起）。
     *
     * <p>这是**回退路线**：外部 JDBC 是另一条连接，Room 的失效触发器不响，
     * 写完要重启宿主才会在原生 UI 里出现（主线见 {@code host/HostSqlBridge}）。</p>
     */
    public static Result sync(List<Entry> entries, String urlPrefix, List<CoverArt.Cover> covers) {
        return sync(entries, urlPrefix, covers, null);
    }

    /**
     * 完整形态（0.10.0 起）：额外把「歌单封面时间」写进 {@code Playlist.coverModifiedTime}
     * （宿主拿它做封面图缓存键，见 {@link PlaylistCover}）。
     *
     * @param playlistCoverTimes 宿主歌单行 id → 封面文件 mtime（可为 null = 本轮没有歌单封面）
     */
    public static Result sync(List<Entry> entries, String urlPrefix, List<CoverArt.Cover> covers,
                              Map<String, Long> playlistCoverTimes) {
        if (entries == null || entries.isEmpty()) {
            return new Result(0, 0, 0, null, "没有要同步的歌单");
        }
        if (!available()) {
            return new Result(0, 0, 0, null, "宿主曲库不可写：" + describe());
        }
        String backup = backupOnce("sync");
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA busy_timeout=5000");
            }
            try {
                Result r = writeOn(new JdbcSql(c), entries, urlPrefix, backup, covers, playlistCoverTimes);
                c.commit();
                PluginLog.i(TAG, "曲库同步完成（外部 JDBC 连接）：" + r + "（备份 " + backup + "，曲库 " + describe() + "）");
                return new Result(r.playlists(), r.tracks(), r.links(), r.covers(), backup, null);
            } catch (Throwable t) {
                // 0.11.7（P0）：出错必须显式回滚（原先只记日志、靠关连接兜底），并且必须 ERROR + 不吞。
                // 「没写进去」绝不许被当成同步成功 —— 0.11.6 真机就是一路静默走完全流程的。
                try {
                    c.rollback();
                } catch (Throwable ignored) {
                    // 连接已经坏了：回滚失败没有别的补救动作，下面的 ERROR 日志才是要紧的
                }
                throw t;
            }
        } catch (Throwable t) {
            PluginLog.e(TAG, "曲库同步失败（本轮已中止、已回滚）：" + t, t);
            return new Result(0, 0, 0, backup, String.valueOf(t));
        }
    }

    /**
     * 在一段**已经打开**的连接上跑完整轮写入（事务归调用方管，本方法只发语句）。
     *
     * <p>0.7.0 起这是唯一的写语句实现：外部 JDBC 路线（{@link #sync}）与宿主同连接路线
     * （{@code host/HostSqlBridge}）都调它，保证两条通路发的 SQL 一字不差，只换连接。
     * 宿主同连接才是主线——只有同连接写入才会点着 Room 的失效触发器、宿主 UI 才会免重启刷新。</p>
     */
    public static Result writeOn(Sql sql, List<Entry> entries, String urlPrefix) throws Exception {
        return writeOn(sql, entries, urlPrefix, null, null, null);
    }

    /**
     * 同上，但把「本轮同步前做的备份目录」带进结果里（宿主同连接路线在插件侧建备份，
     * 写语句在宿主线程里跑，两边只有通过这个参数才能把备份名对进同一份 {@link Result}）。
     */
    public static Result writeOn(Sql sql, List<Entry> entries, String urlPrefix, String backup) throws Exception {
        return writeOn(sql, entries, urlPrefix, backup, null, null);
    }

    /**
     * 完整形态（0.8.0 起）：写完歌单/曲目/关联后，再把封面桩写进 {@code Album}/{@code Artist} 行。
     *
     * <p>封面必须是「本地带图媒体文件」的 file URI（见 {@link CoverArt}）：宿主只从本地文件内嵌标签取图，
     * 我们的曲目 path 是 http 流、流里没有 PICTURE，所以宿主自己派生出来的 cover 永远取不到图。</p>
     *
     * @param covers 专辑封面清单（可为 null）；同名专辑只写一次
     */
    public static Result writeOn(Sql sql, List<Entry> entries, String urlPrefix, String backup,
                                 List<CoverArt.Cover> covers) throws Exception {
        return writeOn(sql, entries, urlPrefix, backup, covers, null);
    }

    /**
     * 最完整形态（0.10.0 起）：歌单行连同它们的封面时间一起写。
     *
     * <p>歌单封面文件由 {@link PlaylistCover} 提前落到宿主的 {@code data\playlist_cover\<歌单行 id>}；
     * 这里只负责把对应文件的 mtime 写进 {@code Playlist.coverModifiedTime}（宿主用它当图片缓存键，
     * 值一变，UI 就会重新取图）。</p>
     */
    public static Result writeOn(Sql sql, List<Entry> entries, String urlPrefix, String backup,
                                 List<CoverArt.Cover> covers, Map<String, Long> playlistCoverTimes)
            throws Exception {
        // P0：整轮写入依赖「能探 schema、能拿影响行数」的通道；拿不到就直接抛，不许将就。
        SqlSchema schema = requireSchema(sql, "整轮写入");
        int pls = 0;
        int pairs = 0;                                  // 歌单×曲目 对数（== links）
        Set<String> trackIds = new LinkedHashSet<>();    // 唯一曲目 id：曲目数必须按它算（见下方自检注释）
        Set<String> playlistIds = new LinkedHashSet<>(); // 本轮写过的歌单（孤儿自检只对本轮范围下断言）
        int links = 0;
        long now = System.currentTimeMillis();
        for (Entry e : entries) {
            String plId = PLAYLIST_PREFIX + e.playlistId();
            resetPlaylistForSync(schema, plId);
            long coverTime = playlistCoverTimes == null ? 0L : playlistCoverTimes.getOrDefault(plId, 0L);
            insertPlaylist(schema, plId, e.name(), "网易云音乐 · 由「网易云音乐接入」插件同步", now, pls, coverTime);
            pls++;
            playlistIds.add(plId);
            int order = 0;
            for (Dto.Song s : e.songs()) {
                String trId = ID_PREFIX + s.id();
                upsertTrack(schema, trId, s, urlPrefix, order, now);
                insertLink(schema, plId, trId, order, now);
                trackIds.add(trId);
                pairs++;
                links++;
                order++;
            }
        }
        // 0.11.7（P0 铁律 2）：写完必须<b>自检「库里真有多少行」</b>，与本轮意图不一致就 ERROR + 抛错中止本轮。
        // 真机铁证（0.11.6）：同一轮里宿主日志只落了一行 INFO「原生同步后 插件行：… 曲目 0」，然后照常当成功
        // —— 这种「结果与意图不一致」从 0.11.7 起一律中止（调用方会回滚，不提交任何半成品）。
        //
        // 注意「曲目数 = 唯一曲目数」：同一首歌出现在多张歌单里是常态（A 轮真机：5935 对里只有 3267 首），
        // 拿对数去比 Track 行数必然误报（0.11.7 A 轮第一版就误报成「写入被静默丢弃」并把整轮回滚了）。
        int want = trackIds.size();
        long trackRows = countPrefix(schema, "Track", "id");
        if (trackRows < want) {
            String msg = "写入自检未通过：本轮声明曲目 " + want + " 首（去重后；关联 " + pairs + " 行），库内实际只有 "
                    + trackRows + " 行（写入被静默丢弃）⇒ 中止本轮、不提交；请查日志里上面第一条 ERROR";
            PluginLog.e(TAG, msg);
            throw new IllegalStateException(msg);
        }
        long plRows = countPrefix(schema, "Playlist", "id");
        if (plRows < pls) {
            String msg = "写入自检未通过：本轮声明歌单 " + pls + " 个，库内实际只有 " + plRows
                    + " 行 ⇒ 中止本轮、不提交；请查日志里上面第一条 ERROR";
            PluginLog.e(TAG, msg);
            throw new IllegalStateException(msg);
        }
        // 0.11.7（P0 铁律 2 补强）：**真正该拦住的那件事** —— 孤儿关联行（trackId 在 Track 里不存在）。
        // 0.11.6 的病灶就是「5935 条关联行全部指向不存在的曲目」而没人报警。
        // 断言只对本轮写过的歌单下（那些行刚由本事务重建）；范围外的 netease- 歌单若还有遗留孤儿，
        // 只 ERROR 提示、不中止本轮的成果（清它们是一次性修复入口 deleteOrphanLinks() 的职责）。
        long orphanInRound = countOrphans(schema, playlistIds);
        if (orphanInRound > 0) {
            String msg = "写入自检未通过：本轮写入的歌单里有 " + orphanInRound
                    + " 条孤儿关联行（trackId 在 Track 里不存在，宿主 join 不到曲目）⇒ 中止本轮、不提交";
            PluginLog.e(TAG, msg);
            throw new IllegalStateException(msg);
        }
        long orphanAll = countOrphans(schema, null);
        if (orphanAll > 0) {
            PluginLog.e(TAG, "注意：库内还有 " + orphanAll + " 条 netease- 孤儿关联行（**不在本轮写入范围内**，"
                    + "多半是 0.11.6 遗留或已下架歌单）——本轮写入本身是完整的；"
                    + "要清掉它们请点配置页的「重建曲库映射」");
        }
        int coversWritten = writeCovers(schema, covers, now);
        PluginLog.i(TAG, "本轮写入：歌单 " + pls + " 个 / 曲目 " + want + " 首（去重后，关联 " + links + " 行）");
        return new Result(pls, want, links, coversWritten, backup, null);
    }

    /**
     * 数孤儿关联行（{@code PlaylistTrack.trackId} 在 {@code Track} 里不存在）。
     *
     * @param playlistIds 只数这些歌单；传 {@code null} 数全部 {@code netease-} 歌单
     */
    private static long countOrphans(SqlSchema c, Set<String> playlistIds) throws Exception {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM PlaylistTrack pt WHERE pt.playlistId LIKE ?"
                        + " AND NOT EXISTS (SELECT 1 FROM Track t WHERE t.id = pt.trackId)");
        List<Object> args = new ArrayList<>();
        args.add(ID_PREFIX + "%");
        if (playlistIds != null) {
            if (playlistIds.isEmpty()) {
                return 0L;
            }
            sql.append(" AND pt.playlistId IN (");
            for (int i = 0; i < playlistIds.size(); i++) {
                sql.append(i == 0 ? "?" : ",?");
            }
            sql.append(')');
            args.addAll(playlistIds);
        }
        List<Object[]> rows = c.rows(sql.toString(), args.toArray());
        return rows.isEmpty() || rows.get(0).length == 0 ? 0L : asLong(rows.get(0)[0]);
    }

    /**
     * 把封面桩挂到宿主的 {@code Album}/{@code Artist} 行上。
     *
     * <p>只发 UPDATE、不插入行：这两张表归宿主所有（它按 Track 里的 album/albumArtist 值派生行）。
     * 更新不上（行还没派生出来）也不报错——每轮同步都会重挂一次，下一轮自然补上。</p>
     */
    /**
     * 只挂封面（事务归调用方管）：供「宿主重读曲库之后再补挂一次」用。
     *
     * <p>宿主每次重建曲库快照都会按 {@code Track.path} 重新派生 {@code Album}/{@code Artist} 的 cover
     * ⇒ 写在重建<b>之前</b>的封面值会被抹掉（真机实证：同一批 32 张封面写进去、只读核对 32/32 命中，
     * 宿主重建后全变回 {@code http://127.0.0.1:17788/netease/<id>}、revision 归 0）。
     * 所以同步流程要在宿主重建<b>之后</b>再补挂一遍（见 {@code NeteasePlugin#reassertCovers}）。</p>
     */
    public static int writeCoversOn(Sql sql, List<CoverArt.Cover> covers) {
        try {
            return writeCovers(sql, covers, System.currentTimeMillis());
        } catch (Throwable t) {
            // 0.11.7（P0）：封面是旁路（不影响曲目映射，所以不中止本轮），但**失败必须可见** ——
            // 0.11.6 这里只打 debug 级，真机「封面核对：Album 命中 0/2090」全被淹没在 INFO 里。
            PluginLog.e(TAG, "补挂封面失败（不影响曲目映射，本轮继续）：" + t);
            return 0;
        }
    }

    /** 只挂封面（插件自带 JDBC 连接 + 独立事务）：宿主同连接不可用时的兜底。 */
    public static int reassertCoversExternal(List<CoverArt.Cover> covers) {
        if (covers == null || covers.isEmpty() || !available()) {
            return 0;
        }
        try (Connection c = open()) {
            c.setAutoCommit(false);
            int n = writeCovers(new JdbcSql(c), covers, System.currentTimeMillis());
            c.commit();
            return n;
        } catch (Throwable t) {
            PluginLog.d(TAG, "补挂封面（外部连接）失败（忽略）：" + t);
            return 0;
        }
    }

    /**
     * 把一条曲目的 {@code path} 改成本地音频缓存文件（0.9.0，事务归调用方管）。
     *
     * <p>为什么要在下载完成后单独改：宿主的「歌曲」行封面只认 {@code path} 指向的本地文件的内嵌图，
     * 而音频是**同步之后**才一首首下下来的。每下完一首就把这一行翻到本地文件，宿主重建曲库时
     * 行封面与专辑/艺术家封面都会跟着用上带图的本地文件。</p>
     *
     * <p>{@code coverRevision} 一起 +1：宿主按它决定要不要重取缩略图。</p>
     */
    public static int setTrackPathOn(Sql sql, String trackId, String fileUri, long size) {
        try {
            sql.exec("UPDATE Track SET path = ?, size = ?, readable = 1, modifiedTime = ?,"
                            + " coverRevision = coverRevision + 1 WHERE id = ?",
                    fileUri, size, System.currentTimeMillis(), trackId);
            return 1;
        } catch (Throwable t) {
            PluginLog.d(TAG, "改曲目 path 失败（忽略）：" + t);
            return 0;
        }
    }

    /** 把一条曲目的 {@code path} 改成本地音频缓存文件（插件自带 JDBC + 独立事务）：宿主同连接不可用时的兜底。 */
    public static int setTrackPathExternal(String trackId, String fileUri, long size) {
        if (!available()) {
            return 0;
        }
        try (Connection c = open()) {
            c.setAutoCommit(false);
            int n = setTrackPathOn(new JdbcSql(c), trackId, fileUri, size);
            c.commit();
            return n;
        } catch (Throwable t) {
            PluginLog.d(TAG, "改曲目 path（外部连接）失败（忽略）：" + t);
            return 0;
        }
    }

    /**
     * 升级迁移：把本插件曲目统一恢复为当前流服务地址（事务归调用方）。
     *
     * <p>0.9.0 会把 {@code Track.path} 改成旧版 {@link AudioCache} 的本地整首文件；仅删除那些
     * 文件会留下不可播放的死路径，而且旧文件还会把音质锁在首次下载的档位。0.10.0 起播放缓存
     * 完全由 {@link NativeStreamServer} 边播边写，所以升级时必须先改回稳定流地址，再清旧文件。</p>
     */
    public static void restoreStreamPathsOn(Sql sql, String urlPrefix) throws Exception {
        if (sql == null) {
            throw new IllegalArgumentException("sql is null");
        }
        String prefix = urlPrefix == null ? "" : urlPrefix.trim();
        if (prefix.isEmpty()) {
            throw new IllegalArgumentException("urlPrefix is blank");
        }
        if (!prefix.endsWith("/")) {
            prefix += "/";
        }
        // SQLite substr 是 1 基；"netease-" 长 8，所以从第 9 个字符开始就是 songId。
        sql.exec("UPDATE Track SET path = ? || substr(id, ?), size = 0, readable = 1, modifiedTime = ?"
                        + " WHERE id GLOB ?",
                prefix, ID_PREFIX.length() + 1, System.currentTimeMillis(), ID_PREFIX + "[0-9]*");
    }

    /**
     * 升级迁移（<b>只动旧版 {@code audio\} 目录</b>）：把 0.9.0 遗留在旧预下载目录里的曲目恢复为流地址。
     *
     * <p>0.11.2 起为什么必须收窄范围：{@code svc.RowCover} 会把「播过的歌」翻到 {@code audio-cover\}
     * 下的带图本地文件（行封面要它）。若这里仍然无条件还原<b>全部</b>本插件曲目，每次启动都会把行封面
     * 一次性推回流地址（等于白做）。判据用路径片段 {@code /audio/}（0.11.2 的目录叫 {@code audio-cover}，
     * 不会误命中）。</p>
     */
    public static void restoreLegacyStreamPathsOn(Sql sql, String urlPrefix, String legacyDirTag) throws Exception {
        if (sql == null) {
            throw new IllegalArgumentException("sql is null");
        }
        String prefix = urlPrefix == null ? "" : urlPrefix.trim();
        if (prefix.isEmpty()) {
            throw new IllegalArgumentException("urlPrefix is blank");
        }
        if (!prefix.endsWith("/")) {
            prefix += "/";
        }
        String tag = (legacyDirTag == null || legacyDirTag.isBlank()) ? "/audio/" : legacyDirTag;
        sql.exec("UPDATE Track SET path = ? || substr(id, ?), size = 0, readable = 1, modifiedTime = ?"
                        + " WHERE id GLOB ? AND path LIKE ?",
                prefix, ID_PREFIX.length() + 1, System.currentTimeMillis(), ID_PREFIX + "[0-9]*",
                "%" + tag + "%");
    }

    /**
     * 单曲还原：把一条曲目的 {@code path} 恢复成流地址（事务归调用方）。
     * <p>0.11.2「行封面」用：本地带图文件要被预算回收（或档位变更）时，<b>必须先还原 path 再删文件</b>，
     * 否则宿主会拿到一个不存在的本地路径（死路径 = 这首歌播不了）。</p>
     */
    public static int restoreStreamPathOn(Sql sql, String trackId, String urlPrefix) throws Exception {
        if (sql == null || trackId == null || trackId.isBlank()) {
            return 0;
        }
        String prefix = urlPrefix == null ? "" : urlPrefix.trim();
        if (prefix.isEmpty()) {
            return 0;
        }
        if (!prefix.endsWith("/")) {
            prefix += "/";
        }
        String id = trackId.trim();
        if (!id.startsWith(ID_PREFIX)) {
            return 0;
        }
        sql.exec("UPDATE Track SET path = ? || ?, size = 0, readable = 1, modifiedTime = ?,"
                        + " coverRevision = coverRevision + 1 WHERE id = ?",
                prefix, id.substring(ID_PREFIX.length()), System.currentTimeMillis(), id);
        return 1;
    }

    /**
     * 只读：行封面预热需要的曲目素材（{@code album / path / size / coverRevision}）。
     *
     * <p>0.11.3 {@code svc.CoverPrimer} 用。走本类已验证的 {@link #available()} + {@link #open()}
     * 路径 —— 插件启动早期 {@link #dbPath()} 可能还是 {@code null}，直接拼 JDBC URL 会连到一个空库
     * （表现为「读到 0 首」而不是报错）。</p>
     *
     * <p>线程：任意非宿主回调线程（只读连接，不占用宿主连接、不触发失效）。</p>
     */
    public static java.util.List<String[]> primeRows() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        if (!available()) {
            return out;
        }
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT album, path, size, coverRevision FROM Track WHERE path LIKE 'http://127.0.0.1%'")) {
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[]{
                            rs.getString(1) == null ? "" : rs.getString(1),
                            rs.getString(2) == null ? "" : rs.getString(2),
                            Long.toString(rs.getLong(3)),
                            Integer.toString(rs.getInt(4))});
                }
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "读取行封面素材失败（忽略：" + t + "）");
        }
        return out;
    }

    /**
     * 全库流式行的 {@code (trackId, location)} 清单（0.11.11 原生播放桥登记用）。
     *
     * <p>宿主在自己的列表里点歌时，它会取那一行曲目自己造曲目项（第 1 字段 = 行 {@code id}、
     * 第 6 字段 = 行 {@code path}）。只有当 {@code MusicVideoAudioSourceRegistry} 里存在
     * <b>完全一致</b>的 {@code (trackId, location)} 对时，宿主的音乐视频拦截器才会认领这个
     * {@code http://} 位置并开流；否则装载器对网络位置直接失败（真机表现为「点了没反应」）。
     * 因此插件必须在启动时把全库这一对<b>批量登记</b>进去，而不是等我们自己的试播链去登记。</p>
     *
     * <p>线程：任意非宿主回调线程（只读连接，不占用宿主连接、不触发失效）。</p>
     *
     * @return 每条 = {@code [trackId, location]}；库不可用时返回空表
     */
    public static java.util.List<String[]> streamSources() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        if (!available()) {
            return out;
        }
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, path FROM Track WHERE id LIKE 'netease-%' AND path LIKE 'http://127.0.0.1%'")) {
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    String path = rs.getString(2);
                    if (id == null || id.isBlank() || path == null || path.isBlank()) {
                        continue;
                    }
                    out.add(new String[]{id, path});
                }
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "读取流式曲目清单失败（忽略：" + t + "）");
        }
        return out;
    }

    /**
     * 单行曲目的封面引用键（{@code netease-<id>} → {@code Track.album + U+0001 + Track.artist}）：
     * 给播放条封面投递做兜底。
     *
     * <p>{@code CoverStore.SONGS} 只登记「同步进库的那批歌单」，宿主列表里的其它曲目查不到；
     * 这条查询直接问宿主库那一行，避免「播放条投不出图」退化成玄学。带上歌手（0.11.49）是为了
     * 和宿主 {@code Album} 行的 (title, artist) 口径一致 —— 同名专辑各挂各的图。</p>
     *
     * @return 引用键；查不到 / 库不可用返回 {@code null}
     */
    public static String refKeyOfTrack(long songId) {
        if (songId <= 0L || !available()) {
            return null;
        }
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT album, artist FROM Track WHERE id = ?")) {
            ps.setString(1, "netease-" + songId);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    String album = rs.getString(1);
                    if (album == null || album.isBlank()) {
                        return null;
                    }
                    return CoverStore.refKey(album, rs.getString(2));
                }
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "读曲目专辑失败（忽略：" + t + "）");
        }
        return null;
    }

    /**
     * 把本插件曲目的 {@code Track.modifiedTime} 钉成常量（0.11.3 行封面缓存键的稳定性前提）。
     *
     * <p>宿主行内缩略图缓存键 = {@code path?t={modifiedTime}&s={size}&r={coverRevision}}；同步轮每轮都会把
     * {@code modifiedTime} 写成一个新的「当前时间」⇒ 键全变，而宿主只在会话第一次取图时读一次缩略图缓存
     * 索引 ⇒ 新键它对内看不见、会被写成负缓存、封面当场消失。钉成常量后键跨同步、跨会话不变。</p>
     *
     * @return 受影响行数（{@code -1} = 库不可用）
     */
    public static int pinModifiedTimeExternal(long value) {
        if (!available()) {
            return -1;
        }
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE Track SET modifiedTime = ? WHERE id GLOB ? AND modifiedTime <> ?")) {
                ps.setLong(1, value);
                ps.setString(2, ID_PREFIX + "[0-9]*");
                ps.setLong(3, value);
                int n = ps.executeUpdate();
                c.commit();
                return n;
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "钉 modifiedTime（外部连接）失败（忽略）：" + t);
            return -1;
        }
    }

    /**
     * 只读：取出「{@code path} 已经指向本地带图文件」的曲目（id → path）。
     *
     * <p>给 {@code svc.RowCover#reapplyAll} 判重用：已经是本地文件的曲目不再重复 UPDATE ——
     * 每次同步都白撞一次 {@code coverRevision} 会让宿主把缩略图全部重取一遍。</p>
     *
     * <p>线程：任意非宿主回调线程（自带只读 JDBC 连接，不占宿主连接、不触发失效）。</p>
     */
    public static java.util.Map<String, String> localCoverPaths() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (!available()) {
            return out;
        }
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT id, path FROM Track WHERE path LIKE ?")) {
            ps.setString(1, "%audio-cover%");
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "读取本地带图路径失败（忽略）：" + t);
        }
        return out;
    }

    /** 单曲还原（插件自带 JDBC + 独立事务）：宿主同连接不可用时的兜底。 */
    public static boolean restoreStreamPathExternal(String trackId, String urlPrefix) {        if (!available()) {
            return false;
        }
        try (Connection c = open()) {
            c.setAutoCommit(false);
            restoreStreamPathOn(new JdbcSql(c), trackId, urlPrefix);
            c.commit();
            return true;
        } catch (Throwable t) {
            PluginLog.d(TAG, "还原流地址（外部连接）失败（忽略）：" + t);
            return false;
        }
    }

    /** 外部 JDBC 回退；成功提交返回 true。 */
    public static boolean restoreStreamPathsExternal(String urlPrefix) {        if (!available()) {
            return false;
        }
        try (Connection c = open()) {
            c.setAutoCommit(false);
            restoreStreamPathsOn(new JdbcSql(c), urlPrefix);
            c.commit();
            return true;
        } catch (Throwable t) {
            PluginLog.d(TAG, "恢复流地址（外部连接）失败（忽略）：" + t);
            return false;
        }
    }

    private static int writeCovers(Sql sql, List<CoverArt.Cover> covers, long now) throws Exception {
        if (covers == null || covers.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (CoverArt.Cover c : covers) {
            if (c.album() != null && !c.album().isBlank()) {
                // 0.11.49：Album 行是按 (title, artist) 派生的（同名不同艺人各有各的行），只按 title
                // 更新会把同一张图写给所有同名专辑 ⇒ 带上 artist（与 Track.artist 同一口径）。
                if (c.artist() != null && !c.artist().isBlank()) {
                    sql.exec("UPDATE Album SET cover = ?, coverModifiedTime = ?, coverRevision = coverRevision + 1"
                            + " WHERE title = ? AND artist = ?", c.fileUri(), now, c.album(), c.artist());
                } else {
                    sql.exec("UPDATE Album SET cover = ?, coverModifiedTime = ?, coverRevision = coverRevision + 1"
                            + " WHERE title = ?", c.fileUri(), now, c.album());
                }
            }
            if (c.artist() != null && !c.artist().isBlank()) {
                sql.exec("UPDATE Artist SET cover = ?, coverModifiedTime = ?, coverRevision = coverRevision + 1"
                        + " WHERE name = ?", c.fileUri(), now, c.artist());
            }
            n++;
        }
        return n;
    }

    /**
     * 只读核对（用插件自带的 JDBC 连接，不占宿主连接、不影响失效）：
     * 看刚挂的封面是否真的落在库里。
     *
     * @return 形如 {@code "Album 命中 2/2（Arcana Eden=file:///…）"}
     */
    public static String verifyCovers(List<CoverArt.Cover> covers) {
        if (covers == null || covers.isEmpty() || !available()) {
            return "无封面可核对";
        }
        int hit = 0;
        StringBuilder sample = new StringBuilder();
        try (Connection c = open()) {
            for (CoverArt.Cover cv : covers) {
                String cover = null;
                String sql = cv.artist() == null || cv.artist().isBlank()
                        ? "SELECT cover FROM Album WHERE title = ?"
                        : "SELECT cover FROM Album WHERE title = ? AND artist = ?";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, cv.album());
                    if (sql.contains("artist = ?")) {
                        ps.setString(2, cv.artist());
                    }
                    try (var rs = ps.executeQuery()) {
                        if (rs.next()) {
                            cover = rs.getString(1);
                        }
                    }
                }
                if (cv.fileUri().equals(cover)) {
                    hit++;
                    if (sample.length() < 80) {
                        sample.append(sample.length() > 0 ? "、" : "").append(cv.album());
                    }
                }
            }
        } catch (Throwable t) {
            return "核对失败：" + t;
        }
        return "Album 命中 " + hit + "/" + covers.size()
                + (sample.length() > 0 ? "（" + sample + "…）" : "（无一命中，宿主可能还没派生出专辑行）");
    }

    /**
     * 库里「还没挂上封面桩」的专辑数（0.11.0 新增，只读）。
     *
     * <p>判定：{@code Album.cover} 为空、或指的不是本插件造的文件桩
     * （{@code file:///…/cover-stub-*.flac}）。宿主的<b>「歌曲」行</b>封面只认
     * {@code Track.path} 指向的媒体文件内嵌图，而<b>专辑/歌单页</b>封面读 {@code Album.cover}
     * ——所以这个数字就是「还差多少张」。</p>
     *
     * <p>线程：任意非宿主回调线程（自带只读 JDBC 连接，不占宿主连接、不触发失效）。</p>
     *
     * @return 缺图专辑数；库不可读返回 -1
     */
    public static int albumsMissingCover() {
        if (!available()) {
            return -1;
        }
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM Album WHERE cover IS NULL OR cover NOT LIKE ?")) {
            ps.setString(1, "%/cover-stub-%");
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 外部 JDBC 连接的 {@link SqlSchema} 适配器（插件自带 {@code sqlite-jdbc}）。 */
    private static final class JdbcSql implements SqlSchema {

        private final Connection c;

        JdbcSql(Connection c) {
            this.c = c;
        }

        @Override
        public void exec(String sql, Object... args) throws Exception {
            execUpdate(sql, args);
        }

        @Override
        public int execUpdate(String sql, Object... args) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                // 0.11.7（P0）：影响行数不再丢弃 —— 0 就是「没写进去」，必须让上层看得见
                return ps.executeUpdate();
            }
        }

        @Override
        public List<Object[]> rows(String sql, Object... args) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                try (var rs = ps.executeQuery()) {
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

        private static void bind(PreparedStatement ps, Object[] args) throws Exception {
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

    // ------------------------------------------------- schema 自适应写库（0.11.7 / P0）

    /** 一列的 schema 事实（来自 {@code PRAGMA table_info}）。 */
    private record Column(String name, String type, boolean notNull, boolean pk) {
    }

    /** 表名 → 列集合（只探一次：宿主改 schema 得重启，进程内不会变）。 */
    private static final Map<String, List<Column>> SCHEMAS = new ConcurrentHashMap<>();

    /** 已经打过「补零值」日志的表（避免 5935 首刷屏）。 */
    private static final Set<String> FILL_LOGGED = ConcurrentHashMap.newKeySet();

    /** 取 {@link SqlSchema} 通道；取不到就抛（P0：拒绝在没有结果反馈的通道上写宿主曲库）。 */
    private static SqlSchema requireSchema(Sql c, String what) {
        if (c instanceof SqlSchema) {
            return (SqlSchema) c;
        }
        throw new IllegalStateException("写库实现 " + (c == null ? "null" : c.getClass().getName())
                + " 不支持 schema 探测 / 影响行数校验（" + what + "）：拒绝在没有结果反馈的通道上写宿主曲库");
    }

    /** 读表的<b>真实</b>列集合（{@code PRAGMA table_info}，进程内缓存）。 */
    private static List<Column> columnsOf(SqlSchema c, String table) throws Exception {
        List<Column> cached = SCHEMAS.get(table);
        if (cached != null) {
            return cached;
        }
        List<Object[]> raw = c.rows("PRAGMA table_info('" + table + "')");
        if (raw == null || raw.isEmpty()) {
            throw new IllegalStateException("宿主曲库里没有 " + table + " 表（PRAGMA table_info 返回空），拒绝写库");
        }
        List<Column> cols = new ArrayList<>();
        for (Object[] r : raw) {
            // 列序：cid, name, type, notnull, dflt_value, pk
            cols.add(new Column(r[1] == null ? "" : String.valueOf(r[1]),
                    r[2] == null ? "" : String.valueOf(r[2]), asLong(r[3]) != 0L, asLong(r[5]) != 0L));
        }
        List<Column> keep = List.copyOf(cols);
        SCHEMAS.put(table, keep);
        long notNull = keep.stream().filter(Column::notNull).count();
        PluginLog.i(TAG, "表结构探测：" + table + " 共 " + keep.size() + " 列（其中 NOT NULL " + notNull
                + " 个）→ " + columnNames(keep));
        return keep;
    }

    private static long asLong(Object v) {
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return v == null ? 0L : Long.parseLong(String.valueOf(v).trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String columnNames(List<Column> cols) {
        StringBuilder sb = new StringBuilder();
        for (Column c : cols) {
            sb.append(sb.length() > 0 ? ", " : "").append(c.name());
        }
        return sb.toString();
    }

    /** 标识符加引号（{@code order} 这种关键字列名必须引）。 */
    private static String qi(String name) {
        return "`" + (name == null ? "" : name.replace("`", "``")) + "`";
    }

    /** 小写列名集合（SQLite 的列名大小写不敏感）。 */
    private static Set<String> cols(String... names) {
        Set<String> s = new HashSet<>();
        for (String n : names) {
            s.add(n.toLowerCase(Locale.ROOT));
        }
        return s;
    }

    /**
     * 未被覆盖的 NOT NULL 列的<b>零值</b>（P0 的核心修法）。
     *
     * <p>宿主 {@code Track} 的 25 列<b>全部 NOT NULL 且没有 DEFAULT</b>（Room {@code user_version=27}），
     * 而插件只列了 20 列 ⇒ {@code trackGain}/{@code trackPeak}/{@code albumGain}/{@code albumPeak}
     * （REAL）与 {@code coverRevision}（INTEGER）被写成 NULL ⇒ 每条 INSERT 都违约。</p>
     */
    private static Object zeroOf(Column col) {
        String t = col.type() == null ? "" : col.type().toUpperCase(Locale.ROOT);
        if (t.contains("INT")) {
            return 0L;
        }
        if (t.contains("REAL") || t.contains("FLOA") || t.contains("DOUB") || t.contains("NUM")) {
            return 0.0d;
        }
        if (t.contains("BLOB")) {
            return new byte[0];
        }
        return "";
    }

    /**
     * schema 自适应 upsert（P0 铁律 1）：列集合按<b>库里的真实表结构</b>现探现用，不写死。
     *
     * <ul>
     *   <li>要写的列 = 表里真实存在的 ∩ 调用方给了值的，再加「表里 NOT NULL 但调用方没覆盖」的补零值；</li>
     *   <li>不用 {@code INSERT OR IGNORE}（NOT NULL 违约会被静默吞掉），改
     *       {@code ON CONFLICT(pk) DO UPDATE} 原地更新；</li>
     *   <li>更不用 {@code INSERT OR REPLACE}：REPLACE = «DELETE + INSERT»，宿主的
     *       {@code ON DELETE CASCADE} 会连带清掉别的歌单的关联行（0.10.0 真机踩过，docs/00 §6.15）；</li>
     *   <li>影响行数 {@code 0} = 没写进去 ⇒ ERROR + 抛错中止本轮（调用方不得继续写 PlaylistTrack）。</li>
     * </ul>
     */
    private static void upsertBySchema(SqlSchema c, String table, String pk,
                                       Map<String, Object> values, Set<String> updatable) throws Exception {
        List<Column> schema = columnsOf(c, table);
        Map<String, Object> given = new HashMap<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            given.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        if (!given.containsKey(pk.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("不给 " + table + " 的主键 " + pk + " 值就写库，拒绝（给了：" + values.keySet() + "）");
        }
        LinkedHashMap<String, Object> want = new LinkedHashMap<>();
        List<String> filled = new ArrayList<>();
        for (Column col : schema) {
            String key = col.name().toLowerCase(Locale.ROOT);
            if (given.containsKey(key)) {
                want.put(col.name(), given.get(key));
            } else if (col.notNull() && !col.pk()) {
                want.put(col.name(), zeroOf(col));
                filled.add(col.name());
            }
        }
        if (!filled.isEmpty() && FILL_LOGGED.add(table)) {
            PluginLog.i(TAG, "表结构自适应：" + table + " 有 " + filled.size()
                    + " 个 NOT NULL 列本轮未覆盖，已补零值（0.11.6 正是缺这些列导致整表写不进去）→ " + filled);
        }

        StringBuilder names = new StringBuilder();
        StringBuilder marks = new StringBuilder();
        List<Object> args = new ArrayList<>(want.size());
        for (Map.Entry<String, Object> e : want.entrySet()) {
            if (names.length() > 0) {
                names.append(", ");
                marks.append(", ");
            }
            names.append(qi(e.getKey()));
            marks.append('?');
            args.add(e.getValue());
        }
        StringBuilder sets = new StringBuilder();
        for (String name : want.keySet()) {
            if (!updatable.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (sets.length() > 0) {
                sets.append(", ");
            }
            sets.append(qi(name)).append(" = excluded.").append(qi(name));
        }
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(qi(table))
                .append(" (").append(names).append(") VALUES (").append(marks).append(") ON CONFLICT(")
                .append(qi(pk)).append(") DO ");
        if (sets.length() == 0) {
            sql.append("NOTHING");   // 没有可更新字段时退化成「只插不改」（仍不吞真正的错误）
        } else {
            sql.append("UPDATE SET ").append(sets);
        }

        int n = c.execUpdate(sql.toString(), args.toArray());
        if (n <= 0) {
            String msg = "写库未生效：" + table + " 影响行数 " + n + "（主键 " + pk + "="
                    + given.get(pk.toLowerCase(Locale.ROOT)) + "）SQL=" + sql;
            PluginLog.e(TAG, msg);
            throw new IllegalStateException(msg);
        }
    }

    /** 只删行不写行：撤销本插件写进宿主曲库的全部内容（宿主原有行不受影响）。 */
    public static Result purgeAll() {
        if (!available()) {
            return new Result(0, 0, 0, null, "宿主曲库不可写：" + describe());
        }
        String backup = backupOnce("purge");
        try (Connection c = open()) {
            c.setAutoCommit(false);
            int links = 0;
            int tracks;
            int pls;
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM PlaylistTrack WHERE playlistId LIKE ? OR trackId LIKE ?")) {
                ps.setString(1, ID_PREFIX + "%");
                ps.setString(2, ID_PREFIX + "%");
                links = ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM Track WHERE id LIKE ?")) {
                ps.setString(1, ID_PREFIX + "%");
                tracks = ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM Playlist WHERE id LIKE ?")) {
                ps.setString(1, ID_PREFIX + "%");
                pls = ps.executeUpdate();
            }
            int covers = resetCovers(c);
            c.commit();
            PluginLog.i(TAG, "已撤销插件写入：歌单 " + pls + " / 曲目 " + tracks + " / 关联 " + links
                    + (covers > 0 ? " / 抹掉封面 " + covers + " 处" : ""));
            return new Result(pls, tracks, links, covers, backup, null);
        } catch (Throwable t) {
            PluginLog.e(TAG, "撤销插件写入失败：" + t.getMessage());
            return new Result(0, 0, 0, backup, String.valueOf(t));
        }
    }

    /**
     * 一次性数据修复（0.11.7 / P0）：清掉插件历史遗留的<b>孤儿关联行</b>。
     *
     * <p>0.11.6 真机事故在宿主库里留下 5935 条 {@code PlaylistTrack} 行，其 {@code trackId}
     * 指向的 {@code Track} 行根本不存在（Track 总计 0）⇒ 宿主列表里「关联 N / 可见 0」。
     * 这些行只属于插件写过的歌单（{@code playlistId LIKE 'netease-%'}），宿主自身数据一行不碰。</p>
     *
     * @return 实际删掉的行数
     */
    public static int deleteOrphanLinks() throws Exception {
        String backup = backupOnce("repair");
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                int n;
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM PlaylistTrack"
                        + " WHERE playlistId LIKE ? AND trackId NOT IN (SELECT id FROM Track)")) {
                    ps.setString(1, ID_PREFIX + "%");
                    n = ps.executeUpdate();
                }
                c.commit();
                PluginLog.i(TAG, "清孤儿关联行：删掉 " + n + " 行（备份 " + backup + "）");
                return n;
            } catch (Throwable t) {
                try {
                    c.rollback();
                } catch (Throwable ignored) {
                    // 回滚失败就交给下面的 ERROR 上抛
                }
                throw t;
            }
        } catch (Throwable t) {
            PluginLog.e(TAG, "清孤儿关联行失败（已回滚）：" + t.getMessage(), t);
            throw t;
        }
    }

    /**
     * 配置页按钮入口（{@code on_click = com.example.netease.svc.NativeLibrary.rebuildLibraryMapping}）：
     * 清孤儿关联行 + 弹 toast 告诉用户下一步。
     *
     * <p>清理本身只是「把 0.11.6 留下的垃圾行删掉」；真正重建曲目行仍靠用户再点一次「同步歌单」
     * （那时 Track 行会按 0.11.7 的新逻辑正常写入）。</p>
     */
    public static void rebuildLibraryMapping() {
        PluginLog.i(TAG, "重建曲库映射：开始清孤儿关联行（0.11.6 遗留数据修复）…");
        try {
            int n = deleteOrphanLinks();
            if (n > 0) {
                Notifier.success("已清掉 " + n + " 条孤儿关联行；请再点一次「同步歌单」重建曲目行");
            } else {
                Notifier.success("没有孤儿关联行，映射本来就是干净的");
            }
        } catch (Throwable t) {
            String msg = "重建曲库映射失败：" + (t.getMessage() == null ? String.valueOf(t) : t.getMessage());
            PluginLog.e(TAG, msg, t);
            Notifier.error(msg);
        }
    }

    // ---------------------------------------------------------------- 自动同步闸（P2）

    /** 自动同步的最小间隔：30 分钟（登录成功先跑一轮，之后按这个节奏增量同步）。 */
    public static final long AUTO_SYNC_INTERVAL_MS = 30L * 60L * 1000L;

    /** 在飞标志：自动同步同一时刻只允许一轮（取闸/放闸成对，放闸在 finally 通路里也必须被调到）。 */
    private static final AtomicBoolean AUTO_IN_FLIGHT = new AtomicBoolean(false);
    private static volatile long autoLastEndMs = 0L;
    private static volatile String autoLastResult = "尚未同步";

    /**
     * 自动同步「取闸」：返回 {@code true} = 调用方可以开始本轮同步，并且<b>必须</b>在 finally 通路
     * 调用 {@link #autoSyncEnd(String)} 放闸（否则后续所有同步都会被在飞标志挡住）。
     *
     * <p>两道闸：① 在飞（同时只允许一轮）；② 距上次结束满 {@link #AUTO_SYNC_INTERVAL_MS}（30 分钟）。
     * 被闸挡住时<b>一定 INFO 说明原因</b>（谁在飞 / 还差多少秒），绝不静默返回 false。</p>
     *
     * <p><b>{@code reason} 以 {@code "force:"} 开头时跳过间隔闸</b>（在飞闸仍然有效）——
     * 配置页的「立即重新同步」走这条（{@code force:手动}）；登录成功 / 30 分钟定时器走普通原因。</p>
     *
     * @param reason 呼入原因（写进日志；{@code force:} 前缀 = 强制立即同步）
     * @return true = 已取到闸，本轮可以开始；false = 本轮跳过（原因已写进日志）
     */
    public static boolean autoSyncBegin(String reason) {
        String why = reason == null || reason.isBlank() ? "未说明" : reason.trim();
        boolean force = why.startsWith("force:");
        if (!AUTO_IN_FLIGHT.compareAndSet(false, true)) {
            PluginLog.i(TAG, "自动同步本轮跳过（未开始）：已有同步在飞（呼入原因=" + why + "）"
                    + "；在飞那轮结束时放闸，之后才允许下一轮");
            return false;
        }
        long now = System.currentTimeMillis();
        long due = autoSyncNextDueMs();
        if (!force && due > now) {
            long waitS = (due - now + 999L) / 1000L;
            AUTO_IN_FLIGHT.set(false);          // 没开始 ⇒ 必须立刻放闸，否则后面的同步全被挡住
            PluginLog.i(TAG, "自动同步本轮跳过（未开始）：距上次同步结束还差 " + waitS + " 秒"
                    + "（间隔 " + (AUTO_SYNC_INTERVAL_MS / 60_000L) + " 分钟；呼入原因=" + why
                    + "）；要立即同步请用 force: 前缀（配置页「立即重新同步」走这条）");
            return false;
        }
        PluginLog.i(TAG, "自动同步开始：呼入原因=" + why + (force ? "（force：跳过 30 分钟间隔闸）" : "")
                + "；上次结束=" + (autoLastEndMs == 0L ? "（无记录，首次同步）" : timeOf(autoLastEndMs))
                + "；上次结果=" + autoLastResult);
        return true;
    }

    /**
     * 自动同步「放闸」：调用方每轮结束（含异常路径）都要调一次，<b>传 null 也不会炸</b>。
     *
     * <p>把在飞标志归零、记下这轮的结束时间与结果摘要（下一个 30 分钟窗口从这一刻起算）。
     * 本轮结果摘要会同时写进日志，让「为什么这轮没被跳过」在事后可查。</p>
     *
     * @param resultSummary 本轮结果摘要（可为 null —— 会被记成「（本轮未给出结果摘要）」）
     */
    public static void autoSyncEnd(String resultSummary) {
        try {
            autoLastEndMs = System.currentTimeMillis();
            autoLastResult = resultSummary == null || resultSummary.isBlank()
                    ? "（本轮未给出结果摘要）" : resultSummary.trim();
            boolean was = AUTO_IN_FLIGHT.getAndSet(false);
            if (!was) {
                PluginLog.w(TAG, "自动同步放闸：这次调用之前并没有在飞的一轮（重复收口，或没走 autoSyncBegin）"
                        + "；在飞标志已归零，避免把后续同步永久卡死");
            }
            PluginLog.i(TAG, "自动同步结束：在飞 " + was + " → false；结果=" + autoLastResult
                    + "；下次最早 " + timeOf(autoSyncNextDueMs())
                    + "（间隔 " + (AUTO_SYNC_INTERVAL_MS / 60_000L) + " 分钟）");
        } catch (Throwable t) {
            AUTO_IN_FLIGHT.set(false);          // finally 通路里的调用：宁可少限流一次，也不能把同步卡死
            PluginLog.e(TAG, "自动同步放闸时出错（在飞标志已强制归零）：" + t, t);
        }
    }

    /** 下一次自动同步最早可跑的时刻（epoch ms）；无上次记录时返回 0（= 立刻可跑）。 */
    public static long autoSyncNextDueMs() {
        long last = autoLastEndMs;
        return last == 0L ? 0L : last + AUTO_SYNC_INTERVAL_MS;
    }

    /** 自动同步状态一行摘要（配置页与日志用）：间隔 / 在飞 / 上次结束与结果 / 下次最早时刻。 */
    public static String autoSyncState() {
        long last = autoLastEndMs;
        long due = autoSyncNextDueMs();
        long now = System.currentTimeMillis();
        String next;
        if (due == 0L) {
            next = "立刻可跑";
        } else if (due <= now) {
            next = "现在就可跑（" + timeOf(due) + "）";
        } else {
            next = timeOf(due) + "（还差 " + ((due - now + 999L) / 1000L) + " 秒）";
        }
        return "自动同步：间隔 " + (AUTO_SYNC_INTERVAL_MS / 60_000L) + " 分钟；在飞="
                + (AUTO_IN_FLIGHT.get() ? "是" : "否") + "；上次结束="
                + (last == 0L ? "（无记录）" : timeOf(last)) + "；上次结果=" + autoLastResult
                + "；下次最早=" + next;
    }

    private static String timeOf(long epochMs) {
        return DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
                .format(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()));
    }

    /**
     * 抹掉插件挂上去的封面值：只动 {@code cover} 里含 {@link CoverArt#STUB_PREFIX} 的行
     * （即「指向本插件封面桩」的那些），宿主自己的封面值一个字节都不碰。
     */
    private static int resetCovers(Connection c) {
        int n = 0;
        long now = System.currentTimeMillis();
        String like = "%" + CoverArt.STUB_PREFIX + "%";
        for (String sql : new String[]{
                "UPDATE Album SET cover = '', coverModifiedTime = ?, coverRevision = coverRevision + 1 WHERE cover LIKE ?",
                "UPDATE Artist SET cover = '', coverModifiedTime = ?, coverRevision = coverRevision + 1 WHERE cover LIKE ?"}) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, now);
                ps.setString(2, like);
                n += ps.executeUpdate();
            } catch (Throwable t) {
                PluginLog.d(TAG, "抹封面跳过一条（忽略）：" + t);
            }
        }
        return n;
    }

    /** 统计当前库里的插件行（用于自检与配置页显示）。 */
    public static String stats() {
        if (!available()) {
            return "曲库不可用：" + describe();
        }
        try (Connection c = open()) {
            long pl = count(c, "Playlist");
            long tr = count(c, "Track");
            long ln = count(c, "PlaylistTrack");
            return "插件行：歌单 " + pl + " / 曲目 " + tr + " / 关联 " + ln;
        } catch (Throwable t) {
            return "统计失败：" + t;
        }
    }

    private static long count(Connection c, String table) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE " + ("PlaylistTrack".equals(table) ? "playlistId" : "id") + " LIKE ?")) {
            ps.setString(1, ID_PREFIX + "%");
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** 按主键前缀数行（0.11.7 写入自检用；走 {@link SqlSchema} 通道，宿主/自带连接通用）。 */
    private static long countPrefix(SqlSchema c, String table, String key) throws Exception {
        List<Object[]> rs = c.rows("SELECT COUNT(*) FROM " + qi(table) + " WHERE " + qi(key) + " LIKE ?",
                ID_PREFIX + "%");
        return rs == null || rs.isEmpty() ? 0L : asLong(rs.get(0)[0]);
    }

    // ------------------------------------------------------------------ SQL

    private static Connection open() throws Exception {
        Driver d = (Driver) Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance();
        Properties p = new Properties();
        p.setProperty("busy_timeout", "5000");
        // 0.11.7（P0）：自带连接必须显式开外键检查。0.11.6 的 5935 条孤儿关联行就是在这个连接上
        // 写出来的 —— 宿主池连接 foreign_keys=ON 会把「曲目行不存在」的关联写过招，而自带连接默认
        // OFF 直接照单全收，于是「换通道重跑」把失败变成了 5935 行垃圾数据。防线不能再靠默认值。
        p.setProperty("foreign_keys", "true");
        Connection c = d.connect("jdbc:sqlite:" + dbPath(), p);
        try (Statement st = c.createStatement()) {
            // 注意：PRAGMA foreign_keys 在事务内是 no-op，必须在任何 BEGIN 之前设（此处连接刚建好）。
            st.execute("PRAGMA foreign_keys=ON");
            try (var rs = st.executeQuery("PRAGMA foreign_keys")) {
                long on = rs.next() ? rs.getLong(1) : 0L;
                if (on != 1L) {
                    throw new IllegalStateException("自带连接未能启用 foreign_keys（读到 " + on
                            + "）：拒绝在无外键检查的连接上写宿主曲库（0.11.6 的 5935 条孤儿行就是这么来的）");
                }
            }
        } catch (Throwable t) {
            try {
                c.close();
            } catch (Throwable ignored) {
                // 关不上就算了，异常正在上抛
            }
            throw t;
        }
        return c;
    }

    /**
     * 每轮同步前重置这张歌单的「曲目 ↔ 歌单」关联（随后由本次 entries 重建）。
     *
     * <p><b>只清关联行，不删 Playlist 行</b>（P2 增量幂等，0.11.7+）：以前这里连带
     * {@code DELETE FROM Playlist}，于是每次同步都把歌单行删掉重建 —— 虽然行数一样，但
     * {@code createdTime} 每轮被刷新、宿主里用户自己换的封面标记 {@code isUserEditedCover}
     * 也被清零（真机上表现为「同步一次封面设置就丢一次」）。现在歌单行交给
     * {@link #insertPlaylist} 的 {@code ON CONFLICT(id) DO UPDATE} 增量更新：插件自己管的列
     * （title / coverModifiedTime / description / order）每轮覆盖，宿主/用户数据
     * （createdTime / isUserEditedCover / trackSort / trackSortDescending）原样保留。</p>
     */
    private static void resetPlaylistForSync(SqlSchema c, String plId) throws Exception {
        c.exec("DELETE FROM PlaylistTrack WHERE playlistId = ?", plId);
    }

    private static void insertPlaylist(SqlSchema c, String id, String title, String desc, long now, int order,
                                       long coverModifiedTime) throws Exception {
        String t = title == null || title.isBlank() ? "网易云歌单" : title;
        // ⚠️ 绝不能用 INSERT OR REPLACE（0.10.0 真机踩坑）：SQLite 的 REPLACE = «DELETE + INSERT»，
        // 而宿主在 PlaylistTrack.playlistId → Playlist.id 上挂了 ON DELETE CASCADE（宿主连接
        // PRAGMA foreign_keys=ON）⇒ 先删后插会把这张歌单**刚写好的全部关联行**清掉。
        // ⚠️ 0.11.7（P0）起也绝不能用 INSERT OR IGNORE：NOT NULL 违约会被静默吞掉 —— Track 表就是
        // 这么「一行都没写进去、却回报成功」的。改走 schema 自适应 upsert（ON CONFLICT(id) DO UPDATE
        // + 校验影响行数）：列集合按库里的真实表结构现探现用。
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", id);
        v.put("title", t);
        v.put("coverModifiedTime", coverModifiedTime);   // 0.10.0：有歌单封面文件时 = 文件 mtime
        v.put("isUserEditedCover", 0L);                  // 用户在宿主里自己换过封面 ⇒ 不进下面的更新列表
        v.put("description", desc);
        v.put("createdTime", now);
        v.put("order", (long) order);
        v.put("trackSort", 0L);
        v.put("trackSortDescending", 0L);
        upsertBySchema(c, "Playlist", "id", v, cols("title", "coverModifiedTime", "description", "order"));
    }

    private static void upsertTrack(SqlSchema c, String id, Dto.Song s, String urlPrefix, int order, long now)
            throws Exception {
        // 始终保留流地址。播放缓存由 NativeStreamServer 在真实读取过程中边传边写；不能在同步
        // 阶段把 Track.path 翻成本地整首文件，否则会变成「下完才播」，音质切换也会被旧文件锁死。
        String title = trim(s.name(), "未知曲目");
        String artist = trim(s.artists(), "未知歌手");
        String album = trim(s.album(), "未知专辑");
        String path = urlPrefix + s.id();
        long size = 0L;
        long duration = Math.max(0L, s.durationMs());
        // ⚠️ 绝不能用 INSERT OR REPLACE（0.10.0 真机踩坑，见 docs/00 §6.15 铁律 7）：
        // Track.id 被 PlaylistTrack / TrackArtist / MusicVideo / TrackTagOverride 四张表以
        // ON DELETE CASCADE 引用（宿主连接 foreign_keys=ON）——REPLACE 的「先删后插」会级联
        // 清掉**其它歌单**里同一首歌的关联行（实测 5935 条关联被清到 3267 条），还会顺带把
        // 宿主的 isFavorite / playCount / addedTime 归零。
        // ⚠️ 0.11.7（P0 真机事故）起也绝不能用 INSERT OR IGNORE：宿主 Track 表 25 列**全部
        // NOT NULL 且没有 DEFAULT**，本方法只列了 20 列 ⇒ trackGain / trackPeak / albumGain /
        // albumPeak / coverRevision 被写成 NULL ⇒ 每条 INSERT 都违约，而 IGNORE 把错误**静默
        // 吞掉**，于是「Track 一行都没写进去、却回报成功」，紧接着 PlaylistTrack 写了 5935 条
        // 孤儿关联行。现在改走 schema 自适应 upsert（ON CONFLICT(id) DO UPDATE + 影响行数校验）。
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", id);
        v.put("title", title);
        v.put("artist", artist);
        v.put("album", album);
        v.put("albumArtist", artist);
        v.put("genre", "");
        v.put("year", 0L);
        v.put("number", (long) (order + 1));
        v.put("isFavorite", 0L);         // 宿主的用户数据 ⇒ 不在更新列表里
        v.put("playCount", 0L);          // 同上
        v.put("order", (long) order);
        v.put("path", path);
        v.put("readable", 1L);
        v.put("size", size);             // 本地缓存有真大小；流地址为 0
        v.put("addedTime", now);         // 同上（宿主的用户数据）
        v.put("modifiedTime", PINNED_MODIFIED_TIME);
        v.put("duration", duration);
        v.put("bitsPerSample", 0L);
        v.put("sampleRate", 0L);
        v.put("bitrate", 0.0d);
        upsertBySchema(c, "Track", "id", v, cols("title", "artist", "album", "albumArtist", "number", "order",
                "path", "readable", "size", "modifiedTime", "duration"));
    }

    private static void insertLink(SqlSchema c, String plId, String trId, int order, long now) throws Exception {
        // 关联行没有下游外键（没有表引用 PlaylistTrack）⇒ REPLACE 的「先删后插」是安全的，
        // 而且正是我们想要的语义（同一 (歌单, 曲目) 只保留一条、order 以本轮为准）。
        // ⚠️ 但 PlaylistTrack.trackId → Track(id) 是**上游**外键，且宿主库 foreign_keys=ON：
        // 目标曲目行不存在时这条 INSERT 会被拒（0.11.6 就是这么撞出 Error code 787 的）。
        // 所以写关联前先在同一事务里断言曲目行存在——不存在就是上游写入失败，必须中止本轮，
        // 绝不能让「宿主连接上的外键拒绝」被当成「池通路不可用」而去换通道重跑（那正是 5935 条孤儿的成因）。
        List<Object[]> exists = c.rows("SELECT 1 FROM Track WHERE id = ? LIMIT 1", trId);
        if (exists == null || exists.isEmpty()) {
            String msg = "曲目行不存在，拒绝写关联（歌单 " + plId + " ← 曲目 " + trId
                    + "）：Track 写入已失败或被回滚 ⇒ 本轮中止、不提交";
            PluginLog.e(TAG, msg);
            throw new IllegalStateException(msg);
        }
        c.exec("INSERT OR REPLACE INTO PlaylistTrack (playlistId, trackId, addedTime, `order`) VALUES (?,?,?,?)",
                plId, trId, now, order);
    }

    private static String trim(String s, String fallback) {
        return text(s, fallback);
    }

    /**
     * 写库用的文本归一（0.8.0 起公开）：空白 → 兜底值，超长截断。
     *
     * <p>封面挂载必须与 {@code Track} 里的 album/albumArtist 值一字不差——宿主正是按这两个值
     * 派生 {@code Album}/{@code Artist} 行的，所以 {@link CoverArt} 也走这里。</p>
     */
    public static String text(String s, String fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        return s.length() > 220 ? s.substring(0, 220) : s;
    }
}
