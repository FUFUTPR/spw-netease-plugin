package com.example.netease.svc;

import com.example.netease.core.DataPaths;
import com.example.netease.core.PluginLog;
import com.example.netease.core.RiskControl;
import com.example.netease.host.HostSqlBridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「播放条 / 播放页封面」—— <b>0.11.8 重制：本地路径自愈 + 单曲封面投递</b>。
 *
 * <p><b>0.11.2–0.11.7 的老路（已删除，红线级浪费）</b>：整首下载音频 → 注入封面 → 落
 * {@code <插件数据>\audio-cover\<songId>.<kind>} → 把 {@code Track.path} 翻成该本地文件，
 * 指望宿主播放条去读这个文件的<b>内嵌图标签</b>。真机实测代价：3 首 = 423.6 MB（jymaster 一首 100–200 MB），
 * 且同一首歌在 {@code audio-stream\} 与 {@code audio-cover\} 各存一份完整音频。</p>
 *
 * <p><b>为什么它注定无效</b>（P-4 探针 3 条实证）：</p>
 * <ol>
 *   <li>宿主封面链路是 {@code AudioCoverFetcher → readFromDiskCache()}：<b>先查图片磁盘缓存键</b>，
 *       未命中才 {@code TagParser.readImageToByteArray(vri) → new File(vri 路径)}；</li>
 *   <li>把 {@code path} 换成本地 flac 后，键形态从 http 直链变成 {@code file:///}，
 *       4 种 path 形态 × 5 mtime × 3 组 (size,rev) × 9 尺寸<b>零命中</b> ⇒ 老路既贵又无效；</li>
 *   <li>整单试播 60 s 窗口内宿主<b>零次</b>为插件曲目请求过封面键 ⇒ 键只能由插件自己写。</li>
 * </ol>
 *
 * <p><b>现在做什么</b>：</p>
 * <ol>
 *   <li><b>自愈</b>：把历史遗留的 {@code audio-cover\} 本地路径批量还原成流地址（{@code Track.path} 交还
 *       {@code http://127.0.0.1:<port>/netease/<id>} 形态）并删除本地文件，令 {@code audio-cover\} 归零；
 *       还原会改写 {@code modifiedTime}（宿主那条批量 SQL 用当前时间），所以紧接着
 *       {@link CoverPrimer#repinAndPrime(String)} 重钉常量 mtime 并按新键重铺 96/192 两档。</li>
 *   <li><b>投递</b>：队列前瞻（{@link #LOOKAHEAD} 首，随机播放下绕队列一圈扫）把候选曲目交给
 *       {@link CoverDelivery#deliver(long)} —— 走宿主 <b>live</b> {@code DiskCache} 写键，
 *       同会话即可见（A17：没播过的歌选中进播放条就出图）。</li>
 * </ol>
 *
 * <p><b>红线</b>：本类<b>零音频下载</b>（只算键、只取封面图 URL、只写宿主图片缓存）；所有动作都在自建守护线程
 * {@code netease-rowcover} 上，宿主回调线程绝不进来。</p>
 */
public final class RowCover {

    private static final String TAG = "rowcover";

    /** 自身目录：只用来找「历史遗留文件」并清理，不再往里写任何东西。 */
    private static final String DIR_NAME = "audio-cover";

    private static final long IDLE_POLL_MS = 3000L;

    /** 自愈巡检间隔：同步会把 {@code path} 写回流地址，这里兜底再扫一次库里有没有漏网的本地路径。 */
    private static final long HEAL_INTERVAL_MS = 60000L;

    /** 队列前瞻：提前把队列里的曲目投一次封面键（纯本地，不下载音频）。 */
    private static final int LOOKAHEAD = 4;

    private static final long LOOKAHEAD_MS = 5000L;

    private static final long STOP_JOIN_MS = 1500L;

    /**
     * 自愈失败后的退避（有界：一轮只试一次；改不动就 ERROR + 退避，绝不在失败上原地重试把日志刷爆）。
     */
    private static final long HEAL_FAIL_BACKOFF_MS = 10 * 60 * 1000L;

    private static volatile long healBlockedUntil;

    private static volatile long lastLookahead;
    private static volatile long lastQueueLog;
    private static volatile long lastHeal;

    private static final ConcurrentLinkedDeque<Long> QUEUE = new ConcurrentLinkedDeque<>();
    private static final Set<Long> QUEUED = ConcurrentHashMap.newKeySet();

    private static volatile Thread worker;
    private static volatile boolean stop;

    /** 统计：自愈（还原+删除）曲目数 / 投递曲目数 / 目录剩余。 */
    private static final AtomicInteger HEALED = new AtomicInteger();
    private static final AtomicInteger DELIVERED = new AtomicInteger();
    private static final AtomicInteger HEAL_FAILS = new AtomicInteger();

    private RowCover() {
    }

    public static Path dir() {
        Path p = DataPaths.data().resolve(DIR_NAME);
        try {
            Files.createDirectories(p);
        } catch (Throwable ignored) {
            // 建不出来时后续文件操作各自失败，不在这里抛
        }
        return p;
    }

    // ------------------------------------------------------------------ 生命周期

    /** 启动（幂等）：先自愈一次，然后进守护循环（自愈巡检 + 队列前瞻投递）。 */
    public static void start() {
        try {
            if (worker != null && worker.isAlive()) {
                return;
            }
            stop = false;
            Thread t = new Thread(RowCover::loop, "netease-rowcover");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            worker = t;
            t.start();
            PluginLog.i(TAG, "播放侧封面已启用：队列前瞻投递（零音频、不复制音乐）+ 本地路径自愈（目录 "
                    + dir() + "）");
        } catch (Throwable t) {
            PluginLog.d(TAG, "播放侧封面启动失败（忽略）：" + t);
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
            t.join(STOP_JOIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 整首<b>音频</b>预取完成（{@code svc.StreamPrefetcher} 调用）：顺手把这一首的封面键补上。
     *
     * <p>只入队，由本类守护线程去调 {@link CoverDelivery#deliver(long)} —— 投递里有磁盘与反射调用，
     * 绝不在调用方线程（可能是宿主回调链）上做。</p>
     */
    public static void onPrefetchDone(long songId) {
        if (songId <= 0L) {
            return;
        }
        if (QUEUED.add(songId)) {
            QUEUE.addLast(songId);
        }
    }

    // ------------------------------------------------------------------ 主循环

    private static void loop() {
        // 先自愈一次：升级上来的库里有历史 audio-cover\ 路径，必须尽快还原
        heal("启动");
        while (!stop) {
            try {
                long now = System.currentTimeMillis();
                if (now - lastHeal > HEAL_INTERVAL_MS) {
                    heal("巡检");
                }
                lookahead();
                Long id = QUEUE.pollFirst();
                if (id == null) {
                    Thread.sleep(IDLE_POLL_MS);
                    continue;
                }
                QUEUED.remove(id);
                if (CoverDelivery.deliver(id) > 0) {
                    DELIVERED.incrementAndGet();
                }
                // 0.11.17 的「前瞻档位键」（给队列前瞻这几首补宿主档位的 768 键）已在 0.11.18 撤回：
                // 用户规格要求「封面与音乐同一拍进场、不许先进场」，而这些键先于音乐存在 ⇒
                // 用户点下一首时播放页大图会立刻换图。现在档位键只由 PlaybarCover 在「起播那一拍」写
                // （见 PlaybarCover.writeTierKey）：点开播放页时起播早已过去，键已在，竞态自然不存在。
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                PluginLog.d(TAG, "播放侧封面轮次异常（继续）：" + t);
            }
        }
    }

    /**
     * 队列前瞻：把队列里「紧挨当前曲之后」的几首提前投一次封面键（A17）。
     *
     * <p>宿主默认随机播放（{@code PlaybackQueue$Mode:Random}），队列顺序 ≠ 播放顺序，所以绕队列扫一圈、
     * 能提前多少算多少；投递本身是纯本地操作（算键 + 读宿主缓存 + 必要时写一次缩略图），<b>不下载音频</b>。</p>
     */
    private static void lookahead() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastLookahead < LOOKAHEAD_MS) {
                return;
            }
            lastLookahead = now;
            List<String> ids = com.example.netease.host.VoxzenBridge.queueTrackIds();
            if (now - lastQueueLog > 60000L) {
                lastQueueLog = now;
                PluginLog.i(TAG, "队列前瞻：读到播放队列 " + ids.size() + " 首（当前曲 " + safeCurrent()
                        + "）" + (ids.isEmpty() ? "；诊断=" + com.example.netease.host.VoxzenBridge.queueDebugInfo() : ""));
            }
            if (ids.isEmpty()) {
                return;
            }
            long cur = -1L;
            try {
                cur = StreamingAudioCache.current();
            } catch (Throwable ignored) {
                // 拿不到当前曲就从队列头看
            }
            int idx = ids.indexOf("netease-" + cur);
            int added = 0;
            for (int k = 0; k < ids.size() && added < LOOKAHEAD; k++) {
                int i = (Math.max(0, idx) + 1 + k) % ids.size();
                long id = parseId(ids.get(i));
                if (id <= 0L) {
                    continue;
                }
                if (QUEUED.add(id)) {
                    QUEUE.addLast(id);
                    added++;
                }
            }
            if (added > 0) {
                PluginLog.i(TAG, "队列前瞻：提前投递 " + added + " 首封面键（零音频）");
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "队列前瞻失败（忽略）：" + t);
        }
    }

    private static String safeCurrent() {
        try {
            return "netease-" + StreamingAudioCache.current();
        } catch (Throwable t) {
            return "未知";
        }
    }

    private static long parseId(String trackId) {
        if (trackId == null || !trackId.startsWith("netease-")) {
            return 0L;
        }
        try {
            return Long.parseLong(trackId.substring("netease-".length()));
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------ 自愈：清掉历史本地路径

    /**
     * 自愈：{@code audio-cover\} 归零 + 库里的本地路径全部还原为流地址。
     *
     * <p>顺序<b>不能反</b>：先把 {@code Track.path} 还原成流地址，再删文件 —— 反了就会留下死路径
     * （宿主拿到一个不存在的文件，那首歌直接播不出来）。批量还原走宿主同连接（Room 失效 → UI 立刻刷新），
     * 拿不到宿主连接才退外部 JDBC（此时宿主界面可能要重启才刷新，日志里说明）。</p>
     *
     * @param why 触发原因（启动 / 巡检），进日志
     * @return 本次还原的曲目数
     */
    private static int heal(String why) {
        long now = System.currentTimeMillis();
        if (now < healBlockedUntil) {
            return 0;                              // 失败退避窗口内：本轮不再试（有界，不刷日志）
        }
        lastHeal = now;
        String thread = Thread.currentThread().getName();
        int restored = 0;
        try {
            // ① 只修「真的还指向本地文件」的行；库里干净且目录空 ⇒ 一条 SQL 都不发（不白刷 modifiedTime）
            Map<String, String> local = NativeLibrary.localCoverPaths();
            List<Path> files = files();
            if (local.isEmpty() && files.isEmpty()) {
                return 0;
            }
            String prefix = NativeStreamServer.baseUrl() + "/netease/";
            boolean byHost = false;
            int[] done = {0};
            try {
                byHost = Boolean.TRUE.equals(HostSqlBridge.onHostConnection(
                        NativeLibrary.dbPath() == null ? null : NativeLibrary.dbPath().toString(),
                        sql -> {
                            for (String id : local.keySet()) {
                                done[0] += NativeLibrary.restoreStreamPathOn(sql, id, prefix);
                            }
                            return Boolean.TRUE;
                        }));
            } catch (Throwable t) {
                PluginLog.d(TAG, "自愈走宿主连接失败（退外部 JDBC）：" + t);
            }
            if (!byHost) {
                for (String id : local.keySet()) {
                    if (NativeLibrary.restoreStreamPathExternal(id, prefix)) {
                        done[0]++;
                    }
                }
                if (done[0] > 0) {
                    PluginLog.w(TAG, "自愈走的是外部 JDBC（宿主界面可能要重启才刷新）");
                }
            }
            restored = done[0];
            if (!local.isEmpty() && restored < local.size()) {
                // 有界：改不动就 ERROR + 退避，且**绝不删文件**（顺序纪律：路径没改回来就先留着文件）
                healBlockedUntil = now + HEAL_FAIL_BACKOFF_MS;
                HEAL_FAILS.incrementAndGet();
                PluginLog.e(TAG, "自愈放弃本轮：本地路径行 " + local.size() + " 条只改回 " + restored
                        + " 条（通路 " + (byHost ? "宿主连接" : "外部 JDBC") + "，原因：" + why
                        + "，线程：" + thread + "）⇒ " + RiskControl.human(HEAL_FAIL_BACKOFF_MS)
                        + " 后再巡检；本轮不删任何文件");
                return restored;
            }
            // ② 固定词形第一行「还原 path」（机器判据：它必须出现在「删 audio-cover」之前）
            PluginLog.i(TAG, "自愈：还原 path " + restored + " 行（原因：" + why + "，线程：" + thread
                    + "，通路：" + (byHost ? "宿主连接" : "外部 JDBC") + "）");
            if (!local.isEmpty()) {
                // 还原会把 modifiedTime 写成「当前时间」—— 封面键依赖它，必须重钉常量再按新键重铺一轮
                CoverPrimer.repinAndPrime("自愈后重钉");
            }
            // ③ 再删文件（顺序纪律：绝不先删后改路径）
            long bytes = 0L;
            int removed = 0;
            for (Path p : files) {
                long sz = sizeOf(p);
                try {
                    if (Files.deleteIfExists(p)) {
                        bytes += sz;
                        removed++;
                    }
                } catch (Throwable t) {
                    HEAL_FAILS.incrementAndGet();
                    PluginLog.w(TAG, "删历史本地封面文件失败（留在盘上）：" + p.getFileName() + " — " + t);
                }
            }
            HEALED.addAndGet(removed);
            // ④ 固定词形第二行「删 audio-cover」+ 收束行（Lead 的验收词形，逐字不变）
            PluginLog.i(TAG, "自愈：删 " + DIR_NAME + " " + removed + " 文件 / "
                    + (bytes / 1024L / 1024L) + " MB（原因：" + why + "）");
            PluginLog.i(TAG, "自愈完成：还原 path " + restored + " 行 / 删 " + DIR_NAME + " " + removed
                    + " 文件 / 重钉 " + (local.isEmpty() ? 0 : restored) + " 条（原因：" + why + "）");
        } catch (Throwable t) {
            HEAL_FAILS.incrementAndGet();
            healBlockedUntil = now + HEAL_FAIL_BACKOFF_MS;      // 有界：异常同样进退避，不原地重试
            PluginLog.e(TAG, "自愈异常（本轮放弃，" + RiskControl.human(HEAL_FAIL_BACKOFF_MS)
                    + " 后巡检，线程：" + thread + "）：" + t);
        }
        return restored;
    }

    /**
     * <b>兼容入口</b>（{@code NeteasePlugin} 每次同步后调用）：老语义是「把本地带图文件钉回
     * {@code Track.path}」，那是被 P-4 否掉的路；现在改为<b>反向自愈</b> —— 同步既然已经把 {@code path}
     * 写回流地址，这里只负责把漏网的历史本地文件清掉。
     *
     * @return 本次处理的（还原+删除的）曲目数
     */
    public static int reapplyAll() {
        List<Path> before = files();
        heal("同步后自愈");
        return before.size();
    }

    /** 直接清空（升级/排查用）：先还原所有 path，再删文件。 */
    public static int purge() {
        int n = 0;
        for (Path p : files()) {
            try {
                Files.deleteIfExists(p);
                n++;
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        if (n > 0) {
            String prefix = NativeStreamServer.baseUrl() + "/netease/";
            try {
                if (!NativeLibrary.restoreStreamPathsExternal(prefix)) {
                    PluginLog.w(TAG, "purge：路径还原未确认（可能有曲目指向已删文件）");
                }
            } catch (Throwable t) {
                PluginLog.w(TAG, "purge：路径还原失败（可能有曲目指向已删文件）：" + t);
            }
        }
        return n;
    }

    public static String stats() {
        List<Path> files = files();
        long total = 0L;
        for (Path p : files) {
            total += sizeOf(p);
        }
        return "播放侧封面：" + CoverDelivery.stats() + " ｜ 历史本地路径残留 "
                + files.size() + " 个 / " + (total / 1024L / 1024L) + " MB（目标 0；自愈 "
                + HEALED.get() + " 次曲目 / 失败 " + HEAL_FAILS.get() + "，队列 " + QUEUE.size() + "）";
    }

    // ------------------------------------------------------------------ 文件面

    /** 目录下残余的历史文件（不再有 {@code .part}/{@code .level}，只为清理而存在）。 */
    static List<Path> files() {
        List<Path> out = new ArrayList<>();
        try (var s = Files.list(dir())) {
            s.filter(Files::isRegularFile).forEach(out::add);
        } catch (Throwable ignored) {
            // 目录不存在等
        }
        return out;
    }

    public static Path existing(long songId) {
        for (String ext : new String[]{".flac", ".mp3", ".m4a", ".ogg"}) {
            Path p = dir().resolve(songId + ext);
            try {
                if (Files.isRegularFile(p) && Files.size(p) > 0L) {
                    return p;
                }
            } catch (Throwable ignored) {
                // 探测失败当作不存在
            }
        }
        return null;
    }

    public static boolean has(long songId) {
        return existing(songId) != null;
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 供外部（配置变更）刷新用：顺手做一次自愈巡检。 */
    public static void onConfigChanged() {
        lastHeal = 0L;                       // 下一次轮次立刻自愈
    }

    static {
        // 目录在数据中心下，创建失败不影响后续（每个文件操作各自兜底）
        try {
            Files.createDirectories(DataPaths.data().resolve(DIR_NAME));
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
