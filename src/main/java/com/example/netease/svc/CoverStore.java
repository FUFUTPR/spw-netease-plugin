package com.example.netease.svc;

import com.example.netease.core.DataPaths;
import com.example.netease.core.Hashes;
import com.example.netease.core.Json;
import com.example.netease.core.PluginLog;
import com.example.netease.core.RiskControl;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

/**
 * 独立的封面缓存（0.11.0）：<b>不删、不设上限</b>，一次铺满就永久复用。
 *
 * <p>为什么必须独立：宿主「歌曲」列表行的封面只认 {@code Track.path} 指向的本地媒体文件内嵌图，
 * 而歌单/专辑的封面是另一回事（写 {@code Album.cover} + 桩 FLAC）。0.10.x 每轮同步只造
 * {@code NATIVE_COVER_MAX=40} 张，库里 2000+ 专辑永远铺不满，于是「有的歌单有封面、有的没有」。
 * 本类把封面从「每轮同步的批量工作」变成「一次性铺满 + 后台补齐 + 播放时即时补」。</p>
 *
 * <p>布局（{@code <插件数据>\cover\}）：</p>
 * <pre>
 *   img\&lt;md5(去参数 picUrl)&gt;.jpg|png   原图（下载一次，重建桩时不再联网）
 *   stub\cover-stub-&lt;md5&gt;.flac          交给宿主的「假 FLAC」：只有一个 PICTURE 块
 *   cover-index.json                    每张图的下载尝试次数 / 原图文件名 / 是否占位
 *   cover-refs.json                     专辑 → 桩 的映射（补齐线程的工作清单也在这里）
 * </pre>
 *
 * <p><b>0.11.1「实时 + 全力」口径</b>（用户定的）：</p>
 * <ol>
 *   <li><b>不限量</b>：清单里有多少待造，就一次性全丢进下载池（不再有「每批/每轮最多 N 张」）；</li>
 *   <li><b>不节流</b>：{@link #NET_THREADS} 条守护线程并行下，没有全局请求间隔；</li>
 *   <li><b>造好一张就写一张</b>：投递线程从就绪队列里「有多少取多少」立刻回调 {@link Sink}
 *       （旧版凑满 {@code FILL_BATCH=200} 才回调 ⇒ 用户看到的是「200 张一跳」）。</li>
 * </ol>
 *
 * <p>线程：下载与落盘只在下载池线程（{@code netease-cover-net-*}）或调用方的网络线程上，
 * 每个 {@code idx} 各自一把锁（同一张图不会被并发下两遍）；<b>投递与索引落盘</b>只在补齐线程
 * {@code netease-cover-fill} 上（单写者，索引文件不会被并发写）。宿主回调线程<b>绝不</b>进这里
 * （docs/00 §4）。</p>
 */
public final class CoverStore {

    private static final String TAG = "cover";

    /** 每张原图最大字节（与 {@link CoverArt#MAX_IMAGE_BYTES} 一致）。 */
    private static final long MEM_GUARD_BYTES = 512L * 1024 * 1024;
    /**
     * 占位图边长（R11）：<b>刻意保持 512²</b>，与取图尺寸 {@link #SIZE}（1200²）分开。
     *
     * <p>占位图不是「封面图」而是纯色 + 首字母的字母块：抬到 1200² 会让两千多个无图专辑多占
     * ~90 MB，而宿主把 API 图与自己画的影响看完全看不出差别。</p>
     *
     * <p><b>0.11.7 A13 收口（2026-10-01 真机）</b>：原先把占位图也落在 {@code cover\img\} 里，
     * 靠「非占位前缀」把 A13 的尺寸判据掰弯 —— 真机实测 {@code img\} 里 2 张 {@code placeholder-*.png}
     * 让「所有文件同为 1200×1200」判据 FAIL。现在占位图单独落 {@link #placeholderDir()}（{@code cover\placeholder\}），
     * {@code img\} 里只剩下载来的原图 ⇒ 两条要求（A13 单尺寸 / docs\50:393 占位 512²）都按字面成立、无需豁免。</p>
     */
    private static final int PLACEHOLDER_SIZE = 512;
    /**
     * 同一张图最多试几次（之后改出占位图，不再反复打同一个坏 URL）。
     *
     * <p>0.11.1 从 3 提到 5：下载改成 8 路并行后，瞬时限流（429/5xx）也可能连挂几次，
     * 而占位图在<b>本会话</b>里是终态（达到上限后不再重下）——阈值太紧会把「一时下不到」锁成
     * 「永远是一张字母块」。失败之间走 {@link #RETRY_BACKOFF_MS} 长退避，恢复后自然补上。</p>
     *
     * <p><b>0.11.51</b>：上限只压本会话；有真图 URL 的占位桩在下个会话/补齐轮会重新尝试
     * （真机 2026-10-04：两张专辑 01:07 一阵网络抖动落成占位后永久卡死，直到用户报障）。</p>
     */
    private static final int MAX_ATTEMPTS = 5;

    /** 有图占位桩的重取节奏（0.11.51）：一次重取失败后至少隔这么久再试，避免把 5 次机会连打光。 */
    private static final long STUCK_RETRY_MS = 120_000L;
    /**
     * 下载并发路数（用户口径「直接全力下」= 不限批、不串行节流）。
     *
     * <p>0.11.8 从 8 提到 32：A11 要求「首轮全量约 2000 张 ≤60 s」，而 200 张样本实测
     * （`harness\probe\p5-preview.py`，只下图片 URL，平均 642 KB/张）8 路只有 10.8 张/s
     * ⇒ 2089 张外推 ≈193 s（必然不达标）；16 路 33.6 张/s（≈62 s，贴线）；32 路 36.2 张/s
     * （≈58 s）—— 瓶颈是 CDN 吞吐（实测上限 ≈22.7 MB/s）而不是 CPU，所以把池开满到
     * 32 路（再往上已无收益：36.2 张/s 就是这条链路的物理上限）。</p>
     *
     * <p>风控不靠「少连」防：单张图自己的失败计数 + {@link RiskControl} 分级退避
     * （403/412/429 ⇒ 10min 起步）才是防线；真机若出现成片 403，日志里会以
     * {@code 封面失败[风控，n/5]} 暴露，而不是静默变慢。</p>
     */
    private static final int NET_THREADS = 32;
    private static final long FLUSH_MS = 5000L;
    private static final long STOP_JOIN_MS = 1500L;
    /** 投递线程的轮询上限：就绪队列一有图就投递，最坏拖这么久。 */
    private static final long POLL_MS = 200L;
    /** 工作清单扫描节流：清单里两千多个专辑，每个都要 `isRegularFile`，不必每 200 ms 扫一遍。 */
    private static final long SUBMIT_SCAN_MS = 500L;

    /**
     * 失败长退避（0.11.8 起，任务书第 6 条）：梯度<b>取自 {@link RiskControl}</b>，
     * 普通网络失败 {@code netBackoffMs} = 2s→4s→…→30s 封顶，风控（403/412/429）
     * {@code riskBackoffMs} = 10min→20min→…→1h 封顶。
     *
     * <p>旧版是「失败后 30 秒无脑重投」——2000+ 张里坏 URL 一多，下载池就被同一批
     * 失败项反复占满（真机 {@code ラストスコア} 那三张每轮都在重试）。现在按<b>每张图
     * 自己的</b>失败次数独立退避，且<b>退避期内先返回占位图</b>：用户当场就有图看，
     * 补上以后再由投递覆盖（不再出现「等 30 秒仍然是一张白」）。</p>
     */
    /** 单张图落盘上限（放大到 1200² 后留足余量）。 */
    private static final int MAX_STORE_BYTES = 8 * 1024 * 1024;

    /** 归一尺寸（Q1/A13）：所有落盘图一律 {@link #SIZE}×{@link #SIZE}。 */
    private static final int SIZE = CoverArt.SIZE;
    /** 解码后的像素上限：超过就拒收（防「请求 1200 却回一张 8000²」把堆撑爆）。 */
    private static final long MAX_PIXELS = 8000L * 8000L;

    private static final String PLACEHOLDER_PREFIX = "album:";

    /** 索引：{@code <stub idx> → {a:专辑, u:picUrl, i:原图文件名, n:尝试次数, p:是否占位}}。 */
    private static final Map<String, Object> INDEX = new LinkedHashMap<>();
    /** 专辑 → {a:专辑, b:歌手, u:picUrl, s:桩文件名}；同时是补齐线程的工作清单。 */
    private static final Map<String, Object> REFS = new LinkedHashMap<>();
    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    private static volatile boolean loaded;
    private static volatile boolean dirty;
    private static volatile long lastFlush;

    private static volatile Thread fillThread;
    private static volatile boolean fillStop;

