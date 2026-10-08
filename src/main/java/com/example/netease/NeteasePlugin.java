package com.example.netease;

import com.example.netease.cfg.PluginConfig;
import com.example.netease.core.DataPaths;
import com.example.netease.core.DevMode;
import com.example.netease.core.EventBus;
import com.example.netease.core.HostBridgeWorker;
import com.example.netease.core.Notifier;
import com.example.netease.core.PluginLog;
import com.example.netease.core.PluginVersions;
import com.example.netease.core.RuntimeCapabilities;
import com.example.netease.core.Timers;
import com.example.netease.host.HostSqlBridge;
import com.example.netease.host.HostTrackListGate;
import com.example.netease.host.VoxzenBridge;
import com.example.netease.net.Dto;
import com.example.netease.net.NeteaseApi;
import com.example.netease.svc.AccountService;
import com.example.netease.svc.CoverPrimer;
import com.example.netease.svc.CoverStore;
import com.example.netease.svc.LyricService;
import com.example.netease.svc.NativeLibrary;
import com.example.netease.svc.NativeStreamServer;
import com.example.netease.svc.NetCheck;
import com.example.netease.ui.AccountStatusWindow;
import com.example.netease.ui.LoginDialog;
import com.example.netease.ui.PlaylistWindow;
import com.example.netease.ui.SearchWindow;
import com.example.netease.ui.UiRegistry;
import com.xuncorp.spw.workshop.api.Channel;
import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.SpwPlugin;

import javax.swing.SwingUtilities;
import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 插件主类（生命周期 + 配置页静态入口）。
 *
 * <p>启动顺序（docs/00 §4 铁律）：日志 → 上下文 → 纯文件初始化 → 起线程 →
 * 配置（入队到 HostBridgeWorker）→ 延迟 3 秒的「已就绪」提示（避开宿主启动高峰）。
 * 每一步都夹异常，任何失败都只降级，绝不让宿主崩溃或卡死。</p>
 */
public final class NeteasePlugin extends SpwPlugin {

    public static final String DEFAULT_ID = "com.example.netease";
    private static final String TAG = "plugin";
    private static final long READY_TOAST_DELAY_MS = 3000L;
    /** 0.3.5 试验开关：数据目录存在该文件时，启动后自动试播一次（无人值守真机取证用）。 */
    private static final String ONLINE_AUTOTEST_FLAG = "online-autotest.flag";
    /** 自动试播延迟：等登录态恢复完、宿主启动高峰过去。 */
    private static final long ONLINE_AUTOTEST_DELAY_MS = 15000L;

    /**
     * 0.6.0 取证开关：数据目录存在该文件时，启动后自动「整单试播」一次
     * （随机歌单 → 随机起点 → {@link VoxzenBridge#tryPlaylist()}）。
     */
    private static final String ONLINE_AUTOTEST_PLAYLIST_FLAG = "online-autotest-playlist.flag";

    /** 整单试播延迟：比单曲试播再晚一些，避免两条取证链互相抢队列。 */
    private static final long ONLINE_AUTOTEST_PLAYLIST_DELAY_MS = 30000L;

    /**
     * 0.6.0 UI 取证开关：数据目录存在该文件时，启动后自动开歌单窗口 →
     * 载入第一个歌单 → 从第 1 首整单连播（{@link PlaylistWindow#__testLoadFirstAndPlay()}）。
     *
     * <p>为什么需要它：封面列、表头「歌单切换 ▾」、以及 0.6.0 修掉的「第一首没歌词」，
     * 三件事都只有在<b>真的有曲目进了曲目表</b>时才看得见；而曲目表要用户手点左侧歌单或点播放
     * 才会有数据。无人值守取证必须自己能走到那一步（用户偏好：装机、启动、取证由插件方全包）。</p>
     */
    private static final String UI_AUTOTEST_PLAYLIST_FLAG = "ui-autotest-playlist.flag";

    /** 开窗延迟：晚于「已就绪」提示、早于 30 秒的整单试播，避免两条链抢队列。 */
    private static final long UI_AUTOTEST_DELAY_MS = 10000L;

    /** 0.3.9：自动试播里「本地文件对照」相对在线试播再延后的毫秒数（等在线那几次读回先跑完）。 */
    private static final long ONLINE_AUTOTEST_LOCAL_DELAY_MS = 20000L;

    /**
     * 0.11.11 原生播放桥（<b>免 flag</b>）：启动后自动 ① 把宿主自己的音乐视频拦截器装进播放器的
     * 活清单与属性清单（{@link VoxzenBridge#armNative()}）；② 把全库流式行的
     * {@code (trackId, location)} 批量登记进宿主注册表（{@link NativeLibrary#streamSources()} →
     * {@link VoxzenBridge#registerSources}）。
     *
     * <p><b>为什么必须免 flag 自动做</b>：宿主在自己的列表里点歌这条路径<b>不经过</b>
     * {@code playOnline}——它拿那一行的 {@code (id, path)} 去问注册表，注册表里没有完全一致的一对、
     * 或播放器活清单里没有音乐视频拦截器，宿主对 {@code http://} 位置的装载就静默失败
     * （真机表现：点了没反应、宿主日志一行都不加）。0.11.10 及之前这套装配只在
     * {@code native-probe.flag} 下做过，属取证开关、从未生产化 ⇒ 用户点歌不播。</p>
     */
    private static final long NATIVE_ARM_DELAY_MS = 8000L;

    /** 装配/登记的重试（两拍退避；装配本身幂等，可反复调）。 */
    private static final long NATIVE_ARM_RETRY_MS = 20000L;
    private static final long NATIVE_ARM_RETRY2_MS = 40000L;

    /** 保活周期：宿主重建播放器 / 重读曲库都会清掉活清单与注册表。 */
    private static final long NATIVE_ARM_KEEPALIVE_MS = 60000L;

    /**
     * 播放条封面兜底轮询：宿主换曲不一定每次都触发三条歌词钩子（内部按曲目判重）。
     *
     * <p>0.11.12：7 秒 → 1.5 秒。主路径已经是「宿主一开口播就投」（{@code NativeStreamServer}）+
     * 成品图缓存命中，这一拍只是<b>兜底</b>（钩子没触发 / 投递失败重试）；1.5 秒内必有一次补齐，
     * 而不是原来最坏要等 7 秒才有封面（用户报障 2026-10-01 18:37）。</p>
     */
    private static final long PLAYBAR_POLL_MS = 1500L;

