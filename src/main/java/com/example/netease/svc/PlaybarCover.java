package com.example.netease.svc;

import com.example.netease.cfg.PluginConfig;
import com.example.netease.core.HostBridgeWorker;
import com.example.netease.core.PluginLog;
import com.example.netease.host.VoxzenBridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 播放条 / 播放页封面投递（D4 路线 ②：把成品位图直投宿主自己的播放条图流）。
 *
 * <p><b>为什么必须有它</b>（0.11.11 真机定案，见 {@code docs/51-探针报告-P4P5.md} §6.0 / §11.7）：
 * 宿主「播放条 / 播放页」这一处<b>不走</b>封面加载器，也就<b>不查</b>
 * {@code cache\shared_cover}（路线 ①-a 对它完全无效）；它走的是
 * {@code PlaybackService.refreshCurrentCover → updateImageBitmap → TagParser.readImage(path,w,h)}，
 * 该函数要求 {@code Files.exists(path)} 且读的是<b>本地音频文件的内嵌图</b> —— 而本插件的
 * {@code Track.path} 恒为 {@code http://127.0.0.1:17788/netease/<id>}，所以必然抛
 * {@code IllegalArgumentException(Failed requirement.)} ⇒ 播放条永远是 ♪ 占位符。</p>
 *
 * <p><b>本类做的事</b>：按曲目 id 取「封面唯一管线」（{@link CoverStore}）里的成品图字节，
 * 解码成宿主自己的位图对象，再投进播放条那条共享流。全程反射，零音频下载（红线：禁止为封面
 * 单独下载音乐），只改宿主内存态（重启即失，因此每次换曲都要重投）。</p>
 *
 * <p>投递链（B2 轮 2026-10-01 02:05 实测全通，逐环都有返回对象；B4 轮在 UI 上取到正面像素证据）：
 * <pre>
 *   ImageBitmapUtil.INSTANCE.decodeToExactSize(bytes,512,512,false)  → ImageBitmap
 *   SkiaImageAsset_skikoKt.asSkiaBitmap(imageBitmap)                 → org.jetbrains.skia.Bitmap
 *   new RefCountedImageBitmap(skiaBitmap)                            → 宿主自己的 owner 对象
 *   ScaledImageBitmapCacheKt.getSharedScaledImageBitmapCache()
 *       .onSourceChanged(owner)                                      → 宿主同款（按尺寸缩放缓存）
 *   PlaybackMonitorKt.updateImageBitmap(PlaybackMonitor.INSTANCE, owner) → MutableSharedFlow.tryEmit
 * </pre>
 * 全是宿主的 public / 未混淆名字的入口（类名与方法名 R8 后保留），所以按名字反射即可；
 * 任何一环不可达都只降级（返回一行原因），绝不让宿主或插件崩。</p>
 *
 * <h2>0.11.12：把「过几秒才出图」压成「跟着换曲一起出」</h2>
 *
 * <p><b>症状</b>（用户报障 2026-10-01 18:37）：播放条 / 播放页封面要等 0~5 秒才出现，
 * 点「下一首 / 上一首」尤其明显；用户的原话是「改成和播放列表一样应该为永久加载」。</p>
 *
 * <p><b>根因</b>（0.11.11 真机日志实测，逐条可复算）：</p>
 * <ol>
 *   <li><b>排队</b>：投递排在 {@link HostBridgeWorker} 那条<b>唯一</b>的宿主交互线程队尾，而队首排着
 *       行封面前瞻（每 3~5 秒一批、每批 4 首投递）、原生装配、库读写 —— 实测同一次投递的
 *       「触发 → 完成」时差在 <b>0~5 秒</b>之间跳（{@code 18:32:27→0s / 18:32:41→5s /
 *       18:37:28→4s / 18:37:38→4s}）。</li>
 *   <li><b>每次都要重新取图 + 重新解码</b>：投递时现调 {@link CoverStore#fetchImage}（读
 *       1200×1200 原图，实测 153 KB~2.4 MB）再 {@code decodeToExactSize(…,512,512)}，
 *       上一次的结果不留。</li>
 *   <li><b>触发点太晚</b>：0.11.11 只靠宿主三条歌词钩子（换曲后由宿主加载歌词时才回调）
 *       + 7 秒轮询兜底。</li>
 * </ol>
 *
 * <p><b>修法</b>（三条一起，缺一条都会留下可感知的延迟）：</p>
 * <ol>
 *   <li>{@link HostBridgeWorker#submitFast} 快车道 —— 投递插队首，不再排在行前台等长任务后面；</li>
 *   <li><b>成品图字节常驻</b>（{@link #CACHE}，LRU，按张数与字节双限）—— 命中时省掉读盘与索引查找；
 *       顺带把 {@link #prefetch(long)}（只取图入缓存、不投递）接到邻曲保温上，下一首 / 上一首在图库里
 *       通常<b>已经躺在缓存里</b>；</li>
 *   <li><b>触发点提前</b>：宿主一开口播这首歌（{@code NativeStreamServer} 的「这首歌真的成了当前曲目」
 *       分支）就投，不再等歌词钩子；轮询兜底从 7 秒收紧到 1.5 秒。</li>
 * </ol>
 *
 * <h2>0.11.13：投上去还要留得住</h2>
 *
 * <p><b>症状</b>（用户报障 2026-10-01 19:05）：0.11.12 把封面做成「跟着换曲秒出」之后，
 * 变成「封面先出、音乐一加载好封面又消失了」—— 用户原话「切的太快了，音乐还有延迟，
 * 封面先切了，然后音乐加载好了来了封面又消失了」。</p>
 *
 * <p><b>根因</b>：宿主自己刷新当前曲封面时走 {@code TagParser.readImage(本地文件内嵌图)}，
 * 而我们的曲目地址是本地流 URL，宿主读不出图就往同一条图流里发空值 ⇒ 清掉我们投的封面。
 * 0.11.11 投得晚（清完才投）表现为「慢」；0.11.12 投得早（还没清）表现为「先出后消失」。
 * 又因为 {@link #claim(long)} 会拦掉同一首的重复投递，兜底轮询也补不回来。</p>
 *
 * <p><b>修法</b>：{@code assertHeld(songId, why)}（+ {@code assertHeldInPlace}）——
 * 记住最近一次投递的 owner 实例，宿主要清就把它原样再发一次；同一首 10 秒内 250 ms 一发、
 * 之后 3 秒心跳。<b>该机制已在 0.11.14 整组退役</b>（用户实测「封面一直在刷新」），
 * 本版不再有周期性重发。</p>
 *
 * <p>日志里现在带两段归因：{@code 缓存命中/补入}、{@code 投递 <ms>（含排队）}、{@code 距触发 <ms>}，
 * 让「快不快」变成可复算的数字（判据见 {@code docs/51-播放条封面零延迟-0.11.12.md}）。</p>
 *
 * <h2>0.11.15：封面出场绑在「音乐出场」上（用户规格）</h2>
 *
 * <p><b>症状</b>（用户报障 2026-10-01 19:2x）：0.11.14 把「开流」当起播信号，而真机上「开流」只是宿主
 * 最早的一次<b>探测</b>请求（{@code 转发 #1: Range=bytes=0-0}），比真正开始供音频字节
 * （{@code 本地直出}）早 <b>3–4 秒</b> ⇒ 封面先出、音乐后到。</p>
 *
 * <p><b>用户给的规格</b>：① 打开软件时若有「恢复的上次曲目」，那一刻就要看到封面；
 * ② 点播放（或换曲）时，<b>音乐和封面同时入场</b>，不早不晚、不反复；③ 不能多次载入封面。</p>
 *
 * <p><b>0.11.15 的规则已被 0.11.18 取代</b>（显示态早出图 + 每曲一次校准那一套整组删除）。
 * 0.11.13 的「四次密集重发 + 3 秒心跳」被用户实测为「封面一直在刷新」，0.11.14 已退役；
 * 0.11.16 的三次定点补发同样被判为用户规格禁止的行为，0.11.18 整组删除（见下）。</p>
 *
 * <h2>0.11.18：一首 = 一次进场，且与音乐同一拍（用户规格，最高优先级）</h2>
 *
 * <p><b>用户规格原文</b>（2026-10-01 21:2x）：「点击下一首后音乐和封面同步进场，而不是封面先进场、
 * 然后音乐才进场，并且封面不允许进场多次，只能进场一次」，并明确「先别管延迟问题、必须按我说的来做」。
 * 与这条冲突的两处 —— <b>显示态早出图</b>（0.11.15）与<b>三次定点补发</b>（0.11.16）—— 本版整组删除。</p>
 *
 * <p><b>0.11.17 真机日志给出的两条实证</b>（{@code logs\plugin-<日期>.log}，21:21–21:24 五次换曲）：</p>
 * <ol>
 *   <li><b>封面确实抢跑</b>：五次换曲里四次走「轮询兜底」在 {@code +272…659 ms} 就投，而宿主报
 *       {@code Ready}/{@code isPlaying=true} 落在 {@code +934…3388 ms}。出声时刻可复算 ——
 *       {@code 首个位置拍 <ms>} 减去它在 {@code 投递落地} 行里报的位置值：如
 *       {@code +3119 ms 首个位置拍 879 ms} ⇒ 出声 ≈ {@code +2240 ms}，而 {@code Ready} 在 {@code +2114 ms}
 *       ⇒ <b>出声 ≈ 宿主报 Ready/isPlaying 那一刻</b>，前面的投递全是「封面先进场」。</li>
 *   <li><b>封面确实进场多次</b>：每首都有 {@code 换曲后 900 / 2600 / 4500 ms} 三次补发（一轮内累计 1→17 次），
 *       外加「恢复播放后重投」与「图流订阅者变多补发」两条 —— 用户看到的就是同一张封面反复进场。</li>
 * </ol>
 *
 * <p><b>本版规则</b>（一首 = 一次进场，且与音乐同一拍）：</p>
 * <ol>
 *   <li>{@link #onMusicStarted}：<b>唯一</b>的投递时刻。宿主报「音乐起来了」（播放态 {@code Ready} /
 *       {@code isPlaying=true}）或位置拍显示这一首刚刚开始（{@code 0 < positionMs ≤ }{@link #FRESH_POS_MS}）。
 *       这三个信号与出声同拍（真机差 ≤ 1 秒），而投递本身只要 5–30 ms。</li>
 *   <li>{@link #noteCurrent}：歌词钩子在换曲瞬间<b>只记不投</b>（显示态早出图已删除：它就是「封面先进场」）。</li>
 *   <li>{@link #onStreamServe}：宿主来要音频字节 —— <b>只记不发</b>（它比出声早 3–4 秒）。</li>
 *   <li>{@link #onPoll}：只做档位键与观测，<b>不投递</b>。</li>
 *   <li><b>没有任何补发</b>：同一首最多进场一次（{@link #musicDeliveredId} + {@link #lastOk} 双闸），
 *       失败不进场、成功后也不再重投。</li>
 * </ol>
 *
 * <p><b>代价（诚实登记）</b>：宿主自己那次失败刷新若落在我们投递<b>之后</b>，没有补发可救 ⇒
 * 「投出去会不会又被抹掉」必须靠真机像素证据确认（判据：投递后 +1 s 与 +8 s 各抓一次图）。
 * 若真机出现「出图后又消失」，正确解法是<b>把投递时点再往后挪</b>，<b>不是</b>加补发（用户规格禁止）。</p>
 *
 * <p>线程：{@link #push} 从宿主回调线程 / 流服务线程调用（只做一次原子判重 + 一次队列插入，不阻塞）；
 * 真正的读盘 / 反射 / 投递跑在 {@link HostBridgeWorker} 上（契约 §4 规则 1：宿主反射只在那一条线程）。</p>
 *
 * <p><b>0.11.21 增补：投出去之后还要复核落地。</b>「上一首播完<b>自动接续</b>」那一轮与「手动点下一首」不同 ——
 * 宿主自己那次起播刷新（{@code PlaybackService.refreshCurrentCover}，对本机流 URL 读不出内嵌图）与我们的投递
 * <b>同拍</b>，它失败后往同一条图流发空值，会把我们投的那一张在几百毫秒后顶成 ♪（用户 2026-10-01 报障）。
 * 于是投递成功后挂一个 8 秒复核：每拍读宿主的 {@code ScaledImageBitmapCache.currentSource}，
 * 还是我们那一张 → 记「落地确认」（零补投）；被换掉 → 用<b>同一个 owner 实例</b>补投（≤2 次，不重新解码、
 * 不产生新实例 ⇒ 不引起重组/闪烁）；复核读不到且这一轮是自动接续 → 兜底补投一次。
 * 正常「点下一首」路径不受影响：仍然是首发一次进场（0.11.18 的「一首一次」规格不变）。</p>
 */
public final class PlaybarCover {

    private static final String TAG = "playbar";

    /** 投递尺寸：512×512（B 轮实证值；播放条槽位 44×44、播放页更大，宿主自己按档位缩放）。 */
    private static final int SIDE = 512;

    /** 成品图字节缓存上限（张数 + 字节双限；LRU 淘汰）。实测原图 153 KB~2.4 MB，取 24 MB ≈ 最近几十首。 */
    private static final int CACHE_MAX_ITEMS = 48;
    private static final long CACHE_MAX_BYTES = 24L * 1024L * 1024L;

    /** 最近一次投递成功的曲目 id：同一首不重复投（换曲即失效）。 */
    private static volatile long lastOk = -1L;

    /** 正在投递中的曲目 id（三条歌词钩子会为同一首各投一次，这里防并发重复）。 */
    private static final AtomicLong pending = new AtomicLong(-1L);

    private static final AtomicLong OK = new AtomicLong();
    private static final AtomicLong FAILS = new AtomicLong();
    private static final AtomicLong PREWARMED = new AtomicLong();

    /** 成品图缓存命中 / 未命中计数：命中率 = 稳态下「换曲零延迟」的直接判据（命中即免读盘 + 免索引查找）。 */
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();

    /** 每个「失败原因」只落一行，避免宿主回调高峰把日志刷爆。 */
    private static final Set<String> LOGGED_FAIL = ConcurrentHashMap.newKeySet();

    /** 成品图字节缓存：album → 原图字节（LRU；只在宿主交互线程上读写，stats() 读计数另算）。 */
    private static final Map<String, byte[]> CACHE =
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return false;   // 淘汰在 put() 里显式做（要按字节数算）
                }
            };
    private static long cacheBytes;

    /** 预热去重（哪些曲目已经排过预热队）。 */
    private static final Set<Long> PREWARM_SEEN = ConcurrentHashMap.newKeySet();

    // ---------------------------------------------------------------- 0.11.18 一次进场通道

    /** 「音乐真的起来」的时刻（宿主播放态 Ready / isPlaying=true / 新鲜位置拍）。只进日志与统计。 */
    private static volatile long musicAt;

    /** 最近一次成功投递的触发来源（诊断用；0.11.18 起只可能是起播类来源）。 */
    private static volatile String deliveredWhy = "";

    /** 「起播」这一路已经认领过的曲目 id（认领即不再投；换曲自然失效）—— <b>一首只进一次场</b>的闸。 */
    private static volatile long musicDeliveredId = -1L;

    /** 上一拍位置（毫秒）：用来识别「位置回落 = 新曲从头开始」（换曲瞬间宿主会先吐上一首的旧位置）。 */
    private static volatile long lastPosMs = -1L;

    /** 位置回落的判定余量（毫秒）：新拍比旧拍小这么多以上才算「回到 0 重新开始」。 */
    private static final long POS_RESET_SLACK_MS = 800L;

    // ------------------------------------------------------------ 起播时序黑匣子（0.11.15 取证）

    /** 黑匣子最多攒多少条（超了直接丢；宿主回调线程上不能无界增长）。 */
    private static final int TRACE_MAX = 64;

    /** 起播时序缓冲：宿主回调线程只入队一行字符串（零 IO），由 1.5 秒轮询统一落盘。 */
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> TRACE =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** 当前这批时序的起点（毫秒）；换曲时重置。 */
    private static volatile long traceT0;

    /** 当前这批时序属于哪一首。 */
    private static volatile long traceSongId = -1L;

    /** 「首个位置拍」已经记过的曲目 id（每曲一次，免得每秒一条把缓冲刷满）。 */
    private static volatile long posTracedId = -1L;

    /**
     * 记一拍起播时序（<b>任何线程都可以调</b>：宿主回调线程上只做一次入队，零 IO、零锁）。
     *
     * <p>为什么需要它：宿主自己一行日志都不出，而「封面投早了 / 投晚了」只能靠<b>宿主钩子的到达顺序</b>判。
     * 黑匣子把「换曲 → 歌词钩子 → 播放态 {@code Ready} / {@code isPlaying} → 首个位置拍 → 投递落地」
     * 排成一条毫秒级时间线，由 {@link #flushTrace(String)} 落盘（真机判读 / 回归对照用）。</p>
     */
    public static void trace(String ev) {
        try {
            if (TRACE.size() >= TRACE_MAX) {
                return;
            }
            long t0 = traceT0;
            if (t0 == 0L) {
                t0 = System.currentTimeMillis();
                traceT0 = t0;
            }
            TRACE.add("+" + Math.max(0L, System.currentTimeMillis() - t0) + "ms " + ev);
        } catch (Throwable ignored) {
            // 记时序失败绝不影响宿主播放流程
        }
    }

    /** 换曲：重置这批时序的起点（同一首重复调只记一次）。 */
    public static void traceSong(long songId) {
        if (songId <= 0L || songId == traceSongId) {
            return;
        }
        traceSongId = songId;
        posTracedId = -1L;
        traceT0 = System.currentTimeMillis();
        trace("换曲 netease-" + songId);
    }

    /** 首个位置拍（每曲只记一次）：音频真的在推进 = 最贴近「耳朵听见」的编译期证据。 */
    public static void traceFirstPos(long songId, long positionMs) {
        if (songId <= 0L || songId == posTracedId) {
            return;
        }
        posTracedId = songId;
        trace("首个位置拍 " + positionMs + " ms");
    }

    /** 把黑匣子攒下的时序落盘（只在插件自己的工作线程上调；没有新事件就什么都不写）。 */
    public static void flushTrace(String why) {
        if (TRACE.isEmpty()) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder(192);
            String e;
            while ((e = TRACE.poll()) != null) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(e);
            }
            PluginLog.i(TAG, "起播时序（" + why + "，曲 netease-" + traceSongId + "）：" + sb);
        } catch (Throwable ignored) {
            // 落盘失败不得影响宿主播放流程
        }
    }

    /**
     * 「这一首刚刚开始」的位置拍上限（毫秒）：位置值落在 {@code (0, 此值]} 才算一次起播信号。
     *
     * <p>为什么必须有上限：换曲瞬间宿主会先吐一拍<b>上一首的旧位置</b>（真机实测 {@code +93 ms → 9161 ms}、
     * {@code +904 ms → 7996 ms}）—— 那种拍投下去就是用户报障的「封面先进场」。新曲真正开始时位置从 0 起算，
     * 首拍必然很小（真机 {@code 879 ms}、{@code 970 ms}、{@code 989 ms}）。</p>
     */
    private static final long FRESH_POS_MS = 1500L;

    /** 宿主最近一次播放态名（{@code Idle} / {@code Ended} / {@code Buffering} / {@code Ready}；空 = 还没收到过）。 */
    private static volatile String hostState = "";

    /** 起播对齐曲目数（音乐起播那一刻投成）。0.11.18 起这也是「进场次数」的唯一计数。 */
    private static final AtomicLong ALIGNED = new AtomicLong();

    /** 宿主正在供流的那首（= 正在播）。状态钩子自己拿不到 id，靠它把「封面 ↔ 音乐」对上。 */
    private static volatile long currentSongId = -1L;

    // ------------------------------------------------------- 0.11.49 权威当前曲（修封面互相串）

    /**
     * 宿主播放器「当前媒资项」的权威读（{@link VoxzenBridge#playingTrackId()}）缓存。
     *
     * <p>真机证据（2026-10-04 10:50:44）：宿主为<b>邻曲保温</b>来要下一首的字节，{@link #onStreamServe}
     * 把 {@code currentSongId} 带偏；同一秒旧曲的起播拍与换曲事件交错，两次投递都过了旧判重，
     * <b>后投的那张（错的）落地并显示</b> ⇒ 用户看到「播放条与列表封面不一致」。
     * {@code currentSongId} 只能证明「宿主碰过这一首的字节」，证明不了「这一首正在播」；
     * 权威判据只有宿主播放器里的当前媒资项。</p>
     */
    private static volatile long playingIdCache;

    /** {@link #playingIdCache} 的刷新时刻（毫秒）；0 = 还没读到过。 */
    private static volatile long playingIdAt;

    /**
     * 「刚换过曲」窗口（宿主报 {@code Ended} 起算）。0.11.49c 真机实证（2026-10-04 11:58:18 自动接续）：
     * 宿主已开流新曲、队列也换了格，但播放器「当前媒资项」字段还可能指着上一首 —— 窗口内遇到
     * 「权威值是已投过的旧曲」时按刚认到的曲/供流凭证走，窗口外一律以权威为准。
     */
    private static final long TRANSITION_MS = 8000L;

    /** 最近一次「被权威否决的供流曲」+ 时刻：换曲窗口里权威滞后时的新曲凭证（0 = 无）。 */
    private static volatile long servedId;
    private static volatile long servedAt;

    /**
     * 宿主此刻是否真的在播（播放态钩子的镜像）。
     *
     * <p>0.11.18 起<b>不再参与投递决策</b>：它只是个观测值（进 {@link #stats()} 与黑匣子）。
     * 历史教训：0.11.15–0.11.17 的兜底轮询拿它当闸，而它是「宿主在放某一首」的全局态 ——
     * 换曲时它一直是 true ⇒ 轮询在 {@code +0.1…1.5 s} 就投，正是用户报障的「封面先进场」。</p>
     */
    private static volatile boolean hostPlaying;

    /** {@link #inject} 最近一次构造出来的 owner（诊断用：判「这一曲到底投成没投成」）。 */
    private static volatile Object lastOwnerInjected;

    /** 上一次读到的宿主封面尺寸档位（{@code -1} = 还没读到；宿主用它同时决定 {@code TagParser.readImage} 的 w/h 与缓存键）。 */
    private static volatile int lastTier = -1;

    /** 已经补过「档位键」的「曲目|档位」指纹（每组合一次；档位键走 {@link CoverDelivery#deliverExtra}，零音频、只多几档图）。 */
    private static final Set<String> TIER_KEYED = ConcurrentHashMap.newKeySet();

    /** 档位键补写次数。 */
    private static final AtomicLong TIER_KEYS = new AtomicLong();

    private PlaybarCover() {
    }

    /**
     * 投一次（异步，非阻塞）：宿主回调线程 / 宿主流服务线程都可以调。
     *
     * <p>走 {@link HostBridgeWorker#submitFast}（插队首）—— 投递必须赶在用户看见播放条之前落地，
     * 不能排在行封面前瞻那类长任务后面。</p>
     *
     * @param songId 曲目 id（裸数字；{@code <= 0} 直接忽略）
     * @param why    触发来源（进日志，便于真机判读）
     * @return 是否已接手（{@code false} = 同曲已投过 / 正在投）
     */
    public static boolean push(long songId, String why) {
        return emit(songId, why, false);
    }

    /** 同 {@link #push}，但<b>已经在宿主交互线程上时</b>就地执行（省掉一次排队）。 */
    public static boolean pushInPlace(long songId, String why) {
        if (!claim(songId)) {
            return false;
        }
        final long at = System.currentTimeMillis();
        final String w = why == null ? "" : why;
        HostBridgeWorker hb = HostBridgeWorker.get();
        if (hb.isOnWorkerThread()) {
            run(songId, w, at, false);
        } else {
            hb.submitFast(() -> run(songId, w, at, false));
        }
        return true;
    }

    /** 投递的统一出口：判重 + 快车道插队首。{@code force} 只给「起播校准」用（越过 {@code lastOk} 判重，不越过在投判重）。 */
    private static boolean emit(long songId, String why, boolean force) {
        if (force) {
            if (songId <= 0L || pending.get() == songId) {
                return false;
            }
        } else if (!claim(songId)) {
            return false;
        }
        final long at = System.currentTimeMillis();
        final String w = why == null ? "" : why;
        HostBridgeWorker.get().submitFast(() -> run(songId, w, at, force));
        return true;
    }

    /**
     * <b>换曲登记</b>（0.11.18）：歌词钩子只知道「该放哪一首」，<b>只记不投</b>。
     *
     * <p><b>为什么在这里投不得</b>：宿主自己的封面刷新链是
     * {@code PlaybackService.refreshCurrentCover → updateImageBitmap → TagParser.readImage(本地文件内嵌图)}，
     * 而插件登记的播放地址是本机流（{@code http://127.0.0.1:17788/netease/<id>}）—— 宿主读不出内嵌图，
     * 会往同一条图流发一次<b>空值</b>；同时「换曲」比「出声」早 1–4 秒（见类注释两条实证）⇒
     * 在这里投正是用户报障的「封面先进场、音乐后进场」。</p>
     *
     * <p>0.11.15 的「宿主没在播 ⇒ 立刻出图（显示态）」与两条宽限定时器已在 0.11.18 删除：
     * 用户规格是「封面与音乐同一拍进场」，宁可等那一拍，也不提前出图。</p>
     *
     * @param songId  曲目 id（{@code <= 0} 忽略）
     * @param why     触发来源（进日志 / 黑匣子，便于真机判读）
     * @param playing 宿主此刻是否在播（只进日志；0.11.18 起不参与投递决策）
     */
    public static void noteCurrent(long songId, String why, boolean playing) {
        if (songId <= 0L || songId == currentSongId) {
            return;
        }
        currentSongId = songId;
        traceSong(songId);
        String st = hostState;
        trace("歌词钩子 " + why + "（宿主态 " + (st.isEmpty() ? "未知" : st) + "，在播=" + playing
                + " ⇒ 只记不投，等起播那一拍）");
        PluginLog.i(TAG, "播放条封面等音乐起播：netease-" + songId + "（" + why
                + "；0.11.18 起只记不投，投递只认起播信号）");
        // 0.11.19：同一拍也给**音频头部**保温 —— 歌词钩子比「宿主来要字节」早 1–3 s，
        // 于是「首播 / 列表点歌」也能落到 `邻曲保温直出`（只备字节：不投图流、不写档位键）。
        NeighborWarm.kick(songId, PluginConfig.audioLevel());
    }

    /**
     * 宿主播放态镜像（{@code onStateChanged} 调）：只存一个名字，零 IO。
     *
     * <p>0.11.18 起<b>它不再参与投递决策</b>（显示态立刻出图那一路已按用户规格整组删除），
     * 只进日志与黑匣子：{@code Ready} 就是「可以出声了」那一拍，是投递信号的来源之一。</p>
     */
    public static void onHostState(String name) {
        hostState = name == null ? "" : name;
        if ("Ended".equals(hostState)) {
            // 0.11.21：「自动接续」这一轮的特征 —— 宿主自己那次失败刷新与我们的起播投递同拍（见 landCheck）
            endedAt = System.currentTimeMillis();
        }
    }

    /**
     * 宿主向本地流服务要了<b>当前曲</b>的音频字节：<b>只记不发</b>。
     *
     * <p>真机实测：这一步只是宿主的探测 / 档位协商，比真正出声早 1.4–3.4 秒；投在这里就是「封面抢跑」。
     * 它仍然有用 —— 它告诉我们「哪一首才是当前曲」（宿主那个「当前曲」反射字段取不到值），
     * 也是 {@code onPoll} 补档位键时的曲目来源。</p>
     */
    public static void onStreamServe(long songId, String why) {
        if (songId <= 0L) {
            return;
        }
        long auth = playingIdCache;                  // 只读缓存（本方法可能在流服务线程上，绝不阻塞）
        if (auth > 0L && auth != songId) {
            // 0.11.49：邻曲保温（下一首 / 上一首）也会来要字节 —— 与宿主当前媒资项不符时不夺位，
            // 否则 currentSongId 被带偏，起播拍会把「邻曲的封面」当成当前曲投出去。
            // 0.11.49c：换曲窗口里权威字段本身可能滞后 —— 把这一首记成「新曲凭证」，起播拍再裁决。
            if (endedAt > 0L && System.currentTimeMillis() - endedAt <= TRANSITION_MS) {
                servedId = songId;
                servedAt = System.currentTimeMillis();
            }
            trace("供流曲目 " + songId + " ≠ 当前曲 " + auth + "（" + why + "）：不夺位");
            PluginLog.d(TAG, "供流曲目 " + songId + " 与宿主当前曲 " + auth + " 不符（" + why
                    + "）：不改变封面目标");
            return;
        }
        if (auth <= 0L && songId != currentSongId) {
            // 0.11.52（F2）：权威读不到时不猜 —— 供流字节只能证明「宿主碰过这一首的字节」，证明不了
            // 「这一首正在播」。邻曲保温恰在权威不可用那一拍来要字节时，沿用旧行为（对齐）会把封面
            // 目标带偏，且起播 / 投递前两次现读若也失败就是错图；宁缺勿错（0.11.49 结论）。
            if (endedAt > 0L && System.currentTimeMillis() - endedAt <= TRANSITION_MS) {
                servedId = songId;                   // 换曲窗口内只记供流凭证（供 lagTolerantTarget 裁决）
                servedAt = System.currentTimeMillis();
            }
            trace("供流曲目 " + songId + "（权威未知，" + why + "）：只记不发");
            PluginLog.d(TAG, "供流曲目 " + songId + "（权威未知，" + why + "）：不改变封面目标");
            return;
        }
        if (songId != currentSongId) {
            currentSongId = songId;
            traceSong(songId);
            trace("宿主来要字节（" + why + "）");
            PluginLog.d(TAG, "供流曲目对齐：" + songId + "（" + why + "）");
        }
    }

    /**
     * 音乐真的起来了：<b>0.11.18 起这是唯一允许投递的时刻</b>（一首 = 一次进场）。
     *
     * <p>三个信号都映射到这里：播放态 {@code Ready}（缓冲够了、可以出声了）、{@code isPlaying=true}
     * （真的在播）、位置拍显示这一首刚开始（{@code 0 < positionMs ≤ }{@link #FRESH_POS_MS}，音频在推进）。
     * 真机复算（类注释两条实证）：宿主报 {@code Ready} 那一刻 ≈ 真正出声，而投递本身只要 5–30 ms ⇒ 同拍。</p>
     *
     * <p><b>没有第二次</b>：同一首认领一次（{@link #musicDeliveredId}），已投成（{@link #lastOk}）直接返回 ——
     * 0.11.15 的「起播校准」与 0.11.16 的定点补发都已删除（用户规格：封面只能进场一次）。</p>
     */
    public static void onMusicStarted(String why) {
        long sid = currentSongId;
        long auth = playingNow();                    // 0.11.49c：worker 现读 —— 只读缓存会滞后一整轮（11:58 自动接续静默丢投）
        if (auth > 0L && auth != sid) {
            sid = lagTolerantTarget(sid, auth);      // 0.11.49c：权威滞后（仍指上一首）时不覆盖刚认到的新曲
            currentSongId = sid;
        }
        if (sid <= 0L || sid == musicDeliveredId || sid == lastOk || pending.get() == sid) {
            return;             // 没认到曲 / 这一首已经进场（或正在投）：绝不第二次进场
        }
        musicDeliveredId = sid;
        musicAt = System.currentTimeMillis();
        String w = why == null ? "起播" : why;
        trace("起播投（" + w + "）");
        if (emit(sid, w, false)) {
            ALIGNED.incrementAndGet();
        }
    }

    /**
     * 位置拍 = 音频真的在推进：换曲场景<b>最贴近「耳朵听见」</b>的起播信号（也作为状态钩子的兜底）。
     *
     * <p>0.11.18 的两道<b>新鲜度闸</b>（换曲瞬间宿主会先吐一拍<b>上一首的旧位置</b>：
     * 真机 {@code +93 ms → 9161 ms}、{@code +904 ms → 7996 ms} —— 那种拍投下去就是「封面先进场」）：</p>
     * <ol>
     *   <li>{@code 0 < positionMs ≤ }{@link #FRESH_POS_MS}：新曲刚开始，位置必然很小；</li>
     *   <li><b>位置回落</b>（{@code positionMs} 比上一拍小 {@link #POS_RESET_SLACK_MS} 以上）：
     *       宿主把播放位置归零重排 —— 这是「这一首从头上路」的另一种凭证（首拍可能已过 1.5 s）。</li>
     * </ol>
     */
    public static void onPositionTick(long positionMs) {
        if (positionMs <= 0L) {
            return;
        }
        long sid = currentSongId;
        long prev = lastPosMs;
        lastPosMs = positionMs;
        traceFirstPos(sid, positionMs);
        boolean fresh = positionMs <= FRESH_POS_MS;
        boolean reset = prev > 0L && positionMs + POS_RESET_SLACK_MS < prev;
        if (!fresh && !reset) {
            return;             // 上一首的旧位置：不是起播信号（0.11.18 实证过两次）
        }
        if (sid <= 0L || sid == musicDeliveredId || sid == lastOk) {
            return;             // 已经进场过 / 认不到曲：只留时序证据，不投
        }
        HostBridgeWorker.get().submitFast(() -> onMusicStarted(
                "起播位置 " + positionMs + " ms" + (reset && !fresh ? "（位置回落）" : "")));
    }

    /**
     * 播放态镜像（{@code onIsPlayingChanged} 调）：宿主回调线程上一次 volatile 写，零 IO。
     *
     * <p>0.11.18 起<b>只做观测</b>：投递时点一律由 {@link #onMusicStarted} 决定（它自己就是那条
     * {@code isPlaying=true} 信号）。0.11.16 的「暂停 → 继续」补发边沿（{@code resumeEpoch}）已删除 ——
     * 它与「封面只能进场一次」冲突。</p>
     */
    public static void noteHostPlaying(boolean playingNow) {
        hostPlaying = playingNow;
    }

    /** 宿主此刻是否在播（0.11.18 起只作观测/诊断；投递不再看它 —— 换曲瞬间它仍是上一首的 true）。 */
    public static boolean hostPlaying() {
        return hostPlaying;
    }

    // ------------------------------------------------------------------ 档位观测（只补键，不投递）

    /** 档位读不出来时的兜底档（只给当前曲补三档，零音频；读到真档位就不用它）。 */
    private static final int[] FALLBACK_TIERS = {256, 512, 1024};

    // 0.11.16 的「定点补发」整组删除（0.11.18）：
    //   REISSUE_AT_MS = {900, 2600, 4500} / scheduleReissue() / reissue() / deliverOwner()
    // 用户规格：「封面不允许进场多次，只能进场一次」。真机日志（0.11.17）证明它们每首让封面多进场 3–5 次
    // （换曲后 900 / 2600 / 4500 ms + 恢复播放 + 订阅者变化），本轮全删。
    // 删除它们的前提：投递时点已经落在「起播」那一拍 —— 宿主自己那次失败刷新发生在出声之前，
    // 我们在它之后再投，就不需要靠补发去覆盖（真机判据：投递后 +1 s 与 +8 s 各抓一次像素，见 docs\51）。

    // ---------------------------------------------------------------- 0.11.21 落地复核（自动接续那一轮）

    /**
     * 宿主报 {@code Ended} 的时刻（毫秒）——「上一首播完自动接续」这一轮的特征。
     *
     * <p>真机（2026-10-01 23:37:40，曲 {@code netease-3318238806}）：{@code +433 ms 播放态 Ended} →
     * {@code +433 ms Buffering} → {@code +434 ms isPlaying=true} → 我们投 → {@code +443 ms 投递落地}，
     * 而播放条最终是 ♪。手动点下一首的几轮时序里没有这一拍 {@code Ended}，故用它区分两条路。</p>
     */
    private static volatile long endedAt;

    /** 落地复核窗口（毫秒）：投递后在这段时间里每拍查一次「宿主当前图源还是不是我们投的那一张」。 */
    private static final long LAND_WATCH_MS = 8000L;

    /** 复核确认门槛（毫秒）：至少观察这么久、且这一拍仍是我们那一张，才算「确认落地」。 */
    private static final long LAND_CONFIRM_MS = 4000L;

    /** 复核读不到时的兜底补投时刻（毫秒）：只在「自动接续」那一轮启用（手动路径不碰）。 */
    private static final long LAND_FALLBACK_MS = 2500L;

    /** 最多补投几次（都是同一个 owner 实例，绝不解码第二次）。 */
    private static final int LAND_MAX_REPAIRS = 2;

    private static final Object LAND_LOCK = new Object();
    private static Object landOwner;                  // 投出去的那一张（复核 / 补投都用它，不重新解码）
    private static long landSongId = -1L;
    private static long landAt;
    private static int landRepairs;
    private static boolean landFirstLogged;

    /** 落地复核计数：确认落地 / 复核到被换 / 同实例补投 / 复核不可读。 */
    private static final AtomicLong LAND_OK = new AtomicLong();
    private static final AtomicLong LAND_LOST = new AtomicLong();
    private static final AtomicLong LAND_REPAIRED = new AtomicLong();
    private static final AtomicLong LAND_BLIND = new AtomicLong();

    /** 「复核不可读」的具体原因只落一行（宿主升级换了成员名时靠它定位）。 */
    private static final Set<String> LOGGED_LAND_BLIND = ConcurrentHashMap.newKeySet();

    /** 投递成功即挂上复核（worker 线程；同一首的新投递会覆盖旧的）。 */
    private static void armLanding(long songId, Object owner) {
        if (songId <= 0L || owner == null) {
            return;
        }
        synchronized (LAND_LOCK) {
            landOwner = owner;
            landSongId = songId;
            landAt = System.currentTimeMillis();
            landRepairs = 0;
            landFirstLogged = false;
        }
    }

    private static void clearLanding() {
        synchronized (LAND_LOCK) {
            landOwner = null;
            landSongId = -1L;
            landAt = 0L;
            landRepairs = 0;
            landFirstLogged = false;
        }
    }

    /** 这一轮是不是「上一首播完自动接续」（宿主在投递前 1.5 s 内报过 {@code Ended}）。 */
    private static boolean autoContinued(long deliveredAt) {
        long e = endedAt;
        return e > 0L && deliveredAt >= e && deliveredAt - e <= 1500L;
    }

    /**
     * 落地复核（1.5 s 轮询调，worker 线程）：投递之后每拍读一次宿主「当前图源」是不是我们投的那一张。
     *
     * <p>三种结论：{@code same}（还是我们那张）/ {@code lost:…}（被换成别的图或清空 = ♪）/ {@code blind:…}
     * （宿主成员读不到 —— 反射不通时如实登记，不做无证据的补投）。</p>
     */
    private static void landCheck() {
        final Object owner;
        final long sid;
        final long at;
        synchronized (LAND_LOCK) {
            owner = landOwner;
            sid = landSongId;
            at = landAt;
        }
        if (owner == null || sid <= 0L) {
            return;                                  // 没有待复核的
        }
        long playing = playingNow();
        if (playing > 0L && playing != sid) {
            // 0.11.49：这一张属于上一首（复核窗口里用户/宿主已换曲）—— 绝不补投，也绝不「确认」错图。
            clearLanding();
            PluginLog.i(TAG, "封面落地复核放弃：netease-" + sid + " 已不是宿主当前曲（现在 netease-"
                    + playing + "）");
            return;
        }
        long age = System.currentTimeMillis() - at;
        if (currentSongId != sid || age > LAND_WATCH_MS) {
            clearLanding();                          // 换曲即失效 / 观察窗口到点
            return;
        }
        String verdict = currentSourceOf(owner);
        boolean auto = autoContinued(at);
        int repairs;
        boolean first;
        synchronized (LAND_LOCK) {
            repairs = landRepairs;
            first = !landFirstLogged;
            landFirstLogged = true;
        }
        if (first) {
            PluginLog.i(TAG, "封面落地复核：netease-" + sid + " 首拍 " + verdict + "（投递后 " + age
                    + " ms，" + (auto ? "自动接续" : "手动换曲") + "）");
        }
        if ("same".equals(verdict)) {
            if (age >= LAND_CONFIRM_MS) {            // 观察满 4 秒还是我们那张 ⇒ 确认落地，收工
                clearLanding();
                LAND_OK.incrementAndGet();
                PluginLog.i(TAG, "封面落地确认：netease-" + sid + " 观察 " + age
                        + " ms 一直是本插件投的那一张（同实例补投 " + repairs + " 次）");
            }
            return;
        }
        if (verdict.startsWith("blind:")) {          // 复核不可读：自动接续那一轮兜底投一次（手动路径不碰）
            if (auto && repairs == 0 && age >= LAND_FALLBACK_MS) {
                repairLanding(owner, sid, "复核不可读（" + verdict.substring(6) + "）⇒ 自动接续兜底一次");
            } else if (age > LAND_FALLBACK_MS + 2000L) {
                LAND_BLIND.incrementAndGet();
                String why = verdict.substring(6);
                if (LOGGED_LAND_BLIND.add(why)) {
                    PluginLog.w(TAG, "封面落地复核不可读：" + why
                            + "（退化为「投一次就不管」；把这一行贴给开发可定位宿主成员改名）");
                }
                clearLanding();
            }
            return;
        }
        if (repairs >= LAND_MAX_REPAIRS) {           // lost 且已到上限：停止补投，留证据
            clearLanding();
            PluginLog.w(TAG, "封面落地复核：netease-" + sid + " 被宿主换掉 " + repairs + " 次，达上限不再补投");
            return;
        }
        repairLanding(owner, sid, "lost:null".equals(verdict)
                ? "宿主图源被清空（失败刷新的空值顶掉）" : "宿主图源被换成别的图");
    }

    /**
     * 同实例补投：<b>只重发图流那两步</b>（{@code onSourceChanged} + {@code updateImageBitmap}），
     * 不重新解码、不写缓存键、不产生新实例 ⇒ 不引起重组/闪烁（0.11.14 的教训：新实例 = 封面一直在刷新）。
     */
    private static void repairLanding(Object owner, long sid, String why) {
        redeliverOnly(owner);
        int n;
        synchronized (LAND_LOCK) {
            n = ++landRepairs;
        }
        LAND_LOST.incrementAndGet();
        LAND_REPAIRED.incrementAndGet();
        PluginLog.i(TAG, "封面落地补投：netease-" + sid + " 第 " + n + " 次（" + why
                + "；同一个 owner 实例，不重新解码）");
    }

    /** 只重发图流（宿主同款两步：按尺寸缩放缓存 + 投进播放条图片流）。 */
    private static void redeliverOnly(Object owner) {
        Object cache = callStatic0(cls("com.xuncorp.voxzen.image.ScaledImageBitmapCacheKt"),
                "getSharedScaledImageBitmapCache");
        if (cache != null) {
            callArgs(cache, "onSourceChanged", owner);
        }
        Object monitor = staticObj(cls("com.xuncorp.voxzen.service.PlaybackMonitor"), "INSTANCE");
        callStaticArgs(cls("com.xuncorp.voxzen.service.PlaybackMonitorKt"), "updateImageBitmap", monitor, owner);
    }

    /**
     * 读宿主「当前图源」（{@code ScaledImageBitmapCache.currentSource}；宿主自己每次换图都写它）。
     *
     * <p>先走宿主自带的公共访问器 {@code access$getCurrentSource$p}（真机可达，见 {@code harness\probe}
     * 的类清单 dump），读不到再退到字段 {@code currentSource}；两条都不通 = {@code blind:…}。</p>
     *
     * @return {@code same} / {@code lost:null} / {@code lost:other} / {@code blind:<原因>}
     */
    private static String currentSourceOf(Object owner) {
        Object cache = callStatic0(cls("com.xuncorp.voxzen.image.ScaledImageBitmapCacheKt"),
                "getSharedScaledImageBitmapCache");
        Class<?> sc = cls("com.xuncorp.voxzen.image.ScaledImageBitmapCache");
        if (cache == null || sc == null) {
            return "blind:宿主图源缓存不可达";
        }
        Object cur = null;
        boolean read = false;
        Method m = staticMethod(sc, "access$getCurrentSource$p", 1);
        if (m != null) {
            try {
                m.setAccessible(true);
                cur = m.invoke(null, cache);
                read = true;
            } catch (Throwable ignored) {
                // 退到字段反射
            }
        }
        if (!read) {
            try {
                Field f = sc.getDeclaredField("currentSource");
                f.setAccessible(true);
                cur = f.get(cache);
                read = true;
            } catch (Throwable t) {
                return "blind:" + brief(t);
            }
        }
        if (cur == null) {
            return "lost:null";
        }
        return cur == owner ? "same" : "lost:other";
    }

    /** 按「名字 + 形参个数」找一个静态方法（读宿主当前图源用）。 */
    private static Method staticMethod(Class<?> c, String name, int argc) {
        for (Class<?> x = c; x != null; x = x.getSuperclass()) {
            for (Method m : x.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == argc
                        && Modifier.isStatic(m.getModifiers())) {
                    return m;
                }
            }
        }
        return null;
    }

    /**
     * 兜底轮询（1.5 s）的观测：<b>宿主封面档位补键</b> —— 0.11.18 起<b>只补缓存键、不投递</b>。
     *
     * <p>{@code com.xuncorp.voxzen.image.CoverSizePolicy.INSTANCE.getCurrentTier()} 是宿主决定
     * 「读多大内嵌图 / 用哪个缓存键」的尺寸档位（缓存键形如
     * {@code {path}?v=1&t=…&s=…&r=…&w=<档位>&h=<档位>}），而本插件历史上只铺过 96/192 两档
     * （{@code CoverPrimer.SIZES}）⇒ 播放页那种更大的面会键不命中。读到档位（或读不到时的兜底档）
     * 就为<b>当前曲</b>补一档键：零音频、只多一张图，<b>不碰图流、不产生任何一次「进场」</b>。</p>
     *
     * <p><b>0.11.18 起只在「这一首的音乐已经进场」之后才补键</b>（{@link #writeTierKey}）——
     * 键若先于音乐存在，用户点下一首时播放页大图会立刻换图，那就是「封面先进场」（用户规格禁止）。
     * 0.11.17 让 {@code svc.RowCover} 给队列前瞻那 4 首也补键（键先于用户点开播放页存在），
     * 因同一理由<b>已撤回</b>：点开播放页时（起播早已过去）键已在，竞态自然不存在。</p>
     *
     * <p><b>0.11.16 在这里做的两件事已删</b>：①「新订阅者补发」读
     * {@code SharedFlow.subscriptionCount}，而宿主是 GraalVM AOT 镜像、没用过的成员反射不到
     * （0.11.17 插桩判死，契约 §11 坑 23 ⇒ 该分支在真机上从未执行）；②「恢复播放后重投」与用户规格
     * 「封面只能进场一次」直接冲突。</p>
     */
    public static void onPoll() {
        try {
            playingNow();                // 0.11.49：每拍刷新权威当前曲（onMusicStarted / onStreamServe 只读它）
            landCheck();                 // 0.11.21：先复核上一首投出去的封面还在不在（自动接续那一轮会补投）
            int tier = hostTier();
            if (tier > 0 && tier != lastTier) {
                int prevTier = lastTier;
                lastTier = tier;
                PluginLog.i(TAG, "宿主封面档位：" + (prevTier < 0 ? "首次读到 " : prevTier + " → ") + tier
                        + "（当前曲 " + (currentSongId <= 0L ? "未知" : "netease-" + currentSongId) + "）");
            }
            long sid = currentSongId;
            // 只给「音乐已经进场」的那一首补键（起播那一拍自己也会写一次，见 run() 成功路径）。
            if (sid > 0L && (sid == musicDeliveredId || sid == lastOk)) {
                writeTierKey(sid, tier);
            }
        } catch (Throwable ignored) {
            // 观测失败不影响任何用户可见路径
        }
    }

    /**
     * 给某一首补「宿主当前尺寸档位」的封面缓存键（零音频、只写磁盘缓存键，不碰图流）。
     *
     * <p>播放页大图走的是 Coil + 缓存键那条路（{@code {path}?v=1&t=…&s=…&r=…&w=<档位>&h=<档位>}），
     * 而插件历史上只铺过 96/192 两档 ⇒ 必须补这一档。0.11.18 起它<b>只在起播那一拍写</b>：
     * 键若先于音乐存在，用户点下一首时播放页大图会立刻换图 = 「封面先进场」。</p>
     *
     * @param tier 已读到的宿主档位；{@code <= 0}（没读到）时在本方法里现读一次，仍读不到才用兜底档
     */
    private static void writeTierKey(long sid, int tier) {
        if (sid <= 0L) {
            return;
        }
        int t = tier > 0 ? tier : hostTier();
        int[] want = t > 0 ? new int[]{t} : FALLBACK_TIERS;
        String tag = t > 0 ? (sid + "|t" + t) : (sid + "|fallback");
        if (!TIER_KEYED.add(tag)) {
            return;                     // 这一首这一档已经补过（每次调用只多一张图）
        }
        HostBridgeWorker.get().submit(() -> {
            int ok = 0;
            for (int sz : want) {
                ok += CoverDelivery.deliverExtra(sid, sz);
            }
            if (ok > 0) {
                TIER_KEYS.incrementAndGet();
                PluginLog.i(TAG, "封面档位键：netease-" + sid + " 补 " + ok + " 档（"
                        + (t > 0 ? "宿主档位 " + t : "档位未知，兜底 "
                        + java.util.Arrays.toString(FALLBACK_TIERS)) + "；零音频）");
            }
        });
    }

    /** 宿主当前封面尺寸档位（{@code CoverSizePolicy}；字段名各版本不同，{@code currentTier} / {@code coverTier} 都试）。 */
    private static int hostTier() {
        Class<?> pol = cls("com.xuncorp.voxzen.image.CoverSizePolicy");
        Object inst = pol == null ? null : staticObj(pol, "INSTANCE");
        if (inst == null) {
            return -1;
        }
        Object tier = callArgs(inst, "getCurrentTier");
        if (tier == null) {
            tier = callArgs(inst, "getCoverTier");
        }
        if (tier == null) {
            return -1;
        }
        Object v = callArgs(tier, "getValue");       // StateFlow<Integer> / MutableState
        return asInt(v == null ? tier : v);
    }

    /** 宿主当前封面尺寸档位（读不到时回退最近一次读到的值；仅供诊断/状态页）。 */
    public static int currentHostTier() {
        int t = hostTier();
        if (t <= 0) {
            t = lastTier;
        }
        return t;
    }

    /** 把一个反射读到的值当整数看（{@code Number} / 数字串都认；其余 {@code -1}）。 */
    private static int asInt(Object o) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o instanceof CharSequence) {
            try {
                return Integer.parseInt(o.toString().trim());
            } catch (Throwable ignored) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * 预热（只取图入缓存，<b>不投递</b>）：给「下一首 / 上一首」把成品图字节先摆好。
     *
     * <p>走<b>普通</b>队列（把快车道让给真正的投递）；同一首选过一次不再重排；已经投过的不必预热。</p>
     */
    public static void prefetch(long songId) {
        if (songId <= 0L || songId == lastOk || songId == pending.get()) {
            return;
        }
        if (PREWARM_SEEN.size() > 512) {
            PREWARM_SEEN.clear();     // 诊断面：不为了它做 LRU，超量就重来
        }
        if (!PREWARM_SEEN.add(songId)) {
            return;
        }
        HostBridgeWorker.get().submit(() -> prewarmOne(songId));
    }

    /** 一枚判重闸：同一首「已成功过」或「正在投」都不再排队。 */
    private static boolean claim(long songId) {
        if (songId <= 0L) {
            return false;
        }
        if (songId == lastOk || songId == pending.get()) {
            return false;
        }
        pending.set(songId);
        return true;
    }

    /**
     * 宿主播放器「当前曲」的权威值（worker 上现读并刷新缓存；其它线程只读缓存 —— 宿主回调线程 /
     * 流服务线程绝不允许在这里阻塞）。
     *
     * @return 裸 id &gt; 0 = 宿主当前播放的就是这一首；0 = 读不到（不得据此判「没有在播」）
     */
    private static long playingNow() {
        try {
            if (HostBridgeWorker.get().isOnWorkerThread()) {
                long p = VoxzenBridge.playingTrackId();
                playingIdCache = p;
                playingIdAt = System.currentTimeMillis();
                return p;
            }
        } catch (Throwable ignored) {
            // 退到缓存
        }
        return playingIdCache;
    }

    /**
     * 权威字段是否只是「还没跟上换曲」（0.11.49c 真机实证，2026-10-04 11:58:18 自动接续）：
     * 宿主已开流新曲、队列也已换格，但播放器「当前媒资项」仍返回<b>上一首</b>（已进过场的旧曲）。
     * 判据 = 刚报过 {@code Ended}（≤{@link #TRANSITION_MS}）+ 权威值是已投过的旧曲 + 目标另有其曲。
     * 只有这个窗口容忍权威滞后；窗口外一律以权威为准（不让「邻曲保温带偏」回潮）。
     */
    private static boolean authLagsBehind(long target) {
        long now = System.currentTimeMillis();
        if (endedAt <= 0L || now - endedAt > TRANSITION_MS || target <= 0L || target == lastOk) {
            return false;
        }
        long playing = playingNow();
        if (playing <= 0L || playing == target) {
            return false;
        }
        return playing == lastOk || playing == musicDeliveredId;
    }

    /** 起播拍目标裁决：权威优先；只有判到「权威滞后」（见 {@link #authLagsBehind}）才改用刚认到的曲 / 供流凭证。 */
    private static long lagTolerantTarget(long sid, long auth) {
        if (!authLagsBehind(sid)) {
            return auth;
        }
        long cand = sid;
        if (cand <= 0L || cand == auth || cand == lastOk || cand == musicDeliveredId) {
            long served = servedAt > 0L && System.currentTimeMillis() - servedAt <= TRANSITION_MS ? servedId : 0L;
            if (served > 0L && served != auth && served != lastOk && served != musicDeliveredId) {
                cand = served;
            }
        }
        if (cand > 0L && cand != auth && cand != lastOk && cand != musicDeliveredId) {
            trace("权威滞后（" + auth + " 已是旧曲）：起播拍改用 " + cand);
            return cand;
        }
        return auth;
    }

    /**
     * 投递取消 + 纠正（0.11.49，修「播放条与列表封面不一致」）。
     *
     * <p>inject 之前对账失败（{@code songId} 已不是宿主当前曲）⇒ 一次都不投；如果真正的当前曲
     * 还没进过场，替它排一次投递。封面仍然<b>一首只进场一次</b>，只是进场的对象永远等于宿主
     * 播放器的当前媒资项。</p>
     */
    private static void cancelFor(long playing, long songId, String why) {
        if (musicDeliveredId == songId) {
            musicDeliveredId = -1L;              // 这一首还没真正进场：撤回认领，等它自己的起播拍
        }
        currentSongId = playing;                 // 后续触发一律对准真正的当前曲
        trace("投递取消：" + songId + " 已不是当前曲（现在 " + playing + "）");
        PluginLog.i(TAG, "播放条封面跳过投递：netease-" + songId + " ≠ 宿主当前曲 netease-" + playing
                + "（" + why + "；不产生错图，改投当前曲）");
        if (playing != lastOk && playing != pending.get()) {
            push(playing, "纠正：当前播放曲已切换");
        }
    }

    private static void run(long songId, String why, long requestAt, boolean force) {
        try {
            // 0.11.49：inject 之前用宿主播放器对一次账（唯一权威判据）。对不上 = 这一首已经不是当前曲
            // （邻曲保温把 currentSongId 带偏 / 用户已换曲）⇒ 一次都不投，并把封面改投真正的当前曲。
            long playing = playingNow();
            if (playing > 0L && playing != songId && !authLagsBehind(songId)) {
                cancelFor(playing, songId, why);
                return;
            }
            if (playing == songId && currentSongId != playing) {
                currentSongId = playing;     // 权威读与认到的曲一致：把目标对齐
            }
            if (songId == lastOk && !force) {
                return;                     // 同一首已经投过（force 只给「起播校准」）
            }
            String album = refKeyOf(songId);
            if (album == null || album.isBlank()) {
                fail(why, "封面引用未知（封面索引与宿主库都没有这一行）");
                return;
            }
            long t0 = System.currentTimeMillis();
            byte[] bytes = cached(album);
            boolean hit = bytes != null && bytes.length > 0;
            if (hit) {
                HITS.incrementAndGet();
            } else {
                MISSES.incrementAndGet();
            }
            if (!hit) {
                bytes = CoverStore.fetchImage(album);    // 离线优先；缺图才经唯一入口补一张（只下图片）
                if (bytes != null && bytes.length > 0) {
                    put(album, bytes);
                }
            }
            if (bytes == null || bytes.length == 0) {
                fail(why, "该专辑没有可用封面图：" + CoverStore.display(album));
                return;
            }
            String bad = inject(bytes, SIDE, songId);
            if (bad != null) {
                fail(why, bad);
                return;
            }
            lastOk = songId;
            armLanding(songId, lastOwnerInjected);   // 0.11.21：挂落地复核（自动接续那一轮可能被宿主空值顶掉）
            long total = OK.incrementAndGet();
            long now = System.currentTimeMillis();
            deliveredWhy = why == null ? "" : why;
            trace("投递落地（" + deliveredWhy + "，图像 " + bytes.length + " B）");
            PluginLog.i(TAG, "播放条封面：netease-" + songId + " → " + bytes.length + " B"
                    + "（" + why + "；路线 ② 直投 PlaybackMonitor.imageBitmapFlow；"
                    + (hit ? "缓存命中" : "缓存补入") + "，投递 " + (now - t0) + " ms"
                    + "，距触发 " + Math.max(0L, now - requestAt) + " ms"
                    + (musicAt > 0L ? "，距起播 " + Math.max(0L, now - musicAt) + " ms" : "")
                    + "）");
            if (total % 32L == 0L) {
                PluginLog.i(TAG, stats());     // 每 32 首落一行计数行（缓存命中率可机器判读）
            }
            // 0.11.18：封面的一切都发生在「音乐起播」这一拍 —— 投递成功后立刻补该档缓存键，
            // 让播放页大图（Coil + 档位键那条路）也在音乐进场之后才有图可读，而不是抢先换图。
            writeTierKey(songId, lastTier);
            // 0.11.18：投递这一拍之外**什么都不做** —— 一次进场就是一次（用户规格）。
            // 宿主自己那次失败刷新发生在我们投递之前（换曲那一刻早于起播），所以不需要补发去覆盖它。
        } catch (Throwable t) {
            fail(why, "投递异常：" + brief(t));
        } finally {
            if (pending.get() == songId) {
                pending.set(-1L);
            }
        }
    }

    /** 预热一支：解析专辑 → 取成品图字节 → 入缓存（不投递、不记成功日志）。 */
    private static void prewarmOne(long songId) {
        try {
            if (songId == lastOk) {
                return;
            }
            String album = refKeyOf(songId);
            if (album == null || album.isBlank()) {
                return;
            }
            if (cached(album) != null) {
                return;
            }
            byte[] bytes = CoverStore.fetchImage(album);
            if (bytes == null || bytes.length == 0) {
                return;
            }
            put(album, bytes);
            long n = PREWARMED.incrementAndGet();
            if (n <= 8L || n % 16L == 0L) {
                PluginLog.i(TAG, "播放条封面预热：netease-" + songId + " → 缓存 " + bytes.length + " B"
                        + "（累计 " + n + " 首）");       // INFO：预热是「零延迟」的因，必须是可判读的机器证据
            }
        } catch (Throwable ignored) {
            // 预热失败不影响任何用户可见路径
        }
    }

    /**
     * 曲目 id → 封面引用键（{@code 专辑 + U+0001 + 歌手}；0.11.49 起带歌手，同名专辑不再互相串图）。
     *
     * <p>先查同步时登记的小表；查不到（宿主列表里其它曲目）直接读宿主库那一行。</p>
     */
    private static String refKeyOf(long songId) {
        String key = CoverStore.refKeyOf(songId);
        if (key == null || key.isBlank()) {
            key = NativeLibrary.refKeyOfTrack(songId);       // 兜底：直接读宿主库那一行
        }
        return key;
    }

    // ------------------------------------------------------------------ 成品图字节缓存

    private static byte[] cached(String album) {
        synchronized (CACHE) {
            return CACHE.get(album);
        }
    }

    private static void put(String album, byte[] bytes) {
        if (album == null || bytes == null || bytes.length == 0) {
            return;
        }
        synchronized (CACHE) {
            byte[] old = CACHE.put(album, bytes);
            if (old != null) {
                cacheBytes -= old.length;
            }
            cacheBytes += bytes.length;
            Iterator<Map.Entry<String, byte[]>> it = CACHE.entrySet().iterator();
            while ((CACHE.size() > CACHE_MAX_ITEMS || cacheBytes > CACHE_MAX_BYTES) && it.hasNext()) {
                Map.Entry<String, byte[]> e = it.next();
                cacheBytes -= e.getValue().length;
                it.remove();
            }
            if (cacheBytes < 0L) {
                cacheBytes = 0L;
            }
        }
    }

    /** 缓存快照（诊断用）：[张数, 字节数]。 */
    public static long[] cacheStats() {
        synchronized (CACHE) {
            return new long[]{CACHE.size(), cacheBytes};
        }
    }

    /** 清空缓存（配置页「清空封面缓存」/ 停用插件时调；幂等）。 */
    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
            cacheBytes = 0L;
        }
        PREWARM_SEEN.clear();
        flushTrace("清理缓存");      // 停用前把黑匣子里最后一批时序落盘，不留半截时间线
    }

    /** 失败记账 + 限流日志（同一个原因只落一行）。 */
    private static void fail(String why, String reason) {
        FAILS.incrementAndGet();
        try {
            String key = reason.length() > 80 ? reason.substring(0, 80) : reason;
            if (LOGGED_FAIL.size() < 64 && LOGGED_FAIL.add(key)) {
                trace("投递失败（" + why + "）：" + key);
                PluginLog.w(TAG, "播放条封面未投递（" + why + "）：" + reason);
            }
        } catch (Throwable ignored) {
            // 记日志失败不得影响宿主播放流程
        }
    }

    /**
     * 五步反射链：成品图字节 → 宿主播放条图流。
     *
     * @param bytes  成品图字节（JPEG/PNG 原图，宿主自己按档位缩放）
     * @param side   投递边长（{@link #SIDE}）
     * @param songId 这一张属于哪一首（补发时要比对，换曲即失效）
     * @return {@code null} = 已投递；否则是一行「哪一环不可达」的原因
     */
    private static String inject(byte[] bytes, int side, long songId) {
        Class<?> ibmUtil = cls("com.xuncorp.voxzen.util.ImageBitmapUtil");
        Class<?> skiaKt = cls("androidx.compose.ui.graphics.SkiaImageAsset_skikoKt");
        Class<?> rcibCls = cls("com.xuncorp.voxzen.image.RefCountedImageBitmap");
        Class<?> skiaBmpCls = cls("org.jetbrains.skia.Bitmap");
        Class<?> pmCls = cls("com.xuncorp.voxzen.service.PlaybackMonitor");
        Class<?> pmKt = cls("com.xuncorp.voxzen.service.PlaybackMonitorKt");
        Class<?> scKt = cls("com.xuncorp.voxzen.image.ScaledImageBitmapCacheKt");
        if (ibmUtil == null || skiaKt == null || rcibCls == null || skiaBmpCls == null
                || pmCls == null || pmKt == null) {
            return "宿主类不可达（宿主升级换了类名？）";
        }
        Object util = staticObj(ibmUtil, "INSTANCE");
        Object ibm = callArgs(util, "decodeToExactSize", bytes, side, side, false);
        if (ibm == null) {
            return "ImageBitmapUtil.decodeToExactSize 返回 null";
        }
        Object skiaBmp = callStaticArgs(skiaKt, "asSkiaBitmap", ibm);
        if (skiaBmp == null) {
            return "SkiaImageAsset_skikoKt.asSkiaBitmap 返回 null";
        }
        Object owner;
        try {
            Constructor<?> ctor = rcibCls.getDeclaredConstructor(skiaBmpCls);
            ctor.setAccessible(true);
            owner = ctor.newInstance(skiaBmp);
        } catch (Throwable t) {
            return "new RefCountedImageBitmap(bitmap) 失败：" + brief(t);
        }
        if (owner == null) {
            return "RefCountedImageBitmap 为空";
        }
        Object monitor = staticObj(pmCls, "INSTANCE");
        if (monitor == null) {
            return "PlaybackMonitor.INSTANCE 取不到";
        }
        Object cache = callStatic0(scKt, "getSharedScaledImageBitmapCache");
        if (cache != null) {
            callArgs(cache, "onSourceChanged", owner);          // 宿主同款：按尺寸缩放缓存
        }
        callStaticArgs(pmKt, "updateImageBitmap", monitor, owner);
        lastOwnerInjected = owner;                             // 判「这一曲到底投成没投成」（诊断用）
        return null;
    }

    /** 供「状态」页 / 自检读的计数行。 */
    public static String stats() {
        long[] c = cacheStats();
        return "播放条封面：已投递 " + OK.get() + " 首 / 失败 " + FAILS.get()
                + " 次，当前曲 " + (lastOk <= 0L ? "（尚未投递）" : "netease-" + lastOk)
                + "；成品图缓存 " + c[0] + " 张 / " + (c[1] / (1024L * 1024L)) + " MB"
                + "，命中 " + HITS.get() + " / 补入 " + MISSES.get()
                + "，预热 " + PREWARMED.get() + " 首"
                + "，起播一次进场 " + ALIGNED.get() + " 首（0.11.18：无补发、无二次进场）"
                + "，档位键补写 " + TIER_KEYS.get()
                + " 首 / 档位 " + (lastTier < 0 ? "未知" : String.valueOf(lastTier))
                + "，落地复核 确认 " + LAND_OK.get() + " / 被换 " + LAND_LOST.get()
                + " / 补投 " + LAND_REPAIRED.get() + " 次 / 读不到 " + LAND_BLIND.get();
    }

    // ------------------------------------------------------------------ 反射工具

    private static List<ClassLoader> loaders() {
        LinkedHashSet<ClassLoader> set = new LinkedHashSet<>();
        set.add(PlaybarCover.class.getClassLoader());
        try {
            set.add(Thread.currentThread().getContextClassLoader());
        } catch (Throwable ignored) {
            // 忽略
        }
        set.add(ClassLoader.getSystemClassLoader());
        List<ClassLoader> out = new ArrayList<>();
        for (ClassLoader l : set) {
            if (l != null) {
                out.add(l);
            }
        }
        return out;
    }

    /** 按 docs/01:235 的顺序加载宿主类：插件加载器（含 parent 链）→ TCCL → 系统 → bootstrap。 */
    private static Class<?> cls(String name) {
        try {
            for (ClassLoader l : loaders()) {
                try {
                    return Class.forName(name, false, l);
                } catch (Throwable ignored) {
                    // 换下一个
                }
            }
            return Class.forName(name, false, null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object staticObj(Class<?> c, String field) {
        try {
            Field f = c.getDeclaredField(field);
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object callArgs(Object o, String name, Object... args) {
        if (o == null) {
            return null;
        }
        try {
            Method m = findMethod(o.getClass(), name, args.length);
            if (m == null) {
                return null;
            }
            m.setAccessible(true);
            return m.invoke(o, args);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object callStatic0(Class<?> c, String name) {
        if (c == null) {
            return null;
        }
        try {
            Method m = findMethod(c, name, 0);
            if (m == null || !Modifier.isStatic(m.getModifiers())) {
                return null;
            }
            m.setAccessible(true);
            return m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object callStaticArgs(Class<?> c, String name, Object... args) {
        if (c == null) {
            return null;
        }
        try {
            for (Class<?> x = c; x != null; x = x.getSuperclass()) {
                for (Method m : x.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == args.length
                            && Modifier.isStatic(m.getModifiers())) {
                        m.setAccessible(true);
                        return m.invoke(null, args);
                    }
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    private static Method findMethod(Class<?> c, String name, int argc) {
        for (Class<?> x = c; x != null; x = x.getSuperclass()) {
            for (Method m : x.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == argc) {
                    return m;
                }
            }
            for (Class<?> i : x.getInterfaces()) {
                for (Method m : i.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == argc) {
                        return m;
                    }
                }
            }
        }
        try {
            for (Method m : c.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == argc) {
                    return m;
                }
            }
        } catch (Throwable ignored) {
            // 无
        }
        return null;
    }

    private static String brief(Throwable t) {
        if (t == null) {
            return "null";
        }
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