    /** 下载池（{@link #NET_THREADS} 条守护线程）：{@link #startFill} 建，{@link #stopFill} 收。 */
    private static volatile java.util.concurrent.ExecutorService netPool;
    /** 正在下载的专辑（键 = 专辑名）：避免同一个专辑被重复投递。 */
    private static final java.util.Set<String> INFLIGHT = ConcurrentHashMap.newKeySet();
    /** 造好的封面：补齐线程从这里「有多少取多少」立刻投递（攒够一批不再是投递前提）。 */
    private static final java.util.concurrent.BlockingQueue<CoverArt.Cover> READY =
            new java.util.concurrent.LinkedBlockingQueue<>();
    /** 造桩失败的专辑 → 最早重试时刻（毫秒）：失败不再空转打网络（键 = 索引 idx）。 */
    private static final Map<String, Long> RETRY_AT = new ConcurrentHashMap<>();
    /** 索引 idx → 连续失败次数（长退避的自变量，成功即清零）。 */
    private static final Map<String, Integer> FAILS = new ConcurrentHashMap<>();
    /**
     * 索引 idx → <b>最近一次</b>失败分类（W6 离线实验室抓出的口径缺陷修复）。
     *
     * <p>失败分桶必须「一张图只进一个桶」：分类计数<b>不再按失败次数累加</b>，而是由本表现算
     * ⇒ 各桶之和恒等于失败张数（{@code FAILS.size()}）。本表与 {@link #FAILS} 同点写入、同点清零，
     * 两张表的键集合恒等（{@link #stats()} 会断言，对不上就打 ERROR，绝不静默）。</p>
     */
    private static final Map<String, String> FAIL_CLASS = new ConcurrentHashMap<>();
    /**
     * 归一<b>过程</b>计数（`缩放补齐/格式转码`）：<b>不是失败</b>，只在汇总行解释「落盘字节与原始响应为何不同」。
     *
     * <p>词形固定为 {@code 封面归一[<label>]：…}，与失败行 {@code 封面失败[<class>，K/N]} 严格分开；
     * 过程标签绝不进 {@link #FAILS} 的桶，否则会出现「失败 1 却列出了两个分类各 1」这种纸面矛盾。</p>
     */
    private static final Map<String, Integer> NORM_KINDS = new ConcurrentHashMap<>();
    /** 以上过程标签的<b>日志节流</b>（每类只打前 3 次，之后汇总日志里给总数）。 */
    private static final Map<String, Integer> NORM_KINDS_LOGGED = new ConcurrentHashMap<>();
    /** 归一到 {@link #SIZE}² 的落盘张数（过程账，A13 判据之一）。 */
    private static final java.util.concurrent.atomic.AtomicInteger NORMALIZED =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 去重账（A9 `去重省下 K 次`）：同一张图（同一 sha256）在进程里/磁盘上命中一次就 +1。 */
    private static final java.util.concurrent.atomic.AtomicLong DEDUP_HITS =
            new java.util.concurrent.atomic.AtomicLong();
    /** 本轮「完成」（新落到 {@code cover\img\} 的图）张数与字节数。 */
    private static final java.util.concurrent.atomic.AtomicInteger FETCHED =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicLong FETCHED_BYTES =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 完成纪元：每送出一批「新落地」的桩 +1（0.11.55 F8）。
     *
     * <p>面板缩略图（{@code ui.CoverCache}）的失败负缓存按它自愈：失败时记下当时的 epoch，
     * 之后一看到 epoch 前进（= 又有新封面落地，先前拿不到的那张可能已在路上）就清掉负缓存重试。
     * 0.11.55 之前的 NO_SRC 记账无人读，已删（无图源的占位终态由 {@link #refetchableStub}
     * 的空 URL 判定兜住）。</p>
     */
    private static final java.util.concurrent.atomic.AtomicLong DONE_EPOCH =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 曲目 id → 专辑（{@link #plan} 登记，播放即时补图用）。
     *
     * <p>播放路径只有 {@code songId}（宿主请求的是 {@code /netease/<id>}），拿不到专辑名；
     * 而同步时每首歌的专辑/歌手/图 URL 都过过手，顺手记一张小表最省事，
     * 也避免了在播放链路上再发一次「查歌曲详情」的网络请求。</p>
     */
    private static final Map<Long, String[]> SONGS = new ConcurrentHashMap<>();

    /** 即时补图的投递出口（{@link #startFill} 注册，与补齐线程共用同一个 Sink）。 */
    private static volatile Sink sink;

    /** 进度日志节流：至少隔 {@link #ROUND_LOG_MS}，或新完成 {@link #ROUND_LOG_EVERY} 张，才打一行汇总。 */
    private static final long ROUND_LOG_MS = 5000L;
    private static final int ROUND_LOG_EVERY = 25;
    private static volatile long startedAt = System.currentTimeMillis();
    /**
     * A11 口径（0.11.8）：本轮「清单可见后**第一次真的投出下载任务**」的时刻，0 = 还没投过。
     *
     * <p>为什么需要它：{@link #startedAt} 是静态初始化值（= CoverStore 类加载时刻，实测 13:28:26），
     * 于是「用时」里混进了「池已就绪、清单还没到」的空转 —— 冷启动轮实测 112815 ms 里有 77 s 是
     * 空转，A11「封面首轮全量 ≤ 60 s」被这段跟封面无关的等待吃掉了。这里把「用时」定义成
     * <b>开工 → 收尾</b>（= 封面管线自身；契约词形 {@code （用时 … ms）} 一字不动，见 docs/00 §6.20.1.1），
     * 空转改在开工行 {@code [cover] 封面本轮开工：…清单等待 W ms} 里单列。</p>
     */
    private static volatile long firstSubmitAt;
    /** 池就绪 →（本轮第一次）开工之间的空转毫秒；池一直有活时恒为 0。 */
    private static volatile long idleBeforeWorkMs;
    /**
     * 清单版本号（0.11.8）：{@link #plan} 每登记一批就自增，用来把正在{@link RiskControl} 退避里
     * <b>长睡</b>的补齐线程立刻叫醒。
     *
     * <p>为什么必须有它：补齐线程在「当下没活」时会按 1/2/5/30 秒退避睡眠。冷启动轮实测（0.11.7）
     * 池 13:28:27 就绪，而歌单详情要 ~60 s 后才把清单交进来 ⇒ 线程正睡在 30 s 那一档里，清单到了
     * 它也一无所知（A11 冷轮 77 s 零完成的主因）。现在睡眠切成 {@link #WAKE_SLICE_MS} 片并看这个
     * 版本号，登记一落地 ≤ 200 ms 内就重新扫。</p>
     */
    private static volatile long planSeq;
    /** 退避睡眠的切片长度：只为让 {@link #planSeq} 的变化能被及时看见，与网络节流无关。 */
    private static final long WAKE_SLICE_MS = 200L;
    private static volatile long lastRoundLog;
    private static volatile int lastRoundAt;
    /** 本轮是否已经打过收尾汇总行（见 {@link #logRoundEnd()}）；有新的投递/开始时复位。 */
    private static volatile boolean roundClosed;

    /** 补齐线程每凑够一批就回调（NeteasePlugin 负责写宿主库 + 刷新曲库）。 */
    public interface Sink {
        void onBatch(List<CoverArt.Cover> covers);
    }

    private CoverStore() {
    }

    // ------------------------------------------------------------------ 目录

    public static Path dir() {
        return DataPaths.data().resolve("cover");
    }

    public static Path imgDir() {
        return dir().resolve("img");
    }

    /**
     * 占位图目录（{@code cover\placeholder\}）—— 纯色 + 首字母的 512² 兜底图<b>不属于封面原图仓库</b>。
     *
     * <p>0.11.7 A13 收口（2026-10-01 真机）：A13 要求 {@code cover\img\} 里<b>所有文件同为 1200×1200</b>，
     * 而 docs\50:393 / docs\00:1561 又规定占位图必须是 512² —— 两条只在不同目录里才能同时成立：
     * {@code img\} 只放下载来的封面原图（{@link #SIZE}²），兜底图另起一目录。</p>
     */
    public static Path placeholderDir() {
        return dir().resolve("placeholder");
    }

    /** 索引里记的图名（{@code "i"} 字段）→ 落盘路径：占位图与封面原图分属两个目录。 */
    private static Path imagePath(String name) {
        return name != null && name.startsWith("placeholder-")
                ? placeholderDir().resolve(name)
                : imgDir().resolve(name);
    }

    public static Path stubDir() {
        return dir().resolve("stub");
    }

    private static Path indexPath() {
        return dir().resolve("cover-index.json");
    }

    private static Path refsPath() {
        return dir().resolve("cover-refs.json");
    }

    /**
     * 封面引用键的分隔符（U+0001）：{@code 专辑名 + SEP + 歌手}。
     *
     * <p>为什么必须带歌手（0.11.49）：宿主自己的 {@code Album} 行就是按 {@code (title, artist)}
     * 派生的（真机 2406 行 / 2094 个标题，147 个标题多行），而旧 refs 只用专辑名做键、先登记的那首
     * 胜出 ⇒「未知专辑」这类同名不同艺人的专辑全部挂同一张图（用户报障「有一些音乐封面绑定错误了」）。
     * 键带上歌手后，每张 {@code Album} 行都有机会拿到自己那一张。</p>
     */
    static final char REF_SEP = '\u0001';

    /** 封面引用键 = 专辑 + {@link #REF_SEP} + 歌手（歌手为空也带分隔符，绝不与旧「纯专辑名」键混用）。 */
    public static String refKey(String album, String artist) {
        String a = text(album, "未知专辑");
        String b = text(artist, "");
        return a + REF_SEP + b;
    }

    /** 引用键里的专辑名（写库 / 人读用的那个，不带歌手）。 */
    public static String albumOfRef(String refKey) {
        if (refKey == null) {
            return "";
        }
        int i = refKey.indexOf(REF_SEP);
        return i < 0 ? refKey : refKey.substring(0, i);
    }

    /** 引用键 → 人读形式（日志用；{@code 专辑 U+0001 歌手} → {@code 专辑 — 歌手}）。 */
    public static String display(String refKey) {
        if (refKey == null) {
            return "";
        }
        int i = refKey.indexOf(REF_SEP);
        if (i < 0) {
            return refKey;
        }
        String a = refKey.substring(0, i);
        String b = refKey.substring(i + 1);
        return b.isEmpty() ? a : a + " — " + b;
    }

    /**
     * 旧记录迁移（0.11.49，幂等）：把「键 = 纯专辑名」的旧 refs 记录搬成 {@link #refKey}
     * （用记录里的 {@code b} 歌手字段）。老数据的图与桩都在记录里，一条不丢；同名专辑的其它艺人
     * 之后会各自登记新键，不再共用第一张图。
     */
    private static void migrateRefs() {
        java.util.List<String[]> move = new ArrayList<>();
        for (Map.Entry<String, Object> e : REFS.entrySet()) {
            if (e.getKey().indexOf(REF_SEP) >= 0 || !(e.getValue() instanceof Map<?, ?> m)) {
                continue;
            }
            Map<String, Object> rec = cast(m);
            String album = text(str(rec.get("a")), e.getKey());
            move.add(new String[]{e.getKey(), refKey(album, str(rec.get("b")))});
        }
        int n = 0;
        for (String[] kv : move) {
            Object v = REFS.remove(kv[0]);
            if (v == null) {
                continue;
            }
            Object cur = REFS.get(kv[1]);
            if (!(cur instanceof Map<?, ?>)) {
                REFS.put(kv[1], v);
                n++;
            } else if (v instanceof Map<?, ?> vm && cur instanceof Map<?, ?> cm) {
                Map<String, Object> merged = cast(vm);      // 旧记录缺的字段补上；两边都有时保留新值
                merged.putAll(cast(cm));
                REFS.put(kv[1], merged);
                n++;
            }
        }
        if (n > 0) {
            dirty = true;
            PluginLog.i(TAG, "封面清单迁移：旧「专辑名」键 → 「专辑+歌手」键 共 " + n
                    + " 条（同名专辑今后各挂各的图）");
        }
    }

    public static Path stubPath(String album, String picUrl) {
        return stubDir().resolve(stubName(idxOf(album, picUrl)));
    }

    private static String stubName(String idx) {
        return CoverArt.STUB_PREFIX + idx + ".flac";
    }

    // ------------------------------------------------------------------ 启动

    /** 建目录 + 收编 0.10.x 留在 {@code cover\} 根下的旧桩 + 读索引。绝不抛。 */
    public static void init() {
        try {
            Files.createDirectories(imgDir());
            Files.createDirectories(placeholderDir());
            Files.createDirectories(stubDir());
            migrateLegacy();
            load();
        } catch (Throwable t) {
            PluginLog.w(TAG, "封面缓存初始化失败（降级为按需生成）：" + t);
        }
    }

    /** 0.10.x 的桩直接躺在 {@code cover\} 根，且文件名用「原始 picUrl」的 md5：搬进 {@code stub\}。 */
    private static void migrateLegacy() {
        try (var stream = Files.list(dir())) {
            int moved = 0;
            for (Path p : stream.toList()) {
                String n = p.getFileName().toString();
                if (!n.startsWith(CoverArt.STUB_PREFIX) || !n.endsWith(".flac") || !Files.isRegularFile(p)) {
                    continue;
                }
                Path dst = stubDir().resolve(n);
                if (Files.exists(dst)) {
                    continue;
                }
                try {
                    Files.move(p, dst, StandardCopyOption.REPLACE_EXISTING);
                    moved++;
                } catch (Throwable ignored) {
                    // 单个文件搬不动不影响其它
                }
            }
            if (moved > 0) {
                PluginLog.i(TAG, "封面目录整理：旧桩搬入 stub\\ 共 " + moved + " 个");
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "封面目录整理跳过：" + t);
        }
    }

    private static void load() {
        if (loaded) {
            return;
        }
        synchronized (REFS) {
            if (loaded) {
                return;
            }
            INDEX.putAll(Json.readObject(indexPath()));
            REFS.putAll(Json.readObject(refsPath()));
            migrateRefs();                       // 0.11.49：旧「专辑名」键搬成「专辑+歌手」键
            loaded = true;
        }
        // 0.11.7 A13 收口：早于下载池起跑，把「名字已是 sha256、像素却不是 SIZE²」的 0.11.6 遗留一次清干净
        // （一次性、原子守卫；见 sweepWrongSize 的 javadoc 与真机故障记录）。
        sweepWrongSize();
    }

    /** 落盘索引（节流：默认 5 秒一次；{@code force} 立即写）。 */
    private static void flush(boolean force) {
        if (!dirty) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastFlush < FLUSH_MS) {
            return;
        }
        lastFlush = now;
        dirty = false;
        Map<String, Object> idx;
        Map<String, Object> refs;
        synchronized (REFS) {
            idx = new LinkedHashMap<>(INDEX);
            refs = new LinkedHashMap<>(REFS);
        }
        Json.writeObject(indexPath(), idx);
        Json.writeObject(refsPath(), refs);
    }

    public static void flushNow() {
        dirty = true;
        flush(true);
    }

    // ------------------------------------------------------------------ 主入口

    /**
     * 取（必要时造）专辑封面的 {@code file:///} URI —— <b>封面层的唯一入口</b>。
     *
     * <p>顺序：已有桩 → 直接返回（不联网）；旧命名桩 → 改名复用；picUrl 为空 → 占位图；
     * 失败满 {@link #MAX_ATTEMPTS} 次 → 占位图。<b>退避期内也先返回占位图</b>（用户当场有图看，
     * 补上以后再由投递覆盖）。<b>0.11.51 起</b>：「有真图 URL 的占位桩」不算「已有可用桩」——
     * 仍会走下载路径重取（{@link #refetchableStub}），成功即原地换成真图。
     * 任何异常都只记日志并返回 null，<b>绝不抛</b>（调用方可能是播放路径）。</p>
     *
     * <p>口径（0.11.8，任务书第 1/2/3 条）：</p>
     * <ul>
     *   <li>唯一取图尺寸 {@link #SIZE}（{@code ?param=1200y1200}，见 {@link CoverArt#withSize});</li>
     *   <li>原图按<b>内容 sha256</b> 命名（{@code img\<sha256>.jpg}）⇒ 同图不同 URL 只落一份；</li>
     *   <li>失败分类 + 每图独立长退避（{@link #RETRY_BACKOFF_MS}），分类计数进 {@code stats()}。</li>
     * </ul>
     */
    public static String ensure(String album, String artist, String picUrl) {
        String name = text(album, "未知专辑");
        String who = text(artist, "未知歌手");
        String url = picUrl == null ? "" : picUrl.trim();
        String idx = idxOf(name, url);
        try {
            load();
            // 1) 这张（专辑+歌手）已经有可用桩：纯离线路径
            String known = refStub(refKey(name, who));
            if (known != null) {
                Path p = stubDir().resolve(known);
                if (usable(p) && !refetchableStub(known, url)) {
                    DEDUP_HITS.incrementAndGet();        // 同一个 sha256 没下第二遍（A9「去重省下 K 次」）
                    return CoverArt.fileUri(p);
                }
            }
            Path stub = stubDir().resolve(stubName(idx));
            if (usable(stub) && !refetchableStub(stub.getFileName().toString(), url)) {
                DEDUP_HITS.incrementAndGet();
                rememberRef(name, who, url, stub.getFileName().toString());
                return CoverArt.fileUri(stub);
            }
            Object lock = LOCKS.computeIfAbsent(idx, k -> new Object());
            synchronized (lock) {
                if (usable(stub) && !refetchableStub(stub.getFileName().toString(), url)) {
                    rememberRef(name, who, url, stub.getFileName().toString());
                    return CoverArt.fileUri(stub);
                }
                if (url.isEmpty()) {
                    return placeholder(name, who, url, idx);   // 无图源：占位即终态
                }
                // 失败长退避：退避期内先给占位图（不再让界面空着等网络）
                Long until = RETRY_AT.get(idx);
                if (until != null && until > System.currentTimeMillis()) {
                    return placeholder(name, who, url, idx);
                }
                if (fails(idx) >= MAX_ATTEMPTS) {
                    return placeholder(name, who, url, idx);
                }
                return download(name, who, url, idx, stub);
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "封面准备失败（跳过）：" + t);
            return null;
        }
    }

    /** 这个封面引用键（{@link #refKey}）已在索引里登记、且桩还在：{@code true} 表示不必进网络路径。 */
    public static boolean has(String refKey) {
        return readyUri(refKey) != null;
    }

    /**
     * 该专辑<b>已有的</b>桩 URI（纯离线：只查内存索引 + {@code Files.isRegularFile}，不联网、不下载）。
     *
     * <p>0.11.1 起同步轮的「随库写一次」走这里：曲库行先落地（用户一打开就能看到列表），
     * 缺图的专辑交给下载池并行补、补好一张写一张（见 {@link #startFill}）。</p>
     *
     * @return {@code file:///…/cover-stub-*.flac}；没有可用桩返回 {@code null}
     */
    public static String readyUri(String refKey) {
        load();
        String s = refStub(refKey);
        if (s == null) {
            return null;
        }
        Path p = stubDir().resolve(s);
        return usable(p) ? CoverArt.fileUri(p) : null;
    }

    // ------------------------------------------------------------------ 造桩

    private static String download(String album, String artist, String url, String idx, Path stub) {
        if (!spaceOk()) {
            return placeholder(album, artist, url, idx);
        }
        adoptLegacy(url);                    // 旧命名原图就地改名成内容寻址，不重下
        byte[] image;
        String mime;
        try {
            Fetch f = fetchRaw(album, artist, url, idx);
            image = f.bytes();
            mime = f.mime();
        } catch (Short e) {
            return fail(album, artist, url, idx, e);
        } catch (Throwable t) {
            return fail(album, artist, url, idx, Fail.NET, 0, t);
        }
        try {
            String imgName = store(image, mime);
            if (imgName == null) {
                return fail(album, artist, url, idx, Fail.DISK, 0, null);
            }
            writeStub(album, artist, url, idx, image, mime, imgName, stub);
            FETCHED.incrementAndGet();
            FETCHED_BYTES.addAndGet(image.length);
            logRound();             // A9 判据行（自节流：≥5 s 或 ≥25 张才真打）
            return CoverArt.fileUri(stub);
        } catch (Throwable t) {
            PluginLog.d(TAG, "封面落盘失败（跳过）：" + t);
            return fail(album, artist, url, idx, Fail.DISK, 0, t);
        }
    }

    /**
     * 取一张「已归一」的封面字节（{@link Fetch#bytes()} 恒为 {@link #SIZE}×{@link #SIZE}）。
     * 素材来源与 {@link #ensure} 完全相同 ⇒ 歌单封面走的也是这一条管线（成果 4）。
     *
     * <p>失败一律抛 {@link Short}（分类 + HTTP 码），由 {@link #fail} 记账。</p>
     */
    private static Fetch fetchRaw(String album, String artist, String url, String idx) throws Short {
        if (!spaceOk()) {
            throw new Short(Fail.NO_SPACE, 0, null);
        }
        // 换域候选（原 URL 优先）：403/URL 过期时换一组 CDN 域常常就通了（任务书第 6 条）。
        List<String> cands = CoverArt.candidates(url);
        if (cands.isEmpty()) {
            throw new Short(Fail.NO_SRC, 0, null);        // 专辑根本没图
        }
        Short last = null;
        for (int i = 0; i < cands.size(); i++) {
            String cand = cands.get(i);
            try {
                Fetch f = fetchOne(cand);
                if (!f.note().isEmpty()) {
                    // 过程标签（非失败）：记账 + 节流日志都在这里，所有调用方（含歌单封面）口径一致
                    noteNorm(f.note(), album + "（" + CoverArt.brief(CoverArt.withSize(cand)) + "）");
                }
                return f;
            } catch (Short e) {
                last = e;
                boolean more = i + 1 < cands.size() && e.kind().swap;
                if (!more) {
                    throw e;
                }
                if (i > 0) {
                    PluginLog.d(TAG, "封面换域重试（" + e.label() + " → " + CoverArt.brief(cand) + "）");
                }
            }
        }
        throw last == null ? new Short(Fail.NET, 0, null) : last;
    }

    /** 取一个具体 URL 并归一（不做换域，换域在 {@link #fetchRaw}）。 */
    private static Fetch fetchOne(String url) throws Short {
        CoverArt.Fetch r = CoverArt.fetchSized(url, SIZE);
        if (!r.ok()) {
            if (r.code() <= 0) {
                throw new Short(Fail.NET, 0, r.error());
            }
            if (RiskControl.isRiskCode(r.code())) {
                throw new Short(Fail.RISK, r.code(), null);
            }
            if (r.code() == 404 || r.code() == 410) {
                throw new Short(Fail.EXPIRED, r.code(), null);
            }
            throw new Short(Fail.HTTP, r.code(), null);
        }
        byte[] raw = r.bytes();
        if (raw.length > MAX_STORE_BYTES) {
            throw new Short(Fail.TOO_BIG, raw.length, null);
        }
        String mime = CoverArt.sniff(raw);
        if (mime == null) {
            var fixed = toJpeg(raw);              // 认不出的格式（WebP 等）：转码成 JPEG，算过程不计失败
            if (fixed == null) {
                throw new Short(Fail.FORMAT, 0, null);
            }
            return new Fetch("image/jpeg", fixed, "格式转码");
        }
        Norm norm = normalize(raw);
        if (norm == null) {
            throw new Short(Fail.NORMALIZE, 0, null);
        }
        if (norm.how().isEmpty()) {
            return new Fetch(mime, raw, "");      // 真实像素已合规：原样保留（不重编码、不改 MIME）
        }
        return new Fetch("image/jpeg", norm.bytes(), norm.how());
    }

    /** 归一结果：{@link #bytes()} 即落盘字节，{@link #how()} 是过程标签（{@code ""} = 原样未动）。 */
    private record Norm(byte[] bytes, String how) {
    }

    /**
     * 只读文件头拿像素尺寸：PNG 读 IHDR（偏移 16/20），JPEG 逐段找 SOFn（C0–CF，排除 C4/C8/CC）。
     *
     * <p><b>只服务一条快路径</b>——「已经在头里写着 {@link #SIZE}²」时可以跳过整图解码；
     * 拿不准的形态（截断、非图、标记流异常、进扫描数据仍没见 SOF）一律返回 {@code null}，
     * 让调用方退回 {@link ImageIO} 全流程。缩放决策<b>不用</b>头里的数字，只用解码后的像素。</p>
     */
    private static int[] dims(byte[] b) {
        if (b == null || b.length < 24) {
            return null;
        }
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[12] == 'I' && b[13] == 'H' && b[14] == 'D' && b[15] == 'R') {
            return new int[]{be32(b, 16), be32(b, 20)};
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
            int i = 2;
            while (i + 9 < b.length) {
                if ((b[i] & 0xFF) != 0xFF) {
                    i++;
                    continue;
                }
                int m = b[i + 1] & 0xFF;
                if (m == 0xFF || m == 0x01 || (m >= 0xD0 && m <= 0xD7)) {
                    i += 2;                       // 填充 / TEM / RSTn：无长度字段的段
                    continue;
                }
                if (m == 0xD9 || m == 0xDA) {
                    return null;                  // EOI 或进扫描数据仍没见 SOF ⇒ 放弃
                }
                int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
                if (len < 2) {
                    return null;
                }
                if (m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                    int h = ((b[i + 5] & 0xFF) << 8) | (b[i + 6] & 0xFF);
                    int w = ((b[i + 7] & 0xFF) << 8) | (b[i + 8] & 0xFF);
                    return new int[]{w, h};
                }
                i += 2 + len;
            }
        }
        return null;
    }

