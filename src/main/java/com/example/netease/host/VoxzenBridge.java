package com.example.netease.host;

import com.example.netease.core.HostBridgeWorker;
import com.example.netease.core.Levels;
import com.example.netease.core.PluginLog;
import com.example.netease.core.Timers;
import com.example.netease.cfg.PluginConfig;
import com.example.netease.net.Dto;
import com.example.netease.net.NeteaseApi;
import com.example.netease.svc.AccountService;
import com.example.netease.svc.LyricService;
import com.example.netease.svc.NativeStreamServer;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 宿主内部播放桥（0.3.x「在线模式」）。
 *
 * <p><b>它做什么</b>：让宿主的播放器直接播网易云的 CDN 直链——宿主自带
 * {@code com.xuncorp.voxzen.cast.HttpRangeBassStream}（HTTP Range 分块取流 + 本地缓存 + 断线重连），
 * 由 {@code MusicVideoStreamInterceptor} 在「打开音频流」时命中；命中条件是
 * {@code MusicVideoAudioSourceRegistry.matches(item)}：注册表里的 {@code (trackId, location)}
 * 必须与该曲目项的第 1、第 6 个字段完全相等。所以注入三步：</p>
 * <ol>
 *   <li>{@code MusicVideoAudioSourceRegistry.INSTANCE.activate(trackId, url)}</li>
 *   <li>{@code new PiscesMediaItem(trackId, 标题, 歌手, 专辑, 专辑艺术家, url)}</li>
 *   <li>{@code PlaybackController.INSTANCE.setPlaybackQueue(item)} + {@code play()}</li>
 * </ol>
 *
 * <p><b>0.3.1 起的新增能力</b>：桌面版播放器的拦截器清单里若没有
 * {@code MusicVideoStreamInterceptor}（静态取证显示 {@code PlaybackService$Companion} 只显式
 * {@code new WavDtsInterceptor()}），第三步就是「队列换成了网络位置但没人认识它」——于是本类新增
 * {@link #installInterceptor()}：用 {@link Proxy 动态代理} 造一个接口实现，把
 * {@code createStream*} 原样转发给宿主自带的真拦截器，再把它塞进活动播放器的拦截器清单。</p>
 *
 * <p><b>0.3.2 的定位（javap 静态取证 + 真机日志）</b>：活动播放器的拦截器清单<b>不在</b>
 * {@code PiscesPlayer$Properties} 里（那份是构造参数，播放器只在构造时读一次），而是在具体播放器
 * {@code com.xuncorp.pisces.Ԩ extends PiscesPlayer} 持有的一个「取流器」对象
 * {@code androidx.compose.ui.Ņ} 的 {@code java.util.List} 字段上——真机自检显示
 * {@code 拦截器=[WavDtsInterceptor]}，即桌面版只装了 WAV/DTS 拦截器。所以 0.3.2 改为：</p>
 * <ol>
 *   <li>{@code PlaybackController} 的 private static {@code service} → {@code PlaybackService}；</li>
 *   <li>它的父类 {@code androidx.compose.ui.ബ} 有 public {@code getPlayer()} → 具体播放器实例；</li>
 *   <li>在该实例（含父类）的字段里<b>按结构找</b>「内部含 List 字段、且清单元素实现 {@code createStream*} 接口」
 *       的那个对象——就是活着的清单持有者；</li>
 *   <li>把 {@link Proxy 动态代理}追加进去（Kotlin {@code listOf} 不可变则改字段引用）。</li>
 * </ol>
 *
 * <p><b>纪律（照抄本工程的死锁铁律，见 {@link HostBridgeWorker}）</b>：</p>
 * <ul>
 *   <li>所有反射调用只在 {@code spw-netease-host} 线程上执行（{@link HostBridgeWorker}），
 *       否则会和宿主静态初始化交叉等待导致永久死锁；</li>
 *   <li>取直链是网络动作，放在本类自己的 {@code netease-online} 线程，取完再投递给宿主线程；</li>
 *   <li>每一步各自 try/catch，异常只记日志、只写 {@link #lastResult()}，<b>绝不冒到宿主</b>；</li>
 *   <li>日志里绝不出现直链本身（只记 host + 扩展名）。</li>
 * </ul>
 *
 * <p><b>非契约面</b>：本类依赖宿主 1.18.5 的内部实现（含混淆名）。任何一步失败都只降级为
 * 「本次在线播放不可用」，不影响插件其余功能。</p>
 */
public final class VoxzenBridge {

    private static final String TAG = "bridge";

    /** 0.7.0：上一次原生态状态转储，用于「只在变化时落日志」（点击取证靠它出信号）。 */
    private static volatile String lastStateDump;

    private static final String CLS_CONTROLLER = "com.xuncorp.voxzen.service.PlaybackController";
    /** 0.7.0：宿主曲库服务（Kotlin object，静态 INSTANCE）——运行期刷新曲库用。 */
    private static final String CLS_REPO_SERVICE = "com.xuncorp.voxzen.service.RepoService";
    private static final String CLS_REGISTRY = "com.xuncorp.voxzen.cast.MusicVideoAudioSourceRegistry";
    private static final String CLS_ITEM = "com.xuncorp.pisces.PiscesMediaItem";

    /**
     * 0.3.18：是否给宿主「补装」音乐视频声源切换器（{@code PlaybackController.musicVideoAudioSwitcher}）。
     *
     * <p>桌面构建把它写死成 null（javap 实证）。0.3.10–0.3.17 默认补装 + bind，结果 http 条目的装载被
     * 引向「临时声源」机制（切换器的 {@code select} → {@code createLoadPlan} → {@code pendingSource}），
     * 而该机制的源只在宿主自己发请求时才被喂上：真机四轮里 {@code pendingSource / activeSource /
     * lastManagedLoad} 恒空、播放器恒 Idle。0.3.18 起默认 false：不装切换器，靠「注册表登记 +
     * 活清单里的真拦截器 + 宿主自己的装载器」这条原生路线（装载器已能开流并分析出真曲目）。</p>
     */
    private static final boolean ONLINE_INSTALL_SWITCHER = false;

    /**
     * 整单播放（0.6.0）一次入队多少首：从用户点的那首起，向后取这么多首。
     *
     * <p>不是「整张歌单都灌进去」：网易云直链带时效（URL 里的时间戳是分钟级），一次灌 3000 首
     * 播到后面必然失效；100 首足够「点哪首就从哪首往下听」，且一次点击内能把直链全取完
     * （{@code NeteaseApi.songUrls} 每 100 首一批，正好一批）。</p>
     */
    private static final int PLAYLIST_WINDOW = 100;

    private static final String CLS_PLAYER = "com.xuncorp.pisces.PiscesPlayer";
    private static final String CLS_PROPERTIES = "com.xuncorp.pisces.PiscesPlayer$Properties";
    private static final String CLS_MV_INTERCEPTOR = "com.xuncorp.voxzen.cast.MusicVideoStreamInterceptor";
    /** 0.3.10：宿主的「声源切换器」——桌面构建里 {@code PlaybackController.<clinit>} 把它写死成 null。 */
    private static final String CLS_SWITCHER = "com.xuncorp.voxzen.service.musicvideo.MusicVideoAudioSwitcher";
    private static final String CLS_SWITCHER_KT =
            "com.xuncorp.voxzen.service.musicvideo.MusicVideoAudioSwitcher_desktopKt";
    private static final String FIELD_SWITCHER = "musicVideoAudioSwitcher";

    /** 0.3.15：宿主自带的 HTTP Range 取流器（{@code create(String,int,HttpRangeStreamOwner)} 能对我们的直链开流）。 */
    private static final String CLS_RANGE_STREAM = "com.xuncorp.voxzen.cast.HttpRangeBassStream";

    /** 0.3.15：上面那个 {@code create} 的第三个实参类型（枚举，真实现传 {@code MusicVideo}）。 */
    private static final String CLS_RANGE_OWNER = "com.xuncorp.voxzen.cast.HttpRangeStreamOwner";

    /** 拦截器接口所在包（接口本身是 R8 混淆名，末位码点见 {@link #IFACE_CODE_POINT}）。 */
    private static final String IFACE_PKG = "androidx.compose.ui.";

    /** 拦截器接口 {@code androidx.compose.ui.ౚ} 的末位码点（仅结构识别全失败时兜底）。 */
    private static final int IFACE_CODE_POINT = 0x0C5A;

    /** 具体播放器类 {@code com.xuncorp.pisces.Ԩ} 的末位码点（1.18.5 静态析出；兜底用）。 */
    private static final int PLAYER_IMPL_CODE_POINT = 0x0528;

    /** 「取流器」类 {@code androidx.compose.ui.Ņ} 的末位码点（1.18.5 静态析出；兜底用）。 */
    private static final int OPENER_CODE_POINT = 0x0145;

    /** 直链解析线程（网络）。 */
    private static final ExecutorService NET = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "netease-online");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 代理拦截器被宿主调用的次数（用于判定「清单是否真的生效」）。 */
    private static final AtomicInteger PROXY_CALLS = new AtomicInteger();

    /**
     * 代理拦截器被调用的**总次数**（0.3.8 起）：只数 {@code createStream*} 会漏掉
     * 「宿主问了「这个位置归你管吗」然后被否掉」这种情况——那时取流方法根本不会被调到。
     */
    private static final AtomicInteger PROXY_ALL = new AtomicInteger();

    /** 0.3.11：直调装载器时，动态代理 Continuation 收到的 {@code resumeWith} 结果（可读文本）。 */
    private static final AtomicReference<String> RESUME = new AtomicReference<>();

    private static volatile String lastResult = "在线播放：未开始";
    private static volatile long lastPlayAtMs;
    private static volatile String lastUrlMeta = "";

    private VoxzenBridge() {
    }

    /** 最近一次在线播放的结果（给配置页/日志用；线程安全，永不抛）。 */
    public static String lastResult() {
        return lastResult;
    }

    public static long lastPlayAtMs() {
        return lastPlayAtMs;
    }

    /** 最近一次取到的直链特征（不含 URL 本身）。 */
    public static String lastUrlMeta() {
        return lastUrlMeta;
    }

    // ------------------------------------------------------------ 对外：在线播放

    /**
     * 在线播放（异步、永不抛）：先在网络线程解析直链，再在宿主线程注入。
     *
     * @param songId      网易云歌曲 id（用作 trackId，格式 {@code netease-<id>}）
     * @param title       歌名
     * @param artist      歌手
     * @param album       专辑
     * @param level       音质档（standard/higher/exhigh/lossless/hires），空则用配置默认
     */
    public static void playOnline(long songId, String title, String artist, String album, String level) {
        final int seq = SEQ.incrementAndGet();
        try {
            NET.execute(() -> {
                // level 为空表示“跟随插件配置”。必须先解析后再取直链：NeteaseApi 对空值有
                // 自己的兼容默认值（exhigh）；若直接把 null 传下去，会把 320k 直链错误地
                // 记到 lossless/hires 的缓存键下，表现成“切到最高音质仍然是低音质”。
                String effectiveLevel = effectiveLevel(level);
                String direct;
                try {
                    direct = NeteaseApi.songUrl(songId, effectiveLevel);
                } catch (Throwable t) {
                    set(seq, "取直链失败：" + brief(t));
                    PluginLog.w(TAG, "在线播放 #" + seq + " 取直链失败 songId=" + songId + "：" + brief(t));
                    return;
                }
                if (direct == null || direct.isEmpty()) {
                    set(seq, "取直链为空（可能未登录或该曲不可播）");
                    PluginLog.w(TAG, "在线播放 #" + seq + " 直链为空 songId=" + songId);
                    return;
                }
                String meta;
                try {
                    meta = NeteaseApi.lastUrlMeta();
                } catch (Throwable t) {
                    meta = "";
                }
                lastUrlMeta = meta == null ? "" : meta;
                String url = direct;
                if (NativeStreamServer.start() > 0) {
                    NativeStreamServer.rememberResolved(songId, effectiveLevel, direct);
                    url = NativeStreamServer.urlFor(songId, effectiveLevel);
                }
                PluginLog.i(TAG, "在线播放 #" + seq + " 取流就绪 songId=" + songId
                        + " 特征=" + describeUrl(direct) + " 档位=" + effectiveLevel
                        + (url.equals(direct) ? "（直连降级）" : "（边听边下）")
                        + (lastUrlMeta.isEmpty() ? "" : " | " + lastUrlMeta));
                final String playbackUrl = url;
                // 0.6.0 歌词：直链已知 → 顺手把这 1 首的歌词预取起来。
                // 必须放在「直链就绪」与 HostBridgeWorker 注入之间：注入之后宿主随时可能开始回放并
                // 回调歌词钩子，钩子只查内存缓存，此时没预取好就只能显示「暂无歌词」。
                // 本调用只做列表拷贝 + 投递给自建 netease-lyric 线程，立即返回，不占用 NET 线程。
                LyricService.prefetch(
                        List.of(new Dto.Song(songId, title, artist, album, 0L, 0, true, "")),
                        Map.of(songId, playbackUrl));
                HostBridgeWorker.get().submit(
                        () -> injectOnWorker(seq, songId, title, artist, album, playbackUrl));
            });
        } catch (Throwable t) {
            PluginLog.e(TAG, "在线播放投递失败（已吞）", t);
        }
    }

    // ------------------------------------------------------------ 对外：试一首

    /**
     * 试播一首（异步、永不抛）：从「我喜欢的音乐」里**随机**取一首，直接让宿主播它的在线音源。
     *
     * <p>0.5.1：原实现固定取 `songs.get(0)`，用户连点几次听到的都是同一首（「一直都是 oh yeah」），
     * 看着像卡住。改成随机取一首后，每次点都有新曲子（曲库只有 1 首时行为不变）。</p>
     *
     * <p>为什么先看登录态：微信/短信登录进行中时凭据文件与 cookie 归 {@code netease-account}
     * 线程所有，此时从别的线程读 cookie 有竞争风险——所以登录中就直接放弃本次试播。</p>
     */
    public static void tryOne() {
        final int seq = SEQ.incrementAndGet();
        try {
            NET.execute(() -> {
                try {
                    AccountService.State st;
                    long uid;
                    try {
                        AccountService.LoginStatus s = AccountService.status();
                        st = s == null ? AccountService.State.NONE : s.state();
                        uid = s == null ? 0L : s.uid();
                    } catch (Throwable t) {
                        set(seq, "试播失败：读登录态异常 " + brief(t));
                        return;
                    }
                    if (st == AccountService.State.WAITING_SCAN || st == AccountService.State.WAITING_CONFIRM) {
                        set(seq, "登录进行中，稍后再试播");
                        PluginLog.w(TAG, "试播 #" + seq + " 跳过：登录进行中（state=" + st + "）");
                        return;
                    }
                    if (!NeteaseApi.isLoggedIn()) {
                        set(seq, "未登录：请先在配置页「扫码登录」或用手机验证码登录");
                        PluginLog.w(TAG, "试播 #" + seq + " 跳过：未登录（cookie 里没有 MUSIC_U）");
                        return;
                    }
                    // 0.3.5：不再把 UI 状态（AccountService.loggedIn）当登录判据。真机 0.3.4 就是被它挡住的：
                    // 误开一次登录窗 → 二维码过期（800）→ 状态 EXPIRED → cookie 明明还在却报「未登录」。
                    if (uid <= 0) {
                        try {
                            Dto.Account acc = NeteaseApi.accountInfo();
                            if (acc != null && acc.userId() > 0) {
                                uid = acc.userId();
                            }
                        } catch (Throwable t) {
                            PluginLog.w(TAG, "试播 #" + seq + " 补取 uid 失败：" + brief(t));
                        }
                    }
                    if (uid <= 0) {
                        set(seq, "未登录：拿不到用户 id，请重新登录后再试");
                        PluginLog.w(TAG, "试播 #" + seq + " 跳过：uid 未知");
                        return;
                    }
                    List<Dto.Song> songs = NeteaseApi.likedSongs(uid);
                    if (songs == null || songs.isEmpty()) {
                        set(seq, "「我喜欢的音乐」为空，无法试播");
                        return;
                    }
                    Dto.Song s = songs.get(ThreadLocalRandom.current().nextInt(songs.size()));
                    PluginLog.i(TAG, "试播 #" + seq + " 随机选中 songId=" + s.id() + " 「" + s.name()
                            + "」（曲库 " + songs.size() + " 首）");
                    playOnline(s.id(), s.name(), s.artists(), s.album(), null);
                } catch (Throwable t) {
                    set(seq, "试播失败：" + brief(t));
                    PluginLog.w(TAG, "试播 #" + seq + " 异常", t);
                }
            });
        } catch (Throwable t) {
            PluginLog.e(TAG, "试播投递失败（已吞）", t);
        }
    }

    // ------------------------------------------------------------ 对外：整单播放（0.6.0）

    /**
     * 整单播放（异步、永不抛）：把歌单里「点的那首起」的一窗口曲子一次灌进宿主队列，从点击那首开始连播。
     *
     * <p><b>为什么不是逐首</b>：0.3.x–0.5.x 一直是 {@code setPlaybackQueue(单曲) + playMusicAt(0,true) + play()}，
     * 播完这首宿主队列里就没有下一首了（用户听到的「点一首就只放一首」）。javap 实证宿主
     * {@code PlaybackController} 有 {@code setPlaybackQueue(java.util.List, int)}（整单 + 起始下标），
     * 所以「连播」这件事不需要我们自己造播放器，接上宿主现成的队列即可。</p>
     *
     * <p><b>为什么只灌一窗口</b>：网易云直链带时效（URL 里有 {@code 20260929122807} 这样的时间戳，
     * 约 20 分钟级别），把 3000 首一次性解析并灌进队列，播到后面早就失效了；一窗口 {@link #PLAYLIST_WINDOW}
     * 首既能满足「点哪首就从哪首往下听」，又能在一次点击内把直链取完。</p>
     *
     * <p><b>窗口内对齐</b>：入参 {@code songs} 是整张歌单，{@code startIndex} 是用户点的那一首；
     * 我们切成 {@code [startIndex, startIndex + WINDOW)} 后入队，所以**用户点的那首恒在队列第 0 位**——
     * {@code playMusicAt(0,true)} 的下标语义不需要猜。</p>
     *
     * @param songs      歌单全部曲目（按界面显示顺序）
     * @param startIndex 从第几首开始（0 基；越界自动夹到合法范围）
     * @param level      音质档（空则用配置默认）
     * @param source     来源标签（进日志，如「我的歌单」「我喜欢的音乐」「本地歌单」/playlist 名）
     */
    public static void playPlaylist(List<Dto.Song> songs, int startIndex, String level, String source) {
        final int seq = SEQ.incrementAndGet();
        final String label = (source == null || source.isEmpty()) ? "整单播放" : "整单播放(" + source + ")";
        try {
            if (songs == null || songs.isEmpty()) {
                set(seq, "整单播放失败：歌单里没有曲目");
                PluginLog.w(TAG, label + " #" + seq + " 跳过：歌单为空");
                return;
            }
            final List<Dto.Song> all = new ArrayList<>(songs);
            final int start = Math.max(0, Math.min(startIndex, all.size() - 1));
            NET.execute(() -> {
                try {
                    // 与单曲播放一致：先把“跟随配置”的空档位解析成确定值，再去批量取链。
                    // 这样请求档位、直链缓存键与播放 URL 三者始终完全一致。
                    String effectiveLevel = effectiveLevel(level);
                    int end = Math.min(all.size(), start + PLAYLIST_WINDOW);
                    List<Dto.Song> win = new ArrayList<>(all.subList(start, end));
                    List<Long> ids = new ArrayList<>();
                    for (Dto.Song s : win) {
                        ids.add(s.id());
                    }
                    List<String> urls;
                    try {
                        urls = NeteaseApi.songUrls(ids, effectiveLevel);
                    } catch (Throwable t) {
                        set(seq, "整单取直链失败：" + brief(t));
                        PluginLog.w(TAG, label + " #" + seq + " 取直链失败：" + brief(t));
                        return;
                    }
                    int usable = 0;
                    for (String u : urls) {
                        if (u != null && !u.isEmpty()) {
                            usable++;
                        }
                    }
                    if (usable == 0) {
                        set(seq, "整单取直链为空（可能未登录或这些曲目都不可播）");
                        PluginLog.w(TAG, label + " #" + seq + " 直链全空（歌单 " + all.size() + " 首）");
                        return;
                    }
                    String meta;
                    try {
                        meta = NeteaseApi.lastUrlMeta();
                    } catch (Throwable t) {
                        meta = "";
                    }
                    lastUrlMeta = meta == null ? "" : meta;
                    if (NativeStreamServer.start() > 0) {
                        List<String> proxied = new ArrayList<>(urls.size());
                        for (int i = 0; i < urls.size(); i++) {
                            String direct = urls.get(i);
                            if (direct == null || direct.isEmpty()) {
                                proxied.add(null);
                                continue;
                            }
                            long id = ids.get(i);
                            NativeStreamServer.rememberResolved(id, effectiveLevel, direct);
                            proxied.add(NativeStreamServer.urlFor(id, effectiveLevel));
                        }
                        urls = proxied;
                    }
                    PluginLog.i(TAG, label + " #" + seq + " 直链就绪：歌单=" + all.size() + " 首，本窗口="
                            + win.size() + " 首（第 " + (start + 1) + " 首「" + win.get(0).name() + "」起）"
                            + " 可用=" + usable + " 首 档位=" + effectiveLevel
                            + (NativeStreamServer.running() ? "（边听边下）" : "（直连降级）")
                            + (lastUrlMeta.isEmpty() ? "" : " | " + lastUrlMeta));
                    final List<String> playbackUrls = new ArrayList<>(urls); // 不可播位保留 null 供下游跳过
                    HostBridgeWorker.get().submit(
                            () -> injectPlaylistOnWorker(seq, label, win, playbackUrls, start, all.size()));
                    // 0.6.0 歌词：整单预取（放在注入之前，理由同 playOnline）。
                    // win 与 urls 是按下标一一对应的（songUrls 保证同序），所以这里按位配对。
                    // 只投递给 netease-lyric 线程后立即返回，绝不阻塞 NET 线程上的整单注入。
                    Map<Long, String> urlById = new LinkedHashMap<>();
                    for (int i = 0; i < win.size() && i < playbackUrls.size(); i++) {
                        String u = playbackUrls.get(i);
                        if (u != null && !u.isEmpty()) {
                            urlById.put(win.get(i).id(), u);
                        }
                    }
                    LyricService.prefetch(win, urlById);
                } catch (Throwable t) {
                    set(seq, "整单播放异常：" + brief(t));
                    PluginLog.w(TAG, label + " #" + seq + " 异常", t);
                }
            });
        } catch (Throwable t) {
            PluginLog.e(TAG, "整单播放投递失败（已吞）", t);
        }
    }

    /**
     * 试播整单（异步、永不抛）：随机挑一张「我的歌单」、随机挑一首作为起点，走 {@link #playPlaylist}。
     *
     * <p>0.6.0 的真机取证入口（由 {@code online-autotest-playlist.flag} 触发）：它同时验证三件事——
     * 歌单数据链可用、批量直链可用、宿主队列真能整单连播（判定口径：日志里出现多个不同 songId
     * 的取流，或位置随时间推进越过第一首的时长）。</p>
     */
    public static void tryPlaylist() {
        final int seq = SEQ.incrementAndGet();
        try {
            NET.execute(() -> {
                try {
                    long uid;
                    try {
                        AccountService.LoginStatus s = AccountService.status();
                        uid = s == null ? 0L : s.uid();
                    } catch (Throwable t) {
                        uid = 0L;
                    }
                    if (uid <= 0) {
                        try {
                            Dto.Account acc = NeteaseApi.accountInfo();
                            if (acc != null && acc.userId() > 0) {
                                uid = acc.userId();
                            }
                        } catch (Throwable t) {
                            PluginLog.d(TAG, "整单试播 #" + seq + " 补取 uid 失败：" + brief(t));
                        }
                    }
                    if (uid <= 0) {
                        set(seq, "整单试播跳过：未登录");
                        return;
                    }
                    List<Dto.PlaylistBrief> mine = NeteaseApi.userPlaylists(uid, 50);
                    if (mine == null || mine.isEmpty()) {
                        set(seq, "整单试播跳过：没有歌单");
                        return;
                    }
                    // 优先挑「有曲目的自有歌单」，避免随机到 0 首的
                    List<Dto.PlaylistBrief> usable = new ArrayList<>();
                    for (Dto.PlaylistBrief p : mine) {
                        if (p != null && p.trackCount() > 0 && p.id() > 0L) {
                            usable.add(p);
                        }
                    }
                    List<Dto.PlaylistBrief> pool = usable.isEmpty() ? mine : usable;
                    Dto.PlaylistBrief pick = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
                    Dto.Playlist pl = NeteaseApi.playlist(pick.id());
                    if (pl == null || pl.tracks() == null || pl.tracks().isEmpty()) {
                        set(seq, "整单试播跳过：歌单「" + pick.name() + "」没取到曲目");
                        return;
                    }
                    int start = ThreadLocalRandom.current().nextInt(pl.tracks().size());
                    PluginLog.i(TAG, "整单试播 #" + seq + " 随机选中歌单「" + pl.name() + "」(" + pl.tracks().size()
                            + " 首) 起点=" + (start + 1) + " songId=" + pl.tracks().get(start).id());
                    playPlaylist(pl.tracks(), start, null, "试播");
                } catch (Throwable t) {
                    set(seq, "整单试播失败：" + brief(t));
                    PluginLog.w(TAG, "整单试播 #" + seq + " 异常", t);
                }
            });
        } catch (Throwable t) {
            PluginLog.e(TAG, "整单试播投递失败（已吞）", t);
        }
    }

    // ------------------------------------------------------------ 对外：自检 / 装拦截器

    /**
     * 在线模式自检（异步）：把宿主内部链路的关键事实写进插件日志——
     * 控制器静态字段、播放服务、玩家实例、**已装配的音频流拦截器清单**、队列状态、注册表激活项。
     *
     * <p>为什么用「结构转储」而不是写死字段名：宿主被 R8 混淆，字段名不可依赖；
     * 0.3.0 的自检就是因为只找实例字段（而 {@code service} / {@code activeSource} 都是 private static）
     * 而误报「未找到」。本版按「类型 + 关键词」找，并把清单打出来，便于离线迭代。</p>
     */
    public static void diagnose() {
        HostBridgeWorker.get().submit(VoxzenBridge::diagnoseOnWorker);
    }

    /**
     * 给活动播放器装一个「转发代理」拦截器（异步）：解决「桌面版拦截器清单里没有
     * MusicVideoStreamInterceptor → 网络位置没人认识」这一环。
     */
    public static void installInterceptor() {
        HostBridgeWorker.get().submit(VoxzenBridge::installInterceptorOnWorker);
    }

    // ------------------------------------------------------------ 对外：原生路线（0.7.0）

    /**
     * 原生路线装配（异步）：把宿主<b>自己的</b>音乐视频拦截器（真实例）与我们的计数代理
     * 一起塞进「活清单」和「播放器属性清单」，让宿主在任何时候（不只是我们从队列注入时）
     * 都能把 {@code http(s)} 位置开成流。
     *
     * <p>为什么需要它：0.7.0 起歌单/曲目是写进宿主自己的库（{@code spw.db}）再用宿主原生 UI
     * 点击播放的——那条路径不会经过 {@link #playOnline}，所以拦截器必须在启动时就装好，
     * 而不是等在线试播时才装。幂等；异常全吞，只降级不抛。</p>
     */
    public static void armNative() {
        HostBridgeWorker.get().submit(() -> {
            StringBuilder sb = new StringBuilder("原生装配：");
            try {
                sb.append("活清单[").append(ensureInterceptorOnWorker()).append(']');
                Object ctrl = staticInstance(CLS_CONTROLLER);
                Object service = ctrl == null ? null : staticField(ctrl.getClass(), "PlaybackService", "service");
                Object player = findPlayer(service);
                sb.append(" 属性清单[").append(ensurePropertiesInterceptorOnWorker(player)).append(']');
            } catch (Throwable t) {
                sb.append("异常：").append(brief(t));
                PluginLog.w(TAG, "原生装配失败", t);
            }
            finish(sb);
        });
    }

    /**
     * 原生路线登记（异步）：把宿主库里那一行曲目的 {@code (trackId, location)} 预先写进
     * {@code MusicVideoAudioSourceRegistry}。
     *
     * <p>宿主从库里取曲目时会自己造曲目项（第 1 字段 = 行 id，第 6 字段 = 行 path），
     * 注册表里必须存在<b>完全一致</b>的这一对，{@code matches(item)} 才为真、宿主才会
     * 用它的 {@code HttpRangeBassStream} 去开这个网络位置。幂等，可反复调（当保活用）。</p>
     */
    public static void registerSource(String trackId, String location) {
        if (trackId == null || location == null) {
            return;
        }
        HostBridgeWorker.get().submit(() -> {
            try {
                PluginLog.i(TAG, "原生登记 " + trackId + " → " + describeUrl(location)
                        + "：" + registryActivate(trackId, location));
            } catch (Throwable t) {
                PluginLog.w(TAG, "原生登记失败 " + trackId, t);
            }
        });
    }

    /**
     * 原生播放桥（0.11.11）<b>批量</b>登记（异步，一次任务）：把全库流式行的
     * {@code (trackId, location)} 一口气写进注册表，只落一行汇总日志。
     *
     * <p>为什么要批量：单条 {@link #registerSource} 每对打一行日志，全库 3000+ 行会把日志刷爆；
     * 而保活轮（每 60 秒一次）要反复登记同一批，噪声必须压在一条上。</p>
     *
     * @param pairs 每条 = {@code [trackId, location]}（见 {@code NativeLibrary#streamSources()}）
     * @param why   触发来源（进日志）
     */
    public static void registerSources(java.util.List<String[]> pairs, String why) {
        if (pairs == null || pairs.isEmpty()) {
            return;
        }
        HostBridgeWorker.get().submit(() -> {
            int ok = 0;
            String firstBad = null;
            for (String[] p : pairs) {
                if (p == null || p.length < 2) {
                    continue;
                }
                String r = registryActivate(p[0], p[1]);
                if (r != null && r.startsWith("已登记")) {
                    ok++;
                } else if (firstBad == null) {
                    firstBad = p[0] + "：" + r;
                }
            }
            PluginLog.i(TAG, "原生登记（" + why + "）：" + ok + "/" + pairs.size()
                    + (firstBad == null ? "（全部就位）" : "（首个失败 " + firstBad + "）"));
        });
    }

    /**
     * 原生路线取证（异步）：往插件日志打一行宿主当前状态
     * （队列、播放器状态、当前项、注册表源、代理调用计数）。
     *
     * <p>用点：在宿主原生 UI 里点一下我们写进库的曲目，这条日志会显示宿主到底把哪个
     * {@code trackId} / {@code location} 送进了播放器——这是「原生点击能不能播」的第一手证据。</p>
     */
    public static void logHostState(String label) {
        HostBridgeWorker.get().submit(() -> {
            try {
                String dump = hostStateDump();
                if (dump.equals(lastStateDump)) {
                    return;   // 只在状态真的变了时落一行，避免刷屏
                }
                lastStateDump = dump;
                PluginLog.i(TAG, label + " 宿主状态：" + dump);
            } catch (Throwable t) {
                PluginLog.d(TAG, label + " 状态读回失败（忽略）：" + t);
            }
        });
    }

    /**
     * 原生路线刷新（异步）：让宿主<b>在自己的运行期</b>重读曲库，免得「写完库必须重启宿主」。
     *
     * <p>链路（只读逆向实证）：{@code com.xuncorp.voxzen.service.RepoService} 是 Kotlin
     * {@code object}（静态字段 {@code INSTANCE}，没有 getInstance），{@code rebuildMusicLibrary()}
     * 是零参 {@code public final void}，内部 {@code getServiceScope().launch(Dispatchers.Default)}，
     * 只从<b>已持久化状态</b>重建曲库快照、<b>不扫盘</b>。所以它是安全入口：调用立刻返回，
     * 真正干活在宿主自己的协程里。</p>
     *
     * <p>已知边界：该 lambda 不含歌单门面 ⇒ 曲目表通常立刻可见，侧栏「歌单」列表可能仍要等
     * 下一次启动。宿主换版本时类名/签名可能变，异常一律吞掉只降级（绝不让插件因此崩）。</p>
     */
    public static void refreshHostLibrary() {
        HostBridgeWorker.get().submit(() -> {
            try {
                Object rs = staticInstance(CLS_REPO_SERVICE);
                if (rs == null) {
                    PluginLog.w(TAG, "原生刷新：取不到 " + CLS_REPO_SERVICE + ".INSTANCE");
                    return;
                }
                java.lang.reflect.Method m = rs.getClass().getMethod("rebuildMusicLibrary");
                m.invoke(rs);
                PluginLog.i(TAG, "原生刷新：RepoService.rebuildMusicLibrary() 已调用"
                        + "（宿主运行期重建曲库快照，不扫盘）");
            } catch (Throwable t) {
                PluginLog.w(TAG, "原生刷新失败（忽略，宿主可能不认这个入口）：" + brief(t));
            }
        });
    }

    private static void diagnoseOnWorker() {
        StringBuilder sb = new StringBuilder("自检 v6：");
        try {
            sb.append(" 类加载器=").append(sameLoader() ? "插件与宿主同源" : "插件与宿主【不同源】");

            Object ctrl = staticInstance(CLS_CONTROLLER);
            sb.append(" 控制器=").append(ctrl == null ? "未取到" : shortName(ctrl.getClass()));
            if (ctrl != null) {
                sb.append(" 播放入口=").append(hasMethod(ctrl.getClass(), "playMusicAt", int.class, boolean.class)
                        ? "playMusicAt(IZ)" : "无 playMusicAt");
                sb.append(" 控制器静态=").append(staticFieldDump(ctrl.getClass(), 6));
            }

            Object service = ctrl == null ? null : staticField(ctrl.getClass(), "PlaybackService", "service");
            sb.append(" 服务=").append(service == null ? "未取到" : tname(service.getClass()));
            if (service != null) {
                sb.append(" 服务字段=").append(instanceFieldDump(service, 6));
            }

            Object player = findPlayer(service);
            sb.append(" 玩家=").append(player == null ? "未取到" : tname(player.getClass()));
            if (player != null) {
                sb.append(" 玩家字段=").append(instanceFieldDump(player, 8));
                // 0.3.7：把「单参 = 曲目项」的公开方法列出来（直驱探针要用的候选）
                sb.append(" 玩家播放方法=").append(itemMethodsDump(player));
            }
            // 0.3.10：声源切换器（桌面构建里被写死成 null ⇒ setPlaybackAudioSource 是静默空操作）
            Object switcher = ctrl == null ? null : staticField(ctrl.getClass(), CLS_SWITCHER, FIELD_SWITCHER);
            sb.append(" 声源切换器=").append(switcher == null
                    ? "空（桌面构建写死 null，需 ①″ 安装）" : tname(switcher.getClass()));
            // 0.3.11：宿主自己的流装载器（androidx.compose.ui.Ņ，持有拦截器清单并逐个调 createStream）
            Object loader = player == null ? null : loaderOf(player);
            sb.append(" 装载器=").append(loader == null ? "未取到" : tname(loader.getClass()));
            sb.append(" 切换链=").append(switcherDump()).append(" 注册表源=").append(registrySourceDump());

            Object props = service == null ? null : instanceField(service, CLS_PROPERTIES, null);
            if (props == null && player != null) {
                props = instanceField(player, CLS_PROPERTIES, null);
            }
            sb.append(" 属性=").append(props == null ? "未取到" : tname(props.getClass()));

            // 0.3.2：清单住在具体播放器持有的「取流器」对象里，按结构找
            //（谁持有「元素实现了 createStream* 接口」的那个 List），并附上混淆名兜底候选
            List<ListCand> cands = interceptorCandidates(player);
            ListCand named = namedCandidate(player);
            if (named != null) {
                cands.add(named);
            }
            ListCand best = bestCandidate(cands);
            sb.append(" 清单候选=").append(candDump(cands));
            sb.append(" 拦截器=").append(best == null ? "未取到" : "[" + elementNames(best.list()) + "]");
            Class<?> iface = interceptorInterface(best == null ? null : best.list(),
                    props == null ? null : props.getClass());
            sb.append(" 拦截器接口=").append(iface == null ? "未识别" : esc(iface.getName()));
            Object delegate = hostInterceptorInstance(best == null ? null : best.list(), player);
            sb.append(" 真拦截器=").append(delegate == null
                    ? "取不到（" + delegateWhy + "）" : tname(delegate.getClass()) + "（" + delegateWhy + "）");
            sb.append(" 代理调用=").append(PROXY_CALLS.get()).append("/总计=").append(PROXY_ALL.get());

            Object queueFlow = ctrl == null ? null
                    : staticField(ctrl.getClass(), "StateFlow", "playbackQueueState");
            if (queueFlow != null) {
                Object state = callAnyNoArg(queueFlow, "getValue");
                sb.append(" 队列状态=").append(dumpQueueState(state));
                if (state != null) {
                    Object items = firstListField(state);
                    sb.append(" 队列项=").append(items instanceof List<?> l
                            ? l.size() + " 首=" + (l.isEmpty() ? "空" : summarizeItem(l.get(0)))
                            : "未取到");
                }
            }

            Object source = readActiveSource();
            sb.append(" 注册表=").append(source == null ? "无激活项" : summarizeItem(source));
        } catch (Throwable t) {
            sb.append(" 失败：").append(brief(t));
            PluginLog.w(TAG, "在线模式自检异常", t);
        }
        String text = sb.toString();
        lastResult = text;
        lastPlayAtMs = System.currentTimeMillis();
        PluginLog.i(TAG, text);
    }

    // ------------------------------------------------------------ 注入实现（宿主线程）

    private static void injectOnWorker(int seq, long songId, String title, String artist,
                                       String album, String url) {
        final String trackId = "netease-" + songId;
        try {
            // ① 注册表：trackId + location 必须与曲目项第 1、6 字段一致，拦截器才会命中
            Object registry = staticInstance(CLS_REGISTRY);
            if (registry == null) {
                set(seq, "失败：宿主注册表类不可用（版本不匹配？）");
                return;
            }
            Method activate = registry.getClass().getMethod("activate", String.class, String.class);
            activate.invoke(registry, trackId, url);
            Object active = readActiveSource();
            PluginLog.i(TAG, "在线播放 #" + seq + " ① 注册表已激活 trackId=" + trackId
                    + "（读回=" + (active == null ? "空" : summarizeItem(active)) + "）");

            // ①′ 0.3.2：先确保活动播放器的「活清单」里有认领 HTTP 位置的拦截器（幂等，失败不阻断）
            String interceptor = ensureInterceptorOnWorker();
            PluginLog.i(TAG, "在线播放 #" + seq + " ①′ 拦截器：" + interceptor);

            // ①′⁺ 0.3.19：播放取流路径读的是 **Properties 里那份清单**（javap 常量池实证：
            //      `com.xuncorp.pisces.Ԩ` 的取流方法从 `PiscesPlayer$Properties.ԯ()Ljava/util/List;`
            //      取清单 → `androidx.compose.ui.ݭ$Ϳ` → `Ӂ$Ϳ.Ϳ(..., List, Continuation)` →
            //      `Ӂ$Ϳ$Ԩ` 逐个调 `ౚ.createStream-_2kQ-ls`）。若它与 ①′ 注入的那份不是同一个
            //      对象，播放器就永远看不到我们的拦截器——这正是「代理调用恒 0」的候选解释。
            try {
                Object ctrlA = staticInstance(CLS_CONTROLLER);
                Object svcA = ctrlA == null ? null : staticField(ctrlA.getClass(), "PlaybackService", "service");
                Object playerA = findPlayer(svcA);
                PluginLog.i(TAG, "在线播放 #" + seq + " ①′⁺ 播放器属性清单："
                        + ensurePropertiesInterceptorOnWorker(playerA));
            } catch (Throwable t) {
                PluginLog.w(TAG, "在线播放 #" + seq + " ①′⁺ 属性清单注入失败：" + brief(t), t);
            }

            // ①″ 0.3.10：宿主桌面构建把「声源切换器」写死成 null（clinit 里 aconst_null）。
            //      0.3.10–0.3.17 我们一直用宿主自己的工厂造一个切换器 + Unsafe 写回 + bind 播放器。
            //      **0.3.18 起默认不再安装**：0.3.14–0.3.17 四轮真机证明，装了它之后 http 条目的装载
            //      会被引向「临时声源（music video）」机制——`pendingSource / activeSource /
            //      lastManagedLoad` 恒空、播放器恒 Idle（0.3.17 连「孪生位置」都错开了，照样空）。
            //      而桌面构建本来就没有这东西（null），宿主自己的装载器 `androidx.compose.ui.Ņ`
            //      已经能把这条 http 直链开成流并分析出真曲目（0.3.15 起 装载器回调=
            //      MorvaniumAnalyzedTrack(trackId=netease-…, durationMs=170584)）⇒ 回归「不装切换器、
            //      只登记注册表 + 把真拦截器放进活清单」的原生路线。
            //      需要回到旧行为时把 ONLINE_INSTALL_SWITCHER 改成 true 即可。
            if (ONLINE_INSTALL_SWITCHER) {
                PluginLog.i(TAG, "在线播放 #" + seq + " ①″ 声源切换器：" + ensureSwitcherOnWorker());
            } else {
                PluginLog.i(TAG, "在线播放 #" + seq + " ①″ 声源切换器：本版**不安装**"
                        + "（桌面构建本来就是 null；装了会把 http 条目的装载引向临时声源机制，而该机制"
                        + "只在宿主自己的请求下才喂源）→ 走原生装载路线；当前字段="
                        + (switcherInstance() == null ? "空" : tname(switcherInstance().getClass())));
            }

            // ② 曲目项：第 6 个参数是「播放位置」，本地路径与 URL 同槽（宿主 copyWithPath 实证）
            //    0.3.18：曲目项位置回到**真链**（0.3.17 的「孪生占位」实验已排除——错开位置之后
            //    切换链里 pendingSource 依旧恒空，说明闸门不在「位置相等」）。不装切换器时，装载走
            //    宿主自己的装载器 → 活清单里的真拦截器 → 注册表按 (trackId, 位置) 匹配 ⇒ 必须是真链。
            Class<?> itemCls = Class.forName(CLS_ITEM);
            Constructor<?> ctor = itemCls.getConstructor(String.class, String.class, String.class,
                    String.class, String.class, String.class);
            Object probeItem = ctor.newInstance(trackId, nz(title), nz(artist), nz(album), nz(artist), url);
            Object item = ctor.newInstance(trackId, nz(title), nz(artist), nz(album), nz(artist), url);
            PluginLog.i(TAG, "在线播放 #" + seq + " ② 曲目项已构造：" + summarizeItem(item)
                    + "（曲目项位置=真链" + (ONLINE_INSTALL_SWITCHER ? "；切换器已开启" : "；本版不装切换器") + "）");

            driveOnWorker(seq, item, probeItem, "在线播放", title, artist, trackId, url);
        } catch (Throwable t) {
            // 兜底：退到 addToPlay（有些队列状态不允许整体替换）
            try {
                Object ctrl = staticInstance(CLS_CONTROLLER);
                Class<?> itemCls = Class.forName(CLS_ITEM);
                Constructor<?> ctor = itemCls.getConstructor(String.class, String.class, String.class,
                        String.class, String.class, String.class);
                Object item = ctor.newInstance(trackId, nz(title), nz(artist), nz(album), nz(artist),
                        altLocation(url));
                ctrl.getClass().getMethod("addToPlay", itemCls).invoke(ctrl, item);
                ctrl.getClass().getMethod("play").invoke(ctrl);
                PluginLog.i(TAG, "在线播放 #" + seq + " ③′ 退路 addToPlay + play 已调用");
                set(seq, "已提交给宿主播放器（退路）：" + nz(title) + " - " + nz(artist));
            } catch (Throwable t2) {
                set(seq, "失败：" + brief(t));
                PluginLog.w(TAG, "在线播放 #" + seq + " 注入失败：" + brief(t), t);
            }
        }
    }

    // ------------------------------------------------------------ 整单注入实现（宿主线程，0.6.0）

    /**
     * 整单注入（宿主线程）：把一窗口曲目**一次**交给宿主队列，并从第 0 位起播。
     *
     * <p>与单曲版 {@link #injectOnWorker} 的三点不同：</p>
     * <ol>
     *   <li>曲目项要**全部**构造出来（宿主 {@code setPlaybackQueue(List,int)} 收的是一份列表）；
     *       拿不到直链的曲目**不入队**——空位置会让宿主去「开一个文件」（历史 {@code FileNotOpened} → Idle）。</li>
     *   <li>注册表只为「起点那一首」登记：其余各首由拦截器在宿主真的来取流时按实参自愈登记
     *       （{@code healStreamCall} 从曲目项里抠 trackId/位置），不需要提前登记全部 100 首。</li>
     *   <li>起点恒为队列下标 0（窗口已切成「点击那首开头」），所以 {@code playMusicAt(0,true)}、
     *       ⑤ 二次起播、⑥ 进度探针都能复用现成实现，不必引入下标参数。</li>
     * </ol>
     *
     * @param win        本窗口曲目（第 0 位＝用户点的那首）
     * @param urls       与 {@code win} 等长的直链（拿不到为 {@code null}）
     * @param startIndex 该窗口在原歌单里的起始下标（只用于日志）
     * @param total      原歌单总首数（只用于日志）
     */
    private static void injectPlaylistOnWorker(int seq, String label, List<Dto.Song> win, List<String> urls,
                                               int startIndex, int total) {
        try {
            Object ctrl = staticInstance(CLS_CONTROLLER);
            if (ctrl == null) {
                set(seq, "失败：宿主播放控制器不可用");
                return;
            }
            Class<?> itemCls = Class.forName(CLS_ITEM);
            Constructor<?> ctor = itemCls.getConstructor(String.class, String.class, String.class,
                    String.class, String.class, String.class);
            List<Object> items = new ArrayList<>();
            Object startItem = null;
            String startTrackId = null;
            String startLoc = null;
            StringBuilder skipped = new StringBuilder();
            for (int i = 0; i < win.size(); i++) {
                String url = i < urls.size() ? urls.get(i) : null;
                Dto.Song s = win.get(i);
                if (url == null || url.isEmpty()) {
                    if (skipped.length() > 0) {
                        skipped.append(',');
                    }
                    skipped.append(s.id());
                    continue;
                }
                String trackId = "netease-" + s.id();
                Object item = ctor.newInstance(trackId, nz(s.name()), nz(s.artists()), nz(s.album()),
                        nz(s.artists()), url);
                if (startItem == null) {
                    startItem = item;
                    startTrackId = trackId;
                    startLoc = url;
                }
                items.add(item);
            }
            if (startItem == null) {
                set(seq, "失败：本窗口没有任何可用直链");
                PluginLog.w(TAG, label + " #" + seq + " 本窗口无可用直链（窗口 " + win.size() + " 首）");
                return;
            }

            // ① 注册表：命中条件是「trackId + 位置」与曲目项第 1、6 字段完全相等
            Object registry = staticInstance(CLS_REGISTRY);
            if (registry == null) {
                set(seq, "失败：宿主注册表类不可用（版本不匹配？）");
                return;
            }
            registry.getClass().getMethod("activate", String.class, String.class)
                    .invoke(registry, startTrackId, startLoc);
            Object active = readActiveSource();
            PluginLog.i(TAG, label + " #" + seq + " ① 注册表已激活 trackId=" + startTrackId
                    + "（读回=" + (active == null ? "空" : summarizeItem(active)) + "）");

            // ①′ 活动播放器的「活清单」里必须有认领 HTTP 位置的拦截器（幂等，失败不阻断）
            PluginLog.i(TAG, label + " #" + seq + " ①′ 拦截器：" + ensureInterceptorOnWorker());

            // ①′⁺ 播放取流读的是 Properties 里那份清单（与单曲版同一处理）
            try {
                Object svcA = staticField(ctrl.getClass(), "PlaybackService", "service");
                Object playerA = findPlayer(svcA);
                PluginLog.i(TAG, label + " #" + seq + " ①′⁺ 播放器属性清单："
                        + ensurePropertiesInterceptorOnWorker(playerA));
            } catch (Throwable t) {
                PluginLog.w(TAG, label + " #" + seq + " ①′⁺ 属性清单注入失败：" + brief(t), t);
            }

            // ② 曲目项已构造（首项＝用户点的那首）
            PluginLog.i(TAG, label + " #" + seq + " ② 曲目项已构造：" + items.size() + " 首（原歌单 " + total
                    + " 首，窗口起点=第 " + (startIndex + 1) + " 首）首项=" + summarizeItem(startItem)
                    + (skipped.length() == 0 ? "" : "；跳过无直链=" + skipped));

            // ③ 整单入队 → 选曲 → 播放；整单入队不被接受时退回单曲（0.5.x 已验证的那条路）
            StringBuilder steps = new StringBuilder();
            boolean queued = false;
            try {
                ctrl.getClass().getMethod("setPlaybackQueue", List.class, int.class)
                        .invoke(ctrl, items, 0);
                steps.append("setPlaybackQueue(整单 ").append(items.size()).append(" 首, 起点 0)");
                queued = true;
            } catch (Throwable t) {
                steps.append("setPlaybackQueue(整单) 跳过(").append(brief(t)).append(')');
            }
            if (!queued) {
                try {
                    ctrl.getClass().getMethod("setPlaybackQueue", itemCls).invoke(ctrl, startItem);
                    steps.append(" + setPlaybackQueue(单曲退路)");
                } catch (Throwable t) {
                    steps.append(" + setPlaybackQueue(单曲退路) 失败(").append(brief(t)).append(')');
                }
            }
            try {
                ctrl.getClass().getMethod("playMusicAt", int.class, boolean.class).invoke(ctrl, 0, true);
                steps.append(" + playMusicAt(0,true)");
            } catch (Throwable t) {
                steps.append(" + playMusicAt(0,true) 失败(").append(brief(t)).append(')');
            }
            try {
                ctrl.getClass().getMethod("play").invoke(ctrl);
                steps.append(" + play()");
            } catch (Throwable t) {
                steps.append(" + play() 失败(").append(brief(t)).append(')');
            }
            PluginLog.i(TAG, label + " #" + seq + " ③ " + steps
                    + "（代理调用=" + PROXY_CALLS.get() + "/总计=" + PROXY_ALL.get() + "）");

            // ③‴ 立即读回（队列项数能直接看出整单入队是否被接受）
            try {
                PluginLog.i(TAG, label + " #" + seq + " ③‴ 立即读回：" + hostStateDump());
            } catch (Throwable t) {
                PluginLog.d(TAG, label + " #" + seq + " ③‴ 立即读回失败：" + brief(t));
            }

            // ③⁺ 保活登记 + ⑤ 二次起播 + ⑥ 进度探针 + ④ 分级读回（与单曲版同一套判定口径）
            keepRegistryAlive(seq, label, startTrackId, startLoc);
            scheduleReplay(seq, label, startTrackId, startLoc);
            progressLater(seq, label, startTrackId, startLoc);
            scheduleStateReadback(seq, label);
            set(seq, "已提交给宿主播放器：整单 " + items.size() + " 首，从「" + nz(win.get(0).name()) + "」开始连播");
        } catch (Throwable t) {
            PluginLog.w(TAG, label + " #" + seq + " 整单注入失败：" + brief(t), t);
            set(seq, "失败：" + brief(t));
        }
    }

    // ------------------------------------------------------------ 交给播放器（0.3.4 级联 / 0.3.7-0.3.9 探针）

    /**
     * 把曲目项交给宿主播放器：级联 + 直驱 + 分级读回。
     *
     * <p>0.3.4 级联：{@code setPlaybackQueue(单曲)} → {@code playMusicAt(0,true)} → {@code play()}；
     * 0.3.7 直驱：绕过协程/命令通道直接调播放器上「单参 = 曲目项」的方法；
     * 0.3.8 立即读回：直驱之后马上读一次宿主真状态；
     * 0.3.6 分级读回：1.5 s / 4 s / 8 s 三次。</p>
     *
     * @param label 日志前缀（在线播放 / 本地试播）
     */
    private static void driveOnWorker(int seq, Object item, Object probeItem, String label, String title,
                                      String artist, String trackId, String location) {
        try {
            // 0.3.3 真机实证：只 setPlaybackQueue(单曲) + play() 时，队列里有项（自检队列项=1）
            // 但宿主什么也没播（代理调用=0、界面回落空态）⇒ 缺的是「选中当前项」这一步。
            Class<?> itemCls = item.getClass();
            Object ctrl = staticInstance(CLS_CONTROLLER);
            if (ctrl == null) {
                set(seq, "失败：宿主播放控制器不可用");
                return;
            }
            StringBuilder steps = new StringBuilder();
            try {
                ctrl.getClass().getMethod("setPlaybackQueue", itemCls).invoke(ctrl, item);
                steps.append("setPlaybackQueue(单曲)");
            } catch (Throwable t) {
                steps.append("setPlaybackQueue 跳过(").append(brief(t)).append(')');
            }
            boolean selected = false;
            try {
                ctrl.getClass().getMethod("playMusicAt", int.class, boolean.class).invoke(ctrl, 0, true);
                steps.append(" + playMusicAt(0,true)");
                selected = true;
            } catch (Throwable t) {
                steps.append(" + playMusicAt(0,true) 失败(").append(brief(t)).append(')');
            }
            if (!selected) {
                try {
                    ctrl.getClass().getMethod("playMusicAt", int.class, boolean.class).invoke(ctrl, 0, false);
                    steps.append(" + playMusicAt(0,false)");
                    selected = true;
                } catch (Throwable t) {
                    steps.append(" + playMusicAt(0,false) 失败(").append(brief(t)).append(')');
                }
            }
            // 0.3.6：无论选曲那条路是否抛异常，都**无条件**补一次 transport「播放」。
            // 取证（javap）：playMusicAt 是异步协程（serviceScope.launch → queue mutex → 两次队列
            // suspend 调用 → Dispatchers.getMain()），真机上只有「已调用」而播放器一直 Idle；
            // play() 走的是另一条路（playbackCommandChannel.trySend(PlaybackCommand$Play)），
            // 两条路都记下来，才能判断卡在哪一段。
            try {
                ctrl.getClass().getMethod("play").invoke(ctrl);
                steps.append(" + play()");
            } catch (Throwable t) {
                steps.append(" + play() 失败(").append(brief(t)).append(')');
            }
            if (!selected) {
                try {
                    ctrl.getClass().getMethod("playOrPause").invoke(ctrl);
                    steps.append(" + playOrPause()");
                } catch (Throwable t) {
                    steps.append(" + playOrPause() 失败(").append(brief(t)).append(')');
                }
            }
            PluginLog.i(TAG, label + " #" + seq + " ③ " + steps
                    + "（代理调用=" + PROXY_CALLS.get() + "/总计=" + PROXY_ALL.get() + "）");

            // ③″ 0.3.7：绕过宿主的协程 / 命令通道，**直接驱动播放器**。
            //     取证（javap -p）：`PlaybackService$playMusicAt$1$1$1$1` 的协程体最终就是调
            //     `PiscesPlayer` 上「单参 = PiscesMediaItem」的方法（public final Object 版 +
            //     public final void 版）。真机上 ③ 一路「已调用」而播放器恒为 Idle，
            //     所以这一步单独验证「播放器到底收不收我们的曲目项」，并把它自己的异常抓出来
            //     （宿主对这类失败不写日志）。
            try {
                Object svc = staticField(ctrl.getClass(), "PlaybackService", "service");
                Object player = findPlayer(svc);
                PluginLog.i(TAG, label + " #" + seq + " ③″ 直驱播放器：" + drivePlayer(player, item));
            } catch (Throwable t) {
                PluginLog.w(TAG, label + " #" + seq + " ③″ 直驱失败：" + brief(t), t);
            }

            // ③⁺ 0.3.13：**本版故意不调** setPlaybackAudioSource。
            //     0.3.12 真机反证：调它之后 ③⁺′ 与 ③‴ 都显示「注册表源=空」——切换器拿到 Request 后
            //     走 createLoadPlan，发现播放器当前项的位置**已经等于**目标位置（就是我们的 http 直链）
            //     ⇒ 判「无需切换」→ finishSourceSwitch → clearSource() 把登记清掉；随后宿主装载这个
            //     条目时没有任何 http 源登记，就把直链当文件打开 ⇒ FileNotOpened ⇒ 播放器退回 Idle。
            //     （③‴ 抓到过 播放器=Buffering：宿主确实开始装了，只是装法不对。）
            //     所以本版只做「保活登记 + 真拦截器也放进清单」，把装载交给宿主自己的链路。
            if (location != null && location.startsWith("http")) {
                PluginLog.i(TAG, label + " #" + seq + " ③⁺ 声源切换：本版不调 setPlaybackAudioSource（它会清空登记）；"
                        + "改为保活登记 登记=" + registryActivate(trackId, location)
                        + " 匹配=" + registryMatches(item));
                keepRegistryAlive(seq, label, trackId, location);
            }

            // ③⁺ᵃ 0.3.16：让宿主自己的声源切换器**真的发一次切换请求**。
            //     0.3.15 真机决定性进展：直调装载器已经能把这条网易云直链开成流、并分析出真曲目
            //     （装载器回调=MorvaniumAnalyzedTrack(trackProfile=MorvaniumTrackProfile(
            //     trackId=netease-3406368630, durationMs=170584, audibleRange=MorvaniumTimeRange(
            //     startMs=208, endMs=1669…)))）⇒ **取流层已通**（0.3.13/0.3.14 同一处是 FileNotOpened）。
            //     但 ③⁺′ 与 ④ 里 pendingSource / activeSource / lastManagedLoad 恒为空、播放器恒 Idle
            //     ⇒ 唯一解释：切换器只被「登记」喂养过，**从没收到过 select 请求**（宿主音乐视频走的
            //     就是 select → createLoadPlan → pendingSource → 播放器 adopt 这条临时声源的路）。
            if (location != null && location.startsWith("http")) {
                PluginLog.i(TAG, label + " #" + seq + " ③⁺ᵃ 切换请求：" + switcherSelect(trackId, location));
                chainReadbackLater(seq, label, 1200L, "③⁺ᵃ′");
            }

            // ③⁺′ 0.3.11：把「声源切换链」的中间状态读出来。0.3.10 真机里 ③⁺ 调了却毫无动静，
            //     必须分清三种情形：① select 没进 channel（latestRequest 空）；② createLoadPlan 判
            //     「不可用」（pendingSource 空）——它要求播放器**当前项**的 trackId 与请求一致；
            //     ③ 登记了但没人来装载（pendingSource 非空而播放器恒 Idle）。
            try {
                PluginLog.i(TAG, label + " #" + seq + " ③⁺′ 切换链：" + switcherDump()
                        + " 注册表源=" + registrySourceDump());
            } catch (Throwable t) {
                PluginLog.d(TAG, label + " #" + seq + " ③⁺′ 切换链读回失败：" + brief(t));
            }

            // ③‴ 0.3.8：直驱之后**立刻**读一次——1.5 秒后才读会漏掉「短暂进了 Loading 又退回 Idle」。
            try {
                PluginLog.i(TAG, label + " #" + seq + " ③‴ 立即读回：" + hostStateDump());
            } catch (Throwable t) {
                PluginLog.d(TAG, label + " #" + seq + " ③‴ 立即读回失败：" + brief(t));
            }

            // ③⁗ 0.3.11：直调宿主自己的「流装载器」。
            //     取证（javap + 常量池扫描）：`androidx.compose.ui.Ņ` 持有拦截器 List，其
            //     `Ņ(?, PlatformContext, List)` 构造 + 唯一公开方法 `(PiscesMediaItem, Continuation)`
            //     → 新建 `Ӂ$Ϳ$Ԩ(List, PlatformContext, item, J, J, I, ?, Ref$IntRef, Continuation)`
            //     → 逐个拦截器调 createStream，返回 Result<BASS 句柄>；而**唯一调用者**是宿主具体
            //     播放器 `com.xuncorp.pisces.Ԩ`（+ 它的两个内部类）。所以这一步只回答一个问题：
            //     「拦截器链能不能真的把 https 位置变成 BASS 句柄」——能，则缺的只是「让播放器自己
            //     触发装载」；若 代理调用 仍是 0，则说明装载器读的清单不是我们追加的那一份。
            try {
                Object svc2 = staticField(ctrl.getClass(), "PlaybackService", "service");
                Object player2 = findPlayer(svc2);
                PluginLog.i(TAG, label + " #" + seq + " ③⁗ 直调装载器："
                        + directLoad(seq, player2, probeItem, trackId, location));
                // ③⁗′ 0.3.12：把**真**音乐视频拦截器（不是代理）直接调一次——0.3.11 真机里
                //     装载器对 http 位置直接抛 FileNotOpened（根本没走拦截器链）。这一步只回答：
                //     「宿主自带的 HTTP-Range 流创建，对**我们这个**位置到底能不能出句柄？」
                //     0.3.13：调用前先重新登记（0.3.12 里那一刻登记已被清空），并把 matches 结果打出来。
                PluginLog.i(TAG, label + " #" + seq + " ③⁗′ 真拦截器："
                        + probeRealInterceptor(player2, probeItem, trackId, location));
                // ③⁗″ 0.3.19：直接调**播放器自己的取流方法**（javap 产物 `…Ԩ.bytecode.txt` 行 6201：
                //     `(PiscesMediaItem, Long, Continuation) → Result<androidx.compose.ui.Ӂ>`）。
                //     它的第一跳就是取 `Properties.ԯ()` 那份清单——所以本条同时回答两个问题：
                //     ① 播放器自己这条路能不能把 http 位置开成流；② ①′⁺ 的注入有没有接到正地方。
                PluginLog.i(TAG, label + " #" + seq + " ③⁗″ 播放器取流路径："
                        + streamPathProbe(player2, probeItem, trackId, location));
            } catch (Throwable t) {
                PluginLog.w(TAG, label + " #" + seq + " ③⁗ 直调装载器失败：" + brief(t), t);
            }

            // ⑤ 0.3.10：真机实证「本地普通路径 1.5 s 后 播放器=Idle→Ready」，但 Ready ≠ Playing。
            //     ③ 里的 play() 是在加载**之前**发的，所以 2.5 s 后（宿主加载完成之后）再补一次
            //     「选曲 + 播放」，验证「加载完了还得再点一次播放」这个假设。
            //     0.3.12：同时在这一刻**重激活注册表 + 重发声源切换**——0.3.11 真机证明 ③⁺ 发得太早
            //     （那一刻播放器的当前项还是上一首，createLoadPlan 的 trackId 闸门直接判不可用）。
            scheduleReplay(seq, label, trackId, location);

            // ⑥ 0.3.20：装载完成后的多拍「播放进度」探针（12/18/24 秒）。
            //     0.3.19 真机首次让在线条目从 Idle 走到 Ready（10:48:17 ④+4s），而 javap 实证
            //     宿主的状态枚举只有 {Buffering, Ended, Idle, Ready}——**没有 Playing**，
            //     所以「Ready 到底是在放还是只是装载完成」只能靠位置是否推进 + 宿主 logs.txt 里
            //     HTTP range 流是否保持打开来判。每拍顺便补一脚 playMusicAt+play（状态不是 Ready 时）。
            progressLater(seq, label, trackId, location);

            scheduleStateReadback(seq, label);
            set(seq, "已提交给宿主播放器：" + nz(title) + " - " + nz(artist));
        } catch (Throwable t) {
            PluginLog.w(TAG, label + " #" + seq + " 交给播放器失败：" + brief(t), t);
            set(seq, "失败：" + brief(t));
        }
    }

    // ------------------------------------------------------------ 声源切换器（0.3.10）

    /**
     * 把宿主自己的「声源切换器」装回 {@code PlaybackController.musicVideoAudioSwitcher}。
     *
     * <p><b>为什么必须走这一步</b>（javap 实证）：{@code PlaybackController.<clinit>} 里这个字段
     * 是 {@code aconst_null; putstatic}——桌面构建**写死成 null**；而
     * {@code setPlaybackAudioSource(a,b,J,Z)} 的字节码是
     * {@code getstatic musicVideoAudioSwitcher; dup; ifnull …; switcher.select(a,b,J,Z); return; …: pop; return}
     * ⇒ 字段为 null 时它是**静默空操作**（0.3.1 真机就见过 {@code musicVideoAudioSwitcher=null}）。
     * 装上之后：{@code select} → {@code activateSource = MusicVideoAudioSourceRegistry.activate(trackId, location)}
     * → {@code MusicVideoStreamInterceptor.createStream} 经 {@code HttpRangeBassStream} 做 HTTP Range
     * 真流式拉流（宿主自带的那条路，不再需要我们自己喂 URL 给播放器）。</p>
     *
     * <p>切换器本身用**宿主的工厂**造：{@code createMusicVideoAudioSwitcher(scope)} ＝
     * {@code new MusicVideoAudioSwitcher(scope, locationOf = item::getPath, copyWithLocation = copyWithPath,
     * isLocationSupported = 默认（恒 true）, beforeSourceLoad = $3, activateSource = registry::activate,
     * clearSource = registry::clear)}；装完立刻 {@code bind(player)}，把观察者从句柄挂到活动播放器上。</p>
     *
     * <p>字段是 {@code private static final}，普通反射写不进去（{@code VarHandle} 对 final 也不给写），
     * 只能用 {@code sun.misc.Unsafe}；宿主 JRE 含 {@code jdk.unsupported}（已核实 legal/jdk.unsupported）。</p>
     *
     * @return 中文单行摘要（永不抛）
     */
    private static String ensureSwitcherOnWorker() {
        try {
            Object ctrl = staticInstance(CLS_CONTROLLER);
            if (ctrl == null) {
                return "跳过：控制器不可用";
            }
            Field f = switcherField();
            if (f == null) {
                return "跳过：宿主流里没有 " + FIELD_SWITCHER + " 字段";
            }
            Object cur = staticField(ctrl.getClass(), CLS_SWITCHER, FIELD_SWITCHER);
            if (cur != null) {
                return "已存在（" + tname(cur.getClass()) + "）";
            }
            Object scope = staticField(ctrl.getClass(), "CoroutineScope", "scope");
            if (scope == null) {
                return "失败：拿不到宿主 CoroutineScope";
            }
            Class<?> kt = Class.forName(CLS_SWITCHER_KT);
            Class<?> scopeCls = Class.forName("kotlinx.coroutines.CoroutineScope");
            Object switcher = kt.getMethod("createMusicVideoAudioSwitcher", scopeCls).invoke(null, scope);
            if (switcher == null) {
                return "失败：宿主工厂返回 null";
            }
            String why = putStaticFinal(f, switcher);
            if (why != null) {
                return "失败：写入静态字段失败（" + why + "）";
            }
            Object back = staticField(ctrl.getClass(), CLS_SWITCHER, FIELD_SWITCHER);
            StringBuilder sb = new StringBuilder();
            sb.append(back == null ? "未生效（读回仍为空）" : "已安装（" + tname(back.getClass()) + "）");
            Object svc = staticField(ctrl.getClass(), "PlaybackService", "service");
            Object player = findPlayer(svc);
            if (back == null || player == null) {
                sb.append(" + 未 bind（").append(player == null ? "找不到播放器" : "字段未生效").append(')');
                return sb.toString();
            }
            try {
                Class<?> playerCls = Class.forName(CLS_PLAYER);
                back.getClass().getMethod("bind", playerCls).invoke(back, player);
                sb.append(" + 已 bind 播放器");
            } catch (Throwable t) {
                sb.append(" + bind 失败(").append(brief(t)).append(')');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "失败：" + brief(t);
        }
    }

    /** 声源切换器的静态字段（找不到返回 null）。 */
    private static Field switcherField() {
        try {
            return Class.forName(CLS_CONTROLLER).getDeclaredField(FIELD_SWITCHER);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 0.3.17：给音频直链造一个「孪生占位位置」——只多一个无害查询参数，scheme 仍是 http/https。
     *
     * <p>用途见 {@code injectOnWorker} ②：切换器的 {@code createLoadPlan} 只有在
     * 「曲目项当前位置 ≠ 请求位置」时才造临时声源；把真链留给请求、占位留给曲目项，才能撬开那道闸门。
     * 占位链永远不会被真正取流（切换器会用请求位置做 {@code copyWithPath} 替换后再装载）。</p>
     */
    private static String altLocation(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        return url + (url.indexOf('?') >= 0 ? "&" : "?") + "spwPlaceholder=1";
    }

    /** 0.3.16：当前挂在 {@code PlaybackController.musicVideoAudioSwitcher} 上的切换器实例（没装返回 null）。 */
    private static Object switcherInstance() {
        Object ctrl = staticInstance(CLS_CONTROLLER);
        if (ctrl == null) {
            return null;
        }
        try {
            return staticField(ctrl.getClass(), CLS_SWITCHER, FIELD_SWITCHER);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 0.3.16：让宿主的声源切换器发一次
     * {@code select(location, trackId, positionMs, play)}——宿主音乐视频播临时声源走的就是它。
     *
     * <p>它内部会写出 {@code latestRequest}，再由 {@code createLoadPlan} 结合播放器**当前项**算出
     * {@code pendingSource}；0.3.15 真机里 {@code latestRequest} 非空却「值取不到」、{@code pendingSource}
     * 恒空，只能说明请求从未真正发出。JVM 里串扰后的 {@code select} 可能被编译成挂起函数
     * （尾参 {@code Continuation}），所以两种形状都试。</p>
     *
     * @return 中文单行摘要（永不抛）
     */
    private static String switcherSelect(String trackId, String location) {
        try {
            Object sw = switcherInstance();
            if (sw == null) {
                return "跳过：切换器未安装";
            }
            Method plain = null;
            Method suspend = null;
            for (Method m : sw.getClass().getDeclaredMethods()) {
                if (!m.getName().startsWith("select") || m.isSynthetic()) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 4 && ps[0] == String.class && ps[1] == String.class && ps[2] == long.class
                        && (ps[3] == boolean.class || ps[3] == Boolean.class)) {
                    plain = m;
                    break;
                }
                if (ps.length == 5 && ps[0] == String.class && ps[1] == String.class && ps[2] == long.class
                        && "kotlin.coroutines.Continuation".equals(ps[4].getName())) {
                    suspend = m;
                }
            }
            if (plain != null) {
                plain.setAccessible(true);
                Object r = plain.invoke(sw, nz(location), nz(trackId), 0L, Boolean.TRUE);
                return "select(" + describeUrl(location) + ", " + nz(trackId) + ", 0, true) -> " + rawText(r);
            }
            if (suspend != null) {
                suspend.setAccessible(true);
                Object r = suspend.invoke(sw, nz(location), nz(trackId), 0L, Boolean.TRUE, continuationProxy());
                return "select（挂起形，" + describeUrl(location) + ", " + nz(trackId) + ", 0, true) -> " + rawText(r);
            }
            return "找不到 select(String,String,long,boolean)";
        } catch (Throwable t) {
            return "失败：" + brief(t);
        }
    }

    /**
     * 用 {@code sun.misc.Unsafe} 写 {@code private static final} 字段（普通反射写不进去）。
     *
     * @return null 表示成功，否则返回失败摘要
     */
    private static String putStaticFinal(Field f, Object value) {
        try {
            f.setAccessible(true);
            Class<?> uc = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = uc.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object u = theUnsafe.get(null);
            Object base = uc.getMethod("staticFieldBase", Field.class).invoke(u, f);
            Object off = uc.getMethod("staticFieldOffset", Field.class).invoke(u, f);
            uc.getMethod("putObject", Object.class, long.class, Object.class)
                    .invoke(u, base, off, value);
            return null;
        } catch (Throwable t) {
            return brief(t);
        }
    }

    // ------------------------------------------------------------ 直调装载器 + 切换链读回（0.3.11）

    /** 宿主「流装载器」类名：{@code androidx.compose.ui.Ņ}（U+0145）。 */
    private static String loaderClass() {
        return "androidx.compose.ui." + cp(0x0145);
    }

    /** 在播放器（含其字段 1~2 层）里找宿主自己的流装载器实例。 */
    private static Object loaderOf(Object player) {
        if (player == null) {
            return null;
        }
        String want = loaderClass();
        for (Class<?> c = player.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                    continue;
                }
                Object v = readField(f, player);
                if (v == null) {
                    continue;
                }
                if (v.getClass().getName().equals(want)) {
                    return v;
                }
                String n = v.getClass().getName();
                if (n.startsWith("java.") || v.getClass().isArray()) {
                    continue;
                }
                for (Class<?> c2 = v.getClass(); c2 != null && c2 != Object.class; c2 = c2.getSuperclass()) {
                    for (Field g : c2.getDeclaredFields()) {
                        if (Modifier.isStatic(g.getModifiers()) || g.isSynthetic()) {
                            continue;
                        }
                        Object w = readField(g, v);
                        if (w != null && w.getClass().getName().equals(want)) {
                            return w;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 直调装载器：调它「首参 = {@code PiscesMediaItem}」的公开方法（真机上是
     * {@code (PiscesMediaItem, Continuation)}），并动态代理一个 {@code kotlin.coroutines.Continuation}
     * 接住 {@code resumeWith} 的结果。
     *
     * <p>这一步**只做验证**：它拿返回值 / 挂起情况 / 代理调用增量来回答「拦截器链到底能不能把
     * https 位置变成 BASS 句柄」。它不负责让宿主开始播放（装载器返回的句柄没人接手）。</p>
     */
    private static String directLoad(int seq, Object player, Object item, String trackId, String location) {
        // 0.3.12：回调是**异步**的（真机证明本地路径 400 ms 内不一定回来）。RESUME 是静态的，
        // 上一次调用的回调会污染下一次的输出 ⇒ 每次进入先清空，并打上本次 seq 便于归因。
        RESUME.set(null);
        Object loader = loaderOf(player);
        if (loader == null) {
            return "未找到装载器（" + esc(loaderClass()) + "）";
        }
        StringBuilder sb = new StringBuilder("装载器=").append(shortName(loader.getClass()));
        // 0.3.13：0.3.12 真机里这一刻「注册表源=空」（被声源切换器 clear 了），装载器于是把直链
        // 当文件打开 ⇒ FileNotOpened。本版在直调之前**先重新登记**，把「有登记 / 无登记」变成唯一变量。
        if (location != null && location.startsWith("http")) {
            sb.append(" 登记=").append(registryActivate(trackId, location))
                    .append(" 匹配=").append(registryMatches(item));
        }
        List<Method> ms = new ArrayList<>();
        for (Method m : loader.getClass().getMethods()) {
            if (m.isSynthetic() || m.isBridge() || m.getParameterCount() == 0
                    || !CLS_ITEM.equals(m.getParameterTypes()[0].getName())) {
                continue;
            }
            ms.add(m);
        }
        if (ms.isEmpty()) {
            return sb.append(" 无「首参=曲目项」的公开方法").toString();
        }
        ms.sort(Comparator.comparingInt((Method m) -> m.getParameterCount()).thenComparing(Method::getName));
        for (Method m : ms) {
            sb.append("；").append(esc(m.getName())).append('/').append(m.getParameterCount()).append("参->");
            try {
                Object[] args = new Object[m.getParameterCount()];
                args[0] = item;
                for (int i = 1; i < args.length; i++) {
                    args[i] = continuationProxy();
                }
                long before = PROXY_CALLS.get();
                Object r = m.invoke(loader, args);
                sleepQuiet(400L);
                long delta = PROXY_CALLS.get() - before;
                if (isSuspended(r)) {
                    sb.append("挂起中");
                } else {
                    sb.append(resumeText(r));
                }
                sb.append("（代理调用+").append(delta).append('/').append(PROXY_CALLS.get()).append("）");
                if (RESUME.get() != null) {
                    sb.append(" 回调=").append(RESUME.get());
                }
            } catch (Throwable t) {
                sb.append("异常(").append(brief(t)).append(')');
            }
        }
        return sb.toString();
    }

    /** 动态代理一个 {@code kotlin.coroutines.Continuation}：把 {@code resumeWith} 的结果记进 {@link #RESUME}。 */
    private static Object continuationProxy() {
        try {
            Class<?> cont = Class.forName("kotlin.coroutines.Continuation");
            return Proxy.newProxyInstance(cont.getClassLoader(), new Class<?>[]{cont}, (p, m, a) -> {
                String n = m.getName();
                if ("toString".equals(n)) {
                    return "netease-continuation";
                }
                if ("hashCode".equals(n)) {
                    return System.identityHashCode(p);
                }
                if ("equals".equals(n)) {
                    Object o = a == null || a.length == 0 ? null : a[0];
                    return o == p;
                }
                if ("getContext".equals(n)) {
                    try {
                        return Class.forName("kotlin.coroutines.EmptyCoroutineContext").getField("INSTANCE").get(null);
                    } catch (Throwable t) {
                        return null;
                    }
                }
                if ("resumeWith".equals(n)) {
                    Object r = a == null || a.length == 0 ? null : a[0];
                    String text = resumeText(r);
                    RESUME.set(text);
                    PluginLog.i(TAG, "装载器回调：resumeWith(" + text + ") 代理调用="
                            + PROXY_CALLS.get() + "/总计=" + PROXY_ALL.get());
                }
                return null;
            });
        } catch (Throwable t) {
            return null;
        }
    }

    /** Kotlin 协程挂起哨兵判定（{@code IntrinsicsKt.getCOROUTINE_SUSPENDED()}）。 */
    private static boolean isSuspended(Object r) {
        try {
            Object s = Class.forName("kotlin.coroutines.intrinsics.IntrinsicsKt")
                    .getMethod("getCOROUTINE_SUSPENDED").invoke(null);
            return r == s;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 返回值 / resumeWith 结果的可读摘要（{@code Result} 的 toString 就够用，过长则截断）。 */
    private static String resumeText(Object r) {
        if (r == null) {
            return "null";
        }
        try {
            String s = String.valueOf(r);
            if (s.length() > 160) {
                s = s.substring(0, 160) + "…";
            }
            return esc(s);
        } catch (Throwable t) {
            return summary(r);
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 声源切换器的关键字段摘要：查明 0.3.10 的 select 走到了哪一步。 */
    private static String switcherDump() {
        try {
            Object sw = staticField(Class.forName(CLS_CONTROLLER), CLS_SWITCHER, FIELD_SWITCHER);
            if (sw == null) {
                return "空";
            }
            StringBuilder sb = new StringBuilder(shortName(sw.getClass()));
            for (String name : new String[]{"latestRequest", "pendingSource", "activeSource",
                    "lastManagedLoad", "player", "observesPlayer", "processingJob", "inFlightSources"}) {
                Object v = fieldByName(sw, name);
                sb.append(' ').append(name).append('=');
                if (v == null) {
                    sb.append("空");
                } else if ("latestRequest".equals(name)) {
                    // 0.3.12：要的是 StateFlow 的**当前值**（= 最近一次 select 的 Request），
                    // 不是它内部槽位（0.3.11 真机里打出了一堆 sequence/slots，看不出请求内容）。
                    Object val = flowValue(v);
                    sb.append(val == null ? "非空(值取不到)" : "非空(" + summary(val) + ")");
                } else if ("player".equals(name)) {
                    // 0.3.11 真机教训：这里深挖会把每行日志撑到 3 KB，且信息量极低。
                    sb.append(tname(v.getClass()));
                } else if ("processingJob".equals(name)) {
                    sb.append(shortName(v.getClass()));
                } else if (v instanceof Boolean || v instanceof Number) {
                    sb.append(v);
                } else if (v instanceof java.util.Collection<?> col) {
                    sb.append("大小").append(col.size());
                } else {
                    sb.append(instanceFieldDump(v, 3));
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "读取失败：" + brief(t);
        }
    }

    /** 取 {@code StateFlow.getValue()}／{@code MutableStateFlow.getValue()} 的当前值，取不到返回 null。 */
    private static Object flowValue(Object flow) {
        try {
            return flow.getClass().getMethod("getValue").invoke(flow);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 0.3.12：直接调**真**音乐视频拦截器（{@code MusicVideoStreamInterceptor}，不是 0.3.2 那个代理），
     * 用我们自己构造的曲目项当参数，看宿主的 HTTP-Range 流创建能不能真的出句柄。
     *
     * <p>依据：0.3.11 javap 证明 `MusicVideoStreamInterceptor.createStream(PlatformContext, PiscesMediaItem,
     * long, long, int, Continuation&lt;Result&lt;Integer&gt;&gt;)` 是「http 位置 → BASS 句柄」的唯一实现，
     * 而 `MusicVideoStreamInterceptorKt.isHttpLocation` 对 http/https 都返回 true（URI.getScheme 比较，
     * 大小写不敏感）⇒ 方案不是底色问题。真机里装载器却对 http 位置直接抛 FileNotOpened（没走拦截器链），
     * 所以必须单独验证「这个实现本身对我们这个位置是什么结果」。</p>
     */
    private static String probeRealInterceptor(Object player, Object item, String trackId, String location) {
        try {
            Object itc = hostInterceptorInstance(null, player);
            if (itc == null) {
                return "取不到真拦截器实例";
            }
            // 0.3.13：先重新登记再调——0.3.12 里这一刻登记已被声源切换器清空（注册表源=空），
            // 拦截器于是对这个位置无从下手（返回 null、无回调）。本版把登记补上，matches 也一并打出来。
            StringBuilder head0 = new StringBuilder();
            if (location != null && location.startsWith("http")) {
                head0.append("登记=").append(registryActivate(trackId, location))
                        .append(" 匹配=").append(registryMatches(item)).append(' ');
            }
            Object ctx = instanceField(player, "com.xuncorp.pisces.PlatformContext", null);
            Method target = null;
            for (Method m : itc.getClass().getMethods()) {
                if (m.getName().startsWith("createStream") && m.getParameterCount() == 6) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                return head0 + "真拦截器上没有 createStream*(6) 方法";
            }
            RESUME.set(null);
            Object r = target.invoke(itc, ctx, item, 0L, 0L, 0, continuationProxy());
            sleepQuiet(900L);
            String head = head0 + esc(target.getName()) + "->"
                    + (isSuspended(r) ? "COROUTINE_SUSPENDED" : resumeText(r));
            return head + (RESUME.get() == null ? "（无回调）" : " 回调=" + RESUME.get());
        } catch (Throwable t) {
            return "抛异常：" + brief(t) + (RESUME.get() == null ? "" : " 回调=" + RESUME.get());
        }
    }

    /**
     * 登记一个「可在 http(s) 位置取流」的源（{@code MusicVideoAudioSourceRegistry.activate(trackId, location)}），
     * 返回中文单行摘要（永不抛）。
     *
     * <p>为什么必须保持它非空：宿主自带的 {@code MusicVideoStreamInterceptor} 只认「已登记过的源」，
     * 登记一旦被清（0.3.12 真机：声源切换器 {@code clearSource()}），装载器就会把 http 直链**当文件**
     * 打开 ⇒ {@code com.xuncorp.pisces.ԥ: FileNotOpened} ⇒ 播放器退回 Idle。</p>
     */
    private static String registryActivate(String trackId, String location) {
        try {
            Object reg = staticInstance(CLS_REGISTRY);
            if (reg == null) {
                return "失败：注册表不可用";
            }
            reg.getClass().getMethod("activate", String.class, String.class)
                    .invoke(reg, nz(trackId), nz(location));
            return "已登记(" + nz(trackId) + ", " + describeUrl(location) + ")";
        } catch (Throwable t) {
            return "失败：" + brief(t);
        }
    }

    /** 宿主注册表是否认这个曲目项（{@code MusicVideoAudioSourceRegistry.matches(PiscesMediaItem)}）。 */
    private static String registryMatches(Object item) {
        try {
            Object reg = staticInstance(CLS_REGISTRY);
            if (reg == null) {
                return "取不到";
            }
            Object r = reg.getClass().getMethod("matches", Class.forName(CLS_ITEM)).invoke(reg, item);
            return Boolean.TRUE.equals(r) ? "真" : "假(" + resumeText(r) + ")";
        } catch (Throwable t) {
            return "取不到(" + brief(t) + ")";
        }
    }

    /**
     * 保活登记：在装载窗口内反复 {@code activate} 并读回，把「登记有没有活到宿主来取的时刻」
     * 变成可观测事实（0.3.12 真机里它在上载之前就被清空了）。
     */
    private static void keepRegistryAlive(int seq, String label, String trackId, String location) {
        long[] delays = {400L, 900L, 1600L, 2400L};
        for (long d : delays) {
            try {
                Timers.later(d, () -> {
                    try {
                        HostBridgeWorker.get().submit(() -> {
                            try {
                                PluginLog.i(TAG, label + " #" + seq + " ③⁺" + d + "ms 保活：" + registryActivate(trackId, location)
                                        + " 读回=" + registrySourceDump());
                            } catch (Throwable t) {
                                PluginLog.d(TAG, label + " #" + seq + " ③⁺" + d + "ms 保活读回失败：" + brief(t));
                            }
                        });
                    } catch (Throwable t) {
                        PluginLog.d(TAG, label + " #" + seq + " ③⁺" + d + "ms 投递失败：" + brief(t));
                    }
                });
            } catch (Throwable t) {
                PluginLog.d(TAG, label + " #" + seq + " ③⁺保活定时失败：" + brief(t));
                return;
            }
        }
    }

    /** 注册表里当前登记的源（{@code MusicVideoAudioSourceRegistry.activeSource}）。 */
    private static String registrySourceDump() {
        try {
            Object s = readActiveSource();
            return s == null ? "空" : instanceFieldDump(s, 4);
        } catch (Throwable t) {
            return "读取失败：" + brief(t);
        }
    }

    /** 按**精确字段名**取实例字段值（{@code instanceField} 是 contains 语义，诊断用这个更稳）。 */
    private static Object fieldByName(Object target, String name) {
        if (target == null || name == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic() || !f.getName().equals(name)) {
                    continue;
                }
                return readField(f, target);
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 本地对照实验（0.3.9）

    /**
     * 本地文件对照（异步、永不抛）：拿宿主本来就会播的**本地音频文件**走同一条注入链。
     *
     * <p><b>为什么要做这个对照</b>：在线链路已经证明——宿主收下了条目（播放器自己的
     * {@code 当前项} 字段就是它）、队列里也有它、队列锁空闲、协程作用域 Active，但
     * {@code 播放器=Idle} 且我们的拦截器代理**一次都没被调过**（{@code 代理调用=0/总计=0}）。
     * 本地文件是宿主**本来就会播**的东西：如果它在同一条链上也不播，说明我们调的这些方法
     * 根本不是「起播入口」，得换入口（命令通道 prepare 等）；如果它播了，那问题就收窄到
     * 「宿主不认 HTTP 位置」这一段。</p>
     *
     * <p>两种路径写法都试：宿主库里存的是 {@code file:///…} URI，而曲目项的「播放位置」字段
     * 可能吃的是普通路径——两种各跑一次，日志里按 {@code 本地试播 #N} 序号区分。</p>
     */
    public static void tryLocal() {
        try {
            NET.execute(() -> {
                List<String> files = localAudioCandidates();
                if (files.isEmpty()) {
                    PluginLog.w(TAG, "本地试播：本地没找到音频文件（跳过对照）");
                    return;
                }
                String file = files.get(0);
                String title = baseName(file);
                PluginLog.i(TAG, "本地试播：对照文件=" + esc(file));
                HostBridgeWorker.get().submit(() -> injectLocalOnWorker(
                        SEQ.incrementAndGet(), file, title, "本地文件", "本地对照"));
                final String uri = toFileUri(file);
                Timers.later(12000L, () -> {
                    try {
                        HostBridgeWorker.get().submit(() -> injectLocalOnWorker(
                                SEQ.incrementAndGet(), uri, title, "本地文件(file://)", "本地对照"));
                    } catch (Throwable t) {
                        PluginLog.w(TAG, "本地试播（URI 形态）投递失败：" + brief(t), t);
                    }
                });
            });
        } catch (Throwable t) {
            PluginLog.e(TAG, "本地试播投递失败（已吞）", t);
        }
    }

    private static void injectLocalOnWorker(int seq, String path, String title, String artist, String album) {
        try {
            Class<?> itemCls = Class.forName(CLS_ITEM);
            Constructor<?> ctor = itemCls.getConstructor(String.class, String.class, String.class,
                    String.class, String.class, String.class);
            String trackId = "local-" + seq;
            Object item = ctor.newInstance(trackId, nz(title), nz(artist), nz(album), nz(artist), path);
            PluginLog.i(TAG, "本地试播 #" + seq + " ① 跳过注册表（对照：本地文件）");
            PluginLog.i(TAG, "本地试播 #" + seq + " ①′ 拦截器：" + ensureInterceptorOnWorker());
            PluginLog.i(TAG, "本地试播 #" + seq + " ② 曲目项已构造：" + summarizeItem(item));
            // 本地对照：location 传本地路径（非 http ⇒ ③⁺ 声源切换自动跳过），label 传「本地试播」
            // 以便 ④ 读回行能正确区分在线/本地（修 0.3.9 真机里的标签错）。
            driveOnWorker(seq, item, item, "本地试播", title, artist, trackId, path);
        } catch (Throwable t) {
            PluginLog.w(TAG, "本地试播 #" + seq + " 注入失败：" + brief(t), t);
        }
    }

    /** 用户音乐目录下的前几个音频文件（最多 3 个，深度 ≤ 3）。 */
    private static List<String> localAudioCandidates() {
        List<String> out = new ArrayList<>();
        try {
            collectAudio(new File(System.getProperty("user.home", "."), "Music"), 0, out);
        } catch (Throwable t) {
            PluginLog.d(TAG, "本地试播：扫描音乐目录失败：" + brief(t));
        }
        return out;
    }

    private static void collectAudio(File dir, int depth, List<String> out) {
        if (dir == null || depth > 3 || out.size() >= 3 || !dir.isDirectory()) {
            return;
        }
        File[] fs = dir.listFiles();
        if (fs == null) {
            return;
        }
        for (File f : fs) {
            if (out.size() >= 3) {
                return;
            }
            if (f.isDirectory()) {
                collectAudio(f, depth + 1, out);
            } else if (isAudioName(f.getName())) {
                out.add(f.getAbsolutePath());
            }
        }
    }

    private static boolean isAudioName(String name) {
        String s = name.toLowerCase();
        return s.endsWith(".mp3") || s.endsWith(".flac") || s.endsWith(".wav")
                || s.endsWith(".m4a") || s.endsWith(".ogg");
    }

    private static String baseName(String path) {
        int i = path.lastIndexOf(File.separatorChar);
        String n = i < 0 ? path : path.substring(i + 1);
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static String toFileUri(String path) {
        return "file:///" + path.replace('\\', '/');
    }

    // ------------------------------------------------------------ 宿主状态读回（0.3.4 / 分级 0.3.6）

    /**
     * 注入后分级延迟读回宿主真状态。
     *
     * <p>「已调用」不等于「已播放」——宿主对不认识的媒体项可能静默失败。0.3.6 起读三次
     * （1.5 s / 4 s / 8 s）：{@code playMusicAt} 的协程链（mutex → 两次队列 suspend 调用 →
     * {@code Dispatchers.getMain()}）若被挂住，三次读回的 {@code 播放器=Idle} 会持续不动，
     * 而 {@code 队列锁} 会一直是「已占」。</p>
     */
    private static void scheduleStateReadback(int seq, String label) {
        readbackLater(seq, 1500L, "④", label);
        readbackLater(seq, 4000L, "④+4s", label);
        readbackLater(seq, 8000L, "④+8s", label);
    }

    /**
     * 0.3.10 ⑤：加载完成后再补一次「选曲 + 播放」。
     *
     * <p>真机实证：本地普通路径 1.5 s 后 {@code 播放器=Idle→Ready}（宿主确实加载了我们的条目），
     * 但 {@code Ready} 不等于 {@code Playing}——③ 里的 {@code play()} 是在加载**之前**发的。
     * 这里 2.5 s 后再补一次，验证「宿主加载完了还得再点一次播放」这个假设（对在线/本地都跑）。</p>
     */
    private static void scheduleReplay(int seq, String label, final String trackId, final String location) {
        try {
            final Object ctrl = staticInstance(CLS_CONTROLLER);
            if (ctrl == null) {
                return;
            }
            Timers.later(2500L, () -> {
                try {
                    HostBridgeWorker.get().submit(() -> {
                        try {
                            Object before = callAnyNoArg(ctrl, "getPlayerState");
                            ctrl.getClass().getMethod("playMusicAt", int.class, boolean.class)
                                    .invoke(ctrl, 0, true);
                            ctrl.getClass().getMethod("play").invoke(ctrl);
                            PluginLog.i(TAG, label + " #" + seq + " ⑤ 二次起播（之前状态="
                                    + (before == null ? "取不到" : summary(before))
                                    + "）：playMusicAt(0,true) + play() 已调用");
                            // ⑤′ 0.3.13：只重登记、**不调** setPlaybackAudioSource——0.3.12 真机证明
                            //     调它会把登记清空（切换器判「无需切换」→ clearSource），而登记一空，
                            //     宿主装载 http 位置时就当文件打开（FileNotOpened）。
                            //     0.3.16：在重登记之后补一次切换器 select（第二次机会：此时队列里已经有
                            //     我们的条目、播放器也刚被 ⑤ 推过一脚），再 2 秒读切换链。
                            if (location != null && location.startsWith("http")) {
                                PluginLog.i(TAG, label + " #" + seq + " ⑤′ 重登记 + 重发切换请求："
                                        + registryActivate(trackId, location)
                                        + " 读回=" + registrySourceDump()
                                        + "；" + switcherSelect(trackId, location));
                            }
                            chainReadbackLater(seq, label, 2000L, "⑤″");
                        } catch (Throwable t) {
                            PluginLog.w(TAG, label + " #" + seq + " ⑤ 二次起播失败：" + brief(t), t);
                        }
                    });
                } catch (Throwable t) {
                    PluginLog.w(TAG, label + " #" + seq + " ⑤ 投递宿主线程失败：" + brief(t), t);
                }
            });
        } catch (Throwable t) {
            PluginLog.w(TAG, label + " #" + seq + " ⑤ 定时器不可用：" + brief(t), t);
        }
    }

    /** 0.3.12：延迟后在宿主线程打一行「切换链 + 注册表源」（判定 ⑤′ 有没有被宿主看在眼里）。 */
    private static void chainReadbackLater(int seq, String label, long delayMs, String tag) {
        try {
            Timers.later(delayMs, () -> {
                try {
                    HostBridgeWorker.get().submit(() -> {
                        try {
                            PluginLog.i(TAG, label + " #" + seq + " " + tag + " 切换链："
                                    + switcherDump() + " 注册表源=" + registrySourceDump());
                        } catch (Throwable t) {
                            PluginLog.w(TAG, label + " #" + seq + " " + tag + " 读回失败：" + brief(t), t);
                        }
                    });
                } catch (Throwable t) {
                    PluginLog.w(TAG, label + " #" + seq + " " + tag + " 投递失败：" + brief(t), t);
                }
            });
        } catch (Throwable t) {
            PluginLog.w(TAG, label + " #" + seq + " " + tag + " 定时失败：" + brief(t), t);
        }
    }

    /** 单个读回点：延迟 delayMs 后在宿主线程打一行 {@code <label> #seq <tag> 宿主状态：…}。 */
    private static void readbackLater(int seq, long delayMs, String tag, String label) {
        try {
            Timers.later(delayMs, () -> {
                try {
                    HostBridgeWorker.get().submit(() -> {
                        try {
                            PluginLog.i(TAG, label + " #" + seq + " " + tag
                                    + " 宿主状态：" + hostStateDump());
                        } catch (Throwable t) {
                            PluginLog.w(TAG, label + " #" + seq + " " + tag
                                    + " 读回失败：" + brief(t), t);
                        }
                    });
                } catch (Throwable t) {
                    PluginLog.w(TAG, label + " #" + seq + " " + tag
                            + " 提交宿主线程失败：" + brief(t), t);
                }
            });
        } catch (Throwable t) {
            PluginLog.w(TAG, label + " #" + seq + " " + tag + " 定时器不可用：" + brief(t), t);
        }
    }

    /**
     * 0.3.20：播放进度探针——宿主 {@code PiscesPlayer} 上「无参、返回 long」的方法
     * （javap 常量池 {@code #500 ()J}，宿主自己多处调用它取值）。
     *
     * <p>返回毫秒，取不到返回 {@code -1}。<b>两次采样的差就是「真的在放」的硬证据</b>：
     * 宿主状态枚举只有 {@code Buffering/Ended/Idle/Ready}（没有 Playing），
     * 单看 {@code Ready} 分不清「在放」与「装载完但没放」。</p>
     */
    private static long playerPosition(Object player) {
        if (player == null) {
            return -1L;
        }
        try {
            for (Method m : player.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == long.class) {
                    m.setAccessible(true);
                    Object r = m.invoke(player);
                    if (r instanceof Long v) {
                        return v;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 落到下面按声明方法找
        }
        try {
            for (Class<?> c = player.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && m.getReturnType() == long.class
                            && !Modifier.isStatic(m.getModifiers())) {
                        m.setAccessible(true);
                        Object r = m.invoke(player);
                        if (r instanceof Long v) {
                            return v;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 探测失败按取不到处理
        }
        return -1L;
    }

    /**
     * 0.3.20 ⑥：装载完成后的多拍进度探针（+12/+18/+24 秒）。
     *
     * <p>每拍打一行「播放器状态 + 位置 + 当前项 + 代理调用」，并在状态不是 {@code Ready}
     * 时补一脚 {@code playMusicAt(0,true) + play()}（排除「装载完了但没收到播放命令」）。
     * 判定：位置在两拍之间明显推进 ⇒ 宿主真的在放这条在线流（北极星判据）。</p>
     */
    private static void progressLater(int seq, String label, String trackId, String location) {
        long[] delays = {12000L, 18000L, 24000L};
        for (long d : delays) {
            final long delay = d;
            try {
                Timers.later(delay, () -> {
                    try {
                        HostBridgeWorker.get().submit(() -> {
                            try {
                                Object ctrl = staticInstance(CLS_CONTROLLER);
                                Object service = ctrl == null ? null
                                        : staticField(ctrl.getClass(), "PlaybackService", "service");
                                Object player = findPlayer(service);
                                long pos = playerPosition(player);
                                Object st = ctrl == null ? null : callAnyNoArg(ctrl, "getPlayerState");
                                String stt = st == null ? "取不到" : summary(st);
                                String kick = "";
                                if (ctrl != null && !stt.contains("Ready")) {
                                    ctrl.getClass().getMethod("playMusicAt", int.class, boolean.class)
                                            .invoke(ctrl, 0, true);
                                    ctrl.getClass().getMethod("play").invoke(ctrl);
                                    kick = "；补 playMusicAt(0,true)+play()";
                                }
                                Object cur = player == null ? null : instanceField(player, CLS_ITEM, null);
                                PluginLog.i(TAG, label + " #" + seq + " ⑥+" + (delay / 1000L) + "s 播放进度：播放器="
                                        + stt + " 位置=" + (pos < 0 ? "取不到" : pos + "ms")
                                        + " 当前项=" + (cur == null ? "空" : summarizeItem(cur))
                                        + " 代理调用=" + PROXY_CALLS.get() + "/总计=" + PROXY_ALL.get()
                                        + kick);
                            } catch (Throwable t) {
                                PluginLog.w(TAG, label + " #" + seq + " ⑥+" + (delay / 1000L) + "s 读回失败："
                                        + brief(t), t);
                            }
                        });
                    } catch (Throwable t) {
                        PluginLog.w(TAG, label + " #" + seq + " ⑥+" + (delay / 1000L) + "s 投递失败："
                                + brief(t), t);
                    }
                });
            } catch (Throwable t) {
                PluginLog.w(TAG, label + " #" + seq + " ⑥+" + (delay / 1000L) + "s 定时失败：" + brief(t), t);
            }
        }
    }

    /**
     * 宿主真状态摘要：队列（模式/队列/索引/顺序/数量/播放中）＋ 播放器状态 ＋ 播放器状态字段
     * ＋ 队列互斥量 ＋ 当前项 ＋ 代理调用。
     */
    private static String hostStateDump() {
        StringBuilder sb = new StringBuilder();
        try {
            Object ctrl = staticInstance(CLS_CONTROLLER);
            Object service = ctrl == null ? null : staticField(ctrl.getClass(), "PlaybackService", "service");
            Object flow = ctrl == null ? null : staticField(ctrl.getClass(), "StateFlow", "playbackQueueState");
            if (flow == null && service != null) {
                flow = callAnyNoArg(service, "getPlaybackQueueState");
            }
            Object state = flow == null ? null : callAnyNoArg(flow, "getValue");
            sb.append("队列=").append(dumpQueueState(state));
            Object pstate = ctrl == null ? null : callAnyNoArg(ctrl, "getPlayerState");
            sb.append(" 播放器=").append(pstate == null ? "取不到" : summary(pstate));
            Object player = findPlayer(service);
            // 0.3.6：播放器自身的 State 字段（PiscesPlayer.State，混淆名）——比 getPlayerState 更贴近内部
            Object pField = player == null ? null : instanceField(player, "PiscesPlayer$State", null);
            sb.append(" 播放器字段=").append(pField == null ? "空" : summary(pField));
            // 0.3.6：队列互斥量（playMusicAt 协程第一步就是抢它）——只打 int/boolean 字段
            Object mutex = service == null ? null
                    : instanceField(service, "kotlinx.coroutines.sync.Mutex", null);
            sb.append(" 队列锁=").append(mutex == null ? "未取到" : lockDump(mutex));
            Object cur = player == null ? null : instanceField(player, CLS_ITEM, null);
            sb.append(" 当前项=").append(cur == null ? "空" : summarizeItem(cur));
            // 0.3.20：播放位置（毫秒）——宿主状态只有 Idle/Ready 四态，位置推进才是「真在放」
            long pos = playerPosition(player);
            sb.append(" 位置=").append(pos < 0 ? "取不到" : pos + "ms");
            // 0.3.11：切换链 + 注册表源——判定「select → createLoadPlan → 登记 → 装载」走到哪一步
            sb.append(" 切换器=").append(switcherDump());
            sb.append(" 注册表源=").append(registrySourceDump());
            sb.append(" 代理调用=").append(PROXY_CALLS.get()).append("/总计=").append(PROXY_ALL.get());
        } catch (Throwable t) {
            sb.append("读回异常：").append(brief(t));
        }
        return sb.toString();
    }

    /** Kotlin Mutex 的占位摘要：走完整继承链打 int/boolean/long 字段，并附 {@code toString()}。 */
    private static String lockDump(Object mutex) {
        StringBuilder sb = new StringBuilder(shortName(mutex.getClass()));
        try {
            sb.append('[').append(esc(summary(mutex))).append(']');
        } catch (Throwable ignored) {
            // toString 不可用就只打字段
        }
        try {
            for (Class<?> c = mutex.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object v = f.get(mutex);
                    if (v instanceof Integer || v instanceof Boolean || v instanceof Long) {
                        sb.append('/').append(esc(f.getName())).append('=').append(v);
                    }
                }
            }
        } catch (Throwable t) {
            sb.append("/异常:").append(brief(t));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 直驱播放器（0.3.7 实验探针）

    /** 播放器上「单参 = 宿主曲目项」的公开方法（去合成/桥接），void 返回的排前面。 */
    private static List<Method> itemMethods(Object player) {
        List<Method> out = new ArrayList<>();
        if (player == null) {
            return out;
        }
        try {
            for (Method m : player.getClass().getMethods()) {
                if (m.isSynthetic() || m.isBridge() || m.getParameterCount() != 1) {
                    continue;
                }
                if (CLS_ITEM.equals(m.getParameterTypes()[0].getName())) {
                    out.add(m);
                }
            }
            out.sort((a, b) -> {
                boolean av = a.getReturnType() == void.class;
                boolean bv = b.getReturnType() == void.class;
                if (av != bv) {
                    return av ? -1 : 1;
                }
                return a.getName().compareTo(b.getName());
            });
        } catch (Throwable t) {
            PluginLog.w(TAG, "枚举玩家播放方法失败：" + brief(t), t);
        }
        return out;
    }

    /** 候选方法清单（自检用）：{@code 名字(参数)->返回类型} 空格分隔。 */
    private static String itemMethodsDump(Object player) {
        List<Method> ms = itemMethods(player);
        if (ms.isEmpty()) {
            return "无（玩家上没有「单参=曲目项」的公开方法）";
        }
        StringBuilder sb = new StringBuilder();
        for (Method m : ms) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(esc(m.getName())).append("->").append(esc(shortName(m.getReturnType())));
        }
        return sb.toString();
    }

    /**
     * 直接驱动播放器：把曲目项交给每个候选方法（各自独立 try/catch），返回结果摘要。
     *
     * <p>这是 0.3.7 的关键探针——宿主 {@code playMusicAt} 的协程体最终就调这些方法，
     * 但它们**不外抛**：播放器若拒收（比如曲目项不在它的库里、引擎未初始化），
     * 只有直接调才能把异常/返回值捞出来。</p>
     */
    private static String drivePlayer(Object player, Object item) {
        if (player == null) {
            return "玩家未取到";
        }
        List<Method> ms = itemMethods(player);
        if (ms.isEmpty()) {
            return "玩家上没有「单参=曲目项」的公开方法";
        }
        StringBuilder sb = new StringBuilder();
        for (Method m : ms) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(esc(m.getName())).append("->");
            try {
                m.setAccessible(true);
                Object r = m.invoke(player, item);
                sb.append(r == null ? "void/null" : summary(r));
            } catch (Throwable t) {
                sb.append("异常(").append(brief(t)).append(')');
            }
        }
        return sb.toString();
    }

    /** 队列状态对象（{@code PlaybackQueueState}）的字段摘要：模式/队列size/索引/顺序size/数量/播放中。 */
    private static String dumpQueueState(Object state) {
        if (state == null) {
            return "取不到";
        }
        try {
            List<String> parts = new ArrayList<>();
            for (Field f : state.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                    continue;
                }
                f.setAccessible(true);
                Object v = f.get(state);
                if (v instanceof List<?> l) {
                    parts.add("列表" + l.size() + "["
                            + (l.isEmpty() || l.get(0) == null ? "空" : shortName(l.get(0).getClass())) + "]");
                } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
                    parts.add(String.valueOf(v));
                } else if (v == null) {
                    parts.add("null");
                } else {
                    parts.add(esc(shortName(v.getClass())) + ":" + summary(v));
                }
            }
            return parts.isEmpty() ? shortName(state.getClass()) : String.join("/", parts);
        } catch (Throwable t) {
            return "读回异常：" + brief(t);
        }
    }

    // ------------------------------------------------------------ 拦截器装配（0.3.1）

    /** 给活动播放器装转发代理拦截器；返回给日志/界面的【完整过程】摘要。 */
    private static void installInterceptorOnWorker() {
        StringBuilder sb = new StringBuilder("装拦截器：");
        try {
            sb.append(ensureInterceptorOnWorker());
        } catch (Throwable t) {
            sb.append("异常：").append(brief(t));
            PluginLog.w(TAG, "装拦截器失败", t);
        }
        finish(sb);
    }

    /**
     * 确保活动播放器的「活清单」里有我们的代理拦截器；幂等，返回过程摘要。
     *
     * <p>链路（1.18.5 实证）：{@code PlaybackController.service}（private static）→
     * {@code PlaybackService.getPlayer()}（定义在父类 {@code androidx.compose.ui.ബ}，public）→
     * 具体播放器 {@code com.xuncorp.pisces.Ԩ} → 它持有的取流器 {@code androidx.compose.ui.Ņ}
     * → 该对象的 {@code java.util.List} 字段（= 宿主的拦截器清单）。</p>
     */
    private static String ensureInterceptorOnWorker() {
        Object ctrl = staticInstance(CLS_CONTROLLER);
        if (ctrl == null) {
            return "失败：宿主播放控制器不可用（版本不匹配？）";
        }
        Object service = staticField(ctrl.getClass(), "PlaybackService", "service");
        Object player = findPlayer(service);
        if (player == null) {
            return "失败：拿不到活动播放器（服务=" + (service == null ? "未取到" : tname(service.getClass())) + "）";
        }

        List<ListCand> cands = interceptorCandidates(player);
        ListCand named = namedCandidate(player);
        if (named != null) {
            cands.add(named);
        }
        ListCand best = bestCandidate(cands);
        if (best == null) {
            return "失败：找不到拦截器清单（玩家=" + tname(player.getClass()) + " 候选=" + candDump(cands) + "）";
        }
        // 0.3.19：带上实例标识——①′（活清单）与 ①′⁺（属性清单）两条日志一比，
        // 「播放取流读的那份清单是不是我们注入的那一份」当场可判。
        String where = "持有=" + tname(best.holder().getClass()) + " 字段=" + esc(best.field().getName())
                + " id=" + System.identityHashCode(best.list())
                + " size=" + best.list().size() + " 元素=[" + elementNames(best.list()) + "]";
        if (alreadyHasMusicVideo(best.list())) {
            return "清单里已有音乐视频拦截器，无需注入（" + where + "）";
        }
        Class<?> iface = interceptorInterface(best.list(), null);
        if (iface == null) {
            return "失败：识别不出拦截器接口（" + where + "）";
        }
        Object real = hostInterceptorInstance(best.list(), player);
        if (real == null) {
            return "失败：拿不到宿主真拦截器实例（" + delegateWhy + "）";
        }
        Object proxy;
        try {
            proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    new DelegateHandler(real));
        } catch (Throwable t) {
            return "失败：造代理失败（" + brief(t) + "）";
        }
        // 0.3.13：把**真拦截器实例**也放进清单（它才是能把 http 位置变成 BASS 句柄的那个），
        // 我们的代理排在它后面当计数器——这样「宿主到底有没有来清单里取流」一眼可辨。
        return installToList(best, real, "真拦截器") + "；" + installToList(best, proxy, "代理")
                + "（接口=" + esc(iface.getName()) + " " + where + "）";
    }

    /**
     * 属性对象里那个 List 字段。
     *
     * <p>javap 常量池实证：{@code PiscesPlayer$Properties} 只有一个 {@code Ljava/util/List;} 字段
     * {@code ԯ}（配 {@code ԯ()Ljava/util/List;} 取值器），构造参数第 9 个就是它——宿主在造播放器时
     * 把拦截器清单灌进去，播放取流路径再从这里取出来。</p>
     */
    private static Field propertiesListField(Class<?> propsClass) {
        for (Class<?> c = propsClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                    continue;
                }
                if (List.class.isAssignableFrom(f.getType())) {
                    return f;
                }
            }
        }
        return null;
    }

    /**
     * 0.3.19 ①′⁺：把真拦截器与我们的代理塞进「播放器属性」里那份清单。
     *
     * <p><b>为什么单列一步</b>：0.3.14 起我们注入的是「活清单候选」（{@code Ԩ → Ņ → List}），
     * 而播放取流走的是 {@code Properties.ԯ}。两者可能本来就是同一个对象（那就无需重复），也可能
     * 只是构造时复制出去的副本——若是副本，「代理调用恒 0 / 播放器从不开流」就全解释通了。
     * 本方法把两份都塞满并**回读**，让 0.3.20 的判断不再靠猜。</p>
     */
    private static String ensurePropertiesInterceptorOnWorker(Object player) {
        if (player == null) {
            return "跳过：拿不到播放器";
        }
        Object props = instanceField(player, CLS_PROPERTIES, null);
        if (props == null) {
            return "跳过：拿不到 " + CLS_PROPERTIES;
        }
        Field f = propertiesListField(props.getClass());
        if (f == null) {
            return "跳过：属性里没有 List 字段（" + tname(props.getClass()) + "）";
        }
        Object cur = readField(f, props);
        if (!(cur instanceof List<?> raw)) {
            return "跳过：属性清单取不到（字段=" + esc(f.getName()) + "）";
        }
        List<Object> list = asObjectList(raw);
        String where = "属性=" + tname(props.getClass()) + " 字段=" + esc(f.getName())
                + " id=" + System.identityHashCode(list) + " size=" + list.size()
                + " 元素=[" + elementNames(list) + "]";
        if (alreadyHasMusicVideo(list)) {
            return "属性清单里已有音乐视频拦截器，无需注入（" + where + "）";
        }
        Class<?> iface = interceptorInterface(list, props.getClass());
        if (iface == null) {
            return "失败：识别不出拦截器接口（" + where + "）";
        }
        Object real = hostInterceptorInstance(list, player);
        if (real == null) {
            return "失败：拿不到宿主真拦截器实例（" + delegateWhy + "）";
        }
        Object proxy;
        try {
            proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    new DelegateHandler(real));
        } catch (Throwable t) {
            return "失败：造代理失败（" + brief(t) + "）";
        }
        ListCand cand = new ListCand(props, f, list, 3, "属性");
        return installToList(cand, real, "真拦截器(属性)") + "；" + installToList(cand, proxy, "代理(属性)")
                + "（接口=" + esc(iface.getName()) + " " + where + "）";
    }

    /**
     * 0.3.19 ③⁗″：直接调**播放器自己的取流方法**，回答 0.3.18 剩下的唯一问题。
     *
     * <p>javap 产物 {@code build\host-javap\com.xuncorp.pisces.Ԩ.bytecode.txt} 行 6201 是
     * {@code (PiscesMediaItem, Long, Continuation) → Object}（Kotlin {@code Result<流>} 的装箱返
     * 回），它是「曲目项 → BASS 流」的播放器侧入口；0.3.11 的 ③⁗ 打的是**分析器**（{@code Ņ}），
     * 那是另一条路。本探针因此专门验：播放器自己这条路，对我们的 http 位置出不出句柄。</p>
     */
    private static String streamPathProbe(Object player, Object item, String trackId, String location) {
        if (player == null || item == null) {
            return "跳过：播放器或曲目项为空";
        }
        Method target = null;
        for (Class<?> c = player.getClass(); c != null && c != Object.class && target == null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 3 || !ps[0].getName().equals(CLS_ITEM)
                        || ps[1] != Long.class
                        || !"kotlin.coroutines.Continuation".equals(ps[2].getName())) {
                    continue;
                }
                target = m;
                break;
            }
        }
        if (target == null) {
            return "找不到取流方法（" + tname(player.getClass()) + "）";
        }
        String name = esc(target.getName());
        registryActivate(trackId, location);
        RESUME.set(null);
        try {
            target.setAccessible(true);
            Object r = target.invoke(player, item, null, continuationProxy());
            if (isSuspended(r)) {
                sleepQuiet(900L);
                return "取流=" + name + "->COROUTINE_SUSPENDED（代理调用=" + PROXY_CALLS.get() + "/" + PROXY_ALL.get()
                        + "） 回调=" + RESUME.get();
            }
            return "取流=" + name + "->" + rawText(r) + "（代理调用=" + PROXY_CALLS.get() + "/" + PROXY_ALL.get() + "）";
        } catch (Throwable t) {
            return "取流=" + name + " 抛异常：" + brief(t);
        }
    }

    /**
     * 把元素塞进清单：先试原地追加（ArrayList），不可变清单则换字段引用（Kotlin listOf）。
     *
     * <p>0.3.13：装完**回读字段**再判定。0.3.12 真机里代理一次也没被调用过（总计=0），
     * 只有「字段里确实有它」才能排除「加了个没人看的副本」这个解释。</p>
     */
    private static String installToList(ListCand cand, Object element, String what) {
        // 0.3.14：以**字段当前值**为准（不信任候选里记的那份引用）——0.3.2 的复制 bug 就是在这里
        // 露的马脚：加进去的清单和字段里的清单不是同一个对象，字段回读永远是 size=1。
        Object cur = readField(cand.field(), cand.holder());
        List<Object> list = cur instanceof List<?> l ? asObjectList(l) : cand.list();
        String base;
        try {
            list.add(element);
            base = "已追加" + what + " size=" + list.size();
        } catch (Throwable t) {
            PluginLog.d(TAG, "装拦截器：清单不可追加（" + brief(t) + "），改换字段引用");
            try {
                List<Object> copy = new ArrayList<>(list);
                copy.add(element);
                Field f = cand.field();
                f.setAccessible(true);
                f.set(cand.holder(), copy);
                base = "已替换清单字段" + what + " size=" + copy.size();
            } catch (Throwable t2) {
                return "失败：清单既不可追加也不可替换（" + brief(t2) + "）";
            }
        }
        Object after = readField(cand.field(), cand.holder());
        if (after instanceof List<?> l2) {
            boolean has = false;
            for (Object o : l2) {
                if (o == element) {
                    has = true;
                    break;
                }
            }
            base += " 字段回读=size=" + l2.size() + " 元素=[" + elementNames(l2) + "] 含此实例=" + has;
        } else {
            base += " 字段回读=取不到";
        }
        return base;
    }

    // ------------------------------------------------------------ 清单发现（0.3.2）

    /** 一个「承载拦截器清单」的候选：持有者、字段、清单、可信分。 */
    private record ListCand(Object holder, Field field, List<Object> list, int score, String why) {
    }

    /**
     * 按结构找候选清单：遍历播放器（含父类）的实例字段——
     * ① 字段本身是 {@code List}；② 字段值是个非 JDK 对象、其内部有 {@code List} 字段。
     * 打分靠「清单元素的特征」（实现 {@code createStream*} 接口 +4、类名含 Interceptor +2）。
     */
    private static List<ListCand> interceptorCandidates(Object player) {
        List<ListCand> out = new ArrayList<>();
        if (player == null) {
            return out;
        }
        for (Class<?> c = player.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                    continue;
                }
                if (List.class.isAssignableFrom(f.getType())) {
                    Object v = readField(f, player);
                    if (v instanceof List<?> l) {
                        out.add(cand(player, f, l, false));
                    }
                    continue;
                }
                Class<?> t = f.getType();
                if (t.isPrimitive() || t.isArray() || t.isEnum() || t.getName().startsWith("java.")) {
                    continue;
                }
                Object holder = readField(f, player);
                if (holder == null) {
                    continue;
                }
                for (Class<?> c2 = holder.getClass(); c2 != null && c2 != Object.class; c2 = c2.getSuperclass()) {
                    for (Field g : c2.getDeclaredFields()) {
                        if (Modifier.isStatic(g.getModifiers()) || g.isSynthetic()
                                || !List.class.isAssignableFrom(g.getType())) {
                            continue;
                        }
                        Object w = readField(g, holder);
                        if (w instanceof List<?> l2) {
                            out.add(cand(holder, g, l2, true));
                        }
                    }
                }
            }
        }
        return out;
    }

    /** 兜底候选：按静态析出的混淆名取「播放器.取流器(\u0145).清单字段(\u0529)」。 */
    private static ListCand namedCandidate(Object player) {
        if (player == null) {
            return null;
        }
        String opener = IFACE_PKG + cp(OPENER_CODE_POINT);
        for (Class<?> c = player.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !f.getType().getName().equals(opener)) {
                    continue;
                }
                Object holder = readField(f, player);
                if (holder == null) {
                    continue;
                }
                for (Class<?> c2 = holder.getClass(); c2 != null && c2 != Object.class; c2 = c2.getSuperclass()) {
                    for (Field g : c2.getDeclaredFields()) {
                        if (Modifier.isStatic(g.getModifiers()) || !List.class.isAssignableFrom(g.getType())) {
                            continue;
                        }
                        Object w = readField(g, holder);
                        if (w instanceof List<?> l) {
                            ListCand cand = cand(holder, g, l, true);
                            return new ListCand(cand.holder(), cand.field(), cand.list(), cand.score() + 5,
                                    "混淆名兜底 " + cand.why());
                        }
                    }
                }
            }
        }
        return null;
    }

    private static ListCand cand(Object holder, Field field, List<?> raw, boolean nested) {
        // 0.3.14 关键修正：**不能复制**。0.3.2 起这里做的是 `new ArrayList<>(raw)`，于是后来所有
        // 「已追加到活清单」都加在**副本**上，宿主的活清单从头到尾只有 [WavDtsInterceptor]
        // （0.3.13 真机字段回读 size=1 / 含此实例=false 就是铁证）⇒ 代理一次也没被调用过（总计=0）。
        // 现在直接持有字段里的那个 List 对象。
        List<Object> list = asObjectList(raw);
        int score = nested ? 1 : 0;
        StringBuilder why = new StringBuilder(nested ? "字段内嵌 " : "字段直挂 ");
        boolean ifaceHit = false;
        boolean nameHit = false;
        for (Object o : list) {
            if (o == null) {
                continue;
            }
            if (!ifaceHit && interfaceWithCreateStream(o.getClass()) != null) {
                score += 4;
                ifaceHit = true;
                why.append("元素实现流接口 ");
            }
            if (!nameHit && o.getClass().getSimpleName().contains("Interceptor")) {
                score += 2;
                nameHit = true;
                why.append("元素名含 Interceptor ");
            }
        }
        if (list.isEmpty()) {
            why.append("空清单");
        }
        return new ListCand(holder, field, list, score, why.toString());
    }

    private static ListCand bestCandidate(List<ListCand> cands) {
        ListCand best = null;
        for (ListCand c : cands) {
            if (best == null || c.score() > best.score()) {
                best = c;
            }
        }
        return best;
    }

    private static String candDump(List<ListCand> cands) {
        if (cands.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder("[");
        for (ListCand c : cands) {
            sb.append('{').append("持有=").append(tname(c.holder().getClass()))
                    .append(" 字段=").append(esc(c.field().getName()))
                    .append(" size=").append(c.list().size())
                    .append(" 元素=[").append(elementNames(c.list())).append(']')
                    .append(" 分=").append(c.score()).append('(').append(c.why()).append(")} ");
        }
        return sb.append(']').toString();
    }

    private static Object readField(Field f, Object target) {
        try {
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 宿主清单是 {@code List<? extends 拦截器>}，我们只把它当 {@code List<Object>} 用来追加/枚举。 */
    @SuppressWarnings("unchecked")
    private static List<Object> asObjectList(List<?> raw) {
        return (List<Object>) raw;
    }

    /** 码点转字符串（宿主混淆名含非 ASCII 字符，源码里写转义序列会被 javac 当转义处理）。 */
    private static String cp(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    private static void finish(StringBuilder sb) {
        String text = sb.toString();
        lastResult = text;
        lastPlayAtMs = System.currentTimeMillis();
        PluginLog.i(TAG, text);
    }

    /** 把 {@code createStream*} 原样转发给宿主真拦截器；其它方法返回 null。 */
    private static final class DelegateHandler implements InvocationHandler {

        private final Object real;

        DelegateHandler(Object real) {
            this.real = real;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("toString".equals(name)) {
                return "netease-online-interceptor";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == (args == null || args.length == 0 ? null : args[0]);
            }
            Method target = findSameShape(real.getClass(), name, args);
            // 0.3.8：这次调用是不是「宿主在问『这个位置归谁管』」——如果是，我们必须认领。
            // 真实现 MusicVideoStreamInterceptor 只认音乐视频的位置，对我们的网易云直链一律 false，
            // 宿主拿到 false 就把条目静默丢掉（既不报错也不写日志），这正是 0.3.6/0.3.7 「收下了却不播」的形态。
            boolean mine = isOurs(args);
            boolean streamCall = name.startsWith("createStream");
            if (streamCall) {
                // 0.3.15：宿主来取流了 —— 在**这一次调用里**把登记按本次实参修好。宿主失败后会自己 clear()，
                // 于是下一轮取流时真实现按协议返回 null＝不认领，装载器随即把 http 直链当文件打开
                // ⇒ com.xuncorp.pisces.ԥ: FileNotOpened ⇒ 播放器退回 Idle。这里堵住这个循环。
                healStreamCall(args, name);
            }
            int n = PROXY_ALL.incrementAndGet();
            if (n <= 6) {
                PluginLog.i(TAG, "代理拦截器心跳 ×" + n + "：" + esc(name)
                        + "(" + argDump(args) + ") 归我们=" + mine);
            }
            if (target == null) {
                if (streamCall) {
                    PluginLog.w(TAG, "代理拦截器：真实现里找不到同名方法 " + name);
                    Object own = createStreamOurselves(args, name);
                    if (own != null) {
                        PluginLog.i(TAG, "代理造流兜底（无同名方法）：自造流 → " + rawText(own));
                        return own;
                    }
                }
                return claim(method, mine);
            }
            try {
                target.setAccessible(true);
                Object res = target.invoke(real, args);
                if (streamCall) {
                    int c = PROXY_CALLS.incrementAndGet();
                    if (c <= 3) {
                        PluginLog.i(TAG, "代理拦截器被调用 ×" + c + "：" + name + " → " + rawText(res));
                    }
                    if (res == null) {
                        // 真实现按协议「不认领」。宿主自家的 HttpRangeBassStream 对我们的直链能开出流
                        // （宿主 logs.txt：HTTP range stream opened: handle=-2147483633 length=6826362），
                        // 所以这里直接替它把流开出来，并按同样的约定装箱成 kotlin.Result 还给装载器。
                        Object own = createStreamOurselves(args, name);
                        if (own != null) {
                            PluginLog.i(TAG, "代理造流兜底 ×" + c + "：真实现不认领 → 用宿主 HttpRangeBassStream 自造流 → "
                                    + rawText(own));
                            return own;
                        }
                        PluginLog.i(TAG, "代理造流兜底 ×" + c + "：真实现不认领，自造流也未成（见上面的取流现场行）");
                    }
                } else if (mine && Boolean.FALSE.equals(res)) {
                    PluginLog.i(TAG, "代理拦截器：接管 " + esc(name) + "（真实现判 false，改判 true）");
                    return Boolean.TRUE;
                }
                return res;
            } catch (Throwable t) {
                if (streamCall) {
                    Object own = createStreamOurselves(args, name);
                    if (own != null) {
                        PluginLog.i(TAG, "代理造流兜底（真实现抛异常 " + brief(t) + "）：自造流 → " + rawText(own));
                        return own;
                    }
                }
                PluginLog.w(TAG, "代理拦截器转发失败：" + brief(t), t);
                return claim(method, mine);
            }
        }

        /** 询问类方法（返回 boolean 且参数指向我们的位置）在真实现缺席/失败时的回答：认领。 */
        private static Object claim(Method method, boolean mine) {
            Class<?> rt = method.getReturnType();
            if (mine && method.getParameterCount() > 0
                    && (rt == boolean.class || rt == Boolean.class)) {
                return Boolean.TRUE;
            }
            return defaultValue(rt);
        }
    }

    // ------------------------------------------- 0.3.15：取流现场自愈 + 自造流兜底（代理内）

    /**
     * 把「宿主正在取流」这件事里我们能自己修的部分当场修好：从实参的曲目项里抠出
     * {@code netease-<id>} 与 http(s) 位置，立刻 {@code activate(trackId, location)}，
     * 并打一行现场（路径 / 曲目标识 / 重登记 / 匹配 / 宿主自己的 isHttpLocation / flags）。
     *
     * <p>为什么要在这里重登记：宿主每次取流失败都会把注册表清掉（0.3.13 真机：
     * 「保活有效但 ③‴/④ 里 注册表源=空」），而下一次取流发生在**我们看不见的时刻**——
     * 只有代理被调用的那一瞬间才能把登记补回去。</p>
     */
    private static void healStreamCall(Object[] args, String name) {
        try {
            Object item = itemArg(args);
            String location = stringStartingWith(item, "http");
            String trackId = stringStartingWith(item, "netease-");
            if (location == null) {
                PluginLog.i(TAG, "代理取流现场：" + esc(name) + " 曲目项里没有 http 位置（标识=" + trackId
                        + " 参数=" + argDump(args) + "）");
                return;
            }
            String act = trackId == null ? "跳过（没有 netease- 标识）" : registryActivate(trackId, location);
            PluginLog.i(TAG, "代理取流现场：" + esc(name) + " 标识=" + trackId + " 路径=" + describeUrl(location)
                    + " 重登记=" + act + " 匹配=" + (item == null ? "无曲目项" : registryMatches(item))
                    + " 宿主判http=" + hostIsHttp(location) + " flags=" + intArg(args));
        } catch (Throwable t) {
            PluginLog.w(TAG, "代理取流现场自愈失败：" + brief(t));
        }
    }

    /**
     * 真实现不认领（返回 {@code null}）时，用宿主自带的
     * {@code HttpRangeBassStream.create(String,int,HttpRangeStreamOwner)} 自己造一条 HTTP Range 流，
     * 并按宿主的约定包成 {@code kotlin.Result}（{@code box-impl}）还给装载器。
     *
     * <p>底气来自宿主自己的 logs.txt：{@code HTTP range stream opened: handle=-2147483633
     * length=6826362 url=http://m704.music.126.net/...}——同一套实现对我们的网易云直链确实能开出 BASS 流
     * （负值 handle 是成功；{@code handle == 0} 才会抛 {@code IllegalStateException}）。
     * 失败/异常一律返回 {@code null}（＝保持宿主原有的「不认领」行为）。</p>
     */
    private static Object createStreamOurselves(Object[] args, String name) {
        Object item = itemArg(args);
        String location = stringStartingWith(item, "http");
        if (location == null) {
            return null;
        }
        try {
            Object range = staticInstance(CLS_RANGE_STREAM);
            if (range == null) {
                PluginLog.w(TAG, "代理造流兜底：拿不到 " + CLS_RANGE_STREAM + ".INSTANCE");
                return null;
            }
            Class<?> ownerCls = Class.forName(CLS_RANGE_OWNER);
            Object owner = null;
            Object[] constants = ownerCls.getEnumConstants();
            if (constants != null) {
                for (Object c : constants) {
                    if (c != null && c.toString().contains("MusicVideo")) {
                        owner = c;
                        break;
                    }
                }
                if (owner == null && constants.length > 0) {
                    owner = constants[0];
                    PluginLog.w(TAG, "代理造流兜底：" + CLS_RANGE_OWNER + " 里没有 MusicVideo，改用 " + constants[0]);
                }
            }
            if (owner == null) {
                PluginLog.w(TAG, "代理造流兜底：" + CLS_RANGE_OWNER + " 不是可用枚举");
                return null;
            }
            Method create = null;
            for (Method m : range.getClass().getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 3) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps[0] == String.class && ps[1] == int.class && ps[2].getName().equals(CLS_RANGE_OWNER)) {
                    create = m;
                    break;
                }
            }
            if (create == null) {
                PluginLog.w(TAG, "代理造流兜底：在 " + CLS_RANGE_STREAM + " 上找不到 3 参 create(String,int,"
                        + CLS_RANGE_OWNER + ")");
                return null;
            }
            create.setAccessible(true);
            int flags = intArg(args);
            Object raw = create.invoke(range, location, flags, owner);
            Class<?> resultCls = hostClass("kotlin.Result", item);
            if (resultCls == null) {
                resultCls = Class.forName("kotlin.Result");
            }
            Method box = resultCls.getDeclaredMethod("box-impl", Object.class);
            box.setAccessible(true);
            Object boxed = box.invoke(null, raw);
            PluginLog.i(TAG, "代理造流兜底：" + esc(name) + " create(" + describeUrl(location) + ", flags=" + flags
                    + ", " + owner + ") → 原始=" + rawText(raw) + " 装箱=" + rawText(boxed));
            return boxed;
        } catch (Throwable t) {
            PluginLog.w(TAG, "代理造流兜底失败：" + brief(t), t);
            return null;
        }
    }

    /** 实参里的曲目项（{@code com.xuncorp.pisces.PiscesMediaItem}，含子类）。 */
    private static Object itemArg(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object a : args) {
            if (a != null && a.getClass().getName().startsWith(CLS_ITEM)) {
                return a;
            }
        }
        return null;
    }

    /** 把对象上所有「无参取 String」的方法试一遍，返回第一个以 {@code prefix} 开头的值（没有则 null）。 */
    private static String stringStartingWith(Object o, String prefix) {
        if (o == null || prefix == null) {
            return null;
        }
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.isSynthetic() || m.getParameterCount() != 0
                        || m.getReturnType() != String.class) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    Object v = m.invoke(o);
                    if (v instanceof String s && s.startsWith(prefix)) {
                        return s;
                    }
                } catch (Throwable ignored) {
                    // 单个取值器抛异常不影响继续找
                }
            }
        }
        return null;
    }

    /** 实参里的 int（{@code createStream} 的 flags）。 */
    private static int intArg(Object[] args) {
        int v = 0;
        if (args != null) {
            for (Object a : args) {
                if (a instanceof Integer i) {
                    v = i;
                }
            }
        }
        return v;
    }

    /** 直接问宿主自己的 {@code MusicVideoStreamInterceptorKt.isHttpLocation(String)}（它认 http 与 https）。 */
    private static String hostIsHttp(String location) {
        try {
            Class<?> kt = Class.forName(CLS_MV_INTERCEPTOR + "Kt");
            for (Method m : kt.getDeclaredMethods()) {
                if (!m.getName().startsWith("isHttpLocation") || m.getParameterCount() != 1) {
                    continue;
                }
                m.setAccessible(true);
                return String.valueOf(m.invoke(null, location));
            }
            return "找不到该方法";
        } catch (Throwable t) {
            return "异常：" + brief(t);
        }
    }

    /** 原样打印代理交给宿主的值（{@code summary} 会美化，归因时反而看不清 null 与 kotlin.Result）。 */
    private static String rawText(Object v) {
        if (v == null) {
            return "null（不认领）";
        }
        String s = String.valueOf(v);
        return esc(s.length() > 160 ? s.substring(0, 160) + "…" : s);
    }

    /** 这次调用的参数里是否带着「我们的」位置（网易云直链，或 {@code netease-<id>} 形式的曲目标识）。 */
    private static boolean isOurs(Object[] args) {
        if (args == null) {
            return false;
        }
        for (Object a : args) {
            if (looksLikeOurs(a)) {
                return true;
            }
        }
        return false;
    }

    /** 单个参数是否指向我们的位置：字符串直接看前缀，对象就扫它的 String 字段。 */
    private static boolean looksLikeOurs(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof CharSequence cs) {
            return oursText(cs.toString());
        }
        try {
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic() || f.getType() != String.class) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v instanceof String s && oursText(s)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 看不清就当不是我们的
        }
        return false;
    }

    private static boolean oursText(String s) {
        return s.startsWith("http") || s.startsWith("netease-");
    }

    /** 调用的参数摘要（走 {@link #summary}：直链只打 host/扩展名，绝不落全文）。 */
    private static String argDump(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object a : args) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(a == null ? "null" : summary(a));
        }
        return sb.toString();
    }

    /** 代理里「真实现没有同名方法」时要给调用方一个合法返回值（不能对原始类型返回 null）。 */
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == void.class) {
            return null;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        return 0;
    }

    private static Method findSameShape(Class<?> cls, String name, Object[] args) {
        int want = args == null ? 0 : args.length;
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == want) {
                    return m;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 反射小工具

    /** 取 Kotlin object 的 {@code INSTANCE} 字段（类不可用时返回 null）。 */
    private static Object staticInstance(String className) {
        try {
            Class<?> cls = Class.forName(className);
            Field f = cls.getField("INSTANCE");
            return f.get(null);
        } catch (Throwable t) {
            PluginLog.d(TAG, "反射：取 " + className + ".INSTANCE 失败：" + brief(t));
            return null;
        }
    }

    /** 宿主真拦截器实例的取得情况（取自哪里 / 为什么失败），供日志与自检使用。 */
    private static volatile String delegateWhy = "未尝试";

    /** 用宿主自己的类加载器加载宿主内部类（插件加载器与宿主不同源时，后者才看得见宿主内部类）。 */
    private static Class<?> hostClass(String name, Object hint) {
        ClassLoader host = hint == null ? null : hint.getClass().getClassLoader();
        ClassLoader plugin = VoxzenBridge.class.getClassLoader();
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        ClassLoader[] loaders = {host, plugin, ctx, null};
        Throwable last = null;
        for (ClassLoader cl : loaders) {
            if (cl == null && last == null) {
                continue;
            }
            try {
                Class<?> c = cl == null ? Class.forName(name) : Class.forName(name, false, cl);
                delegateWhy = "类加载=" + (cl == null ? "系统加载器" : cl.getClass().getSimpleName());
                return c;
            } catch (Throwable t) {
                last = t;
            }
        }
        delegateWhy = "类加载失败（" + brief(last) + "）";
        return null;
    }

    /**
     * 拿「宿主真拦截器」实例（0.3.3 起三条路，按代价从低到高）：
     * ① 活清单里已有的现成实例；② {@code INSTANCE} 静态字段（Kotlin object 版本）；
     * ③ **public 无参构造**——1.18.5 字节码实证：{@code MusicVideoStreamInterceptor} 是普通
     * {@code public final class}，只有公开无参构造 + 空的 {@code Companion}，**没有 INSTANCE**；
     * 0.3.2 只找 INSTANCE，于是真机上打出「类不可用」而卡住（见 docs\08 §24）。
     */
    private static Object hostInterceptorInstance(List<?> liveList, Object loaderHint) {
        if (liveList != null) {
            for (Object o : liveList) {
                if (o != null && o.getClass().getName().equals(CLS_MV_INTERCEPTOR)) {
                    delegateWhy = "取自活清单里的现成实例";
                    return o;
                }
            }
        }
        Class<?> cls = hostClass(CLS_MV_INTERCEPTOR, loaderHint);
        if (cls == null) {
            return null;
        }
        try {
            Field f = cls.getField("INSTANCE");
            Object v = f.get(null);
            if (v != null) {
                delegateWhy = "取自 INSTANCE 静态字段（Kotlin object）";
                return v;
            }
        } catch (Throwable ignored) {
            // 没有 INSTANCE 很正常（1.18.5 就是没有），继续走无参构造
        }
        try {
            Constructor<?> ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object v = ctor.newInstance();
            delegateWhy = delegateWhy + "，用无参构造新建（普通 class 版本）";
            return v;
        } catch (Throwable t) {
            delegateWhy = delegateWhy + "，无 INSTANCE 且无参构造失败（" + brief(t) + "）";
            return null;
        }
    }

    /** 插件是否与宿主共用同一个类加载器（不同源则静态状态互不可见）。 */
    private static boolean sameLoader() {
        try {
            return VoxzenBridge.class.getClassLoader() == Class.forName(CLS_CONTROLLER).getClassLoader();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 找「静态字段」的值（含私有、含父类）：按类型关键词 / 字段名关键词匹配，取第一个非 null。
     *
     * <p>0.3.0 的教训：{@code PlaybackController.service} 与
     * {@code MusicVideoAudioSourceRegistry.activeSource} 都是 <b>private static final</b>，
     * 只扫实例字段必然误报「未找到」。</p>
     */
    private static Object staticField(Class<?> cls, String typeHint, String nameHint) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (typeHint != null && !f.getType().getName().contains(typeHint)) {
                    continue;
                }
                if (nameHint != null && !f.getName().contains(nameHint)) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v != null) {
                        return v;
                    }
                } catch (Throwable ignored) {
                    // 单个字段读不到就继续找
                }
            }
        }
        return null;
    }

    /** 在对象（含父类、含私有）里找第一个类型名包含 typeHint 的实例字段值。 */
    private static Object instanceField(Object target, String typeHint, String nameHint) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (typeHint != null && !f.getType().getName().contains(typeHint)) {
                    continue;
                }
                if (nameHint != null && !f.getName().contains(nameHint)) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(target);
                    if (v != null) {
                        return v;
                    }
                } catch (Throwable ignored) {
                    // 继续找
                }
            }
        }
        return null;
    }

    /** 静态字段转储：{@code 名:类型=值摘要}。 */
    private static String staticFieldDump(Class<?> cls, int cap) {
        StringBuilder sb = new StringBuilder("{");
        int n = 0;
        for (Class<?> c = cls; c != null && c != Object.class && n < cap; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (n >= cap) {
                    break;
                }
                if (!Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                    continue;
                }
                String val;
                try {
                    f.setAccessible(true);
                    val = summary(f.get(null));
                } catch (Throwable t) {
                    val = "?";
                }
                sb.append(esc(f.getName())).append(':').append(tname(f.getType())).append('=').append(val).append(' ');
                n++;
            }
        }
        return sb.append('}').toString();
    }

    /** 实例字段转储：{@code 名:类型=值摘要}。 */
    private static String instanceFieldDump(Object target, int cap) {
        if (target == null) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        int n = 0;
        for (Class<?> c = target.getClass(); c != null && c != Object.class && n < cap; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (n >= cap) {
                    break;
                }
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                    continue;
                }
                String val;
                try {
                    f.setAccessible(true);
                    val = summary(f.get(target));
                } catch (Throwable t) {
                    val = "?";
                }
                sb.append(esc(f.getName())).append(':').append(tname(f.getType())).append('=').append(val).append(' ');
                n++;
            }
        }
        return sb.append('}').toString();
    }

    /** 取对象里第一个 List 字段的值（不递归）。 */
    private static List<?> firstListField(Object target) {
        Object v = instanceField(target, "java.util.List", null);
        return v instanceof List<?> l ? l : null;
    }

    /**
     * 识别「音频流拦截器接口」，多路回退（0.3.2 修正：以前只从属性对象的泛型签名取，真机打「未识别」）：
     * ① 活清单元素的接口里「声明了 {@code createStream*} 方法」的那个；
     * ② 宿主真拦截器类的同一判断；③ 属性对象里 {@code List<? extends X>} 的 X；④ 按混淆名兜底。
     */
    private static Class<?> interceptorInterface(List<?> list, Class<?> propsCls) {
        if (list != null) {
            for (Object o : list) {
                if (o == null) {
                    continue;
                }
                Class<?> i = interfaceWithCreateStream(o.getClass());
                if (i != null) {
                    return i;
                }
            }
        }
        Object real = staticInstance(CLS_MV_INTERCEPTOR);
        if (real != null) {
            Class<?> i = interfaceWithCreateStream(real.getClass());
            if (i != null) {
                return i;
            }
        }
        Class<?> byProps = interceptorInterface(propsCls);
        if (byProps != null) {
            return byProps;
        }
        try {
            return Class.forName(IFACE_PKG + cp(IFACE_CODE_POINT));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 在类（含父类）实现的接口里，找「自己声明了 {@code createStream*} 方法」的那个接口。 */
    private static Class<?> interfaceWithCreateStream(Class<?> cls) {
        if (cls == null) {
            return null;
        }
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                for (Method m : i.getMethods()) {
                    if (m.getName().startsWith("createStream")) {
                        return i;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 识别「音频流拦截器接口」：宿主属性里那个 {@code List<? extends X>} 的 X。
     *
     * <p>为什么不写死名字：该类名是 R8 生成的 {@code androidx.compose.ui.ౚ} 一类混淆名，
     * 写进源码既难维护也难编译；从泛型签名里取 Class 对象最稳。</p>
     */
    private static Class<?> interceptorInterface(Class<?> propsCls) {
        if (propsCls == null) {
            return null;
        }
        for (Class<?> c = propsCls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                Class<?> arg = firstTypeArgument(f.getGenericType());
                if (arg != null) {
                    return arg;
                }
            }
        }
        return null;
    }

    private static Class<?> firstTypeArgument(Type generic) {
        if (!(generic instanceof ParameterizedType pt)) {
            return null;
        }
        for (Type t : pt.getActualTypeArguments()) {
            if (t instanceof Class<?> cls) {
                return cls;
            }
            if (t instanceof java.lang.reflect.WildcardType w && w.getUpperBounds().length > 0
                    && w.getUpperBounds()[0] instanceof Class<?> cls2) {
                return cls2;
            }
        }
        return null;
    }

    /** 清单里是否已经有「音乐视频」拦截器（或我们自己的代理）。 */
    private static boolean alreadyHasMusicVideo(List<?> list) {
        for (Object o : list) {
            if (o == null) {
                continue;
            }
            String n = o.getClass().getName();
            if (n.contains("MusicVideo") || n.contains("netease")) {
                return true;
            }
        }
        return false;
    }

    /** 读注册表当前激活项（private static 的 AtomicReference）。 */
    private static Object readActiveSource() {
        try {
            Class<?> cls = Class.forName(CLS_REGISTRY);
            Object ref = staticField(cls, "AtomicReference", "activeSource");
            if (ref == null) {
                ref = staticField(cls, "AtomicReference", null);
            }
            return ref == null ? null : callAnyNoArg(ref, "get");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从播放服务里找「活动播放器」实例（无参方法 → 实例字段 → 无参方法兜底）。
     *
     * <p>0.3.1 的缺陷：按「类型名 contains 'PiscesPlayer'」找字段，于是先命中了
     * {@code PiscesPlayer$Properties}（属性对象的类型名同样含 PiscesPlayer），自检因此打出
     * 「玩家=PiscesPlayer$Properties」。本版改成 ① 直接调 public 的 {@code getPlayer()}
     * （定义在 {@code PlaybackService} 的父类 {@code androidx.compose.ui.ബ} 上）；
     * ② 字段按 {@code isAssignableFrom} 判定，绝不再用名字包含。</p>
     */
    private static Object findPlayer(Object service) {
        if (service == null) {
            return null;
        }
        Class<?> want;
        try {
            want = Class.forName(CLS_PLAYER);
        } catch (Throwable t) {
            return null;
        }
        for (Class<?> c = service.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("getPlayer");
                m.setAccessible(true);
                Object v = m.invoke(service);
                if (v != null && want.isInstance(v)) {
                    return v;
                }
            } catch (Throwable ignored) {
                // 该类上没有 getPlayer 就继续往父类找
            }
        }
        for (Class<?> c = service.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !want.isAssignableFrom(f.getType())) {
                    continue;
                }
                Object v = readField(f, service);
                if (v != null) {
                    return v;
                }
            }
        }
        return firstNoArgReturning(service, want);
    }

    /** 找第一个「无参且返回类型可赋值给 want」的方法并调用（want 为 null 时返回类型不限）。 */
    private static Object firstNoArgReturning(Object target, Class<?> want) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0 || m.getReturnType() == void.class) {
                    continue;
                }
                if (want != null && !want.isAssignableFrom(m.getReturnType())) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    Object v = m.invoke(target);
                    if (v != null) {
                        return v;
                    }
                } catch (Throwable ignored) {
                    // 继续尝试下一个方法
                }
            }
        }
        return null;
    }

    /** 类（含父类）上是否存在该签名的方法（0.3.4 用于判定播放入口）。 */
    private static boolean hasMethod(Class<?> cls, String name, Class<?>... params) {
        if (cls == null) {
            return false;
        }
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod(name, params);
                return true;
            } catch (Throwable ignored) {
                // 换父类继续
            }
        }
        return false;
    }

    /** 按名字调无参方法（用于宿主里少见的 ASCII 名，如 getValue）。失败返回 null。 */
    private static Object callAnyNoArg(Object target, String name) {
        if (target == null || name == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable ignored) {
                // 换父类继续
            }
        }
        return null;
    }

    /** 元素类名清单（去重、保序）。 */
    private static String elementNames(List<?> list) {
        Set<String> names = new LinkedHashSet<>();
        for (Object o : list) {
            names.add(o == null ? "null" : shortName(o.getClass()));
        }
        return String.join(",", names);
    }

    // ------------------------------------------------------------ 文本工具

    private static void set(int seq, String text) {
        lastResult = "#" + seq + " " + text;
        lastPlayAtMs = System.currentTimeMillis();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String effectiveLevel(String requested) {
        String value = requested == null || requested.isBlank() ? PluginConfig.audioLevel() : requested;
        String canonical = Levels.canonical(value);
        return canonical != null ? canonical : PluginConfig.audioLevel();
    }

    private static String shortName(Class<?> c) {
        String n = c.getName();
        int i = n.lastIndexOf('.');
        return i < 0 ? esc(n) : esc(n.substring(i + 1));
    }

    /** 类名（含包；非 ASCII 字符转成转义序列，便于在日志里读）。 */
    private static String tname(Class<?> c) {
        return c == null ? "?" : esc(c.getName());
    }

    /** 非 ASCII 字符转义，避免混淆名在日志/控制台里变成乱码。 */
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        boolean ascii = true;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 126) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch > 126 || ch < 32) {
                sb.append(String.format("\\u%04x", (int) ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** 值的短摘要（URL 只给特征，长串截断）。 */
    private static String summary(Object v) {
        if (v == null) {
            return "null";
        }
        try {
            if (v instanceof Class<?> c) {
                return tname(c);
            }
            String s = String.valueOf(v);
            if (s.startsWith("http://") || s.startsWith("https://")) {
                return describeUrl(s);
            }
            s = esc(s);
            if (s.length() > 90) {
                s = s.substring(0, 90) + "…";
            }
            return s;
        } catch (Throwable t) {
            return shortName(v.getClass());
        }
    }

    /**
     * 读宿主<b>当前播放队列</b>里的曲目 id 顺序（0.11.6，我自己做的接口：宿主没有公开 API）。
     *
     * <p>用途：播放条 / 播放页的封面只认「{@code Track.path} 指向的本地文件内嵌图」，而播放项在
     * <b>起播那一刻</b>就定型 ⇒ 只有当那首歌在队列里排到我们时可提前准备好本地带图文件，播放页才有图。
     * 本方法给出队列顺序（形如 {@code [netease-<id>, …]}），{@code svc.RowCover} 据此做前瞻准备。</p>
     *
     * <p>线程：内部投到宿主交互线程（{@link HostBridgeWorker}）并用 latch 等结果；任意线程可调。</p>
     *
     * @return 队列里的 trackId 顺序；取不到返回空表（绝不抛）
     */
    public static java.util.List<String> queueTrackIds() {
        return queueView().ids();
    }

    // ------------------------------------------------------------ 播放队列三态读取（0.11.7 W5）

    /** 队列读取三态（docs\00 §6.20.3：**必须**能区分「真空」与「读失败」）。 */
    public enum QueueState {
        /** 读到队列，且至少有一项能解析出 {@code netease-} id。 */
        OK,
        /** 宿主队列确实是空的（真·空队列，不是读失败）。 */
        EMPTY,
        /** 结构/反射读取失败（控制器不可达、状态取不到值、元素解析不出 id）。 */
        FAILED
    }

    /**
     * 一次队列读取的完整结果。
     *
     * @param state   三态
     * @param ids     解析出的 netease id（紧凑表，保持宿主顺序；{@code queueTrackIds()} 沿用）
     * @param aligned 与宿主队列<b>逐位对齐</b>的表，解析不出的位置为 {@code null}。
     *                算「下一首」必须用它：队列里的重复项 / 解析失败项会让紧凑表下标错位。
     * @param note    诊断串（选中了哪个字段、用了哪条判据、配对索引）；给日志与界面，不参与逻辑
     * @param index   配对索引字段的值（宿主「当前第几首」）；取不到 -1
     * @param size    选中列表的长度
     */
    public record QueueView(QueueState state, java.util.List<String> ids, java.util.List<String> aligned,
                            String note, int index, int size) {
    }

    private static QueueView queueFailed(String note) {
        return new QueueView(QueueState.FAILED, java.util.List.of(), java.util.List.of(), note, -1, 0);
    }

    /**
     * 读宿主播放队列（三态 + 配对索引）。线程：内部投 {@link HostBridgeWorker} 并用 latch 等 4s；
     * 任意线程可调；绝不抛——读不到返回 {@link QueueState#FAILED}，<b>不要</b>把它当空队列。
     */
    public static QueueView queueView() {
        QueueView fallback = queueFailed("读队列未完成（投递失败或超时）");
        try {
            HostBridgeWorker w = HostBridgeWorker.get();
            if (w.isOnWorkerThread()) {
                return queueViewOnWorker();
            }
            java.util.concurrent.atomic.AtomicReference<QueueView> box = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            if (!w.submit(() -> {
                try {
                    box.set(queueViewOnWorker());
                } catch (Throwable t) {
                    PluginLog.d(TAG, "读播放队列异常：" + brief(t));
                } finally {
                    done.countDown();
                }
            })) {
                return fallback;
            }
            done.await(4L, java.util.concurrent.TimeUnit.SECONDS);
            QueueView v = box.get();
            return v == null ? fallback : v;
        } catch (Throwable t) {
            PluginLog.d(TAG, "读播放队列失败：" + brief(t));
            return fallback;
        }
    }

    /**
     * 队列候选（0.11.7 W5 / D6 冻结规则）：宿主 {@code PlaybackQueueState} 里<b>每个</b>能解析出 id 的
     * List 字段。P-3 实证有两个（队列本体 + 随机顺序表），<b>两支都满足</b>「配对索引指向当前曲」
     * （{@code Ԩ=[2643127259,4885597,1377530437]} 配索引 2、{@code Ԫ=[2643127259,1377530437,4885597]}
     * 配索引 1，当前曲 = 1377530437），<b>光靠索引分不出哪支是真队列</b>。
     *
     * <p>真队列的裁决权交给<b>自校准</b>：{@code svc.NextTrack#notePlayback(long)} 用相邻两首起播
     * (A→B) 逐支校验 {@code list[(idx_A+1)%len] == B}，命中者调 {@link #pinQueueList(String)} 记为活配；
     * 未校准时由调用方按「与 DB 歌单 order 一致」打分挑一支（B2 实测 {@code Ԩ} 一致）。</p>
     *
     * @param listField 字段名（活配用的键，同一次运行内稳定）
     * @param ids       解析出的 netease id（紧凑表，跳过解析失败项）
     * @param aligned   与宿主列表逐位对齐的表（解析不出的位置为 {@code null}）
     * @param index     配对索引字段的值（宿主「当前第几首」）；取不到 -1
     */
    public record QueueChoice(String listField, java.util.List<String> ids, java.util.List<String> aligned,
                              int index, int size) {
    }

    /** 自校准出来的「活配」字段名；空串 = 尚未校准。 */
    private static volatile String pinnedQueueList = "";

    /** 当前活配字段名（空串 = 未校准）。 */
    public static String pinnedQueueList() {
        String s = pinnedQueueList;
        return s == null ? "" : s;
    }

    /**
     * 记下自校准命中的队列字段（幂等）。只有 {@code svc.NextTrack} 的自校准调用它——写错字段会让
     * 「下一首」算错，所以日志按 INFO 留痕（可 grep 复核）。
     */
    public static void pinQueueList(String listField) {
        if (listField == null || listField.isEmpty()) {
            return;
        }
        String old = pinnedQueueList;
        pinnedQueueList = listField;
        if (!listField.equals(old)) {
            PluginLog.i(TAG, "队列活配：" + (old == null || old.isEmpty() ? "（首次校准）" : "由 " + old + " 改为 ")
                    + listField + "（自校准：相邻两首起播顺序与宿主列表逐位吻合）");
        }
    }

    /** 全部队列候选（读不到返回空表）。线程：投 {@link HostBridgeWorker} + latch 4s；绝不抛。 */
    public static java.util.List<QueueChoice> queueChoices() {
        try {
            HostBridgeWorker w = HostBridgeWorker.get();
            if (w.isOnWorkerThread()) {
                return queueChoicesOnWorker();
            }
            java.util.concurrent.atomic.AtomicReference<java.util.List<QueueChoice>> box =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            if (!w.submit(() -> {
                try {
                    box.set(queueChoicesOnWorker());
                } catch (Throwable t) {
                    PluginLog.d(TAG, "读队列候选异常：" + brief(t));
                } finally {
                    done.countDown();
                }
            })) {
                return java.util.List.of();
            }
            done.await(4L, java.util.concurrent.TimeUnit.SECONDS);
            java.util.List<QueueChoice> v = box.get();
            return v == null ? java.util.List.of() : v;
        } catch (Throwable t) {
            PluginLog.d(TAG, "读队列候选失败：" + brief(t));
            return java.util.List.of();
        }
    }

    private static java.util.List<QueueChoice> queueChoicesOnWorker() {
        java.util.List<QueueChoice> out = new java.util.ArrayList<>();
        Object ctrl = staticInstance(CLS_CONTROLLER);
        if (ctrl == null) {
            return out;
        }
        Object flow = staticField(ctrl.getClass(), "StateFlow", "playbackQueueState");
        Object state = flow == null ? null : callAnyNoArg(flow, "getValue");
        if (state == null) {
            return out;
        }
        for (ListSlot s : listSlots(state)) {
            if (s.ids().isEmpty()) {
                continue;
            }
            out.add(new QueueChoice(s.field().getName(), s.ids(), s.aligned(), s.index(), s.raw().size()));
        }
        return out;
    }

    /**
     * 读宿主<b>当前播放曲目</b>的 netease 裸 id（0.11.7 W5，docs\00 §6.20.5）。
     *
     * <p>路径（P-3 实证）：{@code PlaybackController.service} → {@code findPlayer} →
     * {@code PiscesPlayer$Properties} → 递归找 {@code netease-<数字>}。播放器顶层读不到 id
     * （字段混淆 + 恒 null），属性层能读到。</p>
     *
     * @return 裸数字 id（不带 {@code netease-} 前缀）；<b>未知返回 0</b>——0 是「不知道」而不是
     *         「没有在播」，调用方不得据此判定队列为空
     */
    public static long currentTrackId() {
        try {
            HostBridgeWorker w = HostBridgeWorker.get();
            if (w.isOnWorkerThread()) {
                return currentTrackIdOnWorker();
            }
            java.util.concurrent.atomic.AtomicLong box = new java.util.concurrent.atomic.AtomicLong(0L);
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            if (!w.submit(() -> {
                try {
                    box.set(currentTrackIdOnWorker());
                } catch (Throwable t) {
                    PluginLog.d(TAG, "读当前曲异常：" + brief(t));
                } finally {
                    done.countDown();
                }
            })) {
                return 0L;
            }
            done.await(4L, java.util.concurrent.TimeUnit.SECONDS);
            return box.get();
        } catch (Throwable t) {
            PluginLog.d(TAG, "读当前曲失败：" + brief(t));
            return 0L;
        }
    }

    private static long currentTrackIdOnWorker() {
        Object ctrl = staticInstance(CLS_CONTROLLER);
        if (ctrl == null) {
            return 0L;
        }
        Object service = staticField(ctrl.getClass(), "PlaybackService", "service");
        Object player = findPlayer(service == null ? ctrl : service);
        if (player == null) {
            return 0L;
        }
        Object props = instanceField(player, CLS_PROPERTIES, null);
        if (props == null) {
            return 0L;
        }
        return bareTrackId(deepTrackId(props, 4, new java.util.HashSet<>()));
    }

    /**
     * 读宿主播放器<b>当前媒资项</b>的 netease 裸 id（0.11.49 封面绑定修复轮）。
     *
     * <p>与 {@link #currentTrackId()} 的区别：那条走 Properties 层深搜，真机 600 拍全 0；宿主自己判定
     * 「这一张封面是不是当前曲的」用的是 {@code getPlayer().U+0786()}（返回 {@code PiscesMediaItem}）
     * → {@code U+037F()}（id 字符串）—— 见 {@code PlaybackService.updateImageBitmap} 在
     * {@code Dispatchers.Main} 上的那次比对。本方法走同一条权威路径，是「播放条封面该投哪一首」的
     * 唯一判据来源。</p>
     *
     * @return 裸数字 id（只认 {@code netease-<4 位以上数字>} 形态）；读不到 / 不是本插件曲目 → 0
     *         （0 = 「不知道」而不是「没有在播」，调用方不得据此判空）
     */
    public static long playingTrackId() {
        try {
            HostBridgeWorker w = HostBridgeWorker.get();
            if (w.isOnWorkerThread()) {
                return playingTrackIdOnWorker();
            }
            java.util.concurrent.atomic.AtomicLong box = new java.util.concurrent.atomic.AtomicLong(0L);
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            if (!w.submit(() -> {
                try {
                    box.set(playingTrackIdOnWorker());
                } catch (Throwable t) {
                    PluginLog.d(TAG, "读当前媒资项异常：" + brief(t));
                    warnAuthLost("异常/" + t.getClass().getSimpleName(), "读当前媒资项异常：" + brief(t));
                } finally {
                    done.countDown();
                }
            })) {
                warnAuthLost("提交失败", "宿主交互线程未接受读取任务（队列满 / 已关闭）");
                return 0L;
            }
            done.await(2L, java.util.concurrent.TimeUnit.SECONDS);
            return box.get();
        } catch (Throwable t) {
            warnAuthLost("调用异常/" + t.getClass().getSimpleName(), "调用失败：" + brief(t));
            return 0L;
        }
    }

    /** 解析过的「当前媒资项」取值器（{@code PiscesPlayer} 上 0 参、返回 {@code PiscesMediaItem} 的那个方法）。 */
    private static volatile Method mCurrentMediaItem;

    /** {@link #warnAuthLost} 的去重集合（0.11.52）：签名 = 失败形态；同一形态每会话只提醒一条。 */
    private static final Set<String> AUTH_LOST_WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 权威读失败的一次性 WARN（0.11.52 F2 可观测）。
     *
     * <p>此前这些降级点要么静默（{@code playingTrackIdOnWorker} 的 null 分支 / catch），要么只有 DEBUG
     * ——日志 level=INFO 时数不到「读不到」，万一起播 / 投递时也读不到，就无从对照「权威未知」。提到
     * WARN 且按签名去重后，每份日志里能数到它（见 docs/57）。</p>
     */
    private static void warnAuthLost(String sig, String detail) {
        try {
            if (AUTH_LOST_WARNED.add(sig)) {
                PluginLog.w(TAG, "宿主权威当前曲读不到（" + detail
                        + "）：本会话同类只提示一条；封面守卫按「权威未知」处理（宁缺勿错）");
            }
        } catch (Throwable ignored) {
            // 记日志失败不影响读取路径
        }
    }

    private static long playingTrackIdOnWorker() {
        try {
            Object ctrl = staticInstance(CLS_CONTROLLER);
            if (ctrl == null) {
                warnAuthLost("控制器未就绪", "PlaybackController 未初始化");
                return 0L;
            }
            Object service = staticField(ctrl.getClass(), "PlaybackService", "service");
            Object player = findPlayer(service == null ? ctrl : service);
            if (player == null) {
                warnAuthLost("播放器未找到", "控制器 / 服务上找不到播放器");
                return 0L;
            }
            Object item = currentMediaItem(player);
            if (item == null) {
                warnAuthLost("媒资项为空", "播放器当前媒资项为空（空闲或读取失败）");
                return 0L;
            }
            return neteaseTrackId(itemId(item));
        } catch (Throwable t) {
            warnAuthLost("异常/" + t.getClass().getSimpleName(), "读当前媒资项异常：" + brief(t));
            return 0L;
        }
    }

    /** 宿主播放器上的「当前媒资项」：0 参且返回 {@code com.xuncorp.pisces.PiscesMediaItem} 的方法。 */
    private static Object currentMediaItem(Object player) {
        try {
            Method m = mCurrentMediaItem;
            if (m == null) {
                Class<?> want = Class.forName("com.xuncorp.pisces.PiscesMediaItem");
                for (Class<?> c = player.getClass(); c != null && c != Object.class && m == null;
                        c = c.getSuperclass()) {
                    for (Method cand : c.getDeclaredMethods()) {
                        if (cand.getParameterCount() == 0 && !Modifier.isStatic(cand.getModifiers())
                                && want.isAssignableFrom(cand.getReturnType())) {
                            cand.setAccessible(true);
                            m = cand;
                            break;
                        }
                    }
                }
                if (m == null) {
                    return null;
                }
                mCurrentMediaItem = m;
            }
            return m.invoke(player);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 媒资项的 id 字段（取值器 U+037F；取不到再扫「含 {@code netease-} 的字符串」）。 */
    private static String itemId(Object item) {
        try {
            Method m = item.getClass().getDeclaredMethod("\u037f");
            m.setAccessible(true);
            Object v = m.invoke(item);
            if (v instanceof String sv && !sv.isEmpty()) {
                return sv;
            }
        } catch (Throwable ignored) {
            // 退到扫描
        }
        for (Method m : item.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType() != String.class) {
                continue;
            }
            try {
                m.setAccessible(true);
                Object v = m.invoke(item);
                if (v instanceof String sv && sv.contains("netease-")) {
                    return sv;
                }
            } catch (Throwable ignored) {
                // 试下一个
            }
        }
        return null;
    }

    /** 严格形态：只认 {@code netease-<4 位以上数字>}（本地文件 / 别的曲库 → 0 = 不知道）。 */
    private static long neteaseTrackId(String raw) {
        if (raw == null) {
            return 0L;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("netease-(\\d{4,})").matcher(raw);
        if (!m.find()) {
            return 0L;
        }
        try {
            return Long.parseLong(m.group(1));
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** {@code netease-123…} → 123；非该形态 → 0（容忍尾部混入 {@code @混淆名}）。 */
    private static long bareTrackId(String raw) {
        if (raw == null) {
            return 0L;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4,})").matcher(raw);
        if (!m.find()) {
            return 0L;
        }
        try {
            return Long.parseLong(m.group(1));
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static java.util.List<String> queueTrackIdsOnWorker() {
        return queueViewOnWorker().ids();
    }

    /**
     * 队列读取主体（必须在宿主交互线程执行）。
     *
     * <p>0.11.7 W5 修正（P-2/P-3 实证）：宿主 {@code PlaybackQueueState} 里有<b>多个</b> List 字段
     * （字段序 {@code List/int/List/int}：队列本体 + 随机顺序表，各带一个「当前第几首」索引），
     * 旧实现 {@code firstListField()} 盲取第一个，P-3 实测读到的是<b>随机顺序表</b>（与队列不同长）
     * ⇒ 「下一首」算错。新判据按优先级：</p>
     * <ol>
     *   <li><b>强判据</b>：某列表的配对索引正好指向<b>当前播放曲</b> ⇒ 它就是队列本体；</li>
     *   <li>拿不到当前曲（宿主未播放 / 属性层读不到）时，取<b>解析出 id 最多</b>的那个列表；
     *       并列时按字段声明序取先者。</li>
     * </ol>
     *
     * <p>三态：结构读不到 = {@link QueueState#FAILED}；选中的列表确实为空 = {@link QueueState#EMPTY}；
     * 列表非空但一项都解析不出 id 也算 FAILED（<b>不得</b>当空队列）。</p>
     */
    private static QueueView queueViewOnWorker() {
        StringBuilder diag = new StringBuilder();
        try {
            Object ctrl = staticInstance(CLS_CONTROLLER);
            diag.append("ctrl=").append(ctrl == null ? "null" : shortName(ctrl.getClass()));
            if (ctrl == null) {
                return queueFail(diag, "宿主播放控制器不可达");
            }
            Object flow = staticField(ctrl.getClass(), "StateFlow", "playbackQueueState");
            diag.append(" flow=").append(flow == null ? "null" : shortName(flow.getClass()));
            Object state = flow == null ? null : callAnyNoArg(flow, "getValue");
            diag.append(" state=").append(state == null ? "null" : shortName(state.getClass()));
            if (state == null) {
                return queueFail(diag, "playbackQueueState 取不到值");
            }
            List<ListSlot> slots = listSlots(state);
            if (slots.isEmpty()) {
                return queueFail(diag, "状态对象里没有取到值的 List 字段");
            }
            long cur = currentTrackIdOnWorker();
            ListSlot best = null;
            ListSlot bestByCount = null;
            for (ListSlot s : slots) {
                diag.append(" [").append(s.field().getName()).append(" size=").append(s.raw().size())
                        .append(" 解析=").append(s.ids().size()).append(" 索引=").append(s.index()).append(']');
                if (bestByCount == null || s.ids().size() > bestByCount.ids().size()) {
                    bestByCount = s;
                }
                if (best == null && cur > 0L && s.index() >= 0 && s.index() < s.aligned().size()
                        && ("netease-" + cur).equals(s.aligned().get(s.index()))) {
                    best = s;
                    diag.append(" 命中=索引指向当前曲");
                }
            }
            // 自校准活配优先（D6 冻结规则①）：两支都满足「索引指向当前曲」，索引判据区分不了。
            String pin = pinnedQueueList();
            boolean pinned = false;
            if (!pin.isEmpty()) {
                for (ListSlot s : slots) {
                    if (pin.equals(s.field().getName())) {
                        best = s;
                        pinned = true;
                        diag.append(" 命中=自校准活配 ").append(pin);
                        break;
                    }
                }
                if (!pinned) {
                    diag.append(" 活配字段 ").append(pin).append(" 本次读不到，退回自动判据");
                }
            }
            if (best == null) {
                best = bestByCount;
                diag.append(cur > 0L ? " 判据=解析数最大（无列表索引指向当前曲 #" + cur + "）"
                        : " 判据=解析数最大（当前曲未知）");
            }
            if (best == null) {
                return queueFail(diag, "没有 List 字段取到值");
            }
            diag.append(" 选中=").append(best.field().getName());
            queueDebug = diag.toString();
            if (best.raw().isEmpty()) {
                return new QueueView(QueueState.EMPTY, java.util.List.of(), java.util.List.of(), queueDebug, -1, 0);
            }
            if (best.ids().isEmpty()) {
                return queueFail(diag, "队列 " + best.raw().size() + " 项但一项都解析不出 trackId（首项="
                        + clip(summarizeItem(best.raw().get(0)), 90) + "）");
            }
            return new QueueView(QueueState.OK, best.ids(), best.aligned(), queueDebug,
                    best.index(), best.raw().size());
        } catch (Throwable t) {
            diag.append(" 异常=").append(brief(t));
            queueDebug = diag.toString();
            return queueFailed(queueDebug);
        }
    }

    /** 队列读取失败：写诊断串并返回 FAILED。 */
    private static QueueView queueFail(StringBuilder diag, String why) {
        diag.append(" 失败=").append(why);
        queueDebug = diag.toString();
        return queueFailed(queueDebug);
    }

    /**
     * 状态对象的一个 List 字段候选：值 + 逐位解析结果 + 配对索引（紧随其后的整型字段，P-3 字段序
     * {@code List/int/List/int}；其后没有整型才往前找）。
     */
    private record ListSlot(Field field, List<Object> raw, java.util.List<String> ids,
                            java.util.List<String> aligned, int index) {
    }

    /** 枚举状态对象里<b>所有</b>取到值的 List 字段（含父类、跳过 static/synthetic），按声明序。 */
    private static List<ListSlot> listSlots(Object state) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = state.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                    continue;
                }
                fields.add(f);
            }
        }
        // getDeclaredFields 是「子类在前」，而字段序 List/int/List/int 是声明序 ⇒ 反转成父类在前
        java.util.Collections.reverse(fields);
        List<ListSlot> out = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            if (!List.class.isAssignableFrom(f.getType())) {
                continue;
            }
            Object v = readField(f, state);
            if (!(v instanceof List<?> rawList)) {
                continue;
            }
            List<Object> items = new ArrayList<>(rawList);
            java.util.List<String> ids = new ArrayList<>();
            java.util.List<String> aligned = new ArrayList<>();
            for (Object o : items) {
                String id = neteaseIdOf(o);
                if (id == null) {
                    // 兜底：宿主队列项字段名混淆严重时，解析探针那套摘要文本里的 trackId
                    java.util.regex.Matcher m =
                            java.util.regex.Pattern.compile("netease-\\d+").matcher(summarizeItem(o));
                    if (m.find()) {
                        id = m.group();
                    }
                }
                aligned.add(id);
                if (id != null) {
                    ids.add(id);
                }
            }
            out.add(new ListSlot(f, items, ids, aligned, companionIndex(fields, i, state)));
        }
        return out;
    }

    /** 列表字段的配对「当前第几首」：先找紧随其后的整型字段，没有再往前找；取不到 -1。 */
    private static int companionIndex(List<Field> fields, int listAt, Object state) {
        int after = intFieldValue(fields, listAt + 1, 1, state);
        return after >= 0 ? after : intFieldValue(fields, listAt - 1, -1, state);
    }

    private static int intFieldValue(List<Field> fields, int from, int step, Object state) {
        for (int i = from; i >= 0 && i < fields.size(); i += step) {
            Field f = fields.get(i);
            if (List.class.isAssignableFrom(f.getType())) {
                return -1;  // 越过另一个列表字段就不再跨段配对
            }
            Class<?> t = f.getType();
            if (t != int.class && t != Integer.class && t != long.class && t != Long.class) {
                continue;
            }
            Object v = readField(f, state);
            if (v instanceof Number n) {
                long l = n.longValue();
                return (l >= 0L && l <= Integer.MAX_VALUE) ? (int) l : -1;
            }
        }
        return -1;
    }

    /** 截断给日志用的串。 */
    private static String clip(String s, int max) {
        if (s == null) {
            return "null";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 最近一次读队列的诊断串（给人看，不参与逻辑）。 */
    private static volatile String queueDebug = "";

    public static String queueDebugInfo() {
        return queueDebug;
    }

    /** 从队列项里找出 {@code netease-<数字>} 形式的 trackId（宿主队列项字段名混淆且是嵌套对象 ⇒ 递归找）。 */
    private static String neteaseIdOf(Object item) {
        return deepTrackId(item, 4, new java.util.HashSet<>());
    }

    private static String deepTrackId(Object o, int depth, java.util.Set<Object> seen) {
        if (o == null || depth <= 0) {
            return null;
        }
        if (o instanceof String s) {
            return isTrackId(s) ? s : null;
        }
        if (o instanceof Number || o instanceof Boolean || o instanceof Character
                || o instanceof java.util.Collection<?> || o.getClass().isArray()) {
            return null;
        }
        if (!seen.add(o)) {
            return null;
        }
        Class<?> cls = o.getClass();
        if (cls.getName().startsWith("java.") || cls.getName().startsWith("kotlin.")
                || cls.getName().startsWith("androidx.")) {
            // JDK/Kotlin 包装类只扫字段，不再深挖方法（避免递归进集合内部结构）
            if (cls == java.net.URI.class || cls == java.net.URL.class) {
                return isTrackId(String.valueOf(o)) ? String.valueOf(o) : null;
            }
        }
        // ① 字段（含继承）
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    String hit = deepTrackId(v, depth - 1, seen);
                    if (hit != null) {
                        return hit;
                    }
                } catch (Throwable ignored) {
                    // 单个字段失败继续
                }
            }
        }
        // ② 无参取值器
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.isSynthetic() || m.getParameterCount() != 0) {
                    continue;
                }
                Class<?> rt = m.getReturnType();
                if (rt != String.class && rt.isPrimitive()) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    Object v = m.invoke(o);
                    String hit = deepTrackId(v, depth - 1, seen);
                    if (hit != null) {
                        return hit;
                    }
                } catch (Throwable ignored) {
                    // 继续
                }
            }
        }
        return null;
    }

    private static boolean isTrackId(String s) {
        if (s == null || !s.startsWith("netease-") || s.length() <= "netease-".length()) {
            return false;
        }
        for (int i = "netease-".length(); i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String summarizeItem(Object item) {        if (item == null) {
            return "空";
        }
        try {
            List<String> parts = new ArrayList<>();
            for (Class<?> c = item.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) {
                        continue;
                    }
                    f.setAccessible(true);
                    parts.add(summary(f.get(item)));
                }
            }
            return "[" + String.join(" | ", parts) + "]";
        } catch (Throwable t) {
            return shortName(item.getClass());
        }
    }

    /** URL 特征：只给 host + 扩展名 + 参数名，绝不打完整直链。 */
    static String describeUrl(String url) {
        try {
            java.net.URI u = java.net.URI.create(url);
            String path = u.getPath() == null ? "" : u.getPath();
            int dot = path.lastIndexOf('.');
            String ext = dot < 0 ? "?" : path.substring(dot + 1);
            String query = u.getQuery() == null ? "" : u.getQuery();
            List<String> names = new ArrayList<>();
            for (String kv : query.split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    names.add(kv.substring(0, eq));
                }
            }
            return "host=" + u.getHost() + " ext=" + ext + " 参数=" + names;
        } catch (Throwable t) {
            return "（URL 解析失败）";
        }
    }

    private static String brief(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        return root.getClass().getSimpleName() + (msg == null ? "" : "：" + msg);
    }
}