    /** 轮询取不到宿主当前曲的次数（0.11.13 诊断：这一路若恒为 0 命中，说明它实际是死的）。 */
    private static final java.util.concurrent.atomic.AtomicLong POLL_NO_TRACK =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 0.7.0 原生同步开关：数据目录存在该文件时，启动后把 {@link #NATIVE_SYNC_LIST} 里列出的
     * 网易云歌单写成宿主自己的库行（见 {@link NativeLibrary}），让它们出现在宿主原生 UI 里。
     *
     * <p>这是「把功能做进宿主原生界面」的主路径：宿主 1.18.5 的工作坊 API 没有任何列表注入点，
     * 唯一的入口就是它自己的曲库文件。</p>
     */
    private static final String NATIVE_SYNC_FLAG = "native-sync.flag";

    /** 待同步歌单清单：每行 {@code playlistId|显示名}（{@code #} 开头为注释）。 */
    private static final String NATIVE_SYNC_LIST = "native-sync.txt";

    /** 原生同步延迟：等登录态恢复完、宿主启动高峰过去、库读取完成。 */
    private static final long NATIVE_SYNC_DELAY_MS = 12000L;

    /**
     * 0.7.0 免 flag 自动同步（0.7.0 起是主路径）：只要 {@link #NATIVE_SYNC_LIST} 里有歌单，
     * 启动后就自动同步一轮，用户不需要放任何标记文件。
     */
    private static final long NATIVE_SYNC_AUTO_DELAY_MS = 15000L;

    /**
     * 0.10.0：自动同步时从账号拉取「我的全部歌单」的上限（创建 + 收藏）。
     * 超过上限只取前 N 个并落一行日志；手动清单（{@link #NATIVE_SYNC_LIST}）不受该上限约束。
     */
    private static final int NATIVE_SYNC_PLAYLIST_MAX = 200;

    /** 0.10.0：自动同步等待登录态就绪的重试间隔（宿主启动后账号恢复可能要几十秒）。 */
    private static final long NATIVE_SYNC_AUTO_RETRY_MS = 20000L;

    /** 0.10.0：自动同步等待登录态的最大重试次数（加上首次，共看 4 次）。 */
    private static final int NATIVE_SYNC_AUTO_RETRIES = 3;

    /** 应急闸：数据目录存在该文件时，连自动同步也不做（排查用）。 */
    private static final String NATIVE_SYNC_OFF = "native-sync.off";

    /**
     * 运行期「再同步一轮」请求：宿主<b>正在运行</b>时放下该文件（内容同 {@link #NATIVE_SYNC_LIST}
     * 的格式即以其为准，否则用清单文件），插件在 ≤1 个轮询周期内同步并请求宿主重读曲库。
     *
     * <p>这是「免重启」的入口，也是我自己做真机验证用的抓手：宿主 UI 不能被合成鼠标事件驱动
     * （实测首次点击只激活窗口、后续点击零像素变化），所以运行期的一切都得从文件驱动。</p>
     */
    private static final String NATIVE_RESYNC_FLAG = "native-resync.flag";

    /** 运行期轮询：周期 5 秒 × 17280 次 = 24 小时（覆盖一次正常会话）。 */
    private static final long NATIVE_SYNC_WATCH_PERIOD_MS = 5000L;
    private static final int NATIVE_SYNC_WATCH_TICKS = 17280;

    /** 升级迁移线程；stop() 会中断并有界等待，避免插件停用后留下 netease-* 线程。 */
    private static volatile Thread legacyAudioMigration;

    /**
     * 最近一轮原生同步的摘要与失败原因（0.11.7 · P0 铁律 6）。<b>禁止静默失败</b>：0.11.6 的日志里同时
     * 印着「原生同步结果：曲目 5935 首」和「原生同步后 曲目 0」，却没有一行 ERROR，1.19 MB 日志里
     * 一条 `[ERROR` 都没有 —— 用户与维护者都无从察觉 5935 行全是孤儿。现在：
     * <ul>
     *   <li>{@link #syncNote} 只写「同步真正成功」的那次结果，且带上走了哪条通路（宿主同连接 / 回退）；</li>
     *   <li>{@link #syncFailure} 记最后一次失败（写明本轮已中止），配置页可见；</li>
     *   <li>{@link #syncStatus()} 把两者拼成一行给 UI 用。</li>
     * </ul>
     */
    private static volatile String syncNote = "尚未同步";
    private static volatile String syncFailure;

    // ----------------------------------------------------- 合并歌单（0.11.46，默认开）

    /** 开关 × 同步起跑的互斥锁；{@link #mergeGeneration} 由它守护（见 {@link #beginMergedSync}）。 */
    private static final Object MERGE_LOCK = new Object();

    /**
     * 「合并歌单」写库门闩（0.11.46）：同步的<b>写库段</b>与开关落定互斥。
     *
     * <p>为什么需要它（真机日志 23:16 / 23:19 两轮）：关闭开关时若有一轮同步还在拉歌单
     * （网络阶段 40~60 秒），它会在开关落定之后才走到写库 —— 首版的表现是「撤库之后插件行又冒
     * 出来」，用户看到的就是「关掉了但歌曲里还有网易云歌单」。本版关闭不再撤库（歌单必须保留，
     * 见 {@link HostTrackListGate}），这道闩继续守住「写库段与开关落定」的先后：写库前在锁内
     * 复查开关，关了就整轮不写；已开写的轮次写完为止（即使写完也只是「关掉后不显示」）。</p>
     */
    private static final Object MERGE_WRITE_LOCK = new Object();

    /**
     * 合并歌单的代次：开关每次变化 +1（0.11.46）。
     *
     * <p>用来让「已经取到同步闸、但还没开始写库」的那一轮在开写前作废 —— 免得关掉开关后又冒出
     * 一轮写入（本版即使写了也不显示，保留它是未雨绸缪）。见 {@link #beginMergedSync} /
     * {@link #mergedSyncStillValid}。</p>
     */
    private static long mergeGeneration;

    /**
     * 在「合并歌单」开启的前提下取自动同步闸（0.11.46）。
     *
     * <p>开关检查与 {@link NativeLibrary#autoSyncBegin} 在同一把锁里完成：关掉开关的一方先 +1 代次 ——
     * 于是不可能有「检查时还开着、开关落定后才开写」的抢跑。</p>
     *
     * @return 取到返回本轮代次（≥0）；开关关闭 / 被闸挡住返回 -1（原因已写日志）
     */
    private static long beginMergedSync(String reason) {
        synchronized (MERGE_LOCK) {
            if (!PluginConfig.mergePlaylists()) {
                PluginLog.d(TAG, "原生同步（" + reason + "）跳过：合并歌单已关闭（歌曲里只显示本地歌单）");
                return -1L;
            }
            if (!NativeLibrary.autoSyncBegin(reason)) {
                return -1L;
            }
            return mergeGeneration;
        }
    }

    /** 同步线程开写前的最后一道检查：代次未变且合并歌单仍开着（0.11.46）。 */
    private static boolean mergedSyncStillValid(long generation) {
        synchronized (MERGE_LOCK) {
            return PluginConfig.mergePlaylists() && mergeGeneration == generation;
        }
    }

    /**
     * 「合并歌单」开关变化的落地动作（0.11.46，由 {@code cfg.PluginConfig} 回调）。
     *
     * <p><b>本版口径（真机修正，用户原话）</b>：关 ⇒ 宿主「歌曲」总列表只显示本地音乐，
     * <b>网易云接入的歌单一行不删</b>（歌单页、歌单里的歌都保留）。所以关闭动作<b>不再撤库</b>
     * （首版调 {@link NativeLibrary#purgeAll} 把歌单也一起撤了，用户不接受）——「不显示」改由
     * {@link HostTrackListGate} 在宿主进程内按开关动态过滤「歌曲」列表的查询结果实现，这里只
     * 负责「点一次失效 ⇒ 宿主 UI 立刻重查」。</p>
     *
     * <p>开：立刻让 UI 重查（网易云曲目重新出现），并 force 跑一轮同步把关闭期间缺的数据补齐。</p>
     *
     * <p>⚠️ 回调发生在 HostBridgeWorker 线程上：这里只起一条守护线程就立即返回（刷新走
     * {@link HostSqlBridge#onHostConnection}，内部要等在宿主交互线程上跑完，绝不能在宿主交互
     * 线程上同步等它 —— 独立线程正是为此）。</p>
     */
    private static void onMergePlaylistsChanged(boolean enabled) {
        Thread t = new Thread(() -> {
            try {
                if (enabled) {
                    PluginLog.i(TAG, "合并歌单已开启：「歌曲」列表重新合并显示网易云曲目；同时重跑一轮同步补数据");
                    HostTrackListGate.refresh("合并歌单开启");
                    spawnNativeSync(java.util.List.of(), "force:合并歌单开启");
                    return;
                }
                synchronized (MERGE_LOCK) {
                    mergeGeneration++;
                }
                // 数据一行不撤（撤了歌单会跟着空 —— 首版的错误）：只让「歌曲」列表按开关重新过滤。
                HostTrackListGate.refresh("合并歌单关闭");
            } catch (Throwable ex) {
                PluginLog.e(TAG, "合并歌单开关落地失败（不影响其它功能）", ex);
            }
        }, "netease-merge-playlists");
        t.setDaemon(true);
        t.start();
    }

    /** 配置页/界面用的同步状态一行摘要（见 {@link #syncNote} / {@link #syncFailure}）。 */
    public static String syncStatus() {
        String f = syncFailure;
        return f == null ? syncNote : ("同步失败（本轮已中止）：" + f);
    }

    /**
     * 待补挂的封面账（0.11.1）：{@link #onCoverBatch} 的增量投递与同步轮的离线收集都并进这里，
     * 由 {@link #scheduleCoverReassert} 的两波（+5s / +14s）统一写回。
     *
     * <p>为什么需要它：宿主每次重建曲库（启动扫描、{@code VoxzenBridge.refreshHostLibrary()}）
     * 都会按 {@code Track.path} 重新派生 {@code Album}/{@code Artist} 的 cover，把插件写的桩抹回
     * http 直链（真机实证 docs/08 §31.3）。集合是<b>动态</b>的：下载池在补挂窗口内新造的封面
     * 会被一起带上，写成功即销账（不会反复改 {@code coverRevision} 触发宿主重取缩略图）。</p>
     */
    private static final java.util.Map<String, com.example.netease.svc.CoverArt.Cover> REASSERT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 原生同步后顺带预取的歌词首数上限（跨全部歌单合计）。
     *
     * <p><b>0.11.0 起 = 0（不再做同步期预取）</b>。0.10.x 设 120，但库内 3267 首曲目 /
     * 5935 条关联永远追不上（实测「投递 120 首」→ 可用 37 首，耗时 51.6 秒），而宿主
     * <b>只在换曲那一刻</b>问一次三条歌词钩子 —— 没预取到的歌，抓回来也没人再问。
     * 现在改成「播放时按需抓」：{@code svc.StreamPrefetcher} 开工时调
     * {@code LyricService.prefetchOne(...)}，首播即抓、抓到的就留在内存表里，
     * 下一次宿主再问就能命中（实证见 docs/42 §1.4 与 §4）。</p>
     */
    private static final int NATIVE_LYRIC_PREFETCH_MAX = 0;

    /**
     * 封面：0.11.1 起<b>没有「每轮最多新造多少张」这个闸门了</b>。
     *
     * <p>旧版（0.8.0–0.11.0）在这里限 40/200 张，是因为新造走的是同步线程上的串行下载
     * （全局 300 ms 节流）——不限量会把同步卡死。现在同步轮只做「已有桩随库写」
     * （{@code CoverArt.collectReady}，纯离线），缺的由 {@code CoverStore} 的下载池
     * （{@code NET_THREADS=8} 路并行、无节流、不限量）一张张补，<b>造好一张立刻写库</b>
     * （见 {@link #onCoverBatch}）。用户口径：「不要有限制一次下多少个封面，直接全力下」。</p>
     */
    /** 封面补挂两波的延时（相对「同步轮 / 插件启动」时刻）。 */
    private static final long[] COVER_REASSERT_DELAYS_MS = {5000L, 14000L};

    /**
     * 0.3.20：自动试播里是否还要跑「本地文件对照」——<b>默认 false</b>。
     *
     * <p>真机实证（0.3.19，10:48:23）：本地对照 #3 起播会抢走队列，在线那条 http 流的
     * {@code HttpRangeBassStream} 随之 {@code closed}，把「在线能不能一直放下去」这件事搅浑。
     * 要对照就在配置页点「在线试播」，或手动调 {@code VoxzenBridge.tryLocal()}。</p>
     */
    private static final boolean ONLINE_AUTOTEST_LOCAL = false;

    public NeteasePlugin(PluginContext context) {
        super(context);
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    public void start() {
        try {
            String id = DEFAULT_ID;
            String pluginVersion = "unknown";
            String spwVersion = "unknown";
            String channel = "unknown";
            String pluginPath = null;
            try {
                PluginContext ctx = getPluginContext();
                if (ctx != null) {
                    id = nz(ctx.getPluginId(), id);
                    pluginVersion = nz(ctx.getPluginVersion(), pluginVersion);
                    spwVersion = nz(ctx.getSpwVersion(), spwVersion);
                    Channel ch = ctx.getSpwChannel();
                    channel = ch == null ? channel : ch.name();
                    pluginPath = ctx.getPluginPath();
                }
            } catch (Throwable t) {
                // 读上下文失败不致命
            }
            // ① 纯文件/零宿主依赖的初始化
            DataPaths.init(id);
            PluginLog.init(DataPaths.logs());
            PluginLog.i(TAG, "开始启动：id=" + id
                    + " 宿主版本=" + spwVersion + "(" + channel + ")"
                    + " 插件版本(上下文)=" + pluginVersion
                    + " 插件版本(MANIFEST)=" + PluginVersions.read());
            RuntimeCapabilities.setRuntime(spwVersion, channel);

            // ② 起线程（顺序敏感：HostBridgeWorker 必须先于一切宿主交互）
            HostBridgeWorker.get().start();
            Timers.start();
            EventBus.get().start();

            // ②′ 「歌曲」列表门（0.11.46 真机修正；0.11.47 扩展）：TrackRepo 与 TrackRepoKt 两处 DAO
            // 静态字段各换一个动态代理 —— 关「合并歌单」⇒「歌曲」总列表只显示本地音乐，且对同名
            // （歌名+标签+作者完全一样）曲目只显示占用空间最大的一行；宿主「刷新音乐库」给插件曲目
            // 标「不可读」的写被拦下 ⇒ 插件曲目不再冒感叹号。数据一行不删、歌单页不受影响。
            // ⚠️ 必须抢在宿主 UI 首次调用 TrackRepo.getAllFlow()（创建歌曲页 ViewModel）之前落位，
            // 所以这是插件启动后**第一个**宿主交互任务（越早越稳）。
            HostTrackListGate.installAsync();

            // 已同步进宿主曲库的 Track.path 会在用户点歌时立即访问；流服务不能等 15 秒后的
            // 自动同步才启动，否则插件启动后的第一首会连接被拒绝。配置稍后加载完成时会异步
            // 应用真实容量，服务在此先按安全默认值就绪。
            NativeStreamServer.start();

            // ③ 纯文件的服务初始化（0.5.0 瘦身版：只剩账号/客户端登录态这一条线）
            AccountService.init(DataPaths.data());    // 账号：加密凭据 + 唯一 netease-account 守护线程

            // ③′ 封面独立缓存（0.11.1）：建目录、把 0.10.x 躺在 cover\ 根的旧桩搬进 cover\stub\、
            // 读回 cover-index.json / cover-refs.json。纯文件操作，不联网（同步时才由 CoverStore 下载）。
            com.example.netease.svc.CoverStore.init();

            // ③″ 封面补齐线程 —— **打开插件即开工**（0.11.1）：先吃上次落盘的工作清单
            // （cover-refs.json 里历次登记过的专辑），8 路并行下、造好一张立刻写一张库；
            // 15 秒后的同步轮再用 CoverStore.plan 把「我的全部歌单」里的新专辑追加进清单。
            // 这样「用户打开软件就开始加载全部封面」不再依赖同步轮先跑完。
            // 0.11.7（C 轮取证开关）：数据目录存在 `lyric-only.flag` 时**只起歌词预热**，
            // 封面侧全部不启动 —— 否则封面预热（2092 张，8~16 路）与歌词预热抢同一条出口带宽，
            // A11 的「全量歌词预热耗时」会变成两条管线争带宽的**并发上界**，慢在哪一侧无法归因。
            boolean lyricOnly = isLyricOnly();
            if (lyricOnly) {
                PluginLog.i(TAG, "检测到 lyric-only.flag：本轮只起歌词预热，封面预热/投递/重钉全部跳过（C 轮归因用）");
            }
            if (!lyricOnly) {
                com.example.netease.svc.CoverStore.startFill(NeteasePlugin::onCoverBatch);
                scheduleCoverReassert();
            }

            // ③‴ 行封面（0.11.3）：**零音频下载**方案。宿主的行内缩略图走 coil3 磁盘缓存
            // （<宿主数据>\cache\shared_cover），键可从字节码逐字得到：
            //   "{Track.path}?v=1&t={modifiedTime}&s={size}&r={coverRevision}&w=&h=" → sha256 → <hash>.0/.1 + CLEAN 行
            // 所以把每首歌的封面图按这个键写进缓存即可出图 —— 不下载音频、不翻 Track.path（用户口径：
            // 「不下载音乐的情况把所有音乐封面缓存并链接好，只能要这个方案」）。
            if (!lyricOnly) {
                com.example.netease.svc.CoverPrimer.start();
                com.example.netease.svc.CoverPrimer.primeAll("启动");
            }
            // 歌词后台预热（0.11.4）：宿主只在换曲那一刻问一次歌词钩子，所以必须提前把词抓好
            // （否则首播必然「未命中」= 播放页没词，真机日志实证）。
            // 0.11.8（C 轮实证）：先给 `svc.LyricService` 定目录并做**磁盘缓存回读**。
            // 此前 `LyricService.init(Path)` 从未被任何地方调用 ⇒ `scanDiskOnce()` 是死代码：
            // ① 判据行「歌词缓存回读：磁盘 N 个 .lrc → 内存索引 M 首」一行都不打（J6 无从取证）；
            // ② 每次重启后内存索引是空的，盘上几百个 `.lrc` 要等 `fetch()` 顺带 `readDisk()` 才生效
            //    ⇒ 宿主首播那一问必然 miss（多绕一轮联网，正是 R13′ 要收敛的浪费）。
            com.example.netease.svc.LyricService.init(DataPaths.data());
            // 0.11.7（C 轮取证开关）：`lyric-park.flag` 反向跳过歌词预热，给「audio-stream\ 增长与
            // 歌词预热无关」当负对照（可选，不加也成立）。
            if (!Files.isRegularFile(DataPaths.data().resolve("lyric-park.flag"))) {
                com.example.netease.svc.LyricWarmer.start();
            } else {
                PluginLog.i(TAG, "检测到 lyric-park.flag：本轮不起歌词预热（负对照）");
            }
            // 播放条 / 播放页封面（0.11.5）：那条请求禁用磁盘缓存，只认「本地文件内嵌图」⇒ 给**播过的歌**
            // 留一份带图本地文件（零额外下载：整首预取已在本地），只保留最近 8 首（超了先还原 path 再删）。
            if (!lyricOnly) {
                com.example.netease.svc.RowCover.start();
            }

            // ③‴′ 开发者模式（0.11.30 需求③）：把「配置页行显隐」实体化到装机目录的
            // classes\preference_config.json —— 宿主只在打开配置页时读一次、之后不重读，所以开关
            // 一变就必须改写文件本身（细节见 core.DevMode 的类注释）。拿不到插件目录 ⇒ 开关退化为
            // 只读，打 WARN 不影响其它功能。必须在 PluginConfig.load(id) 之前就位：load 过程中
            // 读到的 dev_mode=true 会立刻回调 DevMode.setOn(true) 重写开发者档。
            try {
                DevMode.init(pluginPath == null || pluginPath.isBlank() ? null : Path.of(pluginPath));
            } catch (Throwable t) {
                PluginLog.w(TAG, "开发者模式初始化失败（忽略）：" + t);
            }

            // ③‴″ 合并歌单（0.11.46，用户需求）：回调必须在 PluginConfig.load 之前挂 —— 首次
            // refresh 若读到 merge_playlists=false 会立刻回调一次，用来清掉上次会话可能残留的插件行。
            try {
                PluginConfig.setMergePlaylistsHook(NeteasePlugin::onMergePlaylistsChanged);
            } catch (Throwable t) {
                PluginLog.w(TAG, "合并歌单回调注册失败（忽略）：" + t);
            }

            // ④ 配置（内部会投递到 HostBridgeWorker 执行）
            PluginConfig.load(id);
            HostBridgeWorker.get().submit(() -> {
                PluginLog.setLevel(PluginConfig.logLevel());
                // 配置就绪后把「启动时自动恢复登录」推给账号服务（快照读取，任意线程安全）
                try {
                    AccountService.setAutoLogin(PluginConfig.autoLogin());
                } catch (Throwable t) {
                    PluginLog.d(TAG, "推送 auto_login 开关失败（忽略）：" + t);
                }
                try {
                    // 0.11.30：配置页展示行（current_account）**只做展示** —— 插件登录成功后往里写
                    // 「昵称（ID …）」，用户往里粘手机号 / 验证码不再触发任何登录（0.11.25 的宽容读取与
                    // 0.11.26 的「按值形态分步」兜底路径都已删除）。挂这个回调的唯一用途：用户在宿主里
                    // 改了配置 ⇒ 把展示行按真实登录态对齐一次（只写不读，见 svc.SmsLogin）。
                    PluginConfig.setAccountChangeHook(com.example.netease.svc.SmsLogin::onAccountConfigChanged);
                    // 启动自愈：遗留验证码（老版本）不该过夜（一次性凭据 + 本文件是明文），顺手把可能留在
                    // 行里的手机号收进内部键 —— 不据此登录、也不自动发码
                    com.example.netease.svc.SmsLogin.startupHygiene();
                    // 展示行按真实登录态对齐（凭据自动恢复的场景由登录后回调再刷一次）
                    com.example.netease.svc.SmsLogin.refreshAccountRow();
                } catch (Throwable t) {
                    PluginLog.d(TAG, "账号组原生登录接线失败（忽略）：" + t);
                }
                try {
                    // 0.11.7（D1-A）：`auto_client`（启动时接入本机客户端）开关随「网易云客户端」组一并删除。
                    // 插件自带精简客户端，不读也不拉起本机客户端 —— 这里不再有对应推送。
                } catch (Throwable ignored) {
                    // 保留 try 只是为了不改动上下文缩进
                }
            });

            // ⑤ 能力探测（worker 线程；用 Class.forName(name,false,...) 不初始化宿主类）
            HostBridgeWorker.get().submit(RuntimeCapabilities::probeOnce);

            // ⑥ 延迟 3 秒的「已就绪」提示，避开宿主启动高峰
            HostBridgeWorker.get().submit(() -> Timers.later(READY_TOAST_DELAY_MS, () -> {
                if (PluginConfig.enabled()) {
                    Notifier.success("网易云插件已就绪 v" + PluginVersions.read());
                }
                scheduleOnlineSelfTest();
                schedulePlaylistSelfTest();
                scheduleUiSelfTest();
                scheduleNativeArm();
                migrateLegacyAudioCache();
                scheduleNativeSync();
            }));

            PluginLog.i(TAG, "启动完成：数据目录=" + DataPaths.display());
        } catch (Throwable t) {
            safeLogError("启动失败（插件已降级，宿主不受影响）", t);
        }
    }

    /**
     * 0.3.5：标记文件驱动的一次性在线试播（只为真机取证，默认不触发）。
     *
     * <p>数据目录里存在 {@code online-autotest.flag} 时，启动后 15 秒自动走一次
     * {@link VoxzenBridge#tryOne()}——这样真机取证不必依赖用户点配置页按钮（用户偏好：
     * 装机与启动由插件方全包、日志由插件方自己读）。文件不存在即什么都不做；异常全吞。</p>
     */
    private static void scheduleOnlineSelfTest() {
        try {
            Path flag = DataPaths.data().resolve(ONLINE_AUTOTEST_FLAG);
            if (!Files.isRegularFile(flag)) {
                return;
            }
            PluginLog.i(TAG, "检测到 " + ONLINE_AUTOTEST_FLAG + "："
                    + (ONLINE_AUTOTEST_DELAY_MS / 1000L) + " 秒后自动试播一次（tag: bridge）");
            Timers.later(ONLINE_AUTOTEST_DELAY_MS, VoxzenBridge::tryOne);
            // 0.3.9：在线试播之后再做一次本地文件对照（+ 12 秒后是 file:/// 形态的第二枪），
            // 这样一次无人值守启动就能同时拿到「在线」与「本地」两组数据用于判定入口。
            // 0.3.20：默认关掉（见 ONLINE_AUTOTEST_LOCAL 注释）——本地对照会抢队列、把在线流掐掉。
            if (ONLINE_AUTOTEST_LOCAL) {
                Timers.later(ONLINE_AUTOTEST_DELAY_MS + ONLINE_AUTOTEST_LOCAL_DELAY_MS, VoxzenBridge::tryLocal);
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "自动试播调度失败（忽略）：" + t);
        }
    }

    /**
     * 0.6.0：标记文件驱动的一次性「整单试播」（只为真机取证，默认不触发）。
     *
     * <p>数据目录里存在 {@code online-autotest-playlist.flag} 时，启动后 30 秒自动走一次
     * {@link VoxzenBridge#tryPlaylist()}（随机歌单 → 随机起点 → 整单入队）。与单曲试播分开成两个
     * 标记文件，是为了让「点一首只放一首」与「整单连播」两条证据链互不干扰（同类实验历史教训：
     * 本地对照抢队列会把在线那条流掐掉，见 {@link #ONLINE_AUTOTEST_LOCAL} 注释）。</p>
     */
    private static void schedulePlaylistSelfTest() {
        try {
            Path flag = DataPaths.data().resolve(ONLINE_AUTOTEST_PLAYLIST_FLAG);
            if (!Files.isRegularFile(flag)) {
                return;
            }
            PluginLog.i(TAG, "检测到 " + ONLINE_AUTOTEST_PLAYLIST_FLAG + "："
                    + (ONLINE_AUTOTEST_PLAYLIST_DELAY_MS / 1000L) + " 秒后自动整单试播一次（tag: bridge）");
            Timers.later(ONLINE_AUTOTEST_PLAYLIST_DELAY_MS, VoxzenBridge::tryPlaylist);
        } catch (Throwable t) {
            PluginLog.d(TAG, "整单试播调度失败（忽略）：" + t);
        }
    }

    /**
     * 0.6.0 UI 取证：标记文件驱动的一次性「开窗 + 载首单 + 整单连播」（只为真机取证，默认不触发）。
     *
     * <p>数据目录里存在 {@code ui-autotest-playlist.flag} 时，启动后 10 秒开歌单窗口，
     * 再 5 秒后载入第一个歌单并从第 1 首整单连播。与另两条试播链用不同标记文件分开，
     * 保证「点一首只放一首」「整单连播」「UI 上真能看见封面/切歌单/第一首就有词」三件事各自独立可判。</p>
     */
    private static void scheduleUiSelfTest() {
        try {
            Path flag = DataPaths.data().resolve(UI_AUTOTEST_PLAYLIST_FLAG);
            if (!Files.isRegularFile(flag)) {
                return;
            }
            PluginLog.i(TAG, "检测到 " + UI_AUTOTEST_PLAYLIST_FLAG + "："
                    + (UI_AUTOTEST_DELAY_MS / 1000L) + " 秒后开歌单窗口，随后自动载单并整单连播");
            Timers.later(UI_AUTOTEST_DELAY_MS, PlaylistWindow::open);
            Timers.later(UI_AUTOTEST_DELAY_MS + 5000L, PlaylistWindow::__testLoadFirstAndPlay);
        } catch (Throwable t) {
            PluginLog.d(TAG, "UI 自检调度失败（忽略）：" + t);
        }
    }

    /**
     * 0.11.11 原生播放桥（<b>免 flag、主路径</b>）：启动后自动装配原生拦截器并把全库流式行登记进注册表。
     *
     * <p>为什么免 flag：宿主原生 UI 里点歌那条路径不经过 {@code playOnline}，它只认
     * 「播放器活清单里有音乐视频拦截器」+「注册表里有完全一致的 {@code (trackId, location)}」。
     * 0.11.10 及之前这套装配只在 {@code native-probe.flag} 下做过（取证开关），所以用户直接点歌的
     * 真机表现是「点了没反应、宿主日志一行都不加」（0.11.11 真机定案）。</p>
     *
     * <p>装配幂等，故按 8s → +20s → +40s 三拍落位（宿主启动高峰 / 原生同步可能还没写完库），
     * 之后每 60 秒保活一轮（宿主重建播放器会清掉活清单）；播放条封面另用 1.5 秒轮询兜底
     * （0.11.15：该轮询只在宿主「真的在播」时投，投递时机由播放态对齐，见 {@code pushPlaybarForCurrent}）。</p>
     */
    private static void scheduleNativeArm() {
        try {
            Timers.later(NATIVE_ARM_DELAY_MS, () -> armNativeRoute("首次"));
            Timers.later(NATIVE_ARM_DELAY_MS + NATIVE_ARM_RETRY_MS, () -> armNativeRoute("重试 1"));
            Timers.later(NATIVE_ARM_DELAY_MS + NATIVE_ARM_RETRY_MS + NATIVE_ARM_RETRY2_MS,
                    () -> armNativeRoute("重试 2"));
            Timers.every(NATIVE_ARM_KEEPALIVE_MS, NATIVE_ARM_KEEPALIVE_MS, () -> armNativeRoute("保活"));
            Timers.every(PLAYBAR_POLL_MS, PLAYBAR_POLL_MS, NeteasePlugin::pushPlaybarForCurrent);
        } catch (Throwable t) {
            PluginLog.d(TAG, "原生播放桥调度失败（忽略）：" + t);
        }
    }

    /**
     * 装配 + 登记一轮（幂等，可反复调）：① 把宿主自己的音乐视频拦截器装进播放器的活清单与属性清单；
     * ② 把全库流式行的 {@code (trackId, location)} 批量登记进注册表（只落一行汇总日志，不刷屏）。
     */
    private static void armNativeRoute(String why) {
        try {
            VoxzenBridge.armNative();
            java.util.List<String[]> pairs = NativeLibrary.streamSources();
            if (pairs.isEmpty()) {
                PluginLog.i(TAG, "原生登记（" + why + "）：库内流式曲目 0 行（原生同步还没写库？）");
                return;
            }
            VoxzenBridge.registerSources(pairs, why);
        } catch (Throwable t) {
            PluginLog.w(TAG, "原生装配（" + why + "）失败（已降级，宿主不受影响）：" + t);
        }
    }

    /**
     * 播放条封面的兜底节拍（1.5 s 一次）—— <b>0.11.18 起它不再投递任何封面</b>。
     *
     * <p>为什么不再投：用户规格（最高优先级）要求「点下一首后音乐与封面<b>同一拍</b>进场，且封面
     * <b>只能进场一次</b>」，而这个 1.5 s 轮询在换曲后 0.1–1.5 s 就会命中（{@code hostPlaying} 是全局播放态，
     * 换曲瞬间仍为 true），它投出去的是「上一首还在播」的假信号。真机证据（0.11.17）：
     * 21:24:07 {@code +659 ms 起播投（轮询兜底）} → {@code +702 ms 投递落地}，而宿主到 {@code +2114 ms}
     * 才报 {@code Ready}（出声就在那一刻）⇒ 封面比音乐早进场 1.4 s。</p>
     *
     * <p>它现在只做三件事：① 记录「正在供流的那一首」（换曲定位；宿主 {@code currentTrackId} 读不到时用它兜底）；
     * ② 把起播时序黑匣子落盘（{@code flushTrace}）；③ 宿主封面尺寸档位观测 + 为该档补<b>磁盘缓存键</b>
     * （{@link com.example.netease.svc.PlaybarCover#onPoll()}，只写键、不碰图流、不产生任何进场）。</p>
     */
    private static void pushPlaybarForCurrent() {
        try {
            HostBridgeWorker.get().submitFast(() -> {
                // 0.11.49：先问宿主播放器的「当前媒资项」（权威路径）；属性层那条历史路径读不到时再退回。
                long sid = VoxzenBridge.playingTrackId();
                if (sid <= 0L) {
                    sid = VoxzenBridge.currentTrackId();
                }
                if (sid > 0L) {
                    com.example.netease.svc.PlaybarCover.onStreamServe(sid, "轮询");
                } else {
                    // 宿主「当前曲目」字段读不到（0.11.13 诊断已证：600 拍全 0）：改用「正在供流的那一首」兜底，
                    // 不再把这一路当成死路；计数只为留证。
                    long n = POLL_NO_TRACK.incrementAndGet();
                    if (n <= 3L || n % 600L == 0L) {
                        PluginLog.w(TAG, "播放条封面轮询取不到宿主当前曲（第 " + n + " 次）：改用供流曲兜底");
                    }
                }
                // 起播时序黑匣子落盘（0.11.15 取证）：没有新事件时它什么都不写。
                com.example.netease.svc.PlaybarCover.flushTrace("轮询");
                // 宿主封面档位观测 + 该档缓存键补写（0.11.16 引入；0.11.18 起这里也删掉了「新订阅者补发」）。
                com.example.netease.svc.PlaybarCover.onPoll();
            });
        } catch (Throwable t) {
            PluginLog.d(TAG, "播放条封面轮询失败（忽略）：" + t);
        }
    }

    /** 读 {@code trackId|url} 清单；文件不存在返回空表，任何异常都只降级。 */
    private static java.util.List<String[]> readNativePairs(Path file) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        try {
            if (!Files.isRegularFile(file)) {
                return out;
            }
            for (String line : Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
                String s = line == null ? "" : line.trim();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                int bar = s.indexOf('|');
                if (bar <= 0 || bar >= s.length() - 1) {
                    continue;
                }
                out.add(new String[]{s.substring(0, bar).trim(), s.substring(bar + 1).trim()});
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "读 " + file.getFileName() + " 失败（忽略）：" + t);
        }
        return out;
    }

    /**
     * 0.11.7（C 轮取证）：数据目录里存在 {@code lyric-only.flag} ⇒ 本轮**只跑歌词管线**。
     *
     * <p>用途：A11 要判定「全量歌词预热耗时」，若封面预热（约 2092 张、8~16 路）同时在跑，
     * 测到的是两条管线抢同一条出口带宽的**并发上界**，慢在哪一侧无法归因。因此该 flag 生效时：
     * 不起封面补齐（{@code CoverStore.startFill}）、不起 {@code CoverPrimer}、不重钉行封面
     * （{@code RowCover}）、跳过封面探针 P-4/P-5，并在每一次跳过处落一行固定日志，便于事后核对
     * 「本轮确实只有歌词在动」。仅为取证开关，不影响默认（flag 不存在）行为。</p>
     */
    private static boolean isLyricOnly() {
        try {
            return Files.isRegularFile(DataPaths.data().resolve("lyric-only.flag"));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 0.7.0 原生同步：标记文件驱动的一次性「启流服务 + 拉歌单 + 写宿主库」。
     *
     * <p>数据目录存在 {@code native-sync.flag} 时：{@link #NATIVE_SYNC_DELAY_MS} 之后启动本地流服务
     * （{@link NativeStreamServer}），按 {@code native-sync.txt} 里的歌单 id 逐个拉取曲目，
     * 再整体写成宿主库行（{@link NativeLibrary#sync}）。</p>
     *
     * <p>0.10.0 起自动同步不再依赖 {@code native-sync.txt}：登录态就绪后把「我的<b>全部</b>歌单」
     * （创建 + 收藏，含「我喜欢的音乐」）整体写进宿主曲库；清单文件与 flag 退化成手动覆盖/测试入口。
     * 免重启由 {@link VoxzenBridge#refreshHostLibrary()}（宿主运行期重建曲库快照）负责。</p>
     */
    private static void scheduleNativeSync() {
        try {
            Path listFile = DataPaths.data().resolve(NATIVE_SYNC_LIST);
            boolean manual = Files.isRegularFile(DataPaths.data().resolve(NATIVE_SYNC_FLAG));
            java.util.List<String[]> list = readNativePairs(listFile);
            if (manual) {
                PluginLog.i(TAG, "检测到 " + NATIVE_SYNC_FLAG + "："
                        + (NATIVE_SYNC_DELAY_MS / 1000L) + " 秒后启动本地流服务并同步清单里的 "
                        + list.size() + " 个歌单到宿主曲库（曲库 " + NativeLibrary.describe() + "）");
                Timers.later(NATIVE_SYNC_DELAY_MS, () -> spawnNativeSync(list, "force:手动 flag"));
            } else if (Files.isRegularFile(DataPaths.data().resolve(NATIVE_SYNC_OFF))) {
                PluginLog.i(TAG, "存在 " + NATIVE_SYNC_OFF + "：跳过自动同步（应急闸）");
            } else if (!PluginConfig.mergePlaylists()) {
                // 0.11.46（合并歌单）：关着就明确说清「不写库」，别让日志还印「自动同步已启用」。
                // 周期定时器与登录后钩子照常注册（都不写库，仅被闸门挡下），用户再打开开关时
                // onMergePlaylistsChanged 会立刻补一轮 —— 不需要重启。
                PluginLog.i(TAG, "合并歌单已关闭：跳过启动自动同步（歌曲里只显示本地歌单；"
                        + "重新打开「合并歌单」会立刻补一轮同步）");
            } else {
                // 0.10.0：自动同步 = 手动清单 ∪ 登录后的「我的全部歌单」；不再要求清单非空。
                PluginLog.i(TAG, "自动同步已启用：" + (NATIVE_SYNC_AUTO_DELAY_MS / 1000L)
                        + " 秒后把「我的全部歌单」"
                        + (list.isEmpty() ? "" : "（外加清单里 " + list.size() + " 个）")
                        + "写进宿主曲库（曲库 " + NativeLibrary.describe() + "）；免 flag，放 "
                        + NATIVE_SYNC_OFF + " 可关");
                scheduleAutoSyncTick(list, 0);
            }
            scheduleNativeResyncWatch();
            scheduleAutoSyncPeriodic();
            // 0.11.7（R4 + §6.21）：登录成功即自动实时映射 —— 账号层登录成功后回调这里。
            // hook 契约（Lead 冻结）：实现必须<b>立即返回</b>、禁止网络/磁盘/锁等待 —— 本方法只起线程，符合；
            // 日志里手机号只写「已填/未填」，绝不写号码本身。
            try {
                AccountService.setAfterLoginHook(() -> {
                    spawnNativeSync(java.util.List.of(), "force:登录后");
                    // 0.11.22：扫码窗 / 配置页验证码 / 启动时用凭据自动恢复 —— 三条登录通路都汇到这个回调，
                    // 配置页「登录」那一行的回填（当前账号）放在这一处，避免每个入口各写一遍（写盘动作丢给 HostBridgeWorker，
                    // 本回调仍然立即返回）。
                    HostBridgeWorker.get().submit(com.example.netease.svc.SmsLogin::refreshAccountRow);
                });
            } catch (Throwable t) {
                PluginLog.e(TAG, "注册「登录成功后自动同步」回调失败：登录后本轮不会自动映射，"
                        + "需手动点配置页「立即重新同步」", t);
            }
            // 0.11.41（用户 m00221 ②）：已登录的用户点开登录窗又直接关窗 ⇒ AccountService 回滚到
            // 扫码前的登录态；这里补一次展示行回写，让配置页「当前账号」当场复原（回滚发生在 EDT，
            // 本回调只投递，写盘照旧回宿主交互线程）。
            try {
                AccountService.setAfterQrRestoreHook(() ->
                        HostBridgeWorker.get().submit(com.example.netease.svc.SmsLogin::refreshAccountRow));
            } catch (Throwable t) {
                PluginLog.w(TAG, "注册「关窗回滚后回写展示行」回调失败（登录态不受影响）：" + t);
            }
            // 0.11.25（真机时序实证）：这个回调是**启动 3 秒后**才注册的，而「用本机凭据自动恢复登录态」
            // 通常更早就完成了（0.11.24 真机：01:50:58 恢复成功、同一秒启动对齐把「登录」行按「未登录」
            // 清空，01:51:01 才注入回调 ⇒ 那次恢复的回调没人接，行与 current_account 键再也没被写回）。
            // 注册完立刻主动对齐一次：恢复若已完成就当场写回；若还没完成，则上面的回调会补写。
            // 两个方向都覆盖 ⇒ 时序无论怎么摆，「登录」行最终都会显示当前账号。
            HostBridgeWorker.get().submit(com.example.netease.svc.SmsLogin::refreshAccountRow);
        } catch (Throwable t) {
            PluginLog.d(TAG, "原生同步调度失败（忽略）：" + t);
        }
    }

    /**
     * 0.9.0 → 当前版的一次性兼容迁移：先把宿主里的本插件曲目改回流地址，成功后才删旧整首缓存。
     * 全程在守护线程上做，宿主同连接不可用时回退外部 JDBC；迁移失败则保留旧文件，避免死路径。
     */
    private static synchronized void migrateLegacyAudioCache() {
        Thread existing = legacyAudioMigration;
        if (existing != null && existing.isAlive()) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                String prefix = NativeStreamServer.baseUrl() + "/netease/";
                Boolean viaHost = HostSqlBridge.onHostConnection(
                        NativeLibrary.dbPath() == null ? null : NativeLibrary.dbPath().toString(),
                        sql -> {
                            // 0.11.2：只还原**旧版 audio\ 目录**里的曲目（行封面用的是 audio-cover\，不能被动）
                            NativeLibrary.restoreLegacyStreamPathsOn(sql, prefix, "/audio/");
                            return Boolean.TRUE;
                        });
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                boolean restored = Boolean.TRUE.equals(viaHost);
                if (!restored) {
                    restored = NativeLibrary.restoreStreamPathsExternal(prefix);
                }
                if (!restored) {
                    PluginLog.d(TAG, "旧版音频缓存迁移跳过：暂时无法写宿主曲库（保留旧文件）");
                    return;
                }
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                int legacy = com.example.netease.svc.AudioCache.purge();
                if (legacy > 0) {
                    PluginLog.i(TAG, "升级迁移完成：曲目已恢复边听边下流地址，清理旧版整首缓存 "
                            + legacy + " 个");
                }
                // 宿主可能已经把旧 file:// Track 载进内存；重建后本次启动立即使用新流地址。
                VoxzenBridge.refreshHostLibrary();
            } catch (Throwable ex) {
                PluginLog.d(TAG, "旧版音频缓存迁移失败（保留旧文件，不影响其它功能）：" + ex);
            } finally {
                synchronized (NeteasePlugin.class) {
                    if (legacyAudioMigration == Thread.currentThread()) {
                        legacyAudioMigration = null;
                    }
                }
            }
        }, "netease-audio-migrate");
        t.setDaemon(true);
        legacyAudioMigration = t;
        t.start();
    }

    /** 中断并短等升级迁移；必须在 HostBridgeWorker.stop() 之前调用。 */
    private static void stopLegacyAudioMigration() {
        Thread t = legacyAudioMigration;
        if (t == null || t == Thread.currentThread()) {
            return;
        }
        t.interrupt();
        try {
            t.join(750L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            PluginLog.d(TAG, "升级迁移线程仍在退出中（守护线程，不阻塞宿主关闭）");
        }
    }

    /**
     * 自动同步的时间闸（0.10.0）：首次等 {@link #NATIVE_SYNC_AUTO_DELAY_MS}；若那一刻登录态还没
     * 恢复（账号线程仍在初始化），每 {@link #NATIVE_SYNC_AUTO_RETRY_MS} 再看一眼，最多
     * {@link #NATIVE_SYNC_AUTO_RETRIES} 次 —— 手动清单非空时不等（照旧同步清单）。
     */
    private static void scheduleAutoSyncTick(java.util.List<String[]> list, int attempt) {
        long delay = attempt == 0 ? NATIVE_SYNC_AUTO_DELAY_MS : NATIVE_SYNC_AUTO_RETRY_MS;
        Timers.later(delay, () -> {
            try {
                if (currentUid() > 0 || !list.isEmpty()) {
                    spawnNativeSync(list, attempt == 0 ? "启动自动" : "启动自动（重试 " + attempt + "）");
                } else if (attempt < NATIVE_SYNC_AUTO_RETRIES) {
                    PluginLog.i(TAG, "自动同步等待登录态（第 " + (attempt + 1) + " 次仍未就绪，"
                            + (NATIVE_SYNC_AUTO_RETRY_MS / 1000L) + " 秒后再看）");
                    scheduleAutoSyncTick(list, attempt + 1);
                } else {
                    PluginLog.i(TAG, "自动同步跳过：未登录且 " + NATIVE_SYNC_LIST + " 为空");
                }
            } catch (Throwable t) {
                PluginLog.d(TAG, "自动同步触发失败（忽略）：" + t);
            }
        });
    }

    /** 起一条<b>非宿主线程</b>跑同步：这活要联网、要写库，绝不占宿主回调线程。 */
    private static void spawnNativeSync(java.util.List<String[]> list, String why) {
        java.util.List<String[]> snapshot = list == null ? java.util.List.of() : java.util.List.copyOf(list);
        String reason = why == null || why.isBlank() ? "未说明" : why;
        // 0.11.46（合并歌单，默认开）：关闭后一切同步入口（手动 flag / 配置页按钮 / 登录后 /
        // 30 分钟增量）都不再写宿主曲库（写进去也不显示，过滤见 host/HostTrackListGate）。重新
        // 打开时由 onMergePlaylistsChanged 再 force 跑一轮。取闸与开关检查都在 beginMergedSync 里原子完成。
        if (!PluginConfig.mergePlaylists()) {
            PluginLog.i(TAG, "原生同步（" + reason + "）跳过：合并歌单已关闭（歌曲里只显示本地歌单）");
            return;
        }
        if (snapshot.isEmpty() && currentUid() <= 0) {
            PluginLog.w(TAG, "原生同步（" + reason + "）跳过：手动清单为空且当前未登录");
            return;
        }
        // 0.11.7（§6.21 自动同步闸）：一切同步都先取闸，取不到就本轮不跑（被挡原因由 autoSyncBegin 写进日志）。
        // 「force:」前缀 = 显式请求（手动 flag / 运行期请求 / 配置页 / 登录后），只绕 30 分钟间隔闸、不绕在飞闸；
        // 「启动自动」「30 分钟增量」走普通闸。
        long mergeGen = beginMergedSync(reason);
        if (mergeGen < 0L) {
            return;
        }
        try {
            Thread t = new Thread(() -> {
                try {
                    if (!mergedSyncStillValid(mergeGen)) {
                        PluginLog.i(TAG, "原生同步（" + reason + "）在开写前被「合并歌单」关闭叫停：本轮不写库");
                        return;
                    }
                    runNativeSync(snapshot);
                } finally {
                    // 放闸必须在 finally：异常路径不放闸会把之后所有同步永久挡在「在飞」闸外。
                    NativeLibrary.autoSyncEnd(syncNote + (syncFailure == null ? "" : "（失败：" + syncFailure + "）"));
                }
            }, "netease-native-sync");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            NativeLibrary.autoSyncEnd("线程未能启动：" + t);
            PluginLog.e(TAG, "原生同步线程启动失败（已放闸）", t);
        }
    }

    /**
     * 30 分钟增量同步（0.11.7 / R4）：登录态的常规自动同步，走 {@link NativeLibrary#autoSyncBegin} 的
     * 普通闸（在飞 + 30 分钟间隔）——同一时刻只有一轮，且不会与「启动自动」抢跑。
     */
    private static void scheduleAutoSyncPeriodic() {
        long period = NativeLibrary.AUTO_SYNC_INTERVAL_MS;
        Timers.every(period, period, () -> {
            try {
                spawnNativeSync(java.util.List.of(), "30分钟增量");
            } catch (Throwable t) {
                PluginLog.d(TAG, "30 分钟增量同步触发失败（忽略）：" + t);
            }
        });
        PluginLog.d(TAG, "30 分钟增量同步定时器已启动（周期 " + (period / 60_000L) + " 分钟，受自动同步闸约束）");
    }

    /** 当前登录 uid；未登录或账号服务尚未就绪时返回 -1（绝不抛）。 */
    private static long currentUid() {
        try {
            if (!AccountService.loggedIn()) {
                return -1L;
            }
            return AccountService.status().uid();
        } catch (Throwable t) {
            PluginLog.d(TAG, "读取登录 uid 失败（按未登录处理）：" + t);
            return -1L;
        }
    }

    /**
     * 运行期轮询（0.7.0）：宿主<b>正在跑</b>的时候也能再同步一轮 —— 命中
     * {@link #NATIVE_RESYNC_FLAG} 就消费它、同步、并请宿主重读曲库（{@link VoxzenBridge#refreshHostLibrary()}）。
     *
     * <p>为什么要它：宿主只在启动时读一次自己的库，插件写库又发生在宿主读库之后。有了这条轮询，
     * 「加歌单」不必重启宿主。周期 5 秒只做一次 {@code isRegularFile}，开销可忽略。</p>
     */
    private static void scheduleNativeResyncWatch() {
        Timers.every(NATIVE_SYNC_WATCH_PERIOD_MS, NATIVE_SYNC_WATCH_PERIOD_MS,
                NeteasePlugin::checkNativeResync);
        PluginLog.d(TAG, "运行期重同步轮询已启动：每 " + (NATIVE_SYNC_WATCH_PERIOD_MS / 1000L)
                + " 秒看一次 " + NATIVE_RESYNC_FLAG);
    }

    private static void checkNativeResync() {
        try {
            Path req = DataPaths.data().resolve(NATIVE_RESYNC_FLAG);
            if (!Files.isRegularFile(req)) {
                return;
            }
            java.util.List<String[]> list = readNativePairs(req);          // 内容优先：可临时换歌单
            if (list.isEmpty()) {
                list = readNativePairs(DataPaths.data().resolve(NATIVE_SYNC_LIST));
            }
            Files.deleteIfExists(req);                                      // 先消费，避免重复触发
            if (!PluginConfig.mergePlaylists()) {
                PluginLog.i(TAG, "检测到 " + NATIVE_RESYNC_FLAG + "：合并歌单已关闭，请求已忽略"
                        + "（歌曲里只显示本地歌单）");
                return;
            }
            PluginLog.i(TAG, "检测到 " + NATIVE_RESYNC_FLAG + "：立即重同步 " + list.size()
                    + " 个歌单（宿主运行中，随后请求宿主重读曲库）");
            spawnNativeSync(list, "force:运行期请求");
        } catch (Throwable t) {
            PluginLog.d(TAG, "运行期重同步检查失败（忽略）：" + t);
        }
    }

    private static void runNativeSync(java.util.List<String[]> list) {
        try {
            int port = NativeStreamServer.start();
            if (port <= 0) {
                PluginLog.w(TAG, "原生同步中止：本地流服务未启动");
                return;
            }
            String prefix = NativeStreamServer.baseUrl() + "/netease/";
            // ① 本轮要同步的清单：手动清单（native-sync.txt）∪ 登录后的「我的全部歌单」（0.10.0）。
            java.util.LinkedHashMap<Long, String> want = new java.util.LinkedHashMap<>();
            for (String[] row : list) {
                if (row == null || row.length == 0) {
                    continue;
                }
                try {
                    want.putIfAbsent(Long.parseLong(row[0]), row.length > 1 ? row[1] : "");
                } catch (Throwable t) {
                    PluginLog.d(TAG, "跳过非法歌单 id：" + row[0]);
                }
            }
            int manual = want.size();
            long uid = currentUid();
            if (uid > 0) {
                try {
                    for (Dto.PlaylistBrief b : NeteaseApi.userPlaylists(uid, NATIVE_SYNC_PLAYLIST_MAX)) {
                        if (b != null && b.id() > 0) {
                            want.putIfAbsent(b.id(), b.name());
                        }
                    }
                } catch (Throwable t) {
                    PluginLog.w(TAG, "取「我的歌单」清单失败（本轮只同步手动清单）：" + t.getMessage());
                }
            } else {
                PluginLog.i(TAG, "未登录：本轮只同步手动清单 " + want.size() + " 个歌单");
            }
            if (want.isEmpty()) {
                PluginLog.w(TAG, "原生同步中止：没有要同步的歌单（未登录且 " + NATIVE_SYNC_LIST + " 为空）");
                return;
            }
            PluginLog.i(TAG, "原生同步清单：手动 " + manual + " 个 + 我的歌单 → 合计 " + want.size()
                    + " 个（逐个拉详情）");
            // ② 逐个拉详情：曲目 + 歌单封面 URL。
            java.util.List<NativeLibrary.Entry> entries = new java.util.ArrayList<>();
            for (java.util.Map.Entry<Long, String> kv : want.entrySet()) {
                try {
                    Dto.Playlist pl = NeteaseApi.playlist(kv.getKey());
                    NativeLibrary.Entry entry =
                            new NativeLibrary.Entry(kv.getKey(), pl.name(), pl.tracks(), pl.coverUrl());
                    entries.add(entry);
                    PluginLog.i(TAG, "原生同步取到歌单「" + pl.name() + "」（" + pl.tracks().size() + " 首）");
                    // 0.11.8（A11）**增量登记 + 立刻开工**：不再等 4 个歌单串行拉完才起下载池。
                    // 0.11.7 把 plan/startFill 全放在循环之后 ⇒ 实测（plugin-20261001.log 冷轮）
                    // 池 13:28:27 就绪、13:29:44 才有第一条清单，77 s 纯空转、2092 张封面全部挤在
                    // 同步结束后的 30 s 里爆发。CoverStore.plan 本身是幂等增量的（已登记专辑按
                    // refStub 跳过、REFS 全程 synchronized(REFS) 守锁）⇒ 逐歌单调用安全。
                    if (!isLyricOnly()) {
                        com.example.netease.svc.CoverStore.plan(java.util.Collections.singletonList(entry));
                        com.example.netease.svc.CoverStore.startFill(NeteasePlugin::onCoverBatch);
                    }
                } catch (Throwable t) {
                    PluginLog.w(TAG, "原生同步取歌单 " + kv.getKey() + " 失败：" + t.getMessage());
                }
            }
            if (entries.isEmpty()) {
                PluginLog.w(TAG, "原生同步中止：没有取到任何歌单");
                return;
            }
            // 封面（0.11.1）：这一轮**不等下载**。只把「已经有桩」的专辑随库写进去（纯离线、瞬间），
            // 曲库行先落地（用户打开就能看到列表）；缺图的专辑由下载池并行补、补好一张写一张
            // （见 CoverStore.startFill + onCoverBatch 的「封面实时写库」）。
            java.util.List<com.example.netease.svc.CoverArt.Cover> covers =
                    com.example.netease.svc.CoverArt.collectReady(entries);
            mergeReassert(covers);
            // 工作清单：把「专辑 → 歌手 / 图 URL」登记下来；下载池接着把没桩的专辑一次全投出去。
            if (!isLyricOnly()) {
                com.example.netease.svc.CoverStore.plan(entries);
                com.example.netease.svc.CoverStore.startFill(NeteasePlugin::onCoverBatch);
                // 用 cover 标签（而非 plugin）打这行：A9/A13 的判据词形是 `[cover] 封面缓存：…`，
                // 审计脚本 tools\smoke\cover-audit.py 按该词形抓「运行时权威完成度」。
                PluginLog.i("cover", com.example.netease.svc.CoverStore.stats());
            } else {
                PluginLog.i(TAG, "lyric-only.flag 生效：同步后不登记封面工作清单、不起下载池（归因隔离）");
            }
            // 0.10.0：歌单封面 —— 宿主的歌单封面是 <宿主数据>\data\playlist_cover\<歌单行 id> 文件
            // （Playlist 表没有 cover 列；字节码实证见 svc/PlaylistCover）。写库时把文件 mtime 一起
            // 写进 Playlist.coverModifiedTime —— 宿主的图片缓存键随之变化，UI 会立刻重新取图。
            java.util.Map<String, Long> plCovers = com.example.netease.svc.PlaylistCover.prepare(entries);
            // 0.7.0 主线：把行交给**宿主自己的 Room 连接**写。只有同连接写入才会点着 Room 的
            // TEMP 触发器失效表，宿主 UI 的 Flow 才会立刻 re-emit；外部 JDBC 是另一条连接，
            // 触发器不响 ⇒ 必须重启宿主才看得见（真机实证 + 字节码逆向，见 host/HostSqlBridge）。
            String backup = NativeLibrary.backupOnce("sync");
            // 0.11.46（合并歌单写库门闩）：写库段与开关落定互斥（见 {@link #MERGE_WRITE_LOCK}）——
            // 用户在这个拉取耗时里把开关关掉时：同步还在网络阶段 ⇒ 这里看到 false 直接不写；
            // 同步已经在写 ⇒ 写完为止（本版关闭不撤库，即使写完也只是「关掉后不显示」）。
            NativeLibrary.Result r;
            boolean viaHost;
            synchronized (MERGE_WRITE_LOCK) {
                if (!PluginConfig.mergePlaylists()) {
                    PluginLog.i(TAG, "原生同步（本轮到写库前）发现合并歌单已关闭：本轮不写库"
                            + "（歌曲里只显示本地歌单）");
                    syncNote = "本轮未写库：合并歌单已关闭（歌曲里只显示本地歌单）";
                    syncFailure = null;
                    return;
                }
                NativeLibrary.Result hostResult = HostSqlBridge.onHostConnection(
                        NativeLibrary.dbPath().toString(),
                        sql -> NativeLibrary.writeOn(sql, entries, prefix, backup, covers, plCovers));
                viaHost = hostResult != null;
                if (viaHost) {
                    r = hostResult;
                } else {
                    PluginLog.w(TAG, "宿主同连接写库不可用，回退外部 JDBC（回退路线写完需重启宿主才会在原生 UI 出现）"
                            + com.example.netease.host.HostSqlBridge.routeNote());
                    r = NativeLibrary.sync(entries, prefix, covers, plCovers);
                }
            }
            // 0.11.7（P0 铁律 6）：结果摘要必须写明**这一轮到底走了哪条通路**。0.11.6 的
            // 5935 条孤儿行就是「摘要说『宿主同连接』、实际走的是自带连接」瞒下来的。
            PluginLog.i(TAG, "原生同步结果（" + (viaHost ? "宿主同连接" : "外部 JDBC 回退")
                    + com.example.netease.host.HostSqlBridge.routeNote() + "）：" + r);
            syncNote = "歌单 " + r.playlists() + " / 曲目 " + r.tracks() + " / 关联 " + r.links()
                    + "（" + (viaHost ? "宿主同连接" : "外部 JDBC 回退") + "）";
            syncFailure = null;
            // 0.11.7：把「本轮曲目是主路径取回的、还是兜底来的」写进摘要 —— 真机实证 track/all
            // 从来没通、生产一直走 trackIds 兜底，这件事必须能从摘要一行看出来（W2 第二段交付）。
            String src = NeteaseApi.lastTrackNote();
            if (src != null && !src.isBlank()) {
                syncNote = syncNote + "；" + oneLine(src);
            }
            if (!covers.isEmpty()) {
                PluginLog.i(TAG, "封面核对（只读）：" + NativeLibrary.verifyCovers(covers));
            }
            PluginLog.i(TAG, "原生同步后 " + NativeLibrary.stats() + "；曲目 path 前缀 " + prefix);
            // 0.11.5：① 同步会重写每条 Track 的 path/size ⇒ 缩略图缓存键变化 → 铺一轮新键；
            // ② 同步会把「播过的歌」的本地带图 path 推回流地址 → 重新钉回去
            //    （否则播放条/播放页永远等不到本地文件，0.11.5 接线时漏过这一步）。
            if (!isLyricOnly()) {
                com.example.netease.svc.CoverPrimer.primeAll("同步后");
                int refixed = com.example.netease.svc.RowCover.reapplyAll();
                if (refixed > 0) {
                    PluginLog.i(TAG, "播放条封面：同步后重钉 " + refixed + " 首本地带图文件");
                }
            } else {
                PluginLog.i(TAG, "lyric-only.flag 生效：同步后跳过封面重铺与重钉（归因隔离）");
            }
            // 歌词预取（原生路线）：0.11.0 起 <b>不再</b>在同步期批量预取（Native 上限 = 0，原因见
            // 常量注释）——改用「播放时按需抓」：StreamPrefetcher 开工时抓这一首的歌词，
            // 与音频共用同一套缓存（内存表 + <数据目录>\lyric\<songId>.lrc）。
            if (NATIVE_LYRIC_PREFETCH_MAX > 0) {
                java.util.Map<Long, String> urlById = new java.util.LinkedHashMap<>();
                java.util.List<Dto.Song> songs = new java.util.ArrayList<>();
                for (NativeLibrary.Entry e : entries) {
                    for (Dto.Song s : e.songs()) {
                        if (songs.size() >= NATIVE_LYRIC_PREFETCH_MAX) {
                            break;
                        }
                        songs.add(s);
                        urlById.put(s.id(), NativeStreamServer.urlFor(s.id()));
                    }
                    if (songs.size() >= NATIVE_LYRIC_PREFETCH_MAX) {
                        break;
                    }
                }
                if (!songs.isEmpty()) {
                    PluginLog.i(TAG, "原生歌词预取：投递 " + songs.size()
                            + " 首（合计上限 " + NATIVE_LYRIC_PREFETCH_MAX + "，串行抓取）");
                    LyricService.prefetch(songs, urlById);
                }
            }
            // 免重启：写库发生在宿主读库之后 ⇒ 请宿主在自己的运行期重建一次曲库快照。
            // 曲目表通常立刻可见；侧栏「歌单」列表要不要重启，看宿主自己的实现（另有记录）。
            VoxzenBridge.refreshHostLibrary();
            // 宿主重建曲库快照时会按 Track 的 path 重新派生 Album/Artist 的 cover ⇒ 上面那一遍封面
            // 会被抹掉（真机实证：只读核对 32/32 命中，重建后全变回 http URL、revision 归 0）。
            // 所以等它重建完，再补挂两波（集合含本轮的离线收集 + 窗口内下载池新造的增量）。
            scheduleCoverReassert();
            // 旧版在这里预下载整首并把 Track.path 改成本地文件；这会让播放等下载、
            // 还会让旧低音质文件锁死后续音质切换。现在一律由 NativeStreamServer 边播边写。
            int legacy = com.example.netease.svc.AudioCache.purge();
            if (legacy > 0) {
                PluginLog.i(TAG, "已清理旧版预下载音频 " + legacy + " 个（曲库路径已恢复为流地址）");
            }
        } catch (Throwable t) {
            // 0.11.7（P0 铁律 6 · 禁止静默失败）：同步失败必须 ERROR + 中止本轮 + 界面可见。
            // 0.11.6 这里是 WARN，且日志里「曲目 5935 首」与「曲目 0」并存也没人报警。
            String why = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
            syncFailure = why;
            syncNote = "同步中止（见错误日志）";
            PluginLog.e(TAG, "原生同步失败：本轮已中止，未写完的数据不会继续写（曲库可能不完整）", t);
            Notifier.error("网易云同步失败：本轮已中止。详见插件日志，可先点「立即重新同步」重试。");
        }
    }

    /**
     * 封面下载池的投递出口（0.11.1）：<b>造好一张就写一张</b>，宿主 UI 当场刷新。
     *
     * <p>写库走宿主同连接：宿主 Room 的 TEMP 失效触发器绑在它自己的连接上，同连接写入才会让
     * UI 的 Flow 立刻 re-emit（免重启可见，机制见 {@code host/HostSqlBridge}）。拿不到宿主连接时
     * 退到插件自带 JDBC（那条路宿主 UI 未必立刻刷新，但值已落库）。</p>
     *
     * <p>写失败（宿主刚启动、库还拿不到）不丢：这一批同样在 {@link #REASSERT} 账本里，
     * 由 {@link #scheduleCoverReassert} 的两波补挂重试。写成功也挂账一次 —— 宿主重建曲库可能
     * 就在这一刻跑（它按 {@code Track.path} 重派生 cover），两波补挂负责把它盖回来，写掉即销账。</p>
     *
     * <p>线程：{@code netease-cover-fill}（任意非宿主回调线程都可安全调用
     * {@link HostSqlBridge#onHostConnection}，见 host/HostSqlBridge 的线程契约）。</p>
     */
    private static void onCoverBatch(java.util.List<com.example.netease.svc.CoverArt.Cover> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        mergeReassert(batch);
        try {
            Integer n = HostSqlBridge.onHostConnection(
                    NativeLibrary.dbPath() == null ? null : NativeLibrary.dbPath().toString(),
                    sql -> NativeLibrary.writeCoversOn(sql, batch));
            boolean viaHost = n != null;
            if (!viaHost) {
                n = NativeLibrary.reassertCoversExternal(batch);
            }
            if (n == null || n == 0) {
                PluginLog.w(TAG, "封面写库暂不可用（已挂账，待下一波补挂）：" + batch.size() + " 张");
                return;
            }
            PluginLog.i(TAG, "封面实时写库：" + batch.size() + " 张 → 命中 "
                    + (n == null ? "失败" : n) + (viaHost ? "（宿主同连接）" : "（外部 JDBC 兜底）")
                    + "；库里缺图专辑 " + NativeLibrary.albumsMissingCover() + " 个");
        } catch (Throwable t) {
            PluginLog.d(TAG, "封面实时写库异常（已挂账）：" + t);
        }
    }

    /** 把一批封面并进补挂账本（按（专辑 + 歌手）去重：同名不同艺人是宿主库的两行、两张图）。 */
    private static void mergeReassert(java.util.List<com.example.netease.svc.CoverArt.Cover> covers) {
        if (covers == null) {
            return;
        }
        for (com.example.netease.svc.CoverArt.Cover c : covers) {
            if (c != null && c.album() != null && !c.album().isBlank()) {
                REASSERT.put(CoverStore.refKey(c.album(), c.artist()), c);
            }
        }
    }

    /**
     * 排两波补挂（{@value #COVER_REASSERT_DELAYS_MS} 常量里的 +5s / +14s）。
     *
     * <p>为什么要等：{@code RepoService.rebuildMusicLibrary()} 在宿主自己的协程里跑（调用立刻返回），
     * 重建过程会按 {@code Track.path} 重新派生 cover 值。两波覆盖「慢重建」，把账本里此刻还挂着的
     * 封面统一盖回去；每波只写一次、写成功即销账。</p>
     */
    private static void scheduleCoverReassert() {
        for (int i = 0; i < COVER_REASSERT_DELAYS_MS.length; i++) {
            final int round = i + 1;
            final long delay = COVER_REASSERT_DELAYS_MS[i];
            Timers.later(delay, () -> reassertRound(round, delay));
        }
    }

    /** 一波补挂：把账本快照写回宿主（宿主同连接优先），写成功才销账。 */
    private static void reassertRound(int round, long delay) {
        try {
            java.util.List<com.example.netease.svc.CoverArt.Cover> batch =
                    new java.util.ArrayList<>(REASSERT.values());
            if (batch.isEmpty()) {
                PluginLog.d(TAG, "封面补挂（第 " + round + " 次，+" + (delay / 1000L) + "s）：账本为空，跳过");
                return;
            }
            Integer n = HostSqlBridge.onHostConnection(
                    NativeLibrary.dbPath() == null ? null : NativeLibrary.dbPath().toString(),
                    sql -> NativeLibrary.writeCoversOn(sql, batch));
            boolean viaHost = n != null;
            if (!viaHost) {
                n = NativeLibrary.reassertCoversExternal(batch);
            }
            boolean ok = n != null && n > 0;
            if (ok) {
                for (com.example.netease.svc.CoverArt.Cover c : batch) {
                    REASSERT.remove(CoverStore.refKey(c.album(), c.artist()), c);
                }
            }
            PluginLog.i(TAG, "封面补挂（第 " + round + " 次，+" + (delay / 1000L) + "s）："
                    + (ok ? batch.size() + " 张" : "失败 " + batch.size() + " 张（留待下一波）")
                    + (viaHost ? "（宿主同连接）" : "（外部 JDBC 兜底）"));
        } catch (Throwable t) {
            PluginLog.d(TAG, "封面补挂第 " + round + " 次异常（忽略）：" + t);
        }
    }

    @Override
    public void stop() {
        try {
            // ① 先销毁插件窗口：窗口若留着，插件停用后仍会滞留在宿主进程里（验收 A9）
            UiRegistry.disposeAll();
            // ② 停业务：账号线程收工（0.4.0 起 stop() 是终态；先断 IO 再有界 join，见 docs/00 §5）
            stopLegacyAudioMigration();
            com.example.netease.svc.CoverPrimer.stop();
            com.example.netease.svc.LyricWarmer.stop();
            com.example.netease.svc.RowCover.stop();
            com.example.netease.svc.PlaybarCover.clearCache();   // 0.11.12：卸载即释放成品图缓存
            com.example.netease.svc.CoverStore.stopFill();
            AccountService.stop();
            NativeStreamServer.stop();
            // ③ 最后停线程（顺序与 start() 相反）
            EventBus.get().stop();
            Timers.stop();
            HostBridgeWorker.get().stop();
            PluginLog.i(TAG, "已停止（数据目录保留：" + DataPaths.display() + "）");
        } catch (Throwable t) {
            safeLogError("停止过程异常（已忽略）", t);
        }
    }

    @Override
    public void delete() {
        try {
            stop();
            PluginLog.i(TAG, "插件被卸载：保留数据目录（如不再使用可手动删除）");
        } catch (Throwable t) {
            safeLogError("delete() 异常（已忽略）", t);
        }
    }

    @Override
    public void update() {
        try {
            PluginLog.i(TAG, "update()：版本更新（当前版本 " + PluginVersions.read() + "，无 schema 迁移）");
        } catch (Throwable t) {
            safeLogError("update() 异常（已忽略）", t);
        }
    }

    // ------------------------------------------------- 配置页 on_click 入口
    // ⚠️ 宿主通过反射调用：必须是 public static、无参、不得抛异常。

    /** 搜索（P2）：搜到的曲目可直接在线播放（0.5.0 起不再提供下载）。 */
    public static void openSearch() {
        guard(SearchWindow::open);
    }

    /**
     * 扫码登录（P3；<b>0.11.30 起并入登录对话框</b>）：打开登录对话框并直接停在<b>扫码档</b> ——
     * 0.11.22–0.11.29 那个独立的 {@code ui.LoginWindow} 已整类退役（需求②：不再弹第二个窗口）。
     *
     * <p>方法名保留：{@code harness\src\spw\harness\P3Checks.java} 的入口清单与配置页历史接线都按
     * 这个 {@code on_click} 名字找。配置页那一行「扫码登录」在 0.11.30 已从用户档撤走（DevMode 档里
     * 也不再列它）——它现在只作为「对话框取不到码」时的兜底入口存在。</p>
     */
    public static void showLoginDialog() {
        guard(() -> {
            String phone = PluginConfig.freshPhone();
            LoginDialog.open(phone == null ? "" : phone);
        });
    }

    /**
     * 登录（P3；<b>0.11.26 用户规格 = 账号组里那个「登录」按钮</b>）：打开登录对话框 ——
     * 0.11.30 起卡片内自带<b>分段切换</b>「扫码 | 验证码」（默认扫码），扫码态 = 260×260 二维码 +
     * 「刷新二维码」，验证码态 = 手机号框 + （验证码框 + 「获取验证码」）+「登录」；被风控要求行为验证时
     * 当场在窗内给出验证二维码（需求①②）。
     *
     * <p>为什么必须自绘：宿主配置页的 {@code edittext} 行点开只会给<b>一个</b>输入框（宿主自绘模态，
     * 插件既改不了它的结构、也拿不到第二次输入），「两个输入框 + 一个按钮同屏」只能由插件开窗
     * （宿主不提供对话框 API，见 docs/00 §6.27）。</p>
     *
     * <p>预填号码必须在宿主交互线程上从内部键读（配置读取的线程规矩），窗口本身交给 EDT 建。</p>
     */
    public static void openLoginDialog() {
        guard(() -> {
            String phone = PluginConfig.freshPhone();
            PluginLog.i(TAG, "配置页请求打开登录对话框（预填号码="
                    + (phone == null || phone.isBlank() ? "无" : phone.trim().length() + " 位（值不进日志）") + "）");
            LoginDialog.open(phone == null ? "" : phone);
        });
    }

    /** 退出登录（P3）：清空本地加密凭据 + 断开账号线程的登录态 + 抹掉配置页残留的验证码。 */
    public static void logout() {
        guard(() -> {
            boolean had = AccountService.loggedIn();
            AccountService.logout();
            // 0.11.23：配置页「登录」那一行跟着清空（账号串让位给下一次输入）；顺手抹掉可能残留的验证码
            //（凭据已经销毁，它再留着没有任何用）
            com.example.netease.svc.SmsLogin.clearAccountRow();
            if (had) {
                Notifier.success("已退出登录，本地凭据已清除");
            } else {
                Notifier.warn("当前未登录（本地凭据也已清空）");
            }
        });
    }

    /** 我的歌单 / 每日推荐（P3）：打开歌单窗口，选中项可直接在线播放。 */
    public static void openPlaylist() {
        guard(PlaylistWindow::open);
    }

    // ------------------------------------------------- 「账号」组（0.11.7 / W1；D1-A + D2-b；0.11.26 登录对话框）

    // 0.11.22 是「四件套」原生控件（登录 / 获取验证码 / 验证码 / 确定登录），0.11.23 收成一行（按值的
    // 形态分步），0.11.26 回到用户明确要的「对话框」形态，0.11.29 加了一行「扫码登录」按钮当风控逃生口，
    // **0.11.30 再收口**：对话框里自带「扫码 | 验证码」分段切换（默认扫码）⇒ 那一行按钮撤掉（账号组回到
    // 5 行），独立扫码窗 ui.LoginWindow 整类退役；展示行只做展示（登录后写「名字（ID …）」，用户往里面
    // 粘号码 / 验证码不再触发任何登录）——判据见 docs/00 §6.27、docs/51-登录对话框-0.11.30.md。

    /**
     * 当前账号（0.11.35，用户 m00001 问题1）：点开 {@link AccountStatusWindow} **才**展示
     * 账号名字 / 账号 id / 会员信息；配置页那一行本身不含任何账号信息，未登录时窗里只有一句
     * 「当前未登录」。
     *
     * <p><b>不设开发者闸</b>：这是普通用户要看的账号信息，不是排查入口（0.11.30 起它原先是开发者档
     * 的「账号状态」按钮，本轮按用户要求撤掉那个按钮、职责并到这一行）。</p>
     *
     * <p><b>只读</b>：不启停任何进程、不碰本机客户端、不改任何配置值；只把瞬时登录态渲染出来。</p>
     */
    public static void currentAccount() {
        guard(() -> {
            AccountStatusWindow.open();
            // 顺手把盘上的登录态落点对齐一次（0.11.32 起它是 freshCurrentAccount 的真源，
            // H9.14 幂等探针也在盯着它），不写配置页。
            com.example.netease.svc.SmsLogin.refreshAccountRow();
        });
    }

    // ------------------------------------------------- 「状态与缓存」组（0.11.7 / W6 接线需求）

    /**
     * 三条管线总览：同步结果 / 封面完成度 / 歌词完成度 / 播放缓存。
     *
     * <p>给人看的那一眼里同时回答 A16：预热期间**不会**下载音乐（播放缓存数字与预热无关）。</p>
     */
    public static void pipelineStatus() {
        guard(() -> {
            if (!devGate("三条管线总览")) {
                return;
            }
            String line = "同步：" + syncStatus()
                    + " ｜ 封面：" + oneLine(CoverPrimer.stats())
                    + " ｜ 歌词：" + oneLine(LyricService.stats())
                    + " ｜ 播放缓存：" + oneLine(NativeStreamServer.cacheStats());
            PluginLog.i(TAG, "管线总览：" + line);
            Notifier.success(line);
        });
    }

    /** 封面完成度（A7/A16）：已就绪 / 待处理 / 失败张数，逐条失败原因在日志 tag: cover。 */
    public static void coverStats() {
        guard(() -> {
            if (!devGate("封面完成度")) {
                return;
            }
            String line = "封面：" + oneLine(CoverStore.stats()) + " ｜ 预热：" + oneLine(CoverPrimer.stats());
            PluginLog.i(TAG, "封面完成度：" + line);
            Notifier.success(line);
        });
    }

    /** 歌词完成度（A10/A16）：可注入首数 / 负缓存 / 落盘目录。 */
    public static void lyricStats() {
        guard(() -> {
            if (!devGate("歌词完成度")) {
                return;
            }
            String line = "歌词：" + oneLine(LyricService.stats());
            PluginLog.i(TAG, "歌词完成度：" + line);
            Notifier.success(line);
        });
    }

    /**
     * 清理音乐封面缓存（A9）：清空 `cover\`（原图 + 占位桩 + 索引），**不动**音频缓存与歌词缓存。
     *
     * <p>⚠️ `CoverStore.clearAll()` 是 W3 的交付物（docs/00 §6.20.1）：用反射调用是为了在它落盘前
     * 不挡住整树编译；方法缺失时**必须报错**而不是假装清过了（红线：不静默失败）。</p>
     */
    public static void clearCoverCache() {
        guard(() -> {
            Object result;
            try {
                Class<?> c = Class.forName("com.example.netease.svc.CoverStore");
                result = c.getMethod("clearAll").invoke(null);
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                PluginLog.e(TAG, "清理封面缓存：CoverStore.clearAll() 尚不可用（W3 未交付）——本次未删除任何文件", e);
                Notifier.error("清理封面缓存失败：封面管线尚未提供清理入口（详见日志 tag: plugin）");
                return;
            } catch (Throwable t) {
                PluginLog.e(TAG, "清理封面缓存失败", t);
                Notifier.error("清理封面缓存失败：" + t.getMessage() + "（详见日志）");
                return;
            }
            PluginLog.i(TAG, "清理封面缓存完成：" + result);
            Notifier.success("已清理封面缓存（" + result + "）；音频缓存与歌词缓存未受影响");
        });
    }

    /** 清理歌词缓存（D5-A）：清空内存索引 + `lyric\` 下的 .lrc，**不动**封面与音频缓存。 */
    public static void clearLyricCache() {
        guard(() -> {
            int before = LyricService.cachedCount();
            LyricService.clearCache();
            int after = LyricService.cachedCount();
            PluginLog.i(TAG, "清理歌词缓存完成：清理前 " + before + " 条，清理后 " + after + " 条");
            Notifier.success("已清理歌词缓存（" + before + " → " + after + " 条）；封面与音频缓存未受影响");
        });
    }

    /** 音质档位状态（A15/D7）：配置的请求档位 + 最近一次实际拿到的档位与是否降级。 */
    public static void audioLevelStatus() {
        guard(() -> {
            if (!devGate("音质档位状态")) {
                return;
            }
            String line = "请求档位=" + PluginConfig.audioLevel()
                    + " ｜ 最近一次直链：" + oneLine(NeteaseApi.lastUrlMeta());
            PluginLog.i(TAG, "档位状态：" + line);
            Notifier.success(line);
        });
    }

    // ------------------------------------------------- 「维护」组：清缓存 / 重同步 / 诊断

    /** 把多行摘要压成一行（toast 与日志都只放得下一行）。 */
    private static String oneLine(String s) {
        if (s == null || s.isBlank()) {
            return "（无数据）";
        }
        String t = s.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s{2,}", " ").trim();
        return t.length() <= 300 ? t : (t.substring(0, 300) + "…");
    }

    /** 打开数据目录。 */
    public static void openDataDir() {
        guard(() -> {
            if (!devGate("打开数据目录")) {
                return;
            }
            Path dir = DataPaths.data();
            try {
                DataPaths.ensure(dir);
            } catch (Throwable ignored) {
                // 忽略
            }
            SwingUtilities.invokeLater(() -> {
                try {
                    Desktop.getDesktop().open(dir.toFile());
                } catch (Throwable t) {
                    Notifier.warn("无法打开目录：" + dir);
                }
            });
        });
    }

    /** 清理播放缓存（文件 IO 放守护线程，不占配置页回调）。 */
    public static void clearPlaybackCache() {
        guard(() -> {
            Thread t = new Thread(() -> {
                int streamed = NativeStreamServer.purgePlaybackCache();
                int legacy = com.example.netease.svc.AudioCache.purge();
                PluginLog.i(TAG, "音乐缓存已清理：流式 " + streamed + " 个，旧版 " + legacy
                        + " 个；剩余 " + NativeStreamServer.cacheStats());
                Notifier.success("音乐缓存已清理（正在播放的临时文件会在请求结束后删除）");
            }, "netease-cache-clear");
            t.setDaemon(true);
            t.start();
        });
    }

    /**
     * 配置页按钮「立即重新同步」（0.11.7 · 任务书 P2）：不重启宿主，立刻重跑一轮全量同步。
     *
     * <p>走的是与启动自动同步<b>完全相同</b>的通路（{@link #spawnNativeSync} → 非宿主线程 → 宿主同连接写库），
     * 空清单=「我的全部歌单」（见 §11 Q3）。失败会 ERROR + toast（{@link #syncStatus()} 里能查到原因）。</p>
     */
    public static void syncNow() {
        guard(() -> {
            if (!PluginConfig.mergePlaylists()) {
                Notifier.warn("合并歌单已关闭：歌曲里只显示本地歌单；要同步请先打开「合并歌单」");
                return;
            }
            if (currentUid() <= 0) {
                Notifier.warn("未登录，无法同步：请先在配置页完成登录");
                return;
            }
            spawnNativeSync(java.util.List.of(), "force:配置页手动");
            Notifier.success("已开始同步全部歌单；失败会有错误提示，进度见日志 tag: plugin / native-lib");
        });
    }

    /**
     * 「刷新音乐库」联动（0.11.48）：宿主点「刷新音乐库」（按钮 / 热键 / 菜单）时，由
     * {@code host.HostTrackListGate} 的扫库开始钩子调用 —— 一次点击做两件事：宿主扫本地曲库 +
     * 顺带 force 一轮网易云同步（用户在网易云侧新加的歌，点一下就能刷出来）。
     *
     * <p>与配置页按钮 {@link #syncNow()} 是同一条同步通路（{@link #spawnNativeSync}，force 前缀
     * 绕 30 分钟间隔闸、不绕在飞闸），但<b>静默</b>：不弹 toast、不打「配置页入口」日志 —— 这是一次
     * 「顺带」触发，未登录 / 关闭合并歌单 / 已有同步在飞时只写日志（原因由 spawnNativeSync 与取闸各自
     * 记录，tag: plugin / native-lib）。绝不能抛：调用方在宿主协程线程上。</p>
     */
    public static void syncFromRefreshLibrary() {
        try {
            spawnNativeSync(java.util.List.of(), "force:刷新音乐库");
        } catch (Throwable t) {
            PluginLog.w(TAG, "刷新音乐库联动：同步触发失败（忽略）：" + t);
        }
    }

    /**
     * 配置页按钮「重建曲库映射」（0.11.7 · P0 一次性数据修复）：清掉 0.11.6 留在宿主库里的
     * 孤儿 {@code PlaylistTrack} 行（{@code trackId} 指向不存在的 {@code Track}），随后提示再同步一轮。
     *
     * <p>只动 {@code playlistId LIKE 'netease-%'} 的行；宿主自身数据一行不碰（见
     * {@code svc.NativeLibrary.deleteOrphanLinks}）。清理前会先备份宿主库三个文件。</p>
     */
    public static void rebuildMapping() {
        guard(() -> {
            if (!devGate("重建曲库映射")) {
                return;
            }
            Thread t = new Thread(com.example.netease.svc.NativeLibrary::rebuildLibraryMapping,
                    "netease-rebuild-mapping");
            t.setDaemon(true);
            t.start();
        });
    }

    /** 网络连通性自检（结果由 NetCheck 自己弹 toast；重复点击会被节流）。 */
    public static void testConnection() {
        guard(() -> {
            if (!devGate("测试网络连通性")) {
                return;
            }
            if (!NetCheck.runAsync()) {
                String last = NetCheck.lastReport();
                Notifier.warn(last == null || last.isBlank()
                        ? "网络自检正在进行中，请稍候"
                        : NetCheck.summaryLine(last) + "（刚跑过，稍候再试）");
            }
        });
    }

    // ---------------------------------------------------------------- 在线模式（0.3.0）

    /**
     * 在线模式自检：检查宿主内部播放链路（播放控制器 / 已装配的音频流拦截器 / 注册表 / 当前曲目项）
     * 是否都取得到，结果写进插件日志（tag {@code bridge}）。**不播放任何东西**。
     *
     * <p>用来判断「在线播放能不能用」：若清单里没有 MusicVideoStreamInterceptor，
     * 就说明这个宿主版本没装配把 HTTP 位置变成音频流的拦截器。</p>
     */
    public static void onlineSelfCheck() {
        guard(() -> {
            if (!devGate("在线模式自检")) {
                return;
            }
            VoxzenBridge.diagnose();
            Notifier.success("在线模式自检已开始，结果见日志（tag: bridge）");
        });
    }

    /** 在线播放试一首：从「我喜欢的音乐」**随机**取一首直接让宿主播（0.5.1 起随机，未登录则提示先登录）。 */
    public static void onlineTryOne() {
        guard(() -> {
            if (!devGate("试播一首")) {
                return;
            }
            VoxzenBridge.tryOne();
            Notifier.success("已请求在线播放试听，失败原因见日志（tag: bridge）");
        });
    }

    /**
     * 本地文件对照试播（0.3.9 诊断）：拿宿主**本来就会播的**本地音频走同一条注入链。
     *
     * <p>用来分辨「我们调的这些方法到底是不是起播入口」：本地文件也不播 ⇒ 入口不对；
     * 本地播了 ⇒ 问题收窄到「宿主不认 HTTP 位置」。只听本地已有文件，不播放任何网络内容。</p>
     */
    public static void onlineTryLocal() {
        guard(() -> {
            if (!devGate("试播本地文件")) {
                return;
            }
            VoxzenBridge.tryLocal();
            Notifier.success("已请求本地对照试播（只听本地文件），结果见日志（tag: bridge）");
        });
    }

    /**
     * 给活动播放器装「转发代理」拦截器（0.3.1 实验）：桌面版播放器的拦截器清单里若没有
     * {@code MusicVideoStreamInterceptor}，网络位置就没人认识——本动作造一个动态代理
     * 把「开流」请求原样转发给宿主自带的真拦截器，再塞进清单。**只改内存、不改宿主文件**。
     *
     * <p>用法：自检发现清单里没有音乐视频拦截器时，点一次本动作，再点「试播一首」。</p>
     */
    public static void onlineInjectInterceptor() {
        guard(() -> {
            if (!devGate("装拦截器")) {
                return;
            }
            VoxzenBridge.installInterceptor();
            Notifier.success("已请求装拦截器，结果见日志（tag: bridge）");
        });
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 配置页入口的公共外壳（0.11.7 / W6 接线需求）。
     *
     * <p>三件事：①**每次点击都留痕**（`[prefs] 配置页入口：<方法名>`），否则「我点过按钮」无法复核；
     * ②异常不再只落日志 —— 用户必须能看到失败（对应 §8「不静默失败」）；③打印耗时，
     * 抓「入口里做了网络/磁盘等待」这种红线（宿主回调线程不许阻塞）。</p>
     */
    /**
     * 开发者模式闸（0.11.30 需求③ · 第二层保险）。
     *
     * <p>关闭开发者模式时配置页里根本不会出现这些排查 / 自检入口（用户档已把它们摘掉，见
     * {@code core.DevMode}）。这道闸防的是「绕过配置页」的调用：旧版留在宿主里的配置页、
     * 或直接反射点 on_click 目标 —— 一律拒绝并 toast，绝不静默执行。</p>
     *
     * <p>⚠️ 登录相关入口（openLoginDialog / showLoginDialog）**不得**加这道闸：登录是普通
     * 用户功能，不是开发者功能。</p>
     */
    private static boolean devGate(String label) {
        if (DevMode.on()) {
            return true;
        }
        PluginLog.i("prefs", "开发者模式未开启 ⇒ 拒绝执行：" + label);
        Notifier.warn("开发者模式未开启：" + label);
        return false;
    }

    private static void guard(Runnable action) {
        String who = StackWalker.getInstance().walk(s -> s.skip(1).findFirst()
                .map(StackWalker.StackFrame::getMethodName).orElse("?"));
        long t0 = System.currentTimeMillis();
        PluginLog.i("prefs", "配置页入口：" + who);
        try {
            action.run();
        } catch (Throwable t) {
            PluginLog.e("prefs", "配置页入口异常：" + who
                    + " 用时 " + (System.currentTimeMillis() - t0) + " ms", t);
            Notifier.error("入口执行失败：" + who + " —— " + t.getMessage() + "（详见日志 tag: prefs）");
            return;
        }
        PluginLog.i("prefs", "配置页入口完成：" + who
                + " 用时 " + (System.currentTimeMillis() - t0) + " ms");
    }

    private static void safeLogError(String message, Throwable t) {
        try {
            PluginLog.e(TAG, message, t);
        } catch (Throwable ignored) {
            // 日志不可用时静默
        }
    }

    private static String nz(String value, String def) {
        return (value == null || value.isBlank()) ? def : value.trim();
    }
}


