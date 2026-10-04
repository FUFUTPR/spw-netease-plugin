package com.example.netease.svc;

import com.example.netease.core.DataPaths;
import com.example.netease.core.PluginLog;
import com.example.netease.net.Dto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 歌词全量预热（0.11.4 起；0.11.8 W4 重写）。
 *
 * <p><b>为什么需要它</b>：宿主只在<b>换曲那一刻</b>问歌词钩子（{@code updateLyrics} →
 * {@code onBeforeLoadLyrics} → {@code onAfterLoadLyrics}），而歌词本身是「播放时按需抓」的
 * ⇒ 首播必然「未命中」（真机日志实证；用户口径：「点击播放后…也没歌词」）。
 * 预热的意义是把「换曲那一刻」提前到「启动同步跑完那一刻」，让首播就有词。</p>
 *
 * <p><b>0.11.8 改了什么（W4）</b></p>
 * <ol>
 *   <li><b>修一个真机级硬 bug</b>：本类原来直接 {@code DriverManager.getConnection("jdbc:sqlite:…")}，
 *       而插件类加载器下 SQLite 驱动<b>从未被 {@code DriverManager} 注册</b>
 *       （{@code NativeLibrary} 走的是 {@code new org.sqlite.JDBC()} 直连，不注册服务发现；
 *       jar 的 {@code META-INF/services/java.sql.Driver} 在插件类加载器里看不见）⇒ 每轮都抛
 *       {@code No suitable driver found for jdbc:sqlite:…}，被 {@code PluginLog.d} 静默，
 *       表现为真机日志里只剩「宿主曲库暂不可读，10 秒后重试」刷屏，<b>全量预热在真机上从未生效过</b>。
 *       现改为显式取驱动类并自己 {@code connect}（与 {@code NativeLibrary#open()} 同款写法）。</li>
 *   <li><b>不再分批</b>：原来每批若干首、批间隔上百秒 ⇒ 3267 首要 35 分钟。
 *       现在一次把整库投给 {@link LyricService#preheat}（<b>全量预热口径</b>，会打
 *       {@code 预热开跑/预热完成/失败分类} 三行冻结判据），由它的 6 路并发
 *       （{@code RiskControl.WARM_CONCURRENCY}）接力抓完（R15「速度优先、并发拉满」）。
 *       <b>C-cold 轮实证修正</b>：这里原来调的是 {@link LyricService#prefetch}（「播放即抓」
 *       口径）⇒ {@code dispatch} 的 {@code warm} 分支成死代码，三行判据一行都不打，
 *       A11 的 {@code Tms} 无从取证。</li>
 *   <li><b>口径修正</b>：原来按 {@code path LIKE 'http://127.0.0.1%'} 收曲目，但宿主把 path 改写成
 *       {@code file:///…} 之后这些行会被 SQL 漏掉（同 W3 的 {@code RowCover} 违规），预热范围会越跑越小。
 *       现在只要求 {@code path} 里有 {@code /netease/<id>} 形态的 id（两种协议通吃）。</li>
 *   <li><b>只读纪律</b>：连接后置 {@code PRAGMA query_only=ON}（写操作直接报错，绝不碰宿主库），
 *       且只读 {@code Track} 表。日志不再静默：读库失败打 {@code warn}（原来走 {@code PluginLog.d}，
 *       info 级别下看不见，才让上面那个 bug 藏了四个版本）。</li>
 *   <li><b>总数登记</b>：读到的曲目数交给 {@link LyricService#noteTotal(long)}，让
 *       {@code LyricService.stats()} 的「待处理」有分母。</li>
 * </ol>
 *
 * <p><b>线程</b>：一条守护线程 {@code netease-lyric-warm}（优先级略低）；{@link #start()} 幂等，
 * {@link #stop()} 打断并 join。</p>
 */
public final class LyricWarmer {

    private static final String TAG = "lyric-warm";

    /**
     * 全量轮跑完后的复查间隔：新同步进来的歌要有词可抓，但不该反复空转读库。
     * （0.11.8 前的 {@code GAP_MS}/{@code BATCH} 已删：分批限速不再需要——并发与退避都在
     * {@link LyricService} 里，本类只负责「喂一次全量」。）
     */
    private static final long RECHECK_MS = 1800000L;

    /** 曲库暂不可读时的短等重试间隔。 */
    private static final long DB_RETRY_MS = 10000L;

    /** 「曲库暂不可读」日志节流条数。 */
    private static final int DB_WAIT_LOG_MAX = 20;

    /** SQLite 驱动（{@code lib/sqlite-jdbc-*.jar}，运行期依赖，见 docs/00 §3）。 */
    private static volatile Driver driver;

    private static volatile Thread worker;
    private static volatile boolean stop;

    /** 「曲库暂不可读」日志节流。 */
    private static volatile int dbWaitLog;

    private LyricWarmer() {
    }

    /** 启动预热线程（幂等；插件启动时调一次）。 */
    public static void start() {
        try {
            if (worker != null && worker.isAlive()) {
                return;
            }
            stop = false;
            Thread t = new Thread(LyricWarmer::loop, "netease-lyric-warm");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            worker = t;
            t.start();
        } catch (Throwable t) {
            PluginLog.d(TAG, "歌词预热启动失败（忽略）：" + t);
        }
    }

    /** 停预热线程（插件卸载时调）。 */
    public static void stop() {
        stop = true;
        Thread t = worker;
        if (t == null) {
            return;
        }
        t.interrupt();
        try {
            t.join(1500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void loop() {
        while (!stop) {
            try {
                // 0.11.50：等歌词层把磁盘回读（.lrc + .none 无词标记）做完再列名单——否则标记还没进内存，
                // 上千首已知无词的歌会在开机竞态里被当成待办重投一遍（0.11.49 真机每启一次 ≈110 s）。
                LyricService.awaitDiskScan(15000L);
                List<String[]> all = tracks();
                if (all.isEmpty()) {
                    // 库还没就绪（宿主启动扫描占锁）或读库失败：短等重试，别当成「全部就绪」
                    if (dbWaitLog++ < DB_WAIT_LOG_MAX) {
                        PluginLog.i(TAG, "歌词预热：宿主曲库暂不可读，10 秒后重试");
                    }
                    Thread.sleep(DB_RETRY_MS);
                    continue;
                }
                dbWaitLog = 0;
                LyricService.noteTotal(all.size());
                List<Dto.Song> songs = new ArrayList<>(all.size());
                Map<Long, String> urls = new LinkedHashMap<>();
                int readyOnDisk = 0;
                int knownNo = 0;
                for (String[] row : all) {
                    if (stop) {
                        break;
                    }
                    long id = parseId(row[0]);
                    if (id <= 0L) {
                        continue;
                    }
                    if (Files.isRegularFile(lrcFile(id))) {
                        readyOnDisk++;
                        continue;
                    }
                    if (LyricService.knownNoLyric(id)) {
                        knownNo++;
                        continue;
                    }
                    songs.add(new Dto.Song(id, row[1], row[2], row[3], 0L, 0, true, ""));
                    urls.put(id, NativeStreamServer.urlFor(id));
                }
                if (songs.isEmpty()) {
                    PluginLog.i(TAG, "歌词预热：无待办（库内 " + all.size() + " 首，磁盘就绪 "
                            + readyOnDisk + " 首，已知无词 " + knownNo + " 首），"
                            + (RECHECK_MS / 60000L) + " 分钟后再看");
                    Thread.sleep(RECHECK_MS);
                    continue;
                }
                PluginLog.i(TAG, "歌词预热：投递 " + songs.size() + " 首（库内 " + all.size()
                        + " 首，磁盘已就绪 " + readyOnDisk + " 首，已知无词 " + knownNo + " 首，并发 "
                        + com.example.netease.core.RiskControl.WARM_CONCURRENCY + "）");
                LyricService.preheat(songs, urls);
                // 投递即返（LyricService 内部 6 路接力抓取），这里等它抓完再复查。
                // 0.11.8 · C-cold 轮实证：这里原来调的是 prefetch（「播放即抓」口径），
                // dispatch 的 warm 分支成死代码 ⇒ 冻结判据行「预热开跑 / 预热完成 / 失败分类」
                // 一行都不打，A11 的 Tms 无从取证。预热必须走预热口径的入口。
                Thread.sleep(RECHECK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                PluginLog.w(TAG, "歌词预热轮次异常（继续）：" + t);
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /** 歌词缓存文件（与 {@link LyricService} 同一约定：{@code <插件数据>\lyric\<songId>.lrc}）。 */
    private static Path lrcFile(long songId) {
        return DataPaths.data().resolve("lyric").resolve(songId + ".lrc");
    }

    /**
     * 读宿主曲库取曲目素材（id/title/artist/album）。
     *
     * <p>两点纪律：① 连接后置 {@code PRAGMA query_only=ON}，确保这条通道<b>只读</b>；
     * ② 不按 {@code path LIKE} 过滤协议 —— 只要 path 里有 {@code /netease/<id>} 或
     * {@code netease-<id>} 形态的 id 就认（宿主可能在 {@code http://127.0.0.1…} 与
     * {@code file:///…} 之间改写 path，过滤协议会让预热范围悄悄缩水）。</p>
     *
     * <p>库不可读/读失败返回空表（上一轮已打 warn，下一轮再试）。</p>
     */
    private static List<String[]> tracks() {
        List<String[]> out = new ArrayList<>();
        Connection c = null;
        try {
            Path db = CoverPrimer.hostDb();
            if (db == null || !Files.isRegularFile(db)) {
                return out;
            }
            Properties p = new Properties();
            p.setProperty("busy_timeout", "5000");
            c = drv().connect("jdbc:sqlite:" + db, p);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA query_only=ON");
                try (ResultSet r = s.executeQuery("SELECT id, path, title, artist, album FROM Track")) {
                    while (r.next()) {
                        String id = neteaseId(r.getString(1), r.getString(2));
                        if (id == null) {
                            continue;
                        }
                        out.add(new String[]{id,
                                nz(r.getString(3), "未知曲目"),
                                nz(r.getString(4), "未知歌手"),
                                nz(r.getString(5), "未知专辑")});
                    }
                }
            }
        } catch (Throwable t) {
            // 0.11.8 起不再静默：这个 catch 原来用 PluginLog.d，info 级别下看不见，
            // 「No suitable driver found」才藏了四个版本（见类注释第 1 条）。
            PluginLog.w(TAG, "读曲库失败（下一轮重试）：" + t);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                    // 关不上不影响本轮结果
                }
            }
        }
        return out;
    }

    /**
     * 从 {@code Track.id}/{@code Track.path} 里取网易云 songId（纯数字串）；取不到返回 null。
     *
     * <p>{@code Track.id} 在真机上是 {@code netease-<songId>} 形态（0.11.6 前也出现过纯数字），
     * 所以先剥前缀再校验；都不行时再从 path 里抠 {@code /netease/<id>}。</p>
     */
    private static String neteaseId(String id, String path) {
        String v = strip(id);
        if (v != null) {
            return v;
        }
        if (path != null) {
            int i = path.indexOf("/netease/");
            if (i >= 0) {
                int p = i + 9;
                StringBuilder b = new StringBuilder();
                while (p < path.length() && b.length() < 15) {
                    char ch = path.charAt(p);
                    if (ch < '0' || ch > '9') {
                        break;
                    }
                    b.append(ch);
                    p++;
                }
                if (b.length() > 0) {
                    return b.toString();
                }
            }
        }
        return null;
    }

    /** 剥掉 {@code netease-} 前缀并校验剩余部分是纯数字。 */
    private static String strip(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        if (v.regionMatches(true, 0, "netease-", 0, 8)) {
            v = v.substring(8);
        }
        if (v.isEmpty() || v.length() > 15) {
            return null;
        }
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch < '0' || ch > '9') {
                return null;
            }
        }
        return v;
    }

    private static long parseId(String s) {
        if (s == null || s.isEmpty()) {
            return -1L;
        }
        try {
            return Long.parseLong(s);
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** SQLite 驱动：显式取类并实例化（插件类加载器下不能用 {@code DriverManager}，见类注释）。 */
    private static Driver drv() throws Exception {
        Driver d = driver;
        if (d != null) {
            return d;
        }
        synchronized (LyricWarmer.class) {
            if (driver == null) {
                Object o = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance();
                driver = (Driver) o;
            }
            return driver;
        }
    }

    private static String nz(String s, String dft) {
        return s == null || s.isBlank() ? dft : s;
    }

    /** 摘要一行（自检/配置页用；真正的完成度口径在 {@link LyricService#stats()}）。 */
    public static String stats() {
        return "歌词预热：全量投递由 LyricService 并发抓取（并发 "
                + com.example.netease.core.RiskControl.WARM_CONCURRENCY + "）｜" + LyricService.stats();
    }
}
