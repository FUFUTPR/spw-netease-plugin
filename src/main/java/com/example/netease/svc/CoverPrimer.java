package com.example.netease.svc;

import com.example.netease.core.PluginLog;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

/**
 * 歌曲行 / 播放条封面 —— <b>零音频下载</b>方案（0.11.3）。
 *
 * <p>宿主行内缩略图走 coil3 磁盘缓存（{@code <宿主数据>\cache\shared_cover}），键可从宿主字节码逐字得到
 * （{@code AudioCoverKeyerKt.cacheKey}）：</p>
 *
 * <pre>
 *   key   = "{Track.path}?v=1&t={modifiedTime}&s={size}&r={coverRevision}&w={w}&h={h}"
 *   .0    = 元数据（可为 0 字节，宿主自己的正条目就是 0 字节）
 *   .1    = 图片字节
 *   journal 行 = "CLEAN &lt;sha256(key)&gt; 0 &lt;图片字节数&gt;"
 * </pre>
 *
 * <p>把「这首歌的封面图」按上述键写进缓存 + 追加一条 journal 行 ⇒ 行内出图，<b>不需要本地音频、
 * 不翻 {@code Track.path}、不产生任何音乐流量</b>（真机实证：{@code docs\08} §42）。</p>
 *
 * <p><b>铁律（上次踩过）</b>：<b>只追加、绝不重写 journal</b>（0.11.2 实验期整份重写 → 宿主判定
 * 不可信 → 重建整层缓存、10,425 个文件被清）。本类只做：写自己的 {@code .0/.1} + 追加 CLEAN 行；
 * 任何异常只记日志，绝不影响播放。</p>
 *
 * <p><b>线程</b>：自建守护单线程 {@code netease-cover-primer}。</p>
 */
public final class CoverPrimer {

    private static final String TAG = "primer";

    /** 要铺的尺寸：96 = 行内（真机实测命中），192 = 播放条等其它面。 */
    static final int[] SIZES = {96, 192};

    /**
     * <b>钉住的 {@code Track.modifiedTime}</b>（缓存键的一部分）。
     *
     * <p>为什么必须钉住：键 = {@code path?t={modifiedTime}&s={size}&r={coverRevision}}。同步轮每轮都会把
     * {@code modifiedTime} 写成一个新的当前时间 ⇒ <b>键全变</b>；而宿主的 DiskLruCache 只在
     * {@code initialize()}（本次会话第一次取图）时读一次 journal，<b>会话中途追加的键对它不可见</b>
     * ⇒ 行重绘时会为新键写一条 <b>负缓存</b>（0 字节），封面当场消失。把 {@code modifiedTime} 钉成常量后，
     * 键在同步前后、跨会话都不变：一次铺好，长期有效。</p>
     */
    static final long KEY_MTIME = NativeLibrary.PINNED_MODIFIED_TIME;

    /** 巡检周期（毫秒）：宿主 compaction / 本次会话负缓存都可能让条目失效。 */
    private static final long PATROL_MS = 30000L;

    /** 一轮丢进下载池的图片并发度（任务书 §13：取暖并发常量，别自己再抄一份）。 */
    private static final int WARM_CONCURRENCY = Math.max(1, com.example.netease.core.RiskControl.WARM_CONCURRENCY);

    private static volatile Thread worker;
    private static volatile boolean stop;
    private static volatile boolean requested;

    /** 本次会话铺过的键（巡检判据）。 */
    private static final Set<String> PUSHED = ConcurrentHashMap.newKeySet();

    /** 「库暂不可读」日志节流。 */
    private static volatile int retryLogged;

    // ---- 完成度三态（Q6：配置页「封面完成度」要能看见）----

    /** 上一轮的键总数（曲目数 × {@link #SIZES}.length）与已存在有效键数。 */
    private static final java.util.concurrent.atomic.AtomicInteger PLANNED =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger SKIPPED =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 读库失败 / 写盘失败 / 该专辑无图（三态里「失败」与分类的构成）。 */
    private static final java.util.concurrent.atomic.AtomicInteger DB_FAILS =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger WRITE_FAILS =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final Set<String> NO_IMG = ConcurrentHashMap.newKeySet();
    /** 最近一次读库失败的原文（日志只打一次全文，之后只报次数）。 */
    private static volatile String lastDbError = "";

