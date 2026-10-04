package com.example.netease.svc;

import com.example.netease.core.DataPaths;
import com.example.netease.core.Json;
import com.example.netease.core.PluginLog;
import com.example.netease.core.RiskControl;
import com.example.netease.net.Dto;
import com.example.netease.net.NeteaseApi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 在线歌词服务（0.6.0 恢复；0.11.8 W4 收敛为<b>歌词层唯一入口</b>）。
 *
 * <p><b>它做什么</b>：在线播放时我们<b>自己就知道 songId</b>（直链是我们请求的），所以不需要
 * 「本地曲目 → 网易云曲目」匹配链路。链路被压成一条直线：</p>
 * <ol>
 *   <li>{@link #prefetch(List, Map)} / {@link #prefetchOne} / {@link #ensureAsync} 入队 →
 *       清洗（{@link LrcUtil#clean}）→ 合并翻译/罗马音 → 写内存 + 落盘 {@code lyric\<songId>.lrc}；</li>
 *   <li>宿主回放时回调歌词钩子，钩子只调 {@link #lrcFor(String, String, String)}；</li>
 *   <li>{@code lrcFor} 是<b>纯内存查表</b>，把文本还给宿主。</li>
 * </ol>
 *
 * <p><b>线程约定（本工程死锁铁律，见 docs/00 §4/§5）</b>：</p>
 * <ul>
 *   <li>{@link #lrcFor(String, String, String)} 会被<b>宿主回调线程</b>同步调用 ⇒ 它
 *       <b>禁止联网、禁止读写磁盘、禁止锁等待</b>，只读 {@code ConcurrentHashMap}
 *       （无竞争时几乎零开销，目标 &lt;1ms）。命中的诊断证据行走
 *       {@link #HITQ 无锁队列}，由后台守护线程落盘（入队不取锁、不写盘）。</li>
 *   <li>{@link #prefetch(List, Map)} / {@link #ensureAsync} 只入队即返，<b>绝不阻塞调用方</b>。</li>
 *   <li>抓取线程上允许联网与写盘（{@code PluginLog} 同步写盘，故日志节制在每轮一行汇总 + 每首一行失败）。</li>
 *   <li>线程池<b>不复用</b> {@code VoxzenBridge.NET}：那条池只有单线程且被直链请求占满，
 *       歌词抓取一旦排队就会把「点歌 → 出声」的时延拖到秒级。</li>
 * </ul>
 *
 * <p><b>0.11.8（W4）改了什么</b>：</p>
 * <ol>
 *   <li><b>并发</b>：单线程串行 → {@link RiskControl#WARM_CONCURRENCY} 路守护抓取（R15「速度优先、
 *       并发拉满、无人工节流」）。歌词请求走本类自建直连，<b>不经 {@code net.Http} 的 300ms 全局节流</b>
 *       —— 那道锁是给宿主界面交互留的，批量预热挂在它后面物理上不可能「与封面同量级」。</li>
 *   <li><b>每曲一次请求</b>：只打 {@code /api/song/lyric?os=pc&id=..&lv=-1&kv=-1&tv=-1}，一次应答里同时
 *       拿主词/翻译/罗马音（老接口把 tlyric/romalrc 放在同级）。{@code NeteaseApi.lyric} 的最坏路径是
 *       4 次 HTTP，只当兜底用。</li>
 *   <li><b>失败分类 + 长退避</b>：{@code 401/403}（风控）、{@code NO_LYRIC}（确认无词）、{@code NET}、
 *       {@code PARSE} 为<b>冻结五段</b>，{@code AUTH}（凭据失效需登录）与 {@code ENV}（环境级错误）
 *       <b>只追加在判据行行尾</b>（见 {@link #failClassText()}）；退避阶梯统一引用
 *       {@link RiskControl}（全插件一份常量），失败不再静默。</li>
 *   <li><b>部分淘汰级联</b>：{@link #trimIfHuge()} 由「四张表各自淘汰」改成<b>按曲目</b>淘汰——
 *       同一首的 URL/键/标题/id 索引与访问计数一起走，不留「URL 索引在、id 索引没了」的错位。</li>
 *   <li><b>完成度三态</b>：{@link #stats()} 报「已就绪 / 待处理 / 失败（分类）」。</li>
 *   <li><b>邻曲保温</b>：{@link #warmNeighbors(long)} 读宿主队列，把当前曲之后的若干首提前入队（R14）。</li>
 * </ol>
 *
 * <p><b>缓存纪律</b>：磁盘缓存 {@code <插件数据目录>\lyric\<songId>.lrc}，内容 = 最终注入文本；
 * <b>空内容绝不落盘</b>（写空串会清空宿主歌词显示）。纯音乐/无歌词从 0.11.50 起只落
 * {@code <songId>.none} 标记（内容一行 {@code no_lyric <epochMs>}，<b>不是歌词</b>）：重启回读后
 * 这些曲目直接计为「无歌词」，既不再重查网络、也不再被算成「待处理」；标记随「清理歌词缓存」
 * 按钮（{@link #clearCache()}）一起清。歌词缓存<b>不随音频缓存删除</b>（任务书 D5-A）。</p>
 */
public final class LyricService {

    /** 日志 tag，与 {@code ext} 区分开，便于真机日志里分辨「谁抓的」和「谁给的」。 */
    private static final String TAG = "lyric";

    /** 磁盘缓存单文件上限：超过就认定不是歌词（防坏缓存把内存吃光）。 */
    private static final long MAX_FILE_BYTES = 1024L * 1024L;

    /** 直连歌词接口（与 {@code net.Http.BASE} 同一台主机；独立成常量便于真机日志对照）。 */
    private static final String LYRIC_API = "https://music.163.com/api/song/lyric";

    /** 直连时的 UA：与 {@code net.Http} 同口径（网易对空 UA 会 403）。 */
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/124.0.0.0 Safari/537.36";

    /** 单次应答文本上限（防坏响应把内存吃光；正常歌词 &lt; 200 KB）。 */
    private static final int MAX_RESP_BYTES = 2 * 1024 * 1024;

    /**
     * 内存正缓存条目上限（按曲目计；超限时按 {@link #TRIM_DROP_RATIO} 分之一级联淘汰）。
     *
     * <p>取 4096 是算过账的：真机库 3267 首，平均歌词 4 KB ⇒ 整库常驻约 13 MB，相对宿主已是零头；
     * 而封顶一旦小于库容量，{@link #scanDiskOnce()} 刚读回来的磁盘缓存就会被当场淘汰（白读 +
     * 下一轮播放又走「未命中 → 补抓」）。宁可多留几 MB，也不让重启后重复抓词。</p>
     */
    private static final int MAX_MEM_ENTRIES = 4096;

    /** 超限时丢掉的比例（1/4）：一次腾出足够空间，避免「刚清完又满」的抖动。 */
    private static final int TRIM_DROP_RATIO = 4;

    /** 内存负缓存条目上限（超限丢一半，不全清）。 */
    private static final int MAX_NEGATIVE = 500;

    /** 无词标记文件后缀（0.11.50）：只落「已确认无歌词」这一行事实，绝不写成空的 {@code .lrc}。 */
    private static final String NEG_SUFFIX = ".none";

    /** 邻曲保温默认前瞻长度。 */
    private static final int NEIGHBOR_LOOKAHEAD = 8;

    /** 命中证据队列容量（满了丢弃，诊断行不能拖累播放）。 */
    private static final int MAX_HIT_QUEUE = 512;

    /** 主路径容器优先级：{@code lrc} 是标准 LRC（缓存文件要的就是它），其余是卡拉OK/逐字变体。 */
    private static final String[] LYRIC_CONTAINERS = {"lrc", "klyric", "yrc"};

    /** 访问计数表的前缀（0.10.x 起沿用，勿改：真机日志/自检按它对照）。 */
    private static final String P_URL = "u:";
    private static final String P_KEY = "k:";
    private static final String P_TITLE = "t:";
    private static final String P_ID = "i:";

    /** 失败分类的键。 */
    private static final String C_OK = "OK";
    private static final String C_NO_LYRIC = "NO_LYRIC";
    private static final String C_AUTH = "401";
    private static final String C_RISK = "403";
    private static final String C_AUTHN = "AUTH";
    private static final String C_NET = "NET";
    private static final String C_PARSE = "PARSE";

    /**
     * 环境级错误（0.11.8 新增，B2 轮真机逼出来的第六类）。
     *
     * <p>{@code NoClassDefFoundError: java/net/http/HttpRequest} 这类错误的含义是「代码在宿主 JVM
     * 里不可用」——它既不是 401/403（风控）、也不是 NET（这次网络不好）或 PARSE（应答不合预期）。
     * 混进 NET 的后果是：短退避 + 重试，UI 上只是一次「失败」计数，而真相是<b>整条链一首也抓不到</b>。
     * 单列一类 + {@link PluginLog#e} 才能让这种缺陷在真机上一眼可见。</p>
     */
    private static final String C_ENV = "ENV";

    /** 线程名校验用序号，风格对齐 {@code core.Timers}。 */
    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 预热口径的抓取线程数（全插件统一常量，见 {@link RiskControl}）。 */
    private static final int WORKERS = Math.max(1, RiskControl.WARM_CONCURRENCY);

    private static final ThreadFactory WARM_FACTORY = r -> {
        Thread t = new Thread(r, "netease-lyric-" + SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    };

    /** 曲目「注入用直链 URL」→ 歌词文本。预取线程写，宿主回调线程读。 */
    private static final Map<String, String> BY_URL = new ConcurrentHashMap<>();

    /** 「title|artist」（小写去空白）→ 歌词文本。 */
    private static final Map<String, String> BY_KEY = new ConcurrentHashMap<>();

    /** 归一化 title（小写去空白去括号后缀）→ 歌词文本。 */
    private static final Map<String, String> BY_TITLE = new ConcurrentHashMap<>();

    /** songId → 歌词文本。 */
    private static final Map<Long, String> BY_ID = new ConcurrentHashMap<>();

    /** 负缓存（已确认纯音乐/无歌词）：只放内存，绝不落盘。 */
    private static final Map<String, Boolean> NEGATIVE = new ConcurrentHashMap<>();

    /** id 负缓存（同上，按曲目）。 */
    private static final Map<Long, Boolean> NEGATIVE_ID = new ConcurrentHashMap<>();

    /** 已落盘无词标记（{@code <id>.none}）的曲目 id；负缓存淘汰必须跳过这些持久条目。 */
    private static final Set<Long> NEGATIVE_DISK = ConcurrentHashMap.newKeySet();

    /** 磁盘回读（.lrc + .none）完成闸：{@code svc.LyricWarmer} 等它，消「投递」与「回读」的开机竞态。 */
    private static final CountDownLatch DISK_LATCH = new CountDownLatch(1);

    /** 访问计数（{@code P_*} 前缀 + 表键）。读路径只 get + increment，不分配、不取锁。 */
    private static final Map<String, LongAdder> HITS = new ConcurrentHashMap<>();

    /** 内存淘汰用「曲目 → 该曲目在索引表里的键 {url, key, normTitle}」，一次淘汰带走整首。 */
    private static final Map<Long, String[]> SONG_KEYS = new ConcurrentHashMap<>();

    /** 内存淘汰用「归一化标题 → 拥有它的曲目」（标题索引与曲目不总是一对一）。 */
    private static final Map<String, Long> TITLE_OWNER = new ConcurrentHashMap<>();

    /** 本轮进程内已入队过的曲目（防同一首歌被多条路径重复投递）。 */
    private static final Set<Long> QUEUED = ConcurrentHashMap.newKeySet();

    /** 失败退避：songId → {下次可试时刻 ms, 已失败次数}。 */
    private static final Map<Long, long[]> BACKOFF = new ConcurrentHashMap<>();

    /** 失败分类计数（{@code OK/NO_LYRIC/401/403/NET/PARSE} 为冻结六段，{@code AUTH/ENV} 追加在
     *  {@link #failClassText()} 行尾）——{@code stats()} 与判据行共用。 */
    private static final Map<String, LongAdder> CLASS = new ConcurrentHashMap<>();

    /** 命中的曲目（去重：同一首只记一行证据）。 */
    private static final Set<Long> HIT_IDS = ConcurrentHashMap.newKeySet();

    /**
     * 命中证据队列：{@code lrcFor}（宿主回调线程）只往里丢一个 {@code long[]{songId, 行数}}，
     * 由 {@code netease-lyric-hits} 守护线程取走落盘。队列写入无锁。
     */
    private static final ConcurrentLinkedQueue<long[]> HITQ = new ConcurrentLinkedQueue<>();

    /** 库内曲目总数（由 {@code svc.LyricWarmer} 读库后登记；0 = 还没读到）。 */
    private static final AtomicLong TOTAL = new AtomicLong();

    /** 磁盘缓存回读只做一次（{@link #scanDiskOnce()}）。 */
    private static final AtomicBoolean DISK_SCANNED = new AtomicBoolean();

    private static volatile ThreadPoolExecutor pool;
    private static volatile Thread hitFlusher;
    private static volatile Path dir;
    /** 第一条命中行的后缀（本会话首次），避免真机日志被刷爆。 */
    private static volatile String hitTag = "首";

    private LyricService() {
    }

    /** 插件启动时把缓存目录定下来（宿主数据目录还没就绪时会被忽略，后续懒解析兜底）。 */
    public static void init(Path dataDir) {
        try {
            Path d = (dataDir == null ? Path.of(".") : dataDir).resolve("lyric");
            try {
                Files.createDirectories(d);
            } catch (Throwable t) {
                PluginLog.d(TAG, "歌词缓存目录创建失败（已忽略）:: " + msg(t));
            }
            dir = d;
        } catch (Throwable t) {
            PluginLog.d(TAG, "歌词目录初始化失败（已忽略）:: " + msg(t));
        }
        scanDiskOnce();
    }

    /**
     * 启动时把<b>磁盘上已有的歌词缓存</b>读回内存索引（0.11.8 新增，自测 T3 抓出来的缺口）。
     *
     * <p><b>为什么必须有它</b>：{@link #lrcFor} 按合同只能是「纯内存只读」，而磁盘缓存只有在走
     * 抓取链（{@link #fetch}）时才会被 {@link #readDisk} 顺带读回。于是插件每次重启后，上一版写下的
     * 几百个 {@code <id>.lrc} 全部变成「内存里没有、但磁盘上有」的孤儿缓存 ⇒ 宿主再问起这些歌
     * 会一路走 {@code 未命中 → ensureAsync → 重新联网抓}，白白多花请求（正是 R13′ 要收敛的浪费）。</p>
     *
     * <p>一次扫描、只认纯数字文件名（插件自己写的命名），单文件读失败跳过；整件事跑在守护线程上，
     * 不阻塞插件初始化，也不与抓取线程争锁（走的是同一套 {@link ConcurrentHashMap} 写路径）。</p>
     *
     * <p><b>0.11.50</b>：同一次扫描也回读 {@code .none} 无词标记（进 {@link #NEGATIVE_ID}）——
     * 重启后确认无词的曲目不再被重查网络、也不再被 {@link #stats()} 算成「待处理」。</p>
     */
    private static void scanDiskOnce() {
        if (!DISK_SCANNED.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            int files = 0;
            int loaded = 0;
            long bytes = 0L;
            int neg = 0;
            int staleNeg = 0;
            try {
                Path d = dir();
                if (!Files.isDirectory(d)) {
                    return;
                }
                List<Long> ids = new ArrayList<>();
                List<Long> negIds = new ArrayList<>();
                try (var s = Files.list(d)) {
                    for (Path p : s.toList()) {
                        String n = p.getFileName().toString();
                        if (n.endsWith(NEG_SUFFIX)) {
                            long id = parseId(n.substring(0, n.length() - NEG_SUFFIX.length()));
                            if (id > 0L) {
                                negIds.add(id);
                            }
                            continue;
                        }
                        if (!n.endsWith(".lrc")) {
                            continue;
                        }
                        files++;
                        long id = parseId(n.substring(0, n.length() - ".lrc".length()));
                        if (id > 0L) {
                            ids.add(id);
                        }
                    }
                }
                for (Long id : ids) {
                    String text = readDisk(id);
                    if (text == null || text.isEmpty()) {
                        continue;
                    }
                    remember(id, "", null, null, text);
                    loaded++;
                    bytes += text.length();
                }
                for (Long id : negIds) {
                    if (BY_ID.containsKey(id)) {
                        clearNegativeMark(id);   // .lrc 与 .none 同时存在 ⇒ 正缓存胜出，标记没用
                        staleNeg++;
                        continue;
                    }
                    NEGATIVE_ID.put(id, Boolean.TRUE);
                    NEGATIVE_DISK.add(id);
                    neg++;
                }
            } catch (Throwable t2) {
                PluginLog.d(TAG, "歌词磁盘回读失败（已忽略）:: " + msg(t2));
            } finally {
                DISK_LATCH.countDown();
            }
            PluginLog.i(TAG, "歌词缓存回读：磁盘 " + files + " 个 .lrc → 内存索引 " + loaded
                    + " 首（就绪 " + BY_ID.size() + " / 无词标记 " + neg + " 条，剔除陈旧 "
                    + staleNeg + " 条 / 约 " + (bytes / 1024L) + " KB，目录 " + displayDir() + "）");
        }, "netease-lyric-scan");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ 取侧（任意线程，入队即返）

    /**
     * 全量预热入口（0.11.8 · C 轮实证修正）。
     *
     * <p><b>为什么不能复用 {@link #prefetch}</b>：{@code prefetch} 是「播放即抓」口径，内部走
     * {@code dispatch(..., warm=false)} ⇒ 冻结判据行 {@code 预热开跑 / 预热完成 / 失败分类}
     * <b>永远不会打印</b>（真机 C-cold 轮实证：04:33:39 只有 {@code [lyric-warm] 投递 2981 首}，
     * 之后 1383 个 {@code .lrc} 落盘、全程零汇总行，A11 的 {@code Tms} 无从取证）。
     * 全量预热与按需抓取只差一个口径开关，不该靠调用方猜。</p>
     *
     * <p>线程：调用方任意线程（只做一次列表拷贝 + 投递，立即返回）。</p>
     *
     * @param songs 整库待抓曲目（{@code LyricWarmer} 已滤掉磁盘上已就绪的）
     * @param urls  songId → 直链（可空；预热只需要 id，URL 仅用于内存索引）
     */
    public static void preheat(List<Dto.Song> songs, Map<Long, String> urls) {
        if (songs == null || songs.isEmpty()) {
            return;
        }
        try {
            dispatch(new ArrayList<>(songs), urls == null ? Map.of() : new LinkedHashMap<>(urls), true);
        } catch (Throwable t) {
            PluginLog.d(TAG, "歌词预热参数处理失败（已忽略）:: " + msg(t));
        }
    }

    /**
     * 异步预取这一批歌的歌词（在线播放取到直链之后立刻调用）。
     *
     * <p>流程：清洗（{@link LrcUtil#clean(String)}）→ 合并翻译/罗马音 → 写内存 + 落盘缓存。
     * <b>单首失败只记日志、绝不抛出</b>；本方法本身也绝不抛出，因为它被
     * {@code host.VoxzenBridge} 的网络线程调用，在那里抛异常会打断「点歌出声」。</p>
     *
     * <p>线程：调用方任意线程（只做一次列表拷贝 + 投递，立即返回）；真正的抓取在
     * {@link #WORKERS} 路守护线程上并行执行。</p>
     *
     * @param songs   本次待播/待注入的曲目（来自 {@code NeteaseApi} 的搜索结果或歌单详情）
     * @param urlById songId → 本次注入用的音频直链 URL；缺项表示这首没有直链（跳过 URL 索引）
     */
    public static void prefetch(List<Dto.Song> songs, Map<Long, String> urlById) {
        if (songs == null || songs.isEmpty()) {
            return;
        }
        try {
            List<Dto.Song> copy = new ArrayList<>(songs);
            Map<Long, String> urls = new LinkedHashMap<>();
            for (Dto.Song s : copy) {
                if (s == null || s.id() <= 0L) {
                    continue;
                }
                if (BY_ID.containsKey(s.id()) || NEGATIVE_ID.containsKey(s.id())
                        || inBackoff(s.id()) || QUEUED.contains(s.id())) {
                    continue;
                }
                String u = urlById == null ? null : urlById.get(s.id());
                urls.put(s.id(), u == null ? "" : u);
            }
            if (urls.isEmpty()) {
                return;
            }
            dispatch(copy, urls, false);
        } catch (Throwable t) {
            PluginLog.d(TAG, "歌词预取参数处理失败（已忽略）:: " + msg(t));
        }
    }

    /**
     * 单首同步抓取（0.11.0 新增入口，保留）：跑在 {@code svc.StreamPrefetcher} 的
     * {@code netease-prefetch-1} 线程上，和音频预取共用<b>同一个工作项</b>。
     *
     * <p>与 {@link #prefetch} 共用同一套内存/磁盘缓存，谁先到谁写，重复抓取被
     * {@link #readDisk} 挡掉。<b>失败只记日志、绝不抛</b>——调用方还要接着拉音频。</p>
     *
     * <p>线程：任意非宿主回调线程（会联网 + 写盘）。</p>
     *
     * @param songId  曲目 id；{@code <=0} 直接返回
     * @param url     音频直链 URL（宿主的 {@code path} 就是它）；{@code null} 表示只按 id/title 索引
     * @param title   曲名（可能为 null）
     * @param artist  歌手（可能为 null）
     */
    public static void prefetchOne(long songId, String url, String title, String artist) {
        if (songId <= 0L) {
            return;
        }
        if (BY_ID.containsKey(songId) || NEGATIVE_ID.containsKey(songId)) {
            return;                              // 内存里已经有了（命中或已知无歌词）
        }
        if (!QUEUED.add(songId)) {
            return;                              // 已入队
        }
        try {
            String u = url == null ? "" : url;
            long r = fetch(songId, u, title, artist);
            noteFirstFail(songId);
            if (r == Fetch.NO_LYRIC) {
                rememberNegative(songId, u, title, artist);
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "歌词抓取失败 id=" + songId + " :: " + msg(t));
        }
    }

    /**
     * 单首补抓（原生路线兜底）。
     *
     * <p>原生路线的曲目由宿主直接播放，插件的预取可能还没轮到这首歌；钩子未命中时投一张票，
     * 抓回来后写内存 + 落盘，<b>下一次</b>宿主再问就能命中。</p>
     *
     * <p>线程：宿主回调线程可安全调用 —— 只做两次 map 查表 + 一次线程池投递，
     * 不联网、不碰磁盘、不记日志、不抛出。</p>
     */
    public static void ensureAsync(long songId, String path, String title, String artist) {
        if (songId <= 0L || BY_ID.containsKey(songId) || NEGATIVE_ID.containsKey(songId)) {
            return;
        }
        if (inBackoff(songId) || !QUEUED.add(songId)) {
            return;
        }
        final String u = path == null ? "" : path;
        try {
            pool().execute(() -> {
                try {
                    long r = fetch(songId, u, title, artist);
                    noteFirstFail(songId);
                    if (r == Fetch.NO_LYRIC) {
                        rememberNegative(songId, u, title, artist);
                    }
                } catch (Throwable t) {
                    PluginLog.d(TAG, "歌词补抓失败 id=" + songId + " :: " + msg(t));
                }
            });
        } catch (Throwable ignored) {
            // 钩子线程上绝不抛、绝不记日志
        }
    }

    /**
     * 邻曲保温（0.11.8 新增，R14）：把宿主播放队列里「当前曲之后」的若干首提前入队抓词，
     * 换曲那一刻 {@link #lrcFor} 就能命中——用户不会看到「第一次播没词」。
     *
     * <p>队列来源与 {@code svc.RowCover} 的队列前瞻一致：{@code host.VoxzenBridge.queueTrackIds()}。
     * 队列 {@code length<=1}（未起播/单曲循环）时退化为「只保当前曲」，并在日志里写明
     * 队列不足这句诊断（D6 的「所属歌单下一首」由 W5 的 {@code svc.NextTrack} 提供，
     * 本类不越界实现，避免两处各写一份队列反射）。</p>
     *
     * <p>线程：任意非宿主回调线程（会读队列 + 投递，不联网不写盘）。本方法只入队即返。</p>
     *
     * @param currentSongId 当前曲目 id；{@code <=0} 时用 {@code StreamingAudioCache.current()}
     * @return 本次实际入队的曲目数
     */
    public static int warmNeighbors(long currentSongId) {
        try {
            long cur = currentSongId;
            if (cur <= 0L) {
                cur = StreamingAudioCache.current();
            }
            List<String> ids = null;
            try {
                ids = com.example.netease.host.VoxzenBridge.queueTrackIds();
            } catch (Throwable t) {
                PluginLog.d(TAG, "邻曲保温：读播放队列失败（忽略）:: " + msg(t));
            }
            if (ids == null || ids.size() < 2) {
                if (cur > 0L) {
                    ensureAsync(cur, "", null, null);
                }
                PluginLog.i(TAG, "邻曲保温：入队 0 首（播放队列仅 "
                        + (ids == null ? 0 : ids.size()) + " 首，无下一首可保）");
                return 0;
            }
            int idx = ids.indexOf("netease-" + cur);
            List<String> picked = new ArrayList<>(NEIGHBOR_LOOKAHEAD);
            for (int k = 0; k < ids.size() && picked.size() < NEIGHBOR_LOOKAHEAD; k++) {
                int i = (Math.max(0, idx) + 1 + k) % ids.size();
                String t = ids.get(i);
                if (t == null || !t.startsWith("netease-")) {
                    continue;
                }
                picked.add(t.substring("netease-".length()));
            }
            for (String p : picked) {
                long id = parseId(p);
                if (id > 0L) {
                    ensureAsync(id, "", null, null);
                }
            }
            // 0.11.12：同一批邻曲（含紧邻的上一首）顺带把「播放条 / 播放页成品图字节」预热进缓存 ——
            // 用户点「下一首 / 上一首」时封面要在换曲那一瞬间就有（报障 2026-10-01 18:37）。
            // 只取图入缓存、不投递（不越界改播放条状态），且不联网：图不在本地时 CoverStore 自己按唯一入口补。
            try {
                if (cur > 0L) {
                    PlaybarCover.prefetch(cur);
                }
                int n = ids.size();
                if (idx >= 0 && n > 1) {
                    int[] dirs = {-1, 1, -2, 2};
                    for (int d : dirs) {
                        long id = parseId(ids.get(((idx + d) % n + n) % n));
                        if (id > 0L) {
                            PlaybarCover.prefetch(id);
                        }
                    }
                }
            } catch (Throwable t) {
                PluginLog.d(TAG, "邻曲封面预热失败（忽略）:: " + msg(t));
            }
            if (!picked.isEmpty()) {
                PluginLog.i(TAG, "邻曲保温：入队 " + String.join(",", picked));
            }
            return picked.size();
        } catch (Throwable t) {
            PluginLog.d(TAG, "邻曲保温失败（忽略）:: " + msg(t));
            return 0;
        }
    }

    /** 上一支被保温的当前曲 id（换曲去重：宿主三个钩子会在同一首歌上连问）。 */
    private static final AtomicLong NEIGHBOR_LAST_ID = new AtomicLong(Long.MIN_VALUE);

    /** 换曲代号：去抖用——同一瞬间的连续换曲只让最后一代落地。 */
    private static final AtomicLong NEIGHBOR_GEN = new AtomicLong();

    /**
     * 换曲时给队列里的下一首保温（0.11.8 接线修复，R14 的<b>调用点</b>）。
     *
     * <p><b>为什么需要这个入口</b>：{@link #warmNeighbors(long)} 此前<b>全树无调用点</b>
     * ——判据行 {@code 邻曲保温：入队 <ids>}（J5）结构上永远打不出来，R14 是死的。
     * 这是个宿主回调线程安全的一层皮：只做「同曲去重 + 起一条短命守护线程」，
     * 真正的读队列/投递在后台线程里跑（{@code warmNeighbors} 自己会读队列）。</p>
     *
     * <p>去抖 250ms：宿主 {@code updateLyrics}/{@code onBeforeLoadLyrics}/{@code onAfterLoadLyrics}
     * 三个钩子在换曲那一刻会连着问同一首；快速连按下一首时只让最后一代跑。</p>
     *
     * @param songId 新当前曲 id；{@code <=0} 直接忽略（拿不到 id 时不做任何事）
     */
    public static void kickWarmNeighbors(long songId) {
        try {
            if (songId <= 0L) {
                return;
            }
            if (NEIGHBOR_LAST_ID.getAndSet(songId) == songId) {
                return;
            }
            final AtomicLong gen = NEIGHBOR_GEN;
            final long mine = gen.incrementAndGet();
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(250L);
                    if (gen.get() != mine) {
                        return;
                    }
                    warmNeighbors(songId);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (Throwable ignored) {
                    // 保温失败绝不能影响宿主播放
                }
            }, "netease-lyric-neighbor");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {
            // 同上：回调线程上绝不抛
        }
    }

    /**
     * 按「注入直链 URL → songId → title|artist → 归一化 title」四级优先级查内存缓存。
     *
     * <p><b>宿主回调线程会同步调用本方法</b>，所以它被刻意写死成「只读内存」：
     * 不联网、不碰磁盘、不取任何可能被别人持有的锁（各表都是 {@code ConcurrentHashMap}，
     * 单线程写、多线程读，读路径无监视器竞争）。</p>
     *
     * <p>命中的证据行 {@code [lyric] 命中 <songId> 行数=N} <b>不在本线程写盘</b>：
     * 只往无锁队列丢一个 {@code long[]}，由 {@code netease-lyric-hits} 守护线程落盘。
     * 未命中返回 {@code null}（宿主据此显示「暂无歌词」），<b>miss 不记日志</b>。</p>
     *
     * @param path   宿主给的音频路径；在线播放时即我们注入的网易云直链 URL（可能为 null）
     * @param title  标题（可能为 null）
     * @param artist 歌手（可能为 null）
     * @return 最终注入文本；未命中返回 null
     */
    public static String lrcFor(String path, String title, String artist) {
        if (path != null && !path.isEmpty()) {
            if (NEGATIVE.containsKey(path)) {
                return null;
            }
            String v = BY_URL.get(path);
            if (v != null) {
                hit(P_URL + path);
                noteHit(songIdOf(path), v);
                return v;
            }
        }
        long sid = songIdOf(path);
        if (sid > 0L) {
            if (NEGATIVE_ID.containsKey(sid)) {
                return null;
            }
            String v = BY_ID.get(sid);
            if (v != null) {
                hit(P_ID + sid);
                noteHit(sid, v);
                return v;
            }
        }
        String t = title == null ? "" : title;
        String a = artist == null ? "" : artist;
        if (!t.isEmpty() || !a.isEmpty()) {
            String k = key(t, a);
            if (NEGATIVE.containsKey(k)) {
                return null;
            }
            String v = BY_KEY.get(k);
            if (v != null) {
                hit(P_KEY + k);
                noteHit(sid, v);
                return v;
            }
        }
        if (!t.isEmpty()) {
            String nt = normTitle(t);
            String v = BY_TITLE.get(nt);
            if (v != null) {
                hit(P_TITLE + nt);
                noteHit(sid, v);
                return v;
            }
        }
        return null;
    }

    /** 从 {@code path} 里抠 songId：{@code http://127.0.0.1:17788/netease/1904261851} → {@code 1904261851}。 */
    public static long songIdOf(String path) {
        if (path == null) {
            return -1L;
        }
        int i = path.indexOf("/netease/");
        if (i < 0) {
            return -1L;
        }
        int p = i + 9;
        long v = 0L;
        int n = 0;
        while (p < path.length() && n < 15) {
            char c = path.charAt(p);
            if (c < '0' || c > '9') {
                break;
            }
            v = v * 10L + (c - '0');
            n++;
            p++;
        }
        return n > 0 ? v : -1L;
    }

    /**
     * 清空内存缓存与磁盘缓存（配置页「清空歌词缓存」入口用）。
     *
     * <p>线程：任意非宿主回调线程（会遍历目录删文件）。</p>
     */
    public static void clearCache() {
        BY_URL.clear();
        BY_KEY.clear();
        BY_TITLE.clear();
        BY_ID.clear();
        NEGATIVE.clear();
        NEGATIVE_ID.clear();
        NEGATIVE_DISK.clear();
        HITS.clear();
        SONG_KEYS.clear();
        TITLE_OWNER.clear();
        QUEUED.clear();
        BACKOFF.clear();
        HIT_IDS.clear();
        CLASS.clear();
        int files = 0;
        try {
            Path d = dir();
            if (Files.isDirectory(d)) {
                try (var stream = Files.list(d)) {
                    for (Path p : stream.toList()) {
                        String n = p.getFileName().toString();
                        if (n.endsWith(".lrc") || n.endsWith(".tmp")
                                || n.endsWith(NEG_SUFFIX) || n.endsWith(NEG_SUFFIX + ".tmp")) {
                            try {
                                if (Files.deleteIfExists(p)) {
                                    files++;
                                }
                            } catch (Throwable ignored) {
                                // 单个文件删不掉不影响其它文件，也不值得打断调用方
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "歌词缓存清理失败（已忽略）:: " + msg(t));
        }
        PluginLog.i(TAG, "歌词缓存已清空：磁盘删除 " + files + " 个文件（目录 " + displayDir() + "）");
    }

    /** 内存正缓存条数（只统计 URL 索引，即「真正能按直链注入的曲目数」；0 GB 缓存下可能为 0）。 */
    public static int cachedCount() {
        return BY_URL.size();
    }

    /**
     * 缓存现状一行摘要（配置页/自检用，0.11.8 改为<b>完成度三态</b>口径）。
     *
     * <p>「待处理」= 库内曲目总数（由 {@code svc.LyricWarmer} 读库后登记）− 已就绪 − 无歌词。
     * 总数还没读到时报 {@code ?}，<b>绝不为了这句话去连库</b>（配置页可能在任何线程上刷新）。</p>
     */
    public static String stats() {
        long total = TOTAL.get();
        long ready = BY_ID.size();
        long no = NEGATIVE_ID.size();
        long pending = total > 0L ? Math.max(0L, total - ready - no) : -1L;
        return String.format(Locale.ROOT,
                "歌词完成度：已就绪 %d / 待处理 %s / 无歌词 %d / 失败 %d（%s）｜内存索引 曲名 %d / 归一化 %d"
                        + " / 直链 %d｜目录 %s",
                ready, pending < 0L ? "?" : Long.toString(pending), no, failCount(), failClassText(),
                BY_KEY.size(), BY_TITLE.size(), BY_URL.size(), displayDir());
    }

    /**
     * 失败分类一行（{@code stats()} 与「失败分类」判据行共用同一份口径）。
     *
     * <p><b>冻结词形</b>是 {@code 401=a 403=b NO_LYRIC=c NET=d PARSE=e}（Lead 冻结的判据行，见
     * {@code docs/51-W4歌词层验证.md} §6.2 J3）。0.11.8 新增的两类一律<b>追加在行尾</b>：
     * {@code AUTH=g}（需要登录 / 凭据失效）与 {@code ENV=h}（环境级错误，见 §4.5 的精简 JRE 缺陷）。</p>
     *
     * <p><b>追加而非改写</b>：冻结的前五段词形逐字不动，Lead 既有的 grep 判据照旧命中。特别注意
     * {@code NO_LYRIC=c NET=d} 必须保持相邻——{@code AUTH} 绝不能插在它们中间，否则
     * {@code NO_LYRIC=c NET=d} 这段字面判据当场失配（这正是本行唯一允许的变化形态：
     * 尾部追加，中间不动）。</p>
     */
    public static String failClassText() {
        return String.format(Locale.ROOT, "401=%d 403=%d NO_LYRIC=%d NET=%d PARSE=%d AUTH=%d ENV=%d",
                sum(C_AUTH), sum(C_RISK), sum(C_NO_LYRIC), sum(C_NET), sum(C_PARSE), sum(C_AUTHN),
                sum(C_ENV));
    }

    /** 失败总数（风控 + 鉴权 + 网络 + 解析 + 环境；不含「确认无歌词」）。 */
    public static long failCount() {
        return sum(C_AUTH) + sum(C_RISK) + sum(C_AUTHN) + sum(C_NET) + sum(C_PARSE) + sum(C_ENV);
    }

    /** 预热目标曲目总数登记（由 {@code svc.LyricWarmer} 读库后调用）。 */
    public static void noteTotal(long total) {
        if (total > 0L) {
            TOTAL.set(total);
        }
    }

    /** 该曲目是否已确认无歌词（含 0.11.50 起的 {@code .none} 标记回读）；{@code svc.LyricWarmer} 用它免重投。 */
    public static boolean knownNoLyric(long songId) {
        return NEGATIVE_ID.containsKey(songId);
    }

    /** 等磁盘回读（.lrc + .none）完成，最多 {@code ms} 毫秒；消「预热投递」与「标记回读」的开机竞态。 */
    public static void awaitDiskScan(long ms) {
        try {
            DISK_LATCH.await(Math.max(0L, ms), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ 抓取分发

    /**
     * 并行抓取分发：任务用「原子游标接力」而不是「一次提交 N 个」。
     *
     * <p>3267 个任务一次性塞进队列会让「用户刚点的那首」排到队尾，而接力分发最多只积压
     * {@code 工作线程数} 个未开工项。</p>
     *
     * @param songs 候选曲目
     * @param urls  songId → 直链（可空串）
     * @param warm  {@code true} = 全量预热口径（打冻结判据行）；{@code false} = 播放即抓口径
     */
    private static void dispatch(List<Dto.Song> songs, Map<Long, String> urls, boolean warm) {
        List<Object[]> jobs = new ArrayList<>();
        for (Dto.Song s : songs) {
            if (s == null || s.id() <= 0L) {
                continue;
            }
            long id = s.id();
            if (BY_ID.containsKey(id) || NEGATIVE_ID.containsKey(id) || inBackoff(id)) {
                continue;
            }
            if (!QUEUED.add(id)) {
                continue;
            }
            String u = urls.get(id);
            jobs.add(new Object[]{id, u == null ? "" : u, s.name(), s.artists()});
        }
        if (jobs.isEmpty()) {
            return;
        }
        if (warm) {
            PluginLog.i(TAG, "预热开跑：计划 " + jobs.size() + " 首（并发 " + WORKERS + "）");
        }
        final AtomicInteger cursor = new AtomicInteger();
        final LongAdder ok = new LongAdder();
        final LongAdder no = new LongAdder();
        final LongAdder fail = new LongAdder();
        final long t0 = System.currentTimeMillis();
        final int total = jobs.size();
        try {
            ThreadPoolExecutor ex = pool();
            int n = Math.min(WORKERS, total);
            for (int w = 0; w < n; w++) {
                ex.execute(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        int i = cursor.getAndIncrement();
                        if (i >= jobs.size()) {
                            return;
                        }
                        Object[] j = jobs.get(i);
                        long id = (Long) j[0];
                        String u = (String) j[1];
                        String name = (String) j[2];
                        String artists = (String) j[3];
                        try {
                            long r = fetch(id, u, name, artists);
                            noteFirstFail(id);
                            if (r == Fetch.OK) {
                                ok.increment();
                            } else if (r == Fetch.NO_LYRIC) {
                                no.increment();
                                rememberNegative(id, u, name, artists);
                            } else {
                                fail.increment();
                            }
                        } catch (Throwable t) {
                            fail.increment();
                            classify(C_PARSE);
                            backoff(id, false);
                            PluginLog.w(TAG, "歌词抓取异常 id=" + id + " :: " + msg(t));
                        }
                    }
                });
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "歌词抓取投递失败（已忽略）:: " + msg(t));
            for (Object[] j : jobs) {
                QUEUED.remove((Long) j[0]);
            }
            return;
        }
        if (!warm) {
            // 播放即抓口径：不打冻结判据行（那是全量预热的），但失败必须留痕
            final long budget = 30_000L;
            startCloser("netease-lyric-close-od", cursor, total, budget, () -> {
                if (fail.sum() > 0L) {
                    PluginLog.w(TAG, "歌词按需抓取：就绪 " + ok.sum() + " / 无词 " + no.sum()
                            + " / 失败 " + fail.sum() + "（共 " + total + " 首）｜" + failClassText());
                }
            });
            return;
        }
        startCloser("netease-lyric-close", cursor, total, 15L * 60_000L, () -> {
            long ms = System.currentTimeMillis() - t0;
            double rate = ms <= 0L ? total : (total * 1000.0 / ms);
            PluginLog.i(TAG, "预热完成：就绪 " + ok.sum() + " / 无词 " + no.sum() + " / 失败 " + fail.sum()
                    + "（共 " + total + " 首，" + ms + "ms，rate="
                    + String.format(Locale.ROOT, "%.1f", rate) + "/s）");
            PluginLog.i(TAG, "失败分类：" + failClassText());
        });
    }

    /** 收口线程：等游标跑完再打汇总行（带兜底时限，绝不无限挂住）。 */
    private static void startCloser(String name, AtomicInteger cursor, int total, long budgetMs, Runnable done) {
        Thread closer = new Thread(() -> {
            long deadline = System.currentTimeMillis() + budgetMs;
            while (cursor.get() < total && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                done.run();
            } catch (Throwable ignored) {
                // 汇总行写不出来不该影响抓取
            }
        }, name);
        closer.setDaemon(true);
        closer.start();
    }

    // ------------------------------------------------------------------ 单首抓取

    /** {@link #fetch} 的返回码。 */
    private static final class Fetch {
        static final long OK = 1L;
        static final long NO_LYRIC = 2L;
        static final long FAIL = 0L;
    }

    /**
     * 单首抓取：先吃磁盘缓存（重启后免联网），再走本类直连（每曲一次请求）；
     * 直连整条链拿不到任何文本时才兜底 {@link NeteaseApi#lyric(long)}（最坏 4 次 HTTP）。
     *
     * @return {@link Fetch#OK} / {@link Fetch#NO_LYRIC} / {@link Fetch#FAIL}（失败已计数与退避）
     */
    private static long fetch(long songId, String url, String name, String artists) {
        if (NEGATIVE_ID.containsKey(songId)) {
            return Fetch.NO_LYRIC;               // 标记回读/本轮已判无词：联网重验没有意义（清缓存可强制重查）
        }
        String cached = readDisk(songId);
        if (cached != null) {
            remember(songId, url, name, artists, cached);
            return Fetch.OK;
        }
        LyricData d = fetchDirect(songId);
        boolean envError = d.isEnvError();
        if (d.isEmptyText() && d.getHttpCode() != 401 && d.getHttpCode() != 403) {
            // 直连没拿到任何文本（网络/版本差异/接口改版）⇒ 兜底走 net.Http（含重试与诊断）
            d = fetchFallback(songId, d);
            if (envError && d.isEmptyText()) {
                d.setEnvError(true);             // 兜底也没救回来 ⇒ 环境级错误的判定保留
            }
        }
        int code = d.getHttpCode();
        if (code == 401 || code == 403 || code == 412 || code == 429) {
            classify(code == 401 ? C_AUTH : C_RISK);
            backoff(songId, true);
            PluginLog.w(TAG, "歌词接口被拦：HTTP " + code + " id=" + songId
                    + "，退避 " + RiskControl.human(backoffMs(songId)));
            return Fetch.FAIL;
        }
        if (d.isEnvError()) {
            // 环境级错误：要么代码在本 JVM 上不可用（LinkageError），要么兜底接口也返回同样的错。
            // 一律 ERROR 级别 —— 它意味着「整条抓取链在当前宿主上不工作」，不许被 WARN 糊成网络波动。
            classify(C_ENV);
            backoff(songId, false);
            PluginLog.e(TAG, "歌词请求遇环境级错误 id=" + songId + " :: " + d.getErrorText()
                    + "（该错误与网络无关：代码在宿主 JVM 上不可用或接口签名不匹配，重试同一路径无意义）");
            return Fetch.FAIL;
        }
        if (d.getError() != 0) {
            classify(C_NET);
            backoff(songId, false);
            PluginLog.w(TAG, "歌词请求失败 id=" + songId + " :: " + d.getErrorText()
                    + "，退避 " + RiskControl.human(backoffMs(songId)));
            return Fetch.FAIL;
        }
        String main;
        try {
            main = LrcUtil.clean(d.getMain());
        } catch (Throwable t) {
            classify(C_PARSE);
            backoff(songId, false);
            PluginLog.w(TAG, "歌词清洗失败 id=" + songId + " :: " + msg(t));
            return Fetch.FAIL;
        }
        if (main == null) {
            classify(C_NO_LYRIC);
            return Fetch.NO_LYRIC;               // 纯音乐 / 无歌词：绝不写空文件
        }
        String text;
        try {
            // 只在真的拿到罗马音时才合并罗马音（没有就跳过，避免多打一次 HTTP）
            text = LrcUtil.merge(main, d.getTranslated(), d.getRomaji(), true, d.getRomaji() != null);
        } catch (Throwable t) {
            classify(C_PARSE);
            backoff(songId, false);
            PluginLog.w(TAG, "歌词合并失败 id=" + songId + " :: " + msg(t));
            return Fetch.FAIL;
        }
        if (text == null || text.isBlank()) {
            classify(C_NO_LYRIC);
            return Fetch.NO_LYRIC;
        }
        remember(songId, url, name, artists, text);
        writeDisk(songId, text);
        classify(C_OK);
        clearBackoff(songId);
        return Fetch.OK;
    }

    /**
     * 直连打一次 {@code /api/song/lyric} 并把主词/翻译/罗马音一起取回。
     *
     * <p><b>为什么用手写 {@code HttpURLConnection} 而不是 {@code java.net.http.HttpClient}</b>
     * （0.11.8 真机实证，B2 轮 14 条同源日志）：
     * {@code 02:05:07 [WARN] [lyric] 歌词请求失败 id=3387052195 :: NoClassDefFoundError:
     * java/net/http/HttpRequest，退避 2.0s} —— 宿主 Salt Player 的精简 JRE <b>没有 {@code java.net.http}
     * 模块</b>。用 {@code HttpClient} 的代价不是「慢一点」，而是<b>一首也抓不到</b>，且异常会被
     * 误当作网络抖动进 {@link RiskControl#netBackoffMs(int)} 短退避，重试也只是重放同一个
     * {@code NoClassDefFoundError}。{@code net/Http.java} 本来就是 {@code HttpURLConnection}，
     * 宿主里只有它能用。</p>
     *
     * <p><b>为什么不用 {@code net.Http}</b>：那道 300ms 全局节流是给宿主界面交互留的，
     * 预热 3267 首挂在它后面就是 16 分钟起步（R15 要求速度优先、并发拉满）。所以这里自建直连：
     * 每个工作线程各开各的连接（{@code HttpURLConnection} 天然线程安全，只要不复用同一个实例）。</p>
     */
    private static LyricData fetchDirect(long songId) {
        LyricData d = new LyricData();
        String url = LYRIC_API + "?os=pc&id=" + songId + "&lv=-1&kv=-1&tv=-1";
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(RiskControl.CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(RiskControl.READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", "https://music.163.com");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Encoding", "identity");
            String ck = cookieHeader();
            if (ck != null && !ck.isEmpty()) {
                conn.setRequestProperty("Cookie", ck);
            }
            int code = conn.getResponseCode();
            d.setHttpCode(code);
            if (code != 200) {
                d.setError(code);
                d.setErrorText("HTTP " + code);
                return d;
            }
            d.setError(0);
            d.setErrorText("");
            ingest(d, readText(conn, MAX_RESP_BYTES));
        } catch (Throwable t) {
            if (isEnvError(t)) {
                d.setEnvError(true);
            }
            d.setError(-1);
            d.setErrorText(describe(t));
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                    // 关连接失败与业务无关
                }
            }
        }
        return d;
    }

    /** 读全响应体（设了 {@code Accept-Encoding: identity}，所以按明文 UTF-8 读；超限即截断报错）。 */
    private static String readText(HttpURLConnection conn, int maxBytes) throws IOException {
        try (InputStream in = conn.getInputStream()) {
            byte[] buf = new byte[8192];
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
            int n;
            while ((n = in.read(buf)) > 0) {
                if (bos.size() + n > maxBytes) {
                    throw new IOException("应答超长（>" + maxBytes + "B）");
                }
                bos.write(buf, 0, n);
            }
            return bos.toString(StandardCharsets.UTF_8);
        }
    }

    /** 兜底：{@link NeteaseApi#lyric(long)}（会经 {@code net.Http} 的节流与重试）。 */
    private static LyricData fetchFallback(long songId, LyricData d) {
        try {
            Dto.Lyric ly = NeteaseApi.lyric(songId);
            if (ly != null) {
                d.setMain(ly.lrc());
                d.setTranslated(ly.translated());
                d.setRomaji(ly.romaji());
            }
            d.setFallback(true);
            if (!d.isEmptyText()) {
                d.setError(0);                   // 兜底拿到了正文 ⇒ 不算失败
                d.setErrorText("");
            }
        } catch (Throwable t) {
            d.setError(-1);
            d.setErrorText("兜底接口失败：" + msg(t));
        }
        return d;
    }

    /** 解析歌词应答（老接口把 {@code tlyric}/{@code romalrc} 放在与 {@code lrc} 同级）。 */
    private static void ingest(LyricData d, String body) {
        if (body == null || body.isEmpty()) {
            d.setError(-1);
            d.setErrorText("空应答");
            return;
        }
        if (body.length() > MAX_RESP_BYTES) {
            d.setError(-1);
            d.setErrorText("应答超长 " + body.length() + "B");
            return;
        }
        Object node;
        try {
            node = Json.parse(body);
        } catch (Throwable t) {
            d.setError(-1);
            d.setErrorText("JSON 解析失败：" + msg(t));
            return;
        }
        int code = Json.integer(node, "code", 0);
        if (code == 401 || code == 403 || code == 412 || code == 429) {
            d.setHttpCode(code);
            d.setError(code);
            d.setErrorText("接口返回 code=" + code);
            return;
        }
        Object inner = Json.get(node, "data");
        Object scope = inner instanceof Map ? inner : node;
        for (String f : LYRIC_CONTAINERS) {
            String v = trimToNull(Json.str(scope, f + ".lyric", null));
            if (v != null) {
                d.setMain(v);
                break;
            }
        }
        String tr = trimToNull(Json.str(scope, "tlyric.lyric", null));
        String ro = trimToNull(Json.str(scope, "romalrc.lyric", null));
        if (inner instanceof Map) {
            if (tr == null) {
                tr = trimToNull(Json.str(node, "tlyric.lyric", null));
            }
            if (ro == null) {
                ro = trimToNull(Json.str(node, "romalrc.lyric", null));
            }
        }
        d.setTranslated(tr);
        d.setRomaji(ro);
    }

    /** 直连用的 cookie（登录态由 W1 账号层维护；这里只取公开出口，取不到就匿名）。 */
    private static String cookieHeader() {
        try {
            String c = NeteaseApi.exportCookies();
            return c == null ? "" : c;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 直连应答的载体（避免回传一堆参数）。 */
    private static final class LyricData {
        private String main;
        private String translated;
        private String romaji;
        private int httpCode;
        private int error;
        private String errorText = "";
        private boolean fallback;

        String getMain() {
            return main;
        }

        void setMain(String v) {
            main = v;
        }

        String getTranslated() {
            return translated;
        }

        void setTranslated(String v) {
            translated = v;
        }

        String getRomaji() {
            return romaji;
        }

        void setRomaji(String v) {
            romaji = v;
        }

        int getHttpCode() {
            return httpCode;
        }

        void setHttpCode(int v) {
            httpCode = v;
        }

        int getError() {
            return error;
        }

        void setError(int v) {
            error = v;
        }

        String getErrorText() {
            return errorText;
        }

        void setErrorText(String v) {
            errorText = v;
        }

        /** 环境级错误（{@link LinkageError} 家族）：代码在本 JVM 里不可用，重试无意义。 */
        private boolean envError;

        boolean isFallback() {
            return fallback;
        }

        boolean isEnvError() {
            return envError;
        }

        void setEnvError(boolean v) {
            envError = v;
        }

        void setFallback(boolean v) {
            fallback = v;
        }

        boolean isEmptyText() {
            return main == null && translated == null && romaji == null;
        }
    }

    // ------------------------------------------------------------------ 失败分类与退避

    private static void classify(String kind) {
        CLASS.computeIfAbsent(kind, k -> new LongAdder()).increment();
    }

    private static long sum(String kind) {
        LongAdder a = CLASS.get(kind);
        return a == null ? 0L : a.sum();
    }

    /** 退避：风控（含 401）走 {@link RiskControl#riskBackoffMs}，其余走 {@code netBackoffMs}。 */
    private static void backoff(long songId, boolean risk) {
        long[] v = BACKOFF.computeIfAbsent(songId, k -> new long[]{0L, 0L});
        int attempt;
        synchronized (v) {
            v[1] = v[1] + 1L;
            attempt = (int) v[1];
            long ms = risk ? RiskControl.riskBackoffMs(attempt) : RiskControl.netBackoffMs(attempt);
            v[0] = System.currentTimeMillis() + ms;
        }
        QUEUED.remove(songId);                   // 退避结束后允许重新入队
    }

    private static void clearBackoff(long songId) {
        BACKOFF.remove(songId);
    }

    private static boolean inBackoff(long songId) {
        long[] v = BACKOFF.get(songId);
        if (v == null) {
            return false;
        }
        synchronized (v) {
            return System.currentTimeMillis() < v[0];
        }
    }

    private static long backoffMs(long songId) {
        long[] v = BACKOFF.get(songId);
        if (v == null) {
            return 0L;
        }
        synchronized (v) {
            return Math.max(0L, v[0] - System.currentTimeMillis());
        }
    }

    /** 失败第一次发生时让「已入队」标记复位，否则退避结束也永不重试。 */
    private static void noteFirstFail(long songId) {
        if (QUEUED.contains(songId) && BACKOFF.containsKey(songId)) {
            QUEUED.remove(songId);
        }
    }

    // ------------------------------------------------------------------ 内存缓存

    /** 把一份可用文本登记进索引表。只在抓取线程调用。 */
    private static void remember(long songId, String url, String name, String artists, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String k = key(name, artists);
        String nt = normTitle(name);
        if (songId > 0L) {
            BY_ID.put(songId, text);
            NEGATIVE_ID.remove(songId);
            if (NEGATIVE_DISK.contains(songId)) {
                clearNegativeMark(songId);       // 已有正式歌词 ⇒ 撤掉无词标记
            }
            touch(P_ID + songId);
            SONG_KEYS.put(songId, new String[]{url == null ? "" : url, k, nt});
        }
        if (url != null && !url.isEmpty()) {
            BY_URL.put(url, text);
            touch(P_URL + url);
        }
        if (!k.isEmpty()) {
            BY_KEY.put(k, text);
            touch(P_KEY + k);
        }
        if (!nt.isEmpty() && songId > 0L) {
            BY_TITLE.put(nt, text);
            touch(P_TITLE + nt);
            TITLE_OWNER.put(nt, songId);
        }
        if (url != null && !url.isEmpty()) {
            NEGATIVE.remove(url);
        }
        if (!k.isEmpty()) {
            NEGATIVE.remove(k);
        }
        trimIfHuge();
    }

    /** 记住「这首没歌词」。只在抓取线程调用。 */
    private static void rememberNegative(long songId, String url, String name, String artists) {
        if (songId > 0L) {
            NEGATIVE_ID.put(songId, Boolean.TRUE);
            markNegativeDisk(songId);            // 0.11.50：落盘标记，重启后仍算「无歌词」
        }
        if (url != null && !url.isEmpty()) {
            NEGATIVE.put(url, Boolean.TRUE);
        }
        String k = key(name, artists);
        if (!k.isEmpty()) {
            NEGATIVE.put(k, Boolean.TRUE);
        }
        if (NEGATIVE.size() > MAX_NEGATIVE) {
            dropOldest(NEGATIVE, MAX_NEGATIVE / 2);   // 部分淘汰：全清会让正在播的歌又去联网
        }
        if (NEGATIVE_ID.size() > MAX_NEGATIVE) {
            trimNegativeIds();
        }
    }

    /** 负缓存部分淘汰：丢最早插入的一半（{@code ConcurrentHashMap} 迭代序近似插入序）。 */
    private static <T> void dropOldest(Map<T, Boolean> m, int drop) {
        if (drop <= 0) {
            return;
        }
        int i = 0;
        for (T k : m.keySet()) {
            if (i++ >= drop) {
                break;
            }
            m.remove(k);
        }
    }

    /**
     * 内存封顶（0.11.8：<b>按曲目级联淘汰</b>）。
     *
     * <p>0.10.x 是「任一张表超限就把四张表整体清空」：一次清空的代价是接下来几首歌全部回落成
     * 「未命中 → 重新联网抓」。0.11.0 改成按访问计数丢 1/4，但<b>四张表各自淘汰</b>——
     * 同一首歌可能出现「URL 索引还在、id 索引已被丢」的错位。现在按曲目淘汰：同一个
     * {@code songId} 的 URL/键/标题/id 条目与访问计数一起走，冷门曲目丢掉、热门曲目留得住。</p>
     *
     * <p><b>容量口径（0.11.8 定稿）</b>：{@link #MAX_MEM_ENTRIES} 取 4096 &gt; 真机库曲目数（3267），
     * 所以「整库歌词都在内存里」也不会触发淘汰。这条是 {@link #scanDiskOnce()} 带来的：
     * 磁盘上有 {@code <id>.lrc} 的曲目现在会被全部读回内存，若封顶仍是 2000，就会把刚读回来的
     * 冷门曲目当场淘汰掉——白读一遍，而且下一首播到它时又要多一轮「未命中 → 补抓」。</p>
     */
    private static void trimIfHuge() {
        if (BY_ID.size() <= MAX_MEM_ENTRIES) {
            return;
        }
        int drop = Math.max(1, MAX_MEM_ENTRIES / TRIM_DROP_RATIO);
        List<Long> ids = new ArrayList<>(BY_ID.keySet());
        ids.sort(Comparator.comparingLong(LyricService::songHeat));
        int dropped = 0;
        for (Long id : ids) {
            if (dropped >= drop) {
                break;
            }
            evict(id);
            dropped++;
        }
        int more = 0;
        for (Long id : new ArrayList<>(SONG_KEYS.keySet())) {
            if (more++ >= drop) {
                break;
            }
            evict(id);
        }
    }

    /** 一首歌的「热度」：四张索引表的访问计数之和（只在写线程上跑）。 */
    private static long songHeat(Long id) {
        String[] keys = SONG_KEYS.get(id);
        if (keys == null) {
            return count(P_ID + id);
        }
        long n = count(P_ID + id);
        if (keys[0] != null && !keys[0].isEmpty()) {
            n += count(P_URL + keys[0]);
        }
        if (keys[1] != null && !keys[1].isEmpty()) {
            n += count(P_KEY + keys[1]);
        }
        if (keys[2] != null && !keys[2].isEmpty()) {
            n += count(P_TITLE + keys[2]);
        }
        return n;
    }

    /** 级联淘汰一首：四张索引表 + 访问计数 + 曲目登记全部清掉。 */
    private static void evict(long id) {
        String[] keys = SONG_KEYS.remove(id);
        BY_ID.remove(id);
        HITS.remove(P_ID + id);
        if (keys == null) {
            return;
        }
        if (keys[0] != null && !keys[0].isEmpty()) {
            BY_URL.remove(keys[0]);
            HITS.remove(P_URL + keys[0]);
        }
        if (keys[1] != null && !keys[1].isEmpty()) {
            BY_KEY.remove(keys[1]);
            HITS.remove(P_KEY + keys[1]);
        }
        if (keys[2] != null && !keys[2].isEmpty()) {
            Long owner = TITLE_OWNER.get(keys[2]);
            if (owner != null && owner == id) {
                BY_TITLE.remove(keys[2]);
                TITLE_OWNER.remove(keys[2]);
                HITS.remove(P_TITLE + keys[2]);
            }
        }
    }

    /** 写路径预建计数对象（读路径因此不需要 {@code computeIfAbsent}，不会在宿主线程上取锁）。 */
    private static void touch(String counterKey) {
        HITS.computeIfAbsent(counterKey, x -> new LongAdder());
    }

    /** 读路径命中计数：无锁、不分配。 */
    private static void hit(String counterKey) {
        LongAdder a = HITS.get(counterKey);
        if (a != null) {
            a.increment();
        }
    }

    private static long count(String counterKey) {
        LongAdder a = HITS.get(counterKey);
        return a == null ? 0L : a.sum();
    }

    /** 查表键：「title|artist」小写、去全部空白。预取与命中两侧必须用同一把尺子。 */
    private static String key(String title, String artist) {
        String t = stripSpace(title);
        String a = stripSpace(artist);
        if (t.isEmpty() && a.isEmpty()) {
            return "";
        }
        return (t + "|" + a).toLowerCase(Locale.ROOT);
    }

    /**
     * 归一化标题：小写、去空白、砍掉括号后缀。
     *
     * <p>砍后缀是为了兜住「歌名 (Live)」「歌名（伴奏）」这类宿主显示名与接口名的偏差；
     * 若标题本身以括号开头则原样保留（不然会归一化成空串）。</p>
     */
    private static String normTitle(String title) {
        String t = stripSpace(title).toLowerCase(Locale.ROOT);
        int cut = t.length();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '(' || c == '[' || c == '（' || c == '【') {
                cut = i;
                break;
            }
        }
        return cut > 0 ? t.substring(0, cut) : t;
    }

    private static String stripSpace(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static long parseId(String s) {
        if (s == null || s.isEmpty()) {
            return -1L;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return -1L;
        }
    }

    // ------------------------------------------------------------------ 命中证据（回调线程只入队）

    /**
     * 命中证据行：{@code lrcFor} 里只做「查重 + 入队」，<b>不写盘、不取锁</b>。
     *
     * <p>行文本由 {@code netease-lyric-hits} 守护线程拼装落盘，形如
     * {@code [lyric] 命中 4885597 行数=92}。同一首歌只记一次（{@link #HIT_IDS}）。</p>
     */
    private static void noteHit(long songId, String text) {
        try {
            if (songId <= 0L || HITQ.size() >= MAX_HIT_QUEUE || !HIT_IDS.add(songId)) {
                return;
            }
            int lines = 1;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    lines++;
                }
            }
            HITQ.add(new long[]{songId, lines});
            startHitFlusher();
        } catch (Throwable ignored) {
            // 回调线程上绝不抛
        }
    }

    private static void startHitFlusher() {
        if (hitFlusher != null) {
            return;
        }
        synchronized (LyricService.class) {
            if (hitFlusher != null) {
                return;
            }
            Thread t = new Thread(() -> {
                while (true) {
                    try {
                        long[] e = HITQ.poll();
                        if (e == null) {
                            Thread.sleep(300L);
                            continue;
                        }
                        PluginLog.i(TAG, "命中 " + e[0] + " 行数=" + e[1] + "（" + hitTag + "）");
                        hitTag = "回调线程命中";
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Throwable ignored) {
                        // 诊断行写不出来不该影响播放
                    }
                }
            }, "netease-lyric-hits");
            t.setDaemon(true);
            hitFlusher = t;
            t.start();
        }
    }

    // ------------------------------------------------------------------ 磁盘缓存

    /** 磁盘缓存目录；懒解析一次后缓存。只在抓取线程/清理路径调用。 */
    private static Path dir() {
        Path d = dir;
        if (d != null) {
            return d;
        }
        synchronized (LyricService.class) {
            if (dir == null) {
                dir = DataPaths.data().resolve("lyric");
            }
            return dir;
        }
    }

    private static String displayDir() {
        try {
            return dir().toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 读磁盘缓存；不存在/空/超限/读失败一律返回 null（调用方转去联网）。 */
    private static String readDisk(long songId) {
        try {
            Path f = dir().resolve(songId + ".lrc");
            if (!Files.isRegularFile(f)) {
                return null;
            }
            if (Files.size(f) > MAX_FILE_BYTES) {
                return null;
            }
            String s = Files.readString(f, StandardCharsets.UTF_8);
            return s.isBlank() ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 写磁盘缓存（临时文件 + 原子替换）。<b>空内容直接返回，绝不落盘。</b> */
    private static void writeDisk(long songId, String text) {
        if (songId <= 0L || text == null || text.isEmpty()) {
            return;
        }
        try {
            Path d = dir();
            Files.createDirectories(d);
            Path tmp = d.resolve(songId + ".lrc.tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, d.resolve(songId + ".lrc"), StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable t) {
                Files.deleteIfExists(tmp);
                throw t;
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "歌词落盘失败 id=" + songId + " :: " + msg(t));
        }
    }

    /**
     * 把「已确认无歌词」落成 {@code <id>.none} 标记（0.11.50；临时文件 + 原子替换）。
     *
     * <p>为什么不写空 {@code .lrc}：宿主把空文本当「清空歌词」显示 —— 红线「空内容绝不落盘」
     * 不变。标记内容是一行 {@code no_lyric <epochMs>}，只被本类回读，宿主完全不需要知道它。</p>
     */
    private static void markNegativeDisk(long songId) {
        if (songId <= 0L || NEGATIVE_DISK.contains(songId)) {
            return;
        }
        try {
            Path d = dir();
            Files.createDirectories(d);
            Path tmp = d.resolve(songId + NEG_SUFFIX + ".tmp");
            Files.writeString(tmp, "no_lyric " + System.currentTimeMillis() + "\n",
                    StandardCharsets.UTF_8);
            try {
                Files.move(tmp, d.resolve(songId + NEG_SUFFIX), StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable t) {
                Files.deleteIfExists(tmp);
                throw t;
            }
            NEGATIVE_DISK.add(songId);
        } catch (Throwable t) {
            PluginLog.d(TAG, "无词标记落盘失败 id=" + songId + " :: " + msg(t));
        }
    }

    /** 撤掉无词标记（歌又有词了 / 正缓存胜出 / 清缓存）。 */
    private static void clearNegativeMark(long songId) {
        NEGATIVE_DISK.remove(songId);
        try {
            Files.deleteIfExists(dir().resolve(songId + NEG_SUFFIX));
        } catch (Throwable t) {
            PluginLog.d(TAG, "无词标记清理失败 id=" + songId + " :: " + msg(t));
        }
    }

    /**
     * 负缓存超限淘汰：<b>只淘汰没落盘的条目</b>。
     *
     * <p>落盘标记是「已确认无词」的持久事实，被淘汰后会立刻被 {@link #stats()} 重算成「待处理」
     * （0.11.49 真机 1250 条假待办的根因）。全库无词量远超 {@link #MAX_NEGATIVE}，淘汰必须跳过它们。</p>
     */
    private static void trimNegativeIds() {
        int need = NEGATIVE_ID.size() - MAX_NEGATIVE / 2;
        if (need <= 0) {
            return;
        }
        for (Long k : NEGATIVE_ID.keySet()) {
            if (need <= 0) {
                break;
            }
            if (NEGATIVE_DISK.contains(k)) {
                continue;
            }
            if (NEGATIVE_ID.remove(k) != null) {
                need--;
            }
        }
    }

    // ------------------------------------------------------------------ 杂项

    /**
     * 懒建守护抓取池：{@link #WORKERS} 路 + 有界队列（{@code WORKERS × 8}）。
     *
     * <p>队列有界 + 任务接力，是为了让「预热 3267 首」不会把「用户刚点的那首」顶到队尾。</p>
     */
    private static ThreadPoolExecutor pool() {
        ThreadPoolExecutor p = pool;
        if (p != null) {
            return p;
        }
        synchronized (LyricService.class) {
            if (pool == null) {
                pool = new ThreadPoolExecutor(WORKERS, WORKERS, 0L, TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>(WORKERS * 8), WARM_FACTORY,
                        new ThreadPoolExecutor.CallerRunsPolicy());
            }
            return pool;
        }
    }

    /**
     * 环境级错误的识别（0.11.8 新增）。
     *
     * <p>{@code NoClassDefFoundError} / {@code NoSuchMethodError} / {@code UnsupportedClassVersionError}
     * 这类 {@link LinkageError} 的含义是「<b>这段代码在宿主 JVM 里根本不可用</b>」，与「这次网络不好」
     * 是两种病：前者重试一万次也是同一个错，必须 {@link PluginLog#e} 报错并单独归一类，
     * 否则会被当成网络抖动进短退避、在 UI 上伪装成正常波动（B2 轮 14 条
     * {@code NoClassDefFoundError: java/net/http/HttpRequest} 就是这么被 WARN 糊过去的）。</p>
     */
    private static boolean isEnvError(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof LinkageError || c instanceof UnsupportedOperationException) {
                return true;
            }
        }
        return false;
    }

    /** 异常的可读描述：恒带类名（环境级错误也能一眼看出是 {@code NoClassDefFoundError}）。 */
    private static String describe(Throwable t) {
        if (t == null) {
            return "null";
        }
        return t.getClass().getSimpleName() + ": " + msg(t);
    }

    private static String msg(Throwable t) {
        if (t == null) {
            return "null";
        }
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : m;
    }
}