    /** 大端读 4 字节无符号（PNG IHDR 的宽/高）。 */
    private static int be32(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16)
                | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    /** {@link #dims(byte[])} 读不出时的兜底：整图解码要真实像素；解不开返回 {@code null}（=拿不准，不判不合规）。 */
    private static int[] imageSize(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            return img == null ? null : new int[]{img.getWidth(), img.getHeight()};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 读一个已落盘文件的像素尺寸：先读头（{@link #dims(byte[])}，64 KB 前缀够所有 JPEG/PNG 段），
     * 读不出才整图解码。返回 {@code null} = 拿不准（调用方<b>不得</b>据此删文件）。
     */
    private static int[] fileSize(Path p) {
        byte[] head;
        try (java.io.InputStream in = Files.newInputStream(p)) {
            head = in.readNBytes(65536);
        } catch (Throwable t) {
            return null;
        }
        int[] d = dims(head);
        if (d != null) {
            return d;
        }
        try {
            BufferedImage img = ImageIO.read(p.toFile());
            return img == null ? null : new int[]{img.getWidth(), img.getHeight()};
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@link #sweepWrongSize()} 的守卫：每次进程启动只跑一次。 */
    private static final java.util.concurrent.atomic.AtomicBoolean SIZE_SWEPT =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 归零「命名已内容寻址、像素却不是 {@link #SIZE}²」的遗留图（0.11.7 A13 收口，2026-10-01 真机）。
     *
     * <p>真机故障：{@code img\} 里 2091 张图名字已是 sha256（被旧版 {@link #adoptLegacy(String)} 收编过），
     * 像素却仍是 0.11.6 时代的 <b>500×500</b> —— 收编只改名、不做尺寸归一，而 {@link #readyUri(String)}
     * 只看桩文件在不在 ⇒ 索引记「已就绪」⇒ <b>永不重取，A13（全为 1200²）无法自愈</b>。</p>
     *
     * <p>每次进程启动只跑一次（{@link #load()} 末尾同步调用，早于下载池起跑）：不合规的图删除、并从
     * {@link #INDEX} 摘掉指向它的记录，同时作废桩（桩内嵌的仍是旧像素）⇒ 下载池按 {@link #SIZE}² 重取、
     * {@link #writeStub} 重写桩。2092 张 ≈ 1.2 GB，32 路并发实测 ≈ 60 s。</p>
     */
    private static void sweepWrongSize() {
        if (!SIZE_SWEPT.compareAndSet(false, true)) {
            return;
        }
        try {
            Path dir = imgDir();
            if (!Files.isDirectory(dir)) {
                return;
            }
            List<String> bad = new ArrayList<>();
            int ok = 0;
            int unsure = 0;
            try (java.util.stream.Stream<Path> s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (!Files.isRegularFile(p)) {
                        continue;
                    }
                    int[] d = fileSize(p);
                    if (d == null) {
                        unsure++;
                        continue;                 // 判不了就不动它（宁留不删）
                    }
                    if (d[0] == SIZE && d[1] == SIZE) {
                        ok++;
                        continue;
                    }
                    bad.add(p.getFileName().toString());
                    Files.deleteIfExists(p);
                }
            }
            if (bad.isEmpty()) {
                PluginLog.i(TAG, "封面尺寸巡检：img " + ok + " 张全部为 " + SIZE + "²"
                        + (unsure > 0 ? "（另有 " + unsure + " 张读不出尺寸，保留）" : "") + "，无需重取");
                return;
            }
            int dropped = 0;
            List<String> idxs = new ArrayList<>();
            synchronized (REFS) {
                java.util.Iterator<Map.Entry<String, Object>> it = INDEX.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Object> e = it.next();
                    Map<String, Object> rec = castOrNull(e.getValue());
                    if (rec == null) {
                        continue;
                    }
                    Object img = rec.get("i");
                    if (img != null && bad.contains(String.valueOf(img))) {
                        idxs.add(e.getKey());
                        it.remove();
                        dropped++;
                    }
                }
                if (dropped > 0) {
                    dirty = true;
                }
            }
            int[] drop = dropStubsFor(idxs);
            PluginLog.i(TAG, "封面尺寸巡检：img 里 " + bad.size() + " 张不是 " + SIZE + "²（0.11.6 遗留）已清除，"
                    + "摘除索引 " + dropped + " 条 / 作废引用 " + drop[0] + " 条 / 删除桩 " + drop[1] + " 张"
                    + " ⇒ 下载池将按 " + SIZE + "² 重取");
            flush(true);
        } catch (Throwable t) {
            PluginLog.w(TAG, "封面尺寸巡检失败（下次启动重试）：" + t);
        }
    }

    /**
     * 只作废<b>指定索引</b>（{@link #INDEX} 的键）的 flac 桩并摘掉挂它们的 {@link #REFS} 记录。
     *
     * <p>{@link #readyUri(String)} 的判据是「桩文件在不在」，而桩内嵌的是<b>当次下载的图字节</b> ——
     * 图要被重取时桩必须跟着作废，否则宿主仍拿着旧像素的那张。</p>
     *
     * <p><b>0.11.7 修正</b>：最初写成「只要发现不合规图就作废全部桩」，真机上一张残留的占位图
     * 会把 2090 张好桩一起打掉、逼下载池再铺一遍全量（2026-10-01 实测）。现在按目标精确作废。</p>
     *
     * <p><b>0.11.53（F4）</b>：引用表自 0.11.49 起是「专辑 + 歌手」复合键，旧实现按<b>纯专辑名</b>
     * 摘 {@code REFS} 记录在复合键下永远打不中 ⇒ 坏桩清不掉、自愈失效（2026-10-07 审查）。
     * 现在改为：遍历 {@link #REFS}，对每条记录用 {@link #idxFromStub(String)} 反查它挂的桩，
     * 索引命中才摘记录 + 删桩 —— 精确到单张桩，同名专辑其它艺人的桩不受牵连；最后再按索引
     * 兜底删一遍桩文件（引用记录缺失 / 无 {@code s} 字段的孤儿桩，删过的不重复计数）。</p>
     *
     * @return {@code int[]{作废引用条数, 删除桩张数}}（前者应与 {@link #sweepWrongSize} 摘除的索引条数一致）
     */
    private static int[] dropStubsFor(java.util.Collection<String> idxs) {
        if (idxs == null || idxs.isEmpty()) {
            return new int[]{0, 0};
        }
        int refs = 0;
        int files = 0;
        try {
            synchronized (REFS) {
                java.util.Iterator<Map.Entry<String, Object>> it = REFS.entrySet().iterator();
                while (it.hasNext()) {
                    Object v = it.next().getValue();
                    String s = null;
                    if (v instanceof Map<?, ?> m) {
                        Object o = m.get("s");
                        s = o == null ? null : String.valueOf(o);
                    }
                    String idx = s == null ? null : idxFromStub(s);
                    if (idx == null || !idxs.contains(idx)) {
                        continue;
                    }
                    it.remove();
                    refs++;
                    if (deleteStubFile(s)) {
                        files++;
                    }
                }
                for (String idx : idxs) {
                    if (deleteStubFile(stubName(idx))) {
                        files++;
                    }
                }
                if (refs > 0) {
                    dirty = true;
                }
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "桩作废跳过：" + t);
        }
        return new int[]{refs, files};
    }

    /** 删一张桩文件（返回是否真的删掉了）；失败只记日志、绝不抛。 */
    private static boolean deleteStubFile(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        try {
            return Files.deleteIfExists(stubDir().resolve(name));
        } catch (Throwable t) {
            PluginLog.d(TAG, "作废桩失败（忽略）：" + name + "：" + t);
            return false;
        }
    }

    /**
     * 解码 → 判定<b>解码后的真实像素</b> → 已合规则原样返回；否则缩放到 {@link #SIZE}² 的居中方块
     * （白底）→ 统一编码 JPEG；解不开/像素超限返回 null。
     *
     * <p>判定只用 {@link BufferedImage#getWidth()}/{@link BufferedImage#getHeight()}（解码后的像素），
     * <b>不</b>看请求串里的 {@code ?param=}、也不看任何默认常量：请求写了 1200 而图床只给 500 时必须走
     * 缩放分支；图床直接给 1200 时必须原样落盘。过程标签只有两个，且都<b>不是失败</b>：
     * {@code 缩放补齐} / {@code 格式转码}（W6 离线实验室曾因二者混入失败桶而出现「失败 1 却两个分类」）。</p>
     *
     * <p>0.11.8 加了一条<b>不解码的快路径</b>：{@link #dims(byte[])} 只读文件头，头里就是
     * {@link #SIZE}² 的图直接原样返回（全量 2000 张里绝大多数走这条），省掉整图 decode；
     * 头里不是（或读不出）才进 {@link ImageIO} 全流程 —— 缩放的判定永远以<b>解码后的像素</b>
     * 为准，头只用于「确认已经合规」这一步。</p>
     */
    private static Norm normalize(byte[] raw) {
        int[] d = dims(raw);
        if (d != null && d[0] == SIZE && d[1] == SIZE) {
            return new Norm(raw, "");               // 头即 SIZE²：一字不改，不整图解码
        }
        try {
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(raw));
            if (img == null) {
                byte[] fixed = toJpeg(raw);        // 头像 JPEG/PNG 但内容解不开：转码兜底
                return fixed == null ? null : new Norm(fixed, "格式转码");
            }
            long px = (long) img.getWidth() * img.getHeight();
            if (px <= 0L || px > MAX_PIXELS) {
                return null;
            }
            if (img.getWidth() == SIZE && img.getHeight() == SIZE) {
                return new Norm(raw, "");           // 解码后真实像素已是 SIZE²：一字不改
            }
            BufferedImage dst = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, SIZE, SIZE);
                int w = img.getWidth();
                int h = img.getHeight();
                double k = Math.min(SIZE / (double) w, SIZE / (double) h);
                int tw = Math.max(1, (int) Math.round(w * k));
                int th = Math.max(1, (int) Math.round(h * k));
                g.drawImage(img, (SIZE - tw) / 2, (SIZE - th) / 2, tw, th, null);
            } finally {
                g.dispose();
            }
            var param = new javax.imageio.plugins.jpeg.JPEGImageWriteParam(java.util.Locale.ROOT);
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.92f);
            var writer = ImageIO.getImageWritersByFormatName("jpg").next();
            var bos = new ByteArrayOutputStream(Math.max(64 * 1024, raw.length));
            try (var ios = ImageIO.createImageOutputStream(bos)) {
                writer.setOutput(ios);
                writer.write(null, new javax.imageio.IIOImage(dst, null, null), param);
            } finally {
                writer.dispose();
            }
            return new Norm(bos.toByteArray(), "缩放补齐");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 认不出的格式兜底：ImageIO 能解就转成 JPEG（尺寸同时归一到 {@link #SIZE}²），否则 null。 */
    private static byte[] toJpeg(byte[] raw) {
        try {
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(raw));
            if (img == null) {
                return null;
            }
            long px = (long) img.getWidth() * img.getHeight();
            if (px <= 0L || px > MAX_PIXELS) {
                return null;
            }
            BufferedImage dst = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, SIZE, SIZE);
                int w = img.getWidth();
                int h = img.getHeight();
                double k = Math.min(SIZE / (double) w, SIZE / (double) h);
                int tw = Math.max(1, (int) Math.round(w * k));
                int th = Math.max(1, (int) Math.round(h * k));
                g.drawImage(img, (SIZE - tw) / 2, (SIZE - th) / 2, tw, th, null);
            } finally {
                g.dispose();
            }
            var param = new javax.imageio.plugins.jpeg.JPEGImageWriteParam(java.util.Locale.ROOT);
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.92f);
            var writer = ImageIO.getImageWritersByFormatName("jpg").next();
            var bos = new ByteArrayOutputStream(Math.max(64 * 1024, raw.length));
            try (var ios = ImageIO.createImageOutputStream(bos)) {
                writer.setOutput(ios);
                writer.write(null, new javax.imageio.IIOImage(dst, null, null), param);
            } finally {
                writer.dispose();
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 内容寻址落盘（任务书第 3 条 / R9）：文件名 = {@code sha256(图片字节)}。
     *
     * <p>同一个专辑的两首歌、或两个 URL 指向同一张图 ⇒ <b>磁盘上只有一份</b>；
     * {@code cover\img\} 的文件数因此 ≤ 专辑数（A9）。已存在就只 +1 去重计数，不重写。</p>
     */
    private static String store(byte[] image, String mime) {
        try {
            String sha = Hashes.sha256Hex(image);
            if (sha == null || sha.length() < 8) {
                PluginLog.w(TAG, "封面原图落盘失败：sha256 算不出来（字节 " + image.length + " B）");
                return null;
            }
            String name = sha + ("image/png".equals(mime) ? ".png" : ".jpg");
            Path dst = imgDir().resolve(name);
            Path tmp = dst.resolveSibling(name + ".tmp");
            // 目标目录可能还不存在（全新安装、刚跑完 clearAll、离线沙箱）⇒ 写之前自己建。
            // 缺这一句时 NoSuchFileException 会被归成「落盘失败」并拖成假失败（W6 cover-ok 场景的根因）。
            Files.createDirectories(dst.getParent());
            Files.write(tmp, image);
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
            return name;
        } catch (Throwable t) {
            PluginLog.w(TAG, "封面原图落盘失败（目录 " + imgDir() + "）：" + t);
            return null;
        }
    }

    /** {@link #ensure} 的字节版：同一入口、同一去重、同一尺寸，给「歌单封面」这类只要字节的调用方。 */
    public static byte[] fetchBytes(String album, String artist, String picUrl) {
        try {
            load();
            String url = picUrl == null ? "" : picUrl.trim();
            String idx = idxOf(text(album, "未知专辑"), url);
            synchronized (LOCKS.computeIfAbsent(idx, k -> new Object())) {
                return fetchRaw(album, artist, url, idx).bytes();
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "取封面字节失败（" + CoverArt.brief(picUrl) + "）：" + t);
            return null;
        }
    }

    /**
     * 用专辑名 hash 生成 {@link #SIZE}² 占位图（纯色 + 首字母）并做成桩；无图专辑的终态。
     *
     * <p>0.11.51 起：<b>有真图 URL</b> 的占位只是应急显示，不是终态 —— 补齐轮/下个会话会重取，
     * 成功即由 {@link #writeStub} 原地替换（见 {@link #refetchableStub}）。</p>
     */
    private static String placeholder(String album, String artist, String url, String idx) {
        Path stub = stubDir().resolve(stubName(idx));
        if (usable(stub)) {
            rememberRef(album, artist, url, stub.getFileName().toString());
            return CoverArt.fileUri(stub);
        }
        byte[] png = placeholderPng(album);
        if (png == null) {
            return null;
        }
        try {
            String imgName = "placeholder-" + Hashes.sha256Hex(png) + ".png";
            Path img = placeholderDir().resolve(imgName);
            Files.createDirectories(img.getParent());
            if (!Files.isRegularFile(img)) {
                Path tmp = img.resolveSibling(imgName + ".tmp");
                Files.write(tmp, png);
                Files.move(tmp, img, StandardCopyOption.REPLACE_EXISTING);
            }
            writeStub(album, artist, url, idx, png, "image/png", imgName, stub);
            return CoverArt.fileUri(stub);
        } catch (Throwable t) {
            PluginLog.d(TAG, "占位封面落盘失败（跳过）：" + t);
            return null;
        }
    }

    /**
     * 写桩 + 登记引用。0.11.53 起引用记录必须归属真实 (专辑, 歌手)：{@link #rememberRef} 走
     * {@link #refKey(String, String)} 复合键，不再按空歌手记（旧记录在复合键世界里是「最后写入者」
     * 歧义，真机 2026-10-07 扫描到 100 条）。
     */
    private static void writeStub(String album, String artist, String url, String idx, byte[] image, String mime,
                                  String imgName, Path stub) throws Exception {
        byte[] flac = CoverArt.flac(image, mime);
        Files.createDirectories(stub.getParent());
        Path tmp = stub.resolveSibling(stub.getFileName() + ".tmp");
        Files.write(tmp, flac);
        Files.move(tmp, stub, StandardCopyOption.REPLACE_EXISTING);
        synchronized (REFS) {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("a", album);
            rec.put("u", url);
            rec.put("i", imgName);
            rec.put("n", 0L);
            rec.put("p", url.isEmpty());
            rec.put("t", System.currentTimeMillis());
            INDEX.put(idx, rec);
            dirty = true;
        }
        rememberRef(album, artist, url, stub.getFileName().toString());
        PluginLog.i(TAG, "封面桩 " + stub.getFileName() + "（" + flac.length + " B，图 "
                + image.length + " B " + mime + "）");
    }

    /**
     * 旧命名（{@code img\<md5(去参数 picUrl)>.jpg|png}）的原图<b>就地改成内容寻址名</b>
     * （{@code img\<sha256(字节)>.jpg|png}）—— 不重新下载，直接收编已有素材。
     *
     * <p>0.11.8 前 {@code img\} 里 2091 张图全是旧命名；本方法在首次访问该专辑时把它们逐个
     * 改名成 sha256（真机一次同步即可全部收编，避免为了换命名把 2000 张全重下一遍）。</p>
     */
    private static void adoptLegacy(String url) {
        if (url.isEmpty()) {
            return;
        }
        try {
            String base = Hashes.md5Hex(stripQuery(url));
            for (String ext : new String[]{".jpg", ".png"}) {
                Path from = imgDir().resolve(base + ext);
                if (!Files.isRegularFile(from)) {
                    continue;
                }
                byte[] bytes = Files.readAllBytes(from);
                int[] d = dims(bytes);
                if (d == null) {
                    d = imageSize(bytes);
                }
                if (d != null && (d[0] != SIZE || d[1] != SIZE)) {
                    // 收编等于把不合尺寸<b>永久固化</b>（索引记「已就绪」、readyUri 只看文件在不在）：
                    // 真机 2026-10-01 实测 img\ 2091 张名字全是 sha256 却仍是 0.11.6 的 500² ⇒ A13 FAIL 且不自愈。
                    // 因此这里弃用它，交给正常 download() 路径按 withSize(url) 重取 SIZE²。
                    Files.deleteIfExists(from);
                    PluginLog.i(TAG, "封面旧图尺寸不合（" + d[0] + "×" + d[1] + " ≠ " + SIZE + "²）已弃用，"
                            + "将按 " + SIZE + "² 重取：" + from.getFileName());
                    continue;
                }
                String sha = Hashes.sha256Hex(bytes);
                if (sha == null) {
                    continue;
                }
                Path to = imgDir().resolve(sha + ext);
                if (Files.exists(to)) {
                    Files.deleteIfExists(from);
                    continue;
                }
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                PluginLog.d(TAG, "封面原图改名（内容寻址）：" + from.getFileName() + " → " + to.getFileName());
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "封面原图改名跳过：" + t);
        }
    }

    /** 去掉 URL 的查询串（{@code ?param=…} 等）。 */
    private static String stripQuery(String url) {
        int q = url.indexOf('?');
        return q > 0 ? url.substring(0, q) : url;
    }

    /**
     * 失败分类标签 —— 给兄弟组件（{@code svc.PlaylistCover}）复用<b>同一套词</b>。
     *
     * <p>禁止各处各写一份分类词形：报告与日志判据都按这套标签 grep（任务书 §7 A8「失败数为 0 或有明确分类日志」）。</p>
     *
     * @return 分类标签；{@code null} = 这次不算失败（2xx 且是图）
     */
    static String kindOf(int code, byte[] bytes) {
        if (code <= 0) {
            return Fail.NET.label;
        }
        if (RiskControl.isRiskCode(code)) {
            return Fail.RISK.label;
        }
        if (code == 404 || code == 410) {
            return Fail.EXPIRED.label;
        }
        if (code / 100 != 2) {
            return Fail.HTTP.label;
        }
        if (bytes == null || bytes.length == 0) {
            return Fail.FORMAT.label;
        }
        return CoverArt.sniff(bytes) == null ? Fail.FORMAT.label : null;
    }

    /** 分类标签 → 该等多久（毫秒）。弟兄组件按同一套退避梯度长退避，不许自己写常量。 */
    static long backoffMs(String kindLabel, int attempts) {
        for (Fail f : Fail.values()) {
            if (f.label.equals(kindLabel)) {
                return retryDelay(f.risk, attempts);
            }
        }
        return retryDelay(false, attempts);
    }

    private static byte[] placeholderPng(String album) {
        try {
            int size = PLACEHOLDER_SIZE;
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                float hue = Math.floorMod(album.hashCode(), 360) / 360.0f;
                g.setColor(Color.getHSBColor(hue, 0.42f, 0.52f));
                g.fillRect(0, 0, size, size);
                String ch = initial(album);
                g.setColor(new Color(255, 255, 255, 232));
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size / 2));
                FontMetrics fm = g.getFontMetrics();
                int y = (size - fm.getHeight()) / 2 + fm.getAscent();
                g.drawString(ch, (size - fm.stringWidth(ch)) / 2, y);
            } finally {
                g.dispose();
            }
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024)) {
                if (!ImageIO.write(img, "png", bos)) {
                    return null;
                }
                return bos.toByteArray();
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "占位图生成失败（跳过）：" + t);
            return null;
        }
    }

    private static String initial(String album) {
        for (int i = 0; i < album.length(); i++) {
            char c = album.charAt(i);
            if (!Character.isWhitespace(c) && c != '[' && c != '(' && c != '（' && c != '【') {
                return String.valueOf(c).toUpperCase(java.util.Locale.ROOT);
            }
        }
        return "?";
    }

    // ------------------------------------------------------------------ 补齐线程

    /**
     * 登记「专辑 → 歌手 / 图 URL」工作清单（同步线程调用，只写内存 + 索引）。
     * 之后 {@link #startFill} 的线程会一张张补齐，不够就无限重试（退避 1/2/5/30 秒）。
     */
    public static void plan(List<NativeLibrary.Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        load();
        int n = 0;
        for (NativeLibrary.Entry e : entries) {
            for (com.example.netease.net.Dto.Song s : e.songs()) {
                String album = text(s.album(), "未知专辑");
                String artist = text(s.artists(), "未知歌手");
                if (s.id() > 0L) {
                    SONGS.put(s.id(), new String[]{album, artist});   // 播放即时补图靠这张小表找回专辑
                }
                if (refStub(refKey(album, artist)) != null) {
                    continue;                        // 这张（专辑+歌手）已登记：别人的同名专辑不影响它
                }
                rememberRef(album, artist, s.picUrl() == null ? "" : s.picUrl().trim(), null);
                n++;
            }
        }
        flush(false);
        planSeq++;                      // 叫醒可能正睡在退避里的补齐线程（见 #planSeq）
        PluginLog.i(TAG, "封面清单：登记 " + n + " 条专辑（累计 " + REFS.size() + " 个专辑）");
    }

    /**
     * 播放即时补图（0.11.0）：这一首正在播，它的专辑却还没图 —— 当场造一张。
     *
     * <p>纯离线：专辑/歌手/图 URL 全部来自 {@link #plan} 登记过的清单（{@link #SONGS} +
     * {@link #REFS}），所以这里<b>只可能</b>发一次图片下载请求，不会去查歌曲详情。</p>
     *
     * <p>线程：由 {@code svc.StreamPrefetcher} 在 {@code netease-prefetch-1} 线程上调用
     * （网络与落盘都合法，绝不是宿主回调线程）。已铺过图的专辑立刻返回 {@code null}，
     * 不写库、不重复上报；全程 catch，绝不抛。</p>
     *
     * @param songId 正在播放的曲目 id
     * @return 新造好的封面（调用方负责投递给宿主）；没造出/早已有图 → {@code null}
     */
    public static CoverArt.Cover ensurePlaying(long songId) {
        if (songId <= 0L) {
            return null;
        }
        try {
            String[] pair = SONGS.get(songId);
            if (pair == null || pair.length < 2) {
                return null;
            }
            String album = pair[0];
            String artist = pair[1];
            String refK = refKey(album, artist);
            String[] ref = refOf(refK);
            String url = ref == null ? "" : ref[1];
            if (has(refK) && !refetchableStub(refStub(refK), url)) {
                return null;                     // 真图已就绪（有真图 URL 的占位桩除外：当场重取，0.11.51）
            }
            if (url == null || url.isEmpty()) {
                return null;                     // 没有图源：占位图与重试交给补齐线程
            }
            String uri = ensure(album, artist, url);
            if (uri == null) {
                return null;
            }
            PluginLog.i(TAG, "封面即时补：" + album + "（" + CoverArt.brief(url) + "）");
            return new CoverArt.Cover(album, artist, uri);
        } catch (Throwable t) {
            PluginLog.d(TAG, "封面即时补失败（忽略）：" + t);
            return null;
        }
    }

    /** 单张投递（播放路径用）：不占批量节奏，直接交给 {@link #startFill} 注册的 Sink。 */
    public static void deliverNow(CoverArt.Cover cover) {
        Sink s = sink;
        if (cover == null || s == null) {
            return;
        }
        deliver(s, new ArrayList<>(List.of(cover)));
    }

    // ------------------------------------------------------------------ 给「行封面」用（0.11.2）

    /** 曲目 id → 专辑名（{@link #plan} 登记过才有）；查不到返回 {@code null}。 */
    public static String albumOf(long songId) {
        String[] pair = SONGS.get(songId);
        return pair == null ? null : pair[0];
    }

    /**
     * 曲目 id → 封面引用键（{@link #refKey}，= 专辑 + 歌手）：播放条 / 行封面取图的键。
     * 查不到返回 {@code null}（调用方再用宿主库那一行兜底）。
     */
    public static String refKeyOf(long songId) {
        String[] pair = SONGS.get(songId);
        return pair == null || pair.length < 2 ? null : refKey(pair[0], pair[1]);
    }

    /** 该专辑登记过的封面图 URL（没有则 {@code null}）；给「缺图时联网补一张」用。 */
    public static String picUrlOf(String refKey) {
        if (refKey == null || refKey.isBlank()) {
            return null;
        }
        String[] ref = refOf(refKey);
        String url = ref == null ? null : ref[1];
        return url == null || url.isBlank() ? null : url;
    }

    /** 该专辑登记过的歌手名（没有则 {@code ""}）。 */
    public static String artistOf(String refKey) {
        if (refKey == null || refKey.isBlank()) {
            return "";
        }
        String[] ref = refOf(refKey);
        String artist = ref == null ? null : ref[0];
        return artist == null ? "" : artist;
    }

    /**
     * 反查：封面图 URL → 专辑名（{@link #plan} 登记过的才有）。
     *
     * <p>给面板缩略图（{@code ui.CoverCache}）用：面板手里只有 URL，必须沿同一条管线取图。
     * 比较时两边都去掉查询串（{@code ?param=…}）—— 登记时与请求时的尺寸参数可能不同。</p>
     */
    public static String albumOfUrl(String picUrl) {
        if (picUrl == null || picUrl.isBlank()) {
            return null;
        }
        String want = stripQuery(picUrl.trim());
        // 返回的是 refs 的键（0.11.49 起 = 专辑+歌手）；直接喂 fetchImage 即可。
        synchronized (REFS) {
            for (Map.Entry<String, Object> e : REFS.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> m)) {
                    continue;
                }
                Object u = m.get("u");
                String url = u == null ? "" : String.valueOf(u);
                if (!url.isEmpty() && stripQuery(url).equals(want)) {
                    return e.getKey();
                }
            }
        }
        return null;
    }

    /**
     * <b>按封面 URL 取原图字节</b> —— 面板缩略图 / 歌单卡片的唯一入口。
     *
     * <p>口径与 {@link #fetchImage(String)} 一致：命中登记专辑就走 {@link #ensureAlbum}
     * （单尺寸 {@code ?param=1200y1200} + 内容 sha256 去重 + 失败分类退避）；没登记过的 URL
     * （例如歌单封面）直接走 {@link CoverArt#download} —— 那是全插件唯一的取图出口。
     * <b>调用方（{@code ui} 包）不得自己开 HTTP、不得自己写封面文件。</b></p>
     */
    public static byte[] imageForUrl(String picUrl) {
        if (picUrl == null || picUrl.isBlank()) {
            return null;
        }
        String album = albumOfUrl(picUrl);
        if (album != null) {
            byte[] local = fetchImage(album);     // album 实为 refs 键（专辑+歌手）
            if (local != null) {
                return local;
            }
        }
        try {
            byte[] raw = CoverArt.download(picUrl);
            return raw == null || CoverArt.sniff(raw) == null ? null : raw;
        } catch (Throwable t) {
            PluginLog.d(TAG, "按 URL 取封面失败（" + CoverArt.brief(picUrl) + "）：" + t);
            return null;
        }
    }

    /**
     * <b>只有专辑名时的唯一入口</b>（行内 / 播放条 / 队列前瞻都用它）：歌手与图 URL 从索引里取。
     *
     * <p>与 {@link #ensure} 完全同一条管线（单尺寸 1200 + 内容 sha256 去重 + 失败分类退避），
     * 区别只是调用方不必自己把 {@code artist}/{@code picUrl} 带进来。</p>
     *
     * @return 桩 URI；专辑没登记过图源返回 {@code null}（占位图与重试交给补齐线程）
     */
    public static String ensureAlbum(String refKey) {
        if (refKey == null || refKey.isBlank()) {
            return null;
        }
        String[] ref = refOf(refKey);
        String artist = ref == null ? "" : ref[0];
        String url = ref == null ? "" : ref[1];
        if (url == null || url.isBlank()) {
            return null;
        }
        return ensure(albumOfRef(refKey), artist, url);
    }

    /**
     * 取（必要时联网下载）该专辑的封面原图字节 —— <b>只下图片</b>，不下音乐。

     * <p>0.11.8 起不再是自己拼 URL 下 500 尺寸：先走 {@link #originalImage}（纯离线），
     * 缺图时经 {@link #ensureAlbum}（唯一入口：1200 单尺寸 + sha256 去重 + 失败分类记账）补一张，
     * 再取一次磁盘字节。给 {@code svc.CoverPrimer} / {@code svc.RowCover} 用。</p>
     */
    public static byte[] fetchImage(String refKey) {
        byte[] local = originalImage(refKey);
        if (local != null) {
            return local;
        }
        try {
            if (ensureAlbum(refKey) == null) {
                return null;
            }
            return originalImage(refKey);
        } catch (Throwable t) {
            PluginLog.d(TAG, "取封面原图失败（忽略）：" + t);
            return null;
        }
    }

    /**
     * 该专辑的<b>封面原图字节</b>（{@code cover\img\<md5>.jpg|png}）：给「行封面」注入用
     * （{@code svc.RowCover} 把图写进本地音频文件的内嵌标签）。
     *
     * <p>纯离线：只按索引查文件名再读盘，不联网。专辑没登记 / 原图不在 → {@code null}。</p>
     */
    public static byte[] originalImage(String refKey) {
        if (refKey == null || refKey.isBlank()) {
            return null;
        }
        try {
            load();
            String stub = refStub(refKey);
            if (stub == null) {
                return null;
            }
            String idx = idxFromStub(stub);
            if (idx == null) {
                return null;
            }
            Map<String, Object> m = castOrNull(INDEX.get(idx));
            String name = m == null ? "" : str(m.get("i"));
            if (name.isEmpty()) {
                return null;
            }
            Path img = imagePath(name);
            return Files.isRegularFile(img) ? Files.readAllBytes(img) : null;
        } catch (Throwable t) {
            PluginLog.d(TAG, "取封面原图失败（忽略）：" + t);
            return null;
        }
    }

    /** {@code cover-stub-<idx>.flac} → {@code <idx>}；认不出返回 null。 */
    private static String idxFromStub(String stub) {
        String s = stub;
        int dot = s.lastIndexOf('.');
        if (dot > 0) {
            s = s.substring(0, dot);
        }
        if (!s.startsWith(CoverArt.STUB_PREFIX)) {
            return null;
        }
        String idx = s.substring(CoverArt.STUB_PREFIX.length());
        return idx.isEmpty() ? null : idx;
    }

    private static Map<String, Object> castOrNull(Object v) {
        return v instanceof Map<?, ?> m ? cast(m) : null;
    }

    /** 取某专辑登记过的「歌手 / 图 URL」；没登记过返回 {@code null}。 */
    private static String[] refOf(String album) {
        synchronized (REFS) {
            Object v = REFS.get(album);
            if (!(v instanceof Map<?, ?>)) {
                return null;
            }
            Map<String, Object> m = cast((Map<?, ?>) v);
            return new String[]{str(m.get("b")), str(m.get("u"))};
        }
    }

    /**
     * 启动补齐线程（幂等）。{@code sink} <b>每造好一张就回调一次</b>（不再攒够 200 张才回调），
     * 调用方（{@code NeteasePlugin}）负责把这一批立刻写进宿主库 ⇒ 宿主 UI 当场刷新。
     */
    public static void startFill(Sink s) {
        load();
        sink = s;
        ensurePool();
        if (fillThread != null && fillThread.isAlive()) {
            return;
        }
        fillStop = false;
        Thread t = new Thread(() -> fillLoop(s), "netease-cover-fill");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        fillThread = t;
        t.start();
    }

    /** 下载池（幂等）：{@link #NET_THREADS} 条守护线程，停用后下次 {@link #startFill} 重建。 */
    private static void ensurePool() {
        java.util.concurrent.ExecutorService p = netPool;
        if (p != null && !p.isShutdown()) {
            return;
        }
        synchronized (CoverStore.class) {
            p = netPool;
            if (p != null && !p.isShutdown()) {
                return;
            }
            java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
            netPool = java.util.concurrent.Executors.newFixedThreadPool(NET_THREADS, r -> {
                Thread t = new Thread(r, "netease-cover-net-" + seq.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
            PluginLog.i(TAG, "封面下载池已就绪：" + NET_THREADS + " 路并行，无全局节流");
        }
    }

    public static void stopFill() {
        fillStop = true;
        Thread t = fillThread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(STOP_JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        java.util.concurrent.ExecutorService p = netPool;
        netPool = null;
        if (p != null) {
            p.shutdownNow();          // 在下的一张下完就自然结束（HttpURLConnection 不响应中断）
        }
        INFLIGHT.clear();
        READY.clear();
        // 强引用留着不碍事：isAlive() 为 false，下次 startFill 会重建。
    }

    /**
     * 补齐主循环（0.11.1）：<b>提交 → 立刻投递</b>，两条腿各走各的。
     *
     * <p>下载在 {@link #NET_THREADS} 路池里并行跑（不限量、不节流）；本线程只负责「把清单里
     * 还没桩的专辑丢进池子」和「把造好的封面取出来立刻交给 {@link Sink}（= 写宿主库）」。
     * 就绪队列里有多少就投多少，绝不为了凑批等待。</p>
     */
    private static void fillLoop(Sink sink) {
        int backoff = 0;
        long lastScan = 0L;
        while (!fillStop) {
            int submitted = 0;
            long now = System.currentTimeMillis();
            if (now - lastScan >= SUBMIT_SCAN_MS || INFLIGHT.isEmpty()) {
                lastScan = now;
                submitted = submitPending();
            }
            List<CoverArt.Cover> batch = drain(POLL_MS);
            if (!batch.isEmpty()) {
                backoff = 0;
                roundClosed = false;
                PluginLog.i(TAG, "封面实时投递：" + batch.size() + " 张（桩合计 " + countStubs() + " 个）");
                deliver(sink, batch);
                flush(true);
                continue;
            }
            flush(true);
            if (fillStop) {
                break;
            }
            if (submitted > 0 || !INFLIGHT.isEmpty()) {
                backoff = 0;
                continue;             // 还有在下的：马上接着等，不睡
            }
            if (!roundClosed) {
                roundClosed = true;
                logRoundEnd();        // 轮次收尾无条件补一行（快轮次可能一条进度都没赶上）
            }
            long sleep = RiskControl.netBackoffMs(backoff + 1);
            backoff++;
            PluginLog.d(TAG, "封面补齐：清单里没有待造的了（桩合计 " + countStubs() + " 个），退避 "
                    + RiskControl.human(sleep) + " 后再扫");
            // 切片睡眠：退避最长 30 s，而清单可能在任何一刻到（同步线程边拉边 plan）——
            // 直接 Thread.sleep(30s) 会让新到的清单干等最多 30 s（0.11.7 冷轮实测 77 s 零完成）。
            long woke = planSeq;
            long left = sleep;
            while (left > 0 && !fillStop && planSeq == woke) {
                long slice = Math.min(WAKE_SLICE_MS, left);
                try {
                    Thread.sleep(slice);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                left -= slice;
            }
        }
    }

    /**
     * 把清单里「还没桩、也不在下载中、也不在失败冷静期」的专辑一次性全部丢进下载池。
     *
     * @return 本次投递数
     */
    private static int submitPending() {
        java.util.concurrent.ExecutorService p = netPool;
        if (p == null || p.isShutdown() || fillStop) {
            return 0;
        }
        int n = 0;
        long now = System.currentTimeMillis();
        for (String[] row : pending()) {
            if (fillStop) {
                break;
            }
            String key = row[0];
            Long until = RETRY_AT.get(idxOf(row[1], row[3]));
            if (until != null && until > now) {
                continue;
            }
            if (!INFLIGHT.add(key)) {                // 同名不同歌手 = 两条任务（键带歌手）
                continue;
            }
            try {
                p.execute(() -> build(row[1], row[2], row[3]));
                n++;
            } catch (Throwable t) {
                INFLIGHT.remove(key);
                PluginLog.d(TAG, "封面任务投递失败（忽略）：" + t);
                break;
            }
        }
        if (n > 0 && firstSubmitAt == 0L) {
            firstSubmitAt = System.currentTimeMillis();
            idleBeforeWorkMs = firstSubmitAt - startedAt;
            PluginLog.i(TAG, "封面本轮开工：首次投出 " + n + " 张下载任务（池就绪 → 开工之间清单等待 "
                    + idleBeforeWorkMs + " ms）");
        }
        return n;
    }

    /** 下载池里的一条工作项：造桩 → 就绪队列（投递由补齐线程负责，HTTP 不占投递线程）。 */
    private static void build(String album, String artist, String url) {
        try {
            String idx = idxOf(album, url);
            boolean wasStuck = refetchableStub(stubName(idx), url);   // 这次是「有图占位」的重取尝试？
            String uri = ensure(album, artist, url);
            if (uri == null) {
                // 只可能是「占位图也画不出来」（无盘/无头环境）；给一个短退避，别把池子打满。
                long wait = RiskControl.netBackoffMs(1);
                RETRY_AT.put(idx, System.currentTimeMillis() + wait);
                return;
            }
            if (refetchableStub(stubName(idx), url)) {
                // 重取没成：占位早已在宿主上（wasStuck）就不必再投；给 2 分钟节奏，别把 5 次机会连打光。
                // ensure→fail 已记账/退避（风控/网络的退避更长，用 max 保留）。
                RETRY_AT.merge(idx, System.currentTimeMillis() + STUCK_RETRY_MS, Math::max);
                if (!wasStuck) {
                    READY.add(new CoverArt.Cover(album, artist, uri));    // 首次失败：占位先给宿主，别让行空着
                }
                return;
            }
            RETRY_AT.remove(idx);
            READY.add(new CoverArt.Cover(album, artist, uri));
        } catch (Throwable t) {
            long wait = RiskControl.netBackoffMs(1);
            RETRY_AT.put(idxOf(album, url), System.currentTimeMillis() + wait);
            PluginLog.w(TAG, "封面造桩异常（" + RiskControl.human(wait) + " 后再试）：" + t);
        } finally {
            // 0.11.52（F1）：加入用的是 refKey(album, artist) 复合键（带歌手），移除也必须同键 —— 此前按纯专辑名
            // 移除永远删不掉，「在下载 N」整场不清零：失败专辑的退避重取失效、fillLoop 还会空转热扫。
            INFLIGHT.remove(refKey(album, artist));
        }
    }

    /**
     * 取就绪封面：最多等 {@code waitMs} 毫秒；拿到第一张就把此刻已经就绪的全部一起取走。
     * 因此投递粒度 = 「这一瞬间造好了多少」，而不是某个固定批量。
     */
    private static List<CoverArt.Cover> drain(long waitMs) {
        List<CoverArt.Cover> out = new ArrayList<>();
        try {
            CoverArt.Cover first = READY.poll(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (first != null) {
                out.add(first);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return out;
        }
        READY.drainTo(out);
        return out;
    }

    private static void deliver(Sink sink, List<CoverArt.Cover> batch) {
        if (batch.isEmpty() || sink == null) {
            return;
        }
        DONE_EPOCH.incrementAndGet();            // 0.11.55（F8）：新批次落地信号（面板负缓存自愈）
        List<CoverArt.Cover> copy = new ArrayList<>(batch);
        batch.clear();
        try {
            sink.onBatch(copy);
        } catch (Throwable t) {
            PluginLog.d(TAG, "封面投递回调失败（忽略）：" + t);
        }
    }

    /** 当前完成纪元（只读）：{@code ui.CoverCache} 判「失败后有没有新批次落地」（0.11.55 F8）。 */
    public static long doneEpoch() {
        return DONE_EPOCH.get();
    }

    /** 还没造出桩的专辑（按登记顺序）。 */
    private static List<String[]> pending() {
        List<String[]> out = new ArrayList<>();
        synchronized (REFS) {
            for (Object v : REFS.values()) {
                if (!(v instanceof Map<?, ?>)) {
                    continue;
                }
                Map<String, Object> m = cast((Map<?, ?>) v);
                String album = text(str(m.get("a")), "");
                String artist = str(m.get("b"));
                String url = str(m.get("u"));
                String stub = m.get("s") == null ? null : String.valueOf(m.get("s"));
                if (album.isEmpty()) {
                    continue;
                }
                if (stub != null && usable(stubDir().resolve(stub))
                        && !refetchableStub(stub, url)) {
                    continue;
                }
                out.add(new String[]{refKey(album, artist), album, artist, url});
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 索引/统计

    /** 索引键：有图 URL 用 URL（去 query）的 md5；无图用「占位 + 专辑名」的 md5。 */
    private static String idxOf(String album, String picUrl) {
        String url = picUrl == null ? "" : picUrl.trim();
        if (url.isEmpty()) {
            return Hashes.md5Hex(PLACEHOLDER_PREFIX + text(album, "未知专辑"));
        }
        int q = url.indexOf('?');
        return Hashes.md5Hex(q > 0 ? url.substring(0, q) : url);
    }

    private static int attempts(String idx) {
        synchronized (REFS) {
            Object v = INDEX.get(idx);
            if (v instanceof Map<?, ?> m && m.get("n") instanceof Number n) {
                return n.intValue();
            }
            return 0;
        }
    }

    private static int bumpAttempts(String idx, String album, String url) {
        synchronized (REFS) {
            Map<String, Object> rec = new LinkedHashMap<>();
            Object old = INDEX.get(idx);
            if (old instanceof Map<?, ?> m) {
                rec.putAll(cast(m));
            }
            int n = 0;
            if (rec.get("n") instanceof Number old2) {
                n = old2.intValue();
            }
            rec.put("a", album);
            rec.put("u", url);
            rec.put("n", (long) (n + 1));
            rec.put("p", Boolean.FALSE);
            rec.put("t", System.currentTimeMillis());
            INDEX.put(idx, rec);
            dirty = true;
            return n + 1;
        }
    }

    // ------------------------------------------------------------------ 失败分类与长退避

    /**
     * 封面失败分类（任务书第 6 条「失败分类 + 长退避」，禁止静默失败）。
     *
     * <p>退避梯度<b>不在这里写死</b>：走 {@link RiskControl#netBackoffMs(int)} /
     * {@link RiskControl#riskBackoffMs(int)}（任务书 §13：全插件共用一套风控口径）。</p>
     *
     * <ul>
     *   <li>{@code cooling=true}：一时取不到（网络/风控）⇒ 记入长退避，到点自动重试；</li>
     *   <li>{@code swap=true}：换个 CDN 域再试（403 与「URL 过期」两类最常见）；</li>
     *   <li>{@code risk=true}：走风控档退避（10 min 起、1 h 封顶）。</li>
     * </ul>
     */
    private enum Fail {
        /** 专辑没有图（picUrl 为空/占位）：永久终态，不进网络。 */
        NO_SRC("无图源", false, false, false),
        /** 超时/DNS/断网：最常见，退避后重试。 */
        NET("网络错误", true, true, false),
        /** 403/412/429：被风控，换域 + 长退避。 */
        RISK("风控", true, true, true),
        /** 404/410：图床那份图过期了，换域再试，仍无则出占位图。 */
        EXPIRED("URL过期", false, true, false),
        /** 其它 4xx/5xx。 */
        HTTP("HTTP异常", true, false, false),
        /** 响应体超过上限（不是图）。 */
        TOO_BIG("体积超限", false, false, false),
        /** 不是 JPEG/PNG 且解不开（走转码分支，转不成才判这里）。 */
        FORMAT("格式异常", false, false, false),
        /** 能解码但归一失败（像素过大等）。 */
        NORMALIZE("尺寸归一", false, false, false),
        /** 写盘失败。 */
        DISK("落盘失败", true, false, false),
        /** 可用空间不足（512 MB 守卫）。 */
        NO_SPACE("空间不足", false, false, false);

        final String label;
        final boolean cooling;
        final boolean swap;
        final boolean risk;

        Fail(String label, boolean cooling, boolean swap, boolean risk) {
            this.label = label;
            this.cooling = cooling;
            this.swap = swap;
            this.risk = risk;
        }
    }

    /**
     * 一次取图失败的分类结果（{@link RuntimeException} 子类：构造点直接 {@code throw}，
     * 由 {@link #fail} 统一记账 —— 不需要在调用链每一层搬返回值）。
     *
     * @param kind  分类
     * @param code  HTTP 码（{@code <= 0} 表示没有码）
     */
    private static final class Short extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final Fail kind;
        final int code;

        Short(Fail kind, int code, Throwable cause) {
            super(kind.label + (code > 0 ? String.valueOf(code) : ""), cause);
            this.kind = kind;
            this.code = code;
        }

        Fail kind() {
            return kind;
        }

        int code() {
            return code;
        }

        /** 计数/日志用的展示名（{@code 风控403} / {@code 网络错误}）。 */
        String label() {
            return code > 0 ? kind.label + code : kind.label;
        }
    }

    /** 一次取图的成果：归一后的字节 + MIME + 归一说明（{@code ""} = 本来就合规）。 */
    private record Fetch(String mime, byte[] bytes, String note) {
    }

    /** 该索引的连续失败次数。 */
    private static int fails(String idx) {
        Integer n = FAILS.get(idx);
        return n == null ? 0 : n;
    }

    /** 索引 idx 的桩内嵌的是不是占位图（{@code i} 以 {@code placeholder-} 开头）。 */
    private static boolean placeholderStub(String idx) {
        synchronized (REFS) {
            Object v = INDEX.get(idx);
            if (v instanceof Map<?, ?> m) {
                Object i = m.get("i");
                return i != null && String.valueOf(i).startsWith("placeholder-");
            }
        }
        return false;
    }

    /**
     * 桩文件是「占位图」、且它对应的真图 URL 还在 ⇒ 这次不许当已就绪，要重取（0.11.51）。
     *
     * <p>只认「桩名反查出的 idx == 当前 URL 的 idx」：URL 变更的专辑不在此列（走正常下载路径）。
     * 无图源的占位（URL 为空）是合法终态，永远返回 {@code false}。</p>
     */
    private static boolean refetchableStub(String stubFile, String url) {
        if (stubFile == null || url == null || url.isEmpty()) {
            return false;
        }
        String idx = idxFromStub(stubFile);
        return idx != null && idx.equals(idxOf("", url)) && placeholderStub(idx);
    }

    /**
     * 失败记账：分类计数 + 每图独立的指数退避 + 写进索引（{@code n}=次数 {@code f}=分类）。
     *
     * @return 占位图 URI（保证调用方「当场有图可看」，绝不返回 null 空等）
     */
    private static String fail(String album, String artist, String url, String idx, Short e) {
        int n = FAILS.merge(idx, 1, Integer::sum);
        if (e.kind().cooling) {
            long wait = retryDelay(e.kind().risk, n);
            if (wait > 0L) {
                RETRY_AT.put(idx, System.currentTimeMillis() + wait);
            }
        }
        FAIL_CLASS.put(idx, e.label());        // 一张图一个桶（取最近一次分类）；与 FAILS 同点写入
        if (n >= MAX_ATTEMPTS) {
            PluginLog.w(TAG, "封面放弃（连续 " + n + " 次 · " + e.label() + "）：" + album
                    + "（" + CoverArt.brief(url) + "）⇒ 用占位图");
        } else {
            PluginLog.w(TAG, "封面失败[" + e.label() + "，" + n + "/" + MAX_ATTEMPTS + "]：" + album
                    + "（" + CoverArt.brief(url) + "）"
                    + (e.kind().cooling ? " ⇒ " + RiskControl.human(retryDelay(e.kind().risk, n)) + " 后重试" : " ⇒ 用占位图"));
        }
        if (e.getCause() != null) {
            String why = "封面失败原因[" + e.label() + "]：" + e.getCause();
            if (e.kind() == Fail.DISK || e.kind() == Fail.NO_SPACE) {
                PluginLog.w(TAG, why);         // 本地缺陷（落盘/空间）必须可见：禁止静默失败
            } else {
                PluginLog.d(TAG, why);
            }
        }
        bumpAttempts(idx, album, url);
        return placeholder(album, artist, url, idx);
    }

    /** 第 {@code n} 次失败后的退避时长（Task §13：风控/普通两档，都从 RiskControl 取）。 */
    private static long retryDelay(boolean risk, int n) {
        return risk ? RiskControl.riskBackoffMs(n) : RiskControl.netBackoffMs(n);
    }

    private static String fail(String album, String artist, String url, String idx, Fail kind, int code,
                              Throwable cause) {
        return fail(album, artist, url, idx, new Short(kind, code, cause));
    }

    /**
     * 归一<b>过程</b>记账 + 节流日志（每类前 3 次）。
     *
     * <p>词形固定 {@code 封面归一[<label>]：…} —— 与失败行 {@code 封面失败[<class>，K/N]} 严格分开；
     * 只进 {@link #NORM_KINDS}（过程账）与 {@link #NORMALIZED}（总数），<b>绝不</b>进失败桶。</p>
     */
    private static void noteNorm(String label, String detail) {
        NORMALIZED.incrementAndGet();
        NORM_KINDS.merge(label, 1, Integer::sum);
        if (NORM_KINDS_LOGGED.merge(label, 1, Integer::sum) <= 3) {
            PluginLog.i(TAG, "封面归一[" + label + "]：" + detail);
        }
    }

    /**
     * 失败分桶：一张图<b>只进一个桶</b>（取最近一次分类）。
     *
     * <p>因为桶由 {@link #FAIL_CLASS}（与 {@link #FAILS} 键集合恒等）现算，所以
     * <b>各桶之和恒等于失败张数</b> —— 不会出现「失败 1 却列出两个分类各 1」。</p>
     */
    private static Map<String, Integer> failBuckets() {
        Map<String, Integer> out = new java.util.TreeMap<>();
        for (String label : FAIL_CLASS.values()) {
            out.merge(label, 1, Integer::sum);
        }
        return out;
    }

    /** 分桶之和（纸面自检用：必须恒等于 {@link #FAILS}{@code .size()}）。 */
    private static int bucketSum(Map<String, Integer> buckets) {
        int sum = 0;
        for (int v : buckets.values()) {
            sum += v;
        }
        return sum;
    }

    /**
     * 轮次收尾汇总：<b>无条件</b>打一行 {@link #logRound()}（绕过节流）。
     *
     * <p><b>0.11.7 修正</b>：{@code logRound()} 原先<b>全树无调用点</b>（死代码）⇒ 冷轮 40 秒铺完 2092 张，
     * 日志里一行 {@code [cover] 完成 M 张 / 去重省下 K 次} 都没有，A9 判据行无从取证。现在：
     * 每张完成时自节流打进度行，补齐线程发现「没有待办」时再补这一行收尾。</p>
     */
    private static void logRoundEnd() {
        lastRoundLog = 0L;          // 让节流门放行
        lastRoundAt = -1;
        logRound();
        // 轮次已收尾 ⇒ 下一轮的「用时」必须从**它自己**第一次投出算起。不重置的话，长时间运行后
        // 第二轮会把「上一轮结束 → 这一轮第一次投出」之间的空闲（可能几小时）也算进去。
        firstSubmitAt = 0L;
        idleBeforeWorkMs = 0L;
    }

    /**
     * 一轮汇总日志（A9 判据行的提供者）：{@code [cover] 完成 M 张 / 去重省下 K 次 / 归一 J 张 / 失败 …}。
     *
     * <p>由补齐线程每 {@link #ROUND_LOG_MS} 毫秒或每完成 {@link #ROUND_LOG_EVERY} 张时打一行；
     * 计数<b>不重置</b>（「完成 M 张」是本次会话累计，审计脚本按文件系统另算增量）。</p>
     */
    private static void logRound() {
        long now = System.currentTimeMillis();
        if (now - lastRoundLog < ROUND_LOG_MS && FETCHED.get() - lastRoundAt < ROUND_LOG_EVERY) {
            return;
        }
        lastRoundLog = now;
        lastRoundAt = FETCHED.get();
        long[] imgs = imgStats();
        PluginLog.i(TAG, "完成 " + FETCHED.get() + " 张 / 去重省下 " + DEDUP_HITS.get() + " 次 / 归一 "
                + normSummary() + " / 失败 " + failSummary() + " / img " + imgs[0] + " 张 "
                + (imgs[1] / 1024L / 1024L) + " MB（用时 "
                + (firstSubmitAt == 0L ? 0L : now - firstSubmitAt) + " ms）");
    }

    /**
     * 失败摘要：{@code 张数（分类 a N / 分类 b M）}，无失败时为 {@code 无}。
     *
     * <p>桶由 {@link #failBuckets()} 现算（一张图只进一个桶）⇒ <b>各桶之和恒等于张数</b>；
     * 一旦对不上就在这里显式写出来，绝不让「失败 1」和「两个分类各 1」同时出现。</p>
     */
    private static String failSummary() {
        int total = FAILS.size();
        if (total == 0 && FAIL_CLASS.isEmpty()) {
            return "无";
        }
        Map<String, Integer> buckets = failBuckets();
        int sum = bucketSum(buckets);
        StringBuilder sb = new StringBuilder();
        sb.append(total).append(" 张（");
        if (sum != total) {
            sb.append("口径异常：分桶 ").append(sum).append(" ≠ 失败 ").append(total).append(" / ");
        }
        if (buckets.isEmpty()) {
            sb.append("无分类");
        } else {
            int i = 0;
            for (Map.Entry<String, Integer> e : buckets.entrySet()) {
                if (i++ > 0) {
                    sb.append(" / ");
                }
                sb.append(e.getKey()).append(' ').append(e.getValue());
            }
        }
        return sb.append('）').toString();
    }

    /** 归一过程账摘要（A9 行与 {@link #stats()} 共用；显式标注「过程，不计失败」）。 */
    private static String normSummary() {
        int total = NORMALIZED.get();
        if (NORM_KINDS.isEmpty()) {
            return total + " 张";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(total).append(" 张（");
        int i = 0;
        for (Map.Entry<String, Integer> e : new java.util.TreeMap<>(NORM_KINDS).entrySet()) {
            if (i++ > 0) {
                sb.append(" / ");
            }
            sb.append(e.getKey()).append(' ').append(e.getValue());
        }
        return sb.append("）（过程，不计失败）").toString();
    }

    /**
     * 这个封面引用键背后「图内容最后一次变化」的时刻（毫秒）：原图文件 mtime 与 refs 登记时刻取大者。
     *
     * <p>给行封面 / 播放条键的<b>陈旧自愈</b>用（0.11.49）：缓存键的明文里只有
     * {@code path?v=1&t=…&s=…&r=…&w=…&h=…}，没有图内容 —— 图换了而键不变时，宿主缓存里的旧图
     * 会永久留下（真机 2026-10-04：40 首键192内容 = 别的专辑的图，键 mtime 10-01、img mtime 10-04）。
     * 铺键前比一下就重铺。</p>
     *
     * @return 毫秒时刻；查不到（没登记 / 没图）返回 0 = 不判陈旧
     */
    public static long imageTime(String refKey) {
        try {
            load();
            long t = 0L;
            String url = "";
            String album = "";
            String stub = null;
            synchronized (REFS) {
                Object v = REFS.get(refKey);
                if (v instanceof Map<?, ?> m) {
                    if (m.get("t") instanceof Number n) {
                        t = n.longValue();
                    }
                    Object u = m.get("u");
                    if (u != null) {
                        url = String.valueOf(u);
                    }
                    Object a = m.get("a");
                    if (a != null) {
                        album = String.valueOf(a);
                    }
                    Object s = m.get("s");
                    if (s != null) {
                        stub = String.valueOf(s);
                    }
                }
            }
            if (t <= 0L && url.isEmpty()) {
                return 0L;
            }
            // 0.11.49b：真机一批 refs 记录没有桩（'s' 缺失）——旧实现直接 return 0，
            // 陈旧判据整段失效 ⇒ 这批判据覆盖不到的键永不重铺（多首串图长期残留）。
            // 没有桩就按 URL 直接求索引键，一样能拿到原图 mtime。
            String idx = stub == null ? null : idxFromStub(stub);
            if (idx == null) {
                idx = idxOf(text(album, "未知专辑"), url);
            }
            Map<String, Object> m = castOrNull(INDEX.get(idx));
            String name = m == null ? "" : str(m.get("i"));
            if (!name.isEmpty()) {
                Path img = imagePath(name);
                if (Files.isRegularFile(img)) {
                    t = Math.max(t, Files.getLastModifiedTime(img).toMillis());
                }
            }
            return t;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** refs 记录（按引用键）；不存在返回 {@code null}。 */
    private static Map<String, Object> refRec(String refKey) {
        synchronized (REFS) {
            Object v = REFS.get(refKey);
            return v instanceof Map<?, ?> m ? cast(m) : null;
        }
    }

    private static String refStub(String album) {
        synchronized (REFS) {
            Object v = REFS.get(album);
            if (v instanceof Map<?, ?> m) {
                Object s = m.get("s");
                return s == null ? null : String.valueOf(s);
            }
            return null;
        }
    }

    private static void rememberRef(String album, String artist, String url, String stub) {
        String name = text(album, "未知专辑");
        String who = text(artist, "");
        synchronized (REFS) {
            String key = refKey(name, who);
            Map<String, Object> rec = new LinkedHashMap<>();
            Object old = REFS.get(key);
            if (old instanceof Map<?, ?> m) {
                rec.putAll(cast(m));
            }
            rec.put("b", who);
            rec.put("a", name);
            rec.put("u", url == null ? "" : url);
            if (stub != null) {
                rec.put("s", stub);
            }
            rec.put("t", System.currentTimeMillis());
            REFS.put(key, rec);
            dirty = true;
        }
    }

    private static Map<String, Object> cast(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static boolean spaceOk() {
        try {
            Path d = dir();
            Files.createDirectories(d);
            long free = d.toFile().getUsableSpace();
            if (free < MEM_GUARD_BYTES) {
                PluginLog.w(TAG, "封面缓存暂停：可用空间不足（剩 " + (free / 1024L / 1024L) + " MB）");
                return false;
            }
        } catch (Throwable ignored) {
            // 查不到余量就照常走
        }
        return true;
    }

    private static boolean usable(Path p) {
        return CoverArt.usable(p);
    }

    private static boolean usable(String name) {
        return name != null && !name.isEmpty() && usable(stubDir().resolve(name));
    }

    /** {@code stub\} 里的桩数量（0.10.x 的旧目录已在 {@link #init()} 里搬空）。 */
    public static int countStubs() {
        try (var stream = Files.list(stubDir())) {
            return (int) stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(CoverArt.STUB_PREFIX) && n.endsWith(".flac");
            }).count();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 原图张数 / 字节。 */
    private static long[] imgStats() {
        long count = 0L;
        long bytes = 0L;
        try (var stream = Files.list(imgDir())) {
            for (Path p : stream.toList()) {
                if (Files.isRegularFile(p)) {
                    count++;
                    bytes += Files.size(p);
                }
            }
        } catch (Throwable ignored) {
            // 目录不存在就是 0
        }
        return new long[]{count, bytes};
    }

    /**
     * 完成度三态 + 失败分类 + 目录占用（配置页「封面完成度」与 A9/A11 判据）。
     *
     * <p>口径：</p>
     * <ul>
     *   <li><b>已就绪</b> = {@code stub\} 里可用的桩数（每张桩对应一张已落地的图）；</li>
     *   <li><b>待处理</b> = 清单里还没桩、也不在失败计数里的专辑数；</li>
     *   <li><b>失败</b> = 有过失败记录的专辑数，后紧跟<b>分类计数</b>（一张图只进一个桶 ⇒ 各桶之和恒等于张数）。</li>
     * </ul>
     */
    public static String stats() {
        long[] imgs = imgStats();
        int stubs = countStubs();
        int failed = FAILS.size();
        int pending = pending().size();
        long dirBytes = treeBytes(dir());
        Map<String, Integer> buckets = failBuckets();
        int sum = bucketSum(buckets);
        StringBuilder sb = new StringBuilder(160);
        sb.append("封面缓存：已就绪 ").append(stubs)
                .append(" / 待处理 ").append(Math.max(0, pending - failed))
                .append(" / 失败 ").append(failed);
        if (!buckets.isEmpty()) {
            sb.append("（分类 ");
            int i = 0;
            for (Map.Entry<String, Integer> e : buckets.entrySet()) {
                if (i++ > 0) {
                    sb.append(" / ");
                }
                sb.append(e.getKey()).append(' ').append(e.getValue());
            }
            sb.append('）');
        }
        if (sum != failed) {
            // 一张图只进一个桶 ⇒ 分桶之和恒等于失败张数；对不上只可能是代码回归，
            // 纸面与日志同时报出来，绝不允许「失败 1 却列出两个分类各 1」那种自相矛盾。
            sb.append("［口径异常：分桶 ").append(sum).append(" ≠ 失败 ").append(failed).append("］");
            PluginLog.e(TAG, "封面失败分桶与失败张数不一致：分桶 " + sum + " ≠ 失败 " + failed
                    + "（FAIL_CLASS " + FAIL_CLASS.size() + " 项 / FAILS " + failed + " 项）");
        }
        sb.append(" ｜ img ").append(imgs[0]).append(" 张 ").append(imgs[1] / 1024L / 1024L)
                .append(" MB / 桩 ").append(stubs).append(" 张 ").append(dirBytes(stubDir()) / 1024L / 1024L)
                .append(" MB / 目录 ").append(dirBytes / 1024L / 1024L).append(" MB")
                .append("（在下载 ").append(INFLIGHT.size()).append(" / 待写库 ").append(READY.size()).append("）");
        if (NORMALIZED.get() > 0 || !NORM_KINDS.isEmpty()) {
            sb.append(" ｜ 归一 ").append(normSummary());
        }
        return sb.toString();
    }

    /**
     * 「清理音乐封面缓存」按钮（R8，配置页反射调用）。
     *
     * <p>只清 {@code cover\} 自己：{@code img\}（含 0.11.7 以前的面板缩略图 PNG）、{@code stub\}、
     * {@code cover-index.json} / {@code cover-refs.json} —— <b>不碰</b> {@code audio-stream\}、
     * {@code audio-cover\}、{@code lyric\}，也不动宿主自己的 {@code cache\shared_cover}（宿主会自清）。</p>
     *
     * <p>清完把内存索引/计数一起归零（否则界面会拿着旧桩名去点已删文件），下次同步会重新铺。</p>
     *
     * @return 可直接拼进提示语的一句（如「已删 4184 个文件 / 687.3 MB」）
     */
    public static String clearAll() {
        long files = 0L;
        long bytes = 0L;
        try {
            synchronized (CoverStore.class) {
                for (Path p : listAll(dir())) {
                    if (!Files.isRegularFile(p)) {
                        continue;
                    }
                    long n = 0L;
                    try {
                        n = Files.size(p);
                    } catch (Throwable ignored) {
                        // 量不到就只计数
                    }
                    if (Files.deleteIfExists(p)) {
                        files++;
                        bytes += n;
                    }
                }
                INDEX.clear();
                REFS.clear();
                SONGS.clear();
                RETRY_AT.clear();
                FAILS.clear();
                FAIL_CLASS.clear();
                NORM_KINDS.clear();
                NORM_KINDS_LOGGED.clear();
                INFLIGHT.clear();
                READY.clear();
                DEDUP_HITS.set(0);
                NORMALIZED.set(0);
                FETCHED.set(0);
                FETCHED_BYTES.set(0);
                dirty = false;
            }
            try {
                Files.createDirectories(imgDir());
                Files.createDirectories(stubDir());
            } catch (Throwable ignored) {
                // 建不回来时后续写各自失败
            }
            PluginLog.i(TAG, "封面缓存已清理：删 " + files + " 个文件 / " + (bytes / 1024L / 1024L)
                    + " MB（" + dir() + "）");
            return "已删 " + files + " 个文件 / " + humanBytes(bytes);
        } catch (Throwable t) {
            PluginLog.e(TAG, "清理封面缓存失败：" + t);
            return "清理失败：" + t;
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
        }
        return bytes / 1024L + " KB";
    }

    /** 目录下所有文件（递归，含子目录），给清理与字节统计用。 */
    private static List<Path> listAll(Path root) {
        List<Path> out = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.forEach(out::add);
        } catch (Throwable ignored) {
            // 目录不存在
        }
        return out;
    }

    /** 递归字节数（{@code img\} + {@code stub\} + 索引文件）。 */
    private static long treeBytes(Path root) {
        long sum = 0L;
        for (Path p : listAll(root)) {
            if (!Files.isRegularFile(p)) {
                continue;
            }
            try {
                sum += Files.size(p);
            } catch (Throwable ignored) {
                // 量不到就跳过
            }
        }
        return sum;
    }

    private static long dirBytes(Path dir) {
        long sum = 0L;
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                if (Files.isRegularFile(p)) {
                    sum += Files.size(p);
                }
            }
        } catch (Throwable ignored) {
            // 目录不存在
        }
        return sum;
    }

    /**
     * 文本归一（封面侧口径）：空白 → 兜底值，trim，超 220 字截断。
     *
     * <p><b>0.11.54（F6）</b>：宿主库里的 album/artist 值都是 {@link NativeLibrary#text} 写的
     * （220 截断），引用键此前不截断 ⇒「库里短名 / 键长名」错配，超长名的曲行永远缺图。
     * 这里对齐 220 上限后，两侧从同一个名字算出同一把键。</p>
     */
    private static String text(String s, String fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        String t = s.trim();
        return t.length() > 220 ? t.substring(0, 220) : t;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