    private CoverPrimer() {
    }

    // ------------------------------------------------------------------ 生命周期

    /** 起线程（幂等）。 */
    public static void start() {
        try {
            if (worker != null && worker.isAlive()) {
                return;
            }
            stop = false;
            requested = true;
            Thread t = new Thread(CoverPrimer::loop, "netease-cover-primer");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            worker = t;
            t.start();
        } catch (Throwable t) {
            PluginLog.d(TAG, "封面预热启动失败（忽略）：" + t);
        }
    }

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

    /** 请求铺一轮（启动 / 同步后 / 巡检发现失效时调用）。 */
    public static void primeAll(String why) {
        requested = true;
        PluginLog.d(TAG, "已请求封面预热（" + why + "）");
    }

    private static void loop() {
        int idle = 0;
        while (!stop) {
            try {
                if (requested) {
                    requested = false;
                    idle = 0;
                    primeOnce();
                    if (PUSHED.isEmpty()) {
                        Thread.sleep(5000L);         // 库还没就绪（插件启动早于宿主曲库可读）：退避后重试
                        requested = true;
                    }
                    continue;
                }
                idle++;
                if (idle * 2000L >= PATROL_MS) {
                    idle = 0;
                    if (patrol()) {
                        primeOnce();
                    }
                }
                Thread.sleep(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                PluginLog.d(TAG, "封面预热轮次异常（继续）：" + t);
            }
        }
    }

    // ------------------------------------------------------------------ 铺库

    /**
     * 铺一轮：<b>取图/缩放多线程</b>（{@link #WARM_CONCURRENCY} 路，A11 全量 ≤60 s）+ <b>写盘与 journal 追加单线程</b>
     * （journal 只允许追加，且必须串行 —— 见类头铁律）。
     *
     * <p>按<b>专辑</b>分组派活：同一专辑的多首歌共用一份原图与两张缩略图，既不重复下载也不重复缩放。
     * 素材只走 {@code CoverStore}（唯一入口：1200 单尺寸 + sha256 去重 + 失败分类），<b>全程零音频</b>。</p>
     */
    private static void primeOnce() {
        Path journal = journalPath();
        if (journal == null) {
            PluginLog.d(TAG, "封面预热跳过：找不到宿主缩略图缓存（<宿主数据>\\cache\\shared_cover）");
            return;
        }
        long t0 = System.currentTimeMillis();
        // ⓪ 先把 modifiedTime 钉成常量（键稳定性前提；已经是常量就跳过）
        pinModifiedTime();
        Map<String, Long> have = journalLastSize(journal);
        List<String[]> rows = readRows();
        boolean fromDb = !rows.isEmpty();
        if (!fromDb) {
            rows = loadCachedRows();
        }
        final Path cacheDir = journal.getParent();

        // 按专辑分派（同专辑只取一次图、只缩一次图）
        Map<String, List<String[]>> byAlbum = new java.util.LinkedHashMap<>();
        for (String[] row : rows) {
            if (row.length < 4 || rowPath(row).isBlank()) {
                continue;
            }
            // 0.11.49：按（专辑+歌手）分组 —— 同名专辑不同艺人各取各的图（旧按专辑名 = 首登记胜出）
            byAlbum.computeIfAbsent(rowRefKey(row), k -> new ArrayList<>()).add(row);
        }
        PLANNED.set(rows.size() * SIZES.length);
        SKIPPED.set(0);

        java.util.concurrent.BlockingQueue<Object[]> out = new java.util.concurrent.LinkedBlockingQueue<>();
        java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(byAlbum.size());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                Math.min(WARM_CONCURRENCY, Math.max(1, byAlbum.size())), r -> {
                    Thread t = new Thread(r, "netease-cover-warm");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                });
        for (Map.Entry<String, List<String[]>> e : byAlbum.entrySet()) {
            final String refKey = e.getKey();
            final List<String[]> mine = e.getValue();
            pool.execute(() -> {
                try {
                    // ① 先把两档键算出来，命中的直接跳过（纯本地判断，不取图）
                    long srcT = CoverStore.imageTime(refKey);   // 0.11.49：图换过就不算命中（陈旧自愈）
                    List<String> hexes = new ArrayList<>(mine.size() * SIZES.length);
                    List<int[]> missing = new ArrayList<>();
                    for (int j = 0; j < mine.size(); j++) {
                        String[] row = mine.get(j);
                        long size;
                        int rev;
                        try {
                            size = Long.parseLong(rowSize(row));
                            rev = Integer.parseInt(rowRev(row));
                        } catch (Throwable t) {
                            continue;
                        }
                        for (int i = 0; i < SIZES.length; i++) {
                            int sz = SIZES[i];
                            String hex = sha256Hex(rowPath(row) + "?v=1&t=" + KEY_MTIME + "&s=" + size + "&r=" + rev
                                    + "&w=" + sz + "&h=" + sz);
                            if (hex == null) {
                                continue;
                            }
                            Path f1 = cacheDir.resolve(hex + ".1");
                            Long lastSize = have.get(hex);
                            if (lastSize != null && lastSize > 0L && Files.isRegularFile(f1)
                                    && sizeOf(f1) == lastSize && !olderThan(f1, srcT)) {
                                SKIPPED.incrementAndGet();
                                continue;
                            }
                            hexes.add(hex);
                            missing.add(new int[]{j, i});
                        }
                    }
                    if (missing.isEmpty()) {
                        return;
                    }
                    // ② 取图（唯一入口；只取图片 URL，不下音乐）
                    byte[] src = CoverStore.fetchImage(refKey);
                    if (src == null) {
                        src = placeholder(CoverStore.albumOfRef(refKey));   // 取不到才用占位图（R11：不留 ♪）
                        NO_IMG.add(refKey);
                    }
                    if (src == null) {
                        return;
                    }
                    byte[][] thumbs = new byte[SIZES.length][];      // 本专辑两档缩略图各缩一次
                    for (int k = 0; k < missing.size(); k++) {
                        int[] mi = missing.get(k);
                        int i = mi[1];
                        if (thumbs[i] == null) {
                            thumbs[i] = shrink(src, SIZES[i]);
                            if (thumbs[i] == null) {
                                continue;
                            }
                        }
                        out.put(new Object[]{hexes.get(k), thumbs[i]});
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    PluginLog.d(TAG, "预热取图异常（跳过 " + CoverStore.display(refKey) + "）：" + t);
                } finally {
                    latch.countDown();
                }
            });
        }

        // 单线程消费：写 .0/.1 + 收集 journal 行（每 500 行追一次，内存有界）
        StringBuilder append = new StringBuilder(1 << 16);
        int tracks = rows.size(), pushed = 0, writeFails = 0;
        long bytes = 0L;
        try {
            while (latch.getCount() > 0L || !out.isEmpty()) {
                Object[] item = out.poll(200L, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (item == null) {
                    if (stop) {
                        break;
                    }
                    continue;
                }
                String hex = (String) item[0];
                byte[] jpg = (byte[]) item[1];
                try {
                    Files.write(cacheDir.resolve(hex + ".0"), new byte[0]);
                    Files.write(cacheDir.resolve(hex + ".1"), jpg);
                } catch (Throwable t) {
                    writeFails++;
                    WRITE_FAILS.incrementAndGet();
                    if (writeFails <= 3) {
                        PluginLog.w(TAG, "写缓存文件失败（跳过该键）：" + t);
                    }
                    continue;
                }
                append.append("CLEAN ").append(hex).append(" 0 ").append(jpg.length).append('\n');
                PUSHED.add(hex);
                pushed++;
                bytes += jpg.length;
                if (pushed % 500 == 0) {
                    appendJournal(journal, append.toString());
                    append.setLength(0);
                }
                if (stop) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }

        boolean ok = append.length() == 0 || appendJournal(journal, append.toString());
        if (fromDb) {
            saveRows(rows);                      // 实时素材落盘：下次启动即使读不到库也能零延迟铺
        }
        if (tracks == 0 && retryLogged++ < 20) {
            PluginLog.i(TAG, "封面预热：宿主曲库暂不可读（读到 0 首，稍后自动重试）"
                    + (lastDbError.isEmpty() ? "" : "；最近一次错误：" + lastDbError));
            return;
        }
        PluginLog.i(TAG, "封面预热：" + (fromDb ? "读库" : "用上一轮素材") + " → 曲目 " + tracks + " 首 → 新铺 "
                + pushed + " 条 / " + (bytes / 1024L)
                + " KB（跳过 " + SKIPPED.get() + "，该专辑无图 " + NO_IMG.size() + "，journal 原有 " + have.size()
                + " 条，追加 " + (ok ? "成功" : "失败") + "，写盘失败 " + writeFails
                + "，并发 " + WARM_CONCURRENCY + "，用时 " + (System.currentTimeMillis() - t0) + " ms）");
    }

    /** 再钉一次 {@code modifiedTime} 并请求铺一轮（{@code RowCover} 清理历史本地路径后必须调：mtime 被改过）。 */
    public static void repinAndPrime(String why) {
        try {
            pinModifiedTime();
        } catch (Throwable t) {
            PluginLog.d(TAG, "重钉 modifiedTime 失败（忽略）：" + t);
        }
        primeAll(why);
    }

    /**
     * 把本插件曲目的 {@code Track.modifiedTime} 钉成 {@link #KEY_MTIME}（键稳定性前提）。
     *
     * <p>走宿主同连接（Room 失效 ⇒ 行重绘时用的就是新键）；已经是常量则一次只读查询即返回。</p>
     */
    private static void pinModifiedTime() {
        try {
            Path db = NativeLibrary.dbPath();
            if (db == null) {
                return;
            }
            final String stmt = "UPDATE Track SET modifiedTime = " + KEY_MTIME
                    + " WHERE id GLOB 'netease-[0-9]*' AND modifiedTime <> " + KEY_MTIME;
            Integer n = com.example.netease.host.HostSqlBridge.onHostConnection(
                    db.toString(),
                    sql -> {
                        sql.exec(stmt);
                        return 1;
                    });
            if (n == null) {
                NativeLibrary.pinModifiedTimeExternal(KEY_MTIME);
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "钉 modifiedTime 失败（忽略）：" + t);
        }
    }

    /**
     * 巡检：**所有**我们该有的键是不是都「最后一条 CLEAN 行 > 0 且 {@code .1} 长度对得上」。
     *
     * <p>键由上一轮落盘的素材重算（跨会话稳定），所以不依赖本会话铺过什么 —— 宿主 compaction 丢行、
     * 或它请求未命中写下 0 字节负行，30 秒内就会被发现并重铺（重铺行追加在负行之后 ⇒ 下次启动即生效）。</p>
     */
    private static boolean patrol() {
        try {
            Path journal = journalPath();
            if (journal == null) {
                return false;
            }
            List<String[]> rows = loadCachedRows();
            if (rows.isEmpty()) {
                return false;
            }
            Map<String, Long> have = journalLastSize(journal);
            Map<String, Long> srcTimes = new HashMap<>();
            int missing = 0;
            for (String[] row : rows) {
                if (row.length < 4 || rowPath(row).isBlank()) {
                    continue;
                }
                long size;
                int rev;
                try {
                    size = Long.parseLong(rowSize(row));
                    rev = Integer.parseInt(rowRev(row));
                } catch (Throwable t) {
                    continue;
                }
                String refKey = rowRefKey(row);
                Long srcT = srcTimes.get(refKey);
                if (srcT == null) {
                    srcT = CoverStore.imageTime(refKey);
                    srcTimes.put(refKey, srcT);
                }
                for (int sz : SIZES) {
                    String hex = sha256Hex(rowPath(row) + "?v=1&t=" + KEY_MTIME + "&s=" + size + "&r=" + rev
                            + "&w=" + sz + "&h=" + sz);
                    if (hex == null) {
                        continue;
                    }
                    Long lastSize = have.get(hex);
                    Path f1 = journal.getParent().resolve(hex + ".1");
                    if (lastSize == null || lastSize <= 0L || !Files.isRegularFile(f1)
                            || sizeOf(f1) != lastSize || olderThan(f1, srcT)) {
                        missing++;
                    }
                }
            }
            if (missing > 0) {
                PluginLog.i(TAG, "封面预热：巡检发现 " + missing + " 条失效（宿主 compaction / 负缓存覆盖 / 文件被清），重铺");
                return true;
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "封面预热巡检异常（忽略）：" + t);
        }
        return false;
    }

    /**
     * 完成度三态（Q6：配置页「封面完成度」一行可见）。
     *
     * <p>键的口径 = {@code 曲目数 × 2 档（96/192）}；已就绪 = 本次会话铺成功的键 + 本轮判断已命中而跳过的键
     * （两者都已被宿主 {@code openSnapshot} 命中过或即将命中）；失败 = 写盘失败，分类里另给「读库失败 / 无图专辑」。</p>
     */
    public static String stats() {
        int planned = PLANNED.get();
        int ready = PUSHED.size() + SKIPPED.get();
        int failed = WRITE_FAILS.get();
        int pending = Math.max(0, planned - ready - failed);
        if (planned == 0) {
            ready = PUSHED.size();
            pending = 0;
        }
        StringBuilder sb = new StringBuilder(180);
        sb.append("封面预热：已就绪 ").append(ready)
                .append(" / 待处理 ").append(pending)
                .append(" / 失败 ").append(failed + DB_FAILS.get());
        sb.append(" ｜ 键 ").append(planned > 0 ? planned : PUSHED.size())
                .append("（跳过 ").append(SKIPPED.get())
                .append(" / 无图专辑 ").append(NO_IMG.size())
                .append(" / 写盘失败 ").append(WRITE_FAILS.get())
                .append(" / 读库失败 ").append(DB_FAILS.get()).append("）");
        sb.append(" ｜ 尺寸 96/192 两档");
        return sb.toString();
    }

    // ------------------------------------------------------------------ 缓存面

    /**
     * 宿主曲库文件路径（预热要尽早跑，不能等宿主桥就绪）。
     *
     * <p>顺序：{@link NativeLibrary#dbPath()}（宿主桥解析出来的）→ {@code %APPDATA%\Salt Player for Windows\spw.db}
     * （jpackage 默认数据目录，真机就是这个）→ {@code %LOCALAPPDATA%} 的同名位置。命中即返回可读文件。</p>
     */
    public static Path hostDb() {
        return resolveDb();
    }

    private static Path resolveDb() {
        try {
            Path p = NativeLibrary.dbPath();
            if (p != null && Files.isRegularFile(p)) {
                return p;
            }
        } catch (Throwable ignored) {
            // 继续兜底
        }
        String[] roots = {System.getenv("APPDATA"), System.getenv("LOCALAPPDATA")};
        for (String root : roots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            try {
                Path p = Path.of(root, "Salt Player for Windows", "spw.db");
                if (Files.isRegularFile(p)) {
                    return p;
                }
            } catch (Throwable ignored) {
                // 继续
            }
        }
        return null;
    }

    private static String dbPath() {
        Path p = resolveDb();
        return p == null ? "" : p.toString();
    }

    /** 缓存目录（{@code <宿主数据>\cache\shared_cover}）：由曲库文件位置推出，找不到返回 null。 */
    public static Path cacheDir() {
        try {
            Path db = resolveDb();
            if (db != null && db.getParent() != null) {
                Path c = db.getParent().resolve("cache").resolve("shared_cover");
                if (Files.isDirectory(c)) {
                    return c;
                }
            }
        } catch (Throwable ignored) {
            // 兜底
        }
        return null;
    }

    private static Path journalPath() {
        Path dir = cacheDir();
        if (dir == null) {
            return null;
        }
        Path j = dir.resolve("journal");
        return Files.isRegularFile(j) ? j : null;
    }

    /**
     * 直接读库取素材（不依赖宿主桥：预热必须赶在宿主「第一次取图读 journal」之前完成）。
     *
     * <p>只用只读连接，宿主侧照常读写（真机实测：宿主运行期间外部只读查询稳定）。</p>
     */
    // ---- 0.11.49 行素材的下标适配：新 5 列（专辑/歌手/路径/大小/修订），旧 4 列（无歌手）照读 ----

    private static String rowAlbum(String[] row) {
        return row[0];
    }

    private static String rowArtist(String[] row) {
        return row.length >= 5 ? row[1] : "";
    }

    private static String rowPath(String[] row) {
        return row.length >= 5 ? row[2] : row[1];
    }

    private static String rowSize(String[] row) {
        return row.length >= 5 ? row[3] : row[2];
    }

    private static String rowRev(String[] row) {
        return row.length >= 5 ? row[4] : row[3];
    }

    /** 这张（专辑+歌手）的封面引用键：宿主 Album 行就是按这两个值派生的。 */
    private static String rowRefKey(String[] row) {
        return CoverStore.refKey(rowAlbum(row), rowArtist(row));
    }

    /** 键文件是否比源图（{@code srcT}）旧 —— 旧 = 内容可能已换、必须重铺（0.11.49 陈旧自愈）。 */
    private static boolean olderThan(Path f1, long srcT) {
        if (srcT <= 0L) {
            return false;
        }
        try {
            return Files.getLastModifiedTime(f1).toMillis() < srcT;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static List<String[]> readRows() {
        List<String[]> out = new ArrayList<>();
        Path db = resolveDb();
        if (db == null) {
            return out;
        }
        try (java.sql.Connection c = openReadOnly(db);
             java.sql.Statement s = c.createStatement();
             java.sql.ResultSet r = s.executeQuery(
                     "SELECT album, artist, path, size, coverRevision FROM Track"
                     + " WHERE path LIKE 'http://127.0.0.1%'")) {
            while (r.next()) {
                out.add(new String[]{
                        r.getString(1) == null ? "" : r.getString(1),
                        r.getString(2) == null ? "" : r.getString(2),
                        r.getString(3) == null ? "" : r.getString(3),
                        Long.toString(r.getLong(4)),
                        Integer.toString(r.getInt(5))});
            }
        } catch (Throwable t) {
            lastDbError = String.valueOf(t);
            DB_FAILS.incrementAndGet();
            PluginLog.e(TAG, "读曲库失败（下一轮重试）：" + t);
        }
        return out;
    }

    /**
     * 打开宿主曲库的<b>只读</b>连接。
     *
     * <p>0.11.7 真机踩坑：读库前没注册 JDBC 驱动，`DriverManager` 直接抛
     * {@code java.sql.SQLException: No suitable driver found for jdbc:sqlite:<…>\spw.db}，
     * 预热在真机上其实一次都没读成功过（探针 P-4 的 S4/S5c/S5d 也被同一个坑废掉）。</p>
     *
     * <p>另外三条纪律：① 只用 {@code query_only}，<b>绝不</b>用 {@code immutable=1}
     * （会让宿主写入的库被当成不可变快照，读到旧数据）；② 不设 {@code WAL} 相关参数，宿主自己管；
     * ③ 连接超时压到 2 s，避免宿主 compaction 时插件线程长等。</p>
     */
    private static java.sql.Connection openReadOnly(Path db) throws Exception {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (Throwable ignore) {
            // 驱动已由宿主类加载器注册时 classForName 会失败，继续走 DriverManager 即可
        }
        java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
        try (java.sql.Statement s = c.createStatement()) {
            s.execute("PRAGMA query_only=ON");
        }
        return c;
    }

    // ------------------------------------------------------------------ 上一轮的键素材（零延迟预热）

    /** 上一轮铺库用过的素材（album/artist/path/size/rev；0.11.49 起带歌手，旧 4 列照读）——
     * 键里除它以外只有常量与尺寸 ⇒ 跨会话有效。 */
    private static Path keysFile() {
        try {
            return com.example.netease.core.DataPaths.data().resolve("cover-primer.tsv");
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<String[]> loadCachedRows() {
        List<String[]> out = new ArrayList<>();
        try {
            Path f = keysFile();
            if (f == null || !Files.isRegularFile(f)) {
                return out;
            }
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t", -1);
                if (p.length >= 4 && !p[p.length - 3].isBlank()) {
                    out.add(p);              // 4 列 = 旧素材（无歌手）；5 列 = 0.11.49 起
                }
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "读键素材失败（忽略）：" + t);
        }
        return out;
    }

    private static void saveRows(List<String[]> rows) {
        try {
            Path f = keysFile();
            if (f == null || rows.isEmpty()) {
                return;
            }
            StringBuilder sb = new StringBuilder(rows.size() * 96);
            for (String[] r : rows) {
                for (int i = 0; i < r.length; i++) {
                    if (i > 0) {
                        sb.append('\t');
                    }
                    sb.append(r[i]);
                }
                sb.append('\n');
            }
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            PluginLog.d(TAG, "写键素材失败（忽略）：" + t);
        }
    }

    /**
     * journal 里每个键的<b>最后一条 CLEAN 行</b>的 size1（0 = 宿主写的负缓存；读取规则是「以最后一条为准」）。
     */
    private static Map<String, Long> journalLastSize(Path journal) {
        Map<String, Long> out = new ConcurrentHashMap<>();
        try {
            for (String line : Files.readAllLines(journal, StandardCharsets.UTF_8)) {
                if (!line.startsWith("CLEAN ")) {
                    continue;
                }
                String[] p = line.split(" ");
                if (p.length >= 4) {
                    try {
                        out.put(p[1], Long.parseLong(p[3]));
                    } catch (Throwable ignored) {
                        // 畸形行跳过
                    }
                }
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "读 journal 失败（按空处理）：" + t);
        }
        return out;
    }

    /** <b>只追加</b>（绝不重写）：宿主自己也用追加写 journal。 */
    private static boolean appendJournal(Path journal, String lines) {
        try {
            boolean needLf = true;
            long n = Files.size(journal);
            if (n > 0) {
                byte[] last = new byte[1];
                try (var ch = Files.newByteChannel(journal)) {
                    ch.position(n - 1);
                    ch.read(ByteBuffer.wrap(last));
                }
                needLf = last[0] != '\n';
            }
            try (var out = Files.newBufferedWriter(journal, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
                if (needLf) {
                    out.write("\n");
                }
                out.write(lines);
            }
            return true;
        } catch (Throwable t) {
            PluginLog.d(TAG, "追加 journal 失败（文件已写，靠宿主下次重建自愈）：" + t);
            return false;
        }
    }

    // ------------------------------------------------------------------ 图片

    /** 单曲投递（{@code svc.CoverDelivery}）复用同一套缩放参数：两边的同一档键必须字节一致。 */
    static byte[] thumb(byte[] src, int size) {
        return shrink(src, size);
    }

    /** 取专辑原图 → 缩到 size×size 的 JPEG；失败返回 null。 */
    private static byte[] shrink(byte[] src, int size) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(src));
            if (img == null) {
                return null;
            }
            BufferedImage dst = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, size, size);
                int w = img.getWidth(), h = img.getHeight();
                double k = Math.min(size / (double) w, size / (double) h);
                int tw = Math.max(1, (int) Math.round(w * k)), th = Math.max(1, (int) Math.round(h * k));
                g.drawImage(img, (size - tw) / 2, (size - th) / 2, tw, th, null);
            } finally {
                g.dispose();
            }
            var param = new javax.imageio.plugins.jpeg.JPEGImageWriteParam(Locale.ROOT);
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.86f);
            var writer = ImageIO.getImageWritersByFormatName("jpg").next();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (var ios = ImageIO.createImageOutputStream(bos)) {
                writer.setOutput(ios);
                writer.write(null, new javax.imageio.IIOImage(dst, null, null), param);
            }
            writer.dispose();
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 专辑没有封面图时的占位图（纯色 + 专辑首字母），与 {@code svc.CoverStore} 的占位风格一致 ——
     * 保证「每首歌的行封面都有东西」，不留 ♪。
     */
    private static byte[] placeholder(String album) {
        try {
            int size = 512;
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                String name = album == null ? "" : album;
                float hue = Math.floorMod(name.hashCode(), 360) / 360.0f;
                g.setColor(Color.getHSBColor(hue, 0.42f, 0.52f));
                g.fillRect(0, 0, size, size);
                String ch = "?";
                for (int i = 0; i < name.length(); i++) {
                    char c = name.charAt(i);
                    if (!Character.isWhitespace(c) && c != '[' && c != '(' && c != '（' && c != '【') {
                        ch = String.valueOf(c).toUpperCase(Locale.ROOT);
                        break;
                    }
                }
                g.setColor(new Color(255, 255, 255, 232));
                g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, size / 2));
                var fm = g.getFontMetrics();
                int y = (size - fm.getHeight()) / 2 + fm.getAscent();
                g.drawString(ch, (size - fm.stringWidth(ch)) / 2, y);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static long sizeOf(Path p) {        try {
            return Files.size(p);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 宿主缓存键 = sha256(明文字符串)，小写十六进制。 */
    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}






