package com.example.netease.ui.controller;

import com.example.netease.cfg.PluginConfig;
import com.example.netease.core.PluginLog;
import com.example.netease.host.VoxzenBridge;
import com.example.netease.net.Dto;
import com.example.netease.net.NeteaseApi;
import com.example.netease.ui.LatestTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 搜索页业务控制器（批次 2；契约 build/_tmp/batch2-contracts.md，需求 docs/PLAN.md §7.1/§7.2）。
 *
 * <p>把 {@code SearchWindow} 里的搜索、选中、播放反馈逻辑抽成<b>无 UI 依赖</b>的状态机：
 * 本类不 import 任何 Swing / AWT / Kotlin / Compose 类型，只把 {@link State} 快照交给 UI 渲染。</p>
 *
 * <p>线程规则：
 * <ul>
 *   <li>状态变更与 listener 通知在同一把锁内完成，保证「先真正改状态、再通知」且通知顺序与变更一致；</li>
 *   <li>{@link Async} 的 run/cancel/close <b>在锁外</b>调用：生产实现 {@link LatestTask} 会在自己的监视器里执行回调，
 *       若持锁调它就会出现「LatestTask 锁 → 控制器锁」与「控制器锁 → LatestTask 锁」的反向锁序死锁；</li>
 *   <li>{@link #createDefault} 按契约注入 {@code Runnable::run}，回调可能来自后台线程，listener 需自行切到 UI 线程。</li>
 * </ul>
 *
 * <p>语义要点：迟到结果由内部 version 计数丢弃（LatestTask 的 latest-wins 是第二道保险）；
 * 新结果按歌曲 ID 恢复仍有效的选中；播放只回显宿主 report，绝不把「已投递」写成「已出声」，
 * 并在 30 秒窗口内持续轮询、保留最后一条反馈；搜索重试与播放重试分开管理，选中另一首歌不会让播放重试打错对象。</p>
 */
public final class SearchController {

    /** 搜索请求条数，等同 {@code SearchWindow.SEARCH_LIMIT}。 */
    public static final int LIMIT = 30;

    /** 播放反馈等待上限（毫秒）。 */
    public static final long PLAYBACK_TIMEOUT_MS = 30_000L;

    private static final String TAG = "ui.search";

    /** 搜索请求超时：等同 LatestTask 的 20 秒（契约：不得改动）。 */
    private static final long REQUEST_TIMEOUT_MS = 20_000L;

    private static final String TEXT_IDLE = "就绪";
    private static final String TEXT_EMPTY_KEYWORD = "请输入关键词";
    private static final String TEXT_SEARCHING = "搜索中…";
    private static final String TEXT_EMPTY_RESULT = "没有找到结果，可换个关键词重试";
    private static final String TEXT_NO_SELECTION = "请先选择一首歌";
    private static final String TEXT_UNPLAYABLE = "这首歌当前无版权或不可播放";
    private static final String TEXT_NO_SEARCH_RETRY = "没有可重试的搜索";
    private static final String TEXT_NO_PLAYBACK_RETRY = "没有可重试的播放请求";
    private static final String TEXT_PLAY_GONE = "上次播放的歌曲已不在当前结果中，无法重试";
    private static final String TEXT_PLAYBACK_TIMEOUT = "播放请求：等待宿主反馈超时（30 秒），可重试";
    private static final String TEXT_FALLBACK_ERROR = "网络或服务暂不可用，请重试";
    private static final String TEXT_ACCOUNT_CHANGED = "账号状态已变化，旧的搜索请求与播放反馈已作废";

    /** 搜索页阶段。 */
    public enum Phase { IDLE, SEARCHING, RESULTS, EMPTY, FAILED }

    /**
     * 一次性状态快照（不可变，rows 永不为 null）。
     *
     * @param phase            阶段
     * @param message          面向用户的一行反馈
     * @param rows             当前结果（快照）
     * @param sourceKeyword    当前列表的来源关键词；未搜索过 ""
     * @param requestedKeyword 最近一次搜索请求的关键词
     * @param searchRetryable  搜索重试是否可用
     * @param playRetryable    播放重试是否可用（与搜索重试完全独立）
     * @param selectedSongId   选中歌曲 id；null = 无选中
     * @param selectedRow      选中行；-1 = 无选中
     * @param pendingPlayRequest 最近一次投递的播放请求编号；窗口结束后仍保留，0 = 从未投递
     * @param playbackText     最后一条「播放请求：…」反馈原文
     * @param playing          是否仍在 30 秒播放反馈窗口内
     */
    public record State(
            Phase phase,
            String message,
            List<Dto.Song> rows,
            String sourceKeyword,
            String requestedKeyword,
            boolean searchRetryable,
            boolean playRetryable,
            Long selectedSongId,
            int selectedRow,
            int pendingPlayRequest,
            String playbackText,
            boolean playing) {

        public State {
            phase = phase == null ? Phase.IDLE : phase;
            message = message == null ? "" : message;
            rows = rows == null ? List.of() : List.copyOf(rows);
            sourceKeyword = sourceKeyword == null ? "" : sourceKeyword;
            requestedKeyword = requestedKeyword == null ? "" : requestedKeyword;
            playbackText = playbackText == null ? "" : playbackText;
        }
    }

    /** 状态观察者；只在状态真正变化后调用，{@link #close()} 之后不再调用。 */
    public interface Listener { void onState(State state); }

    /** 回到 UI 线程前的工作单元（{@code fetch()} 可抛受检异常）。 */
    public interface Fetcher<T> { T fetch() throws Exception; }

    /** 异步通道；生产实现是 {@link LatestTask}，测试可用同步假实现。 */
    public interface Async {
        <T> void run(Fetcher<T> work, Consumer<T> success, Consumer<Throwable> failure);
        void cancel();
        void close();
    }

    /** 播放通道；{@code report(requestId)} 返回宿主反馈原文，未就绪返回 null。 */
    public interface Playback {
        int playOnline(Dto.Song song, String level);
        String report(int requestId);
    }

    private final Listener listener;
    private final Async async;
    private final Playback playback;
    private final Supplier<String> audioLevel;
    private final LongSupplier clock;
    private final Object lock = new Object();

    // ---- 受 lock 保护的字段 ----
    private Phase phase = Phase.IDLE;
    private String message = TEXT_IDLE;
    private List<Dto.Song> rows = List.of();
    private String sourceKeyword = "";
    private String requestedKeyword = "";
    private boolean searchRetryable;
    private boolean playRetryable;
    private Long selectedSongId;
    private int selectedRow = -1;
    private int pendingPlayRequest;
    private String playbackText = "";
    private boolean playing;
    private State current;

    /** 搜索请求代号：每次发起自增，回调里对不上就丢弃（防迟到结果覆盖新结果）。 */
    private long version;
    private String lastSearchKeyword = "";
    private Long lastPlaySongId;
    /** 窗口内最后一条宿主反馈原文；null/blank 表示尚未收到任何反馈。 */
    private String lastPlaybackReport;
    private long playbackStart;
    private boolean closed;

    public SearchController(Listener listener, Async async, Playback playback,
                            Supplier<String> audioLevel, LongSupplier clock) {
        this.listener = listener == null ? state -> { } : listener;
        this.async = Objects.requireNonNull(async, "async");
        this.playback = Objects.requireNonNull(playback, "playback");
        this.audioLevel = audioLevel == null ? () -> null : audioLevel;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        synchronized (lock) {
            this.current = snapshotLocked();
        }
    }

    /**
     * 生产装配：LatestTask（20 秒超时，{@code Runnable::run} 直接执行回调）+ 宿主播放桥 + 现配置音质。
     *
     * <p>{@code Runnable::run} 是契约指定的注入方式（默认构造器会用 {@code SwingUtilities::invokeLater}，
     * 在 headless 探针里会炸）。因此 listener 可能在后台线程被调用，Compose 侧需自行切线程。</p>
     */
    public static SearchController createDefault(Listener listener) {
        LatestTask requests = new LatestTask("netease-search", REQUEST_TIMEOUT_MS, Runnable::run);
        return new SearchController(listener, new LatestTaskAsync(requests), new BridgePlayback(),
                PluginConfig::audioLevel, System::currentTimeMillis);
    }

    /** 当前状态快照。 */
    public State state() {
        synchronized (lock) {
            return current;
        }
    }

    // ------------------------------------------------------------------ 搜索

    /** 发起搜索；空白关键词只提示，不动列表。 */
    public void search(String rawKeyword) {
        String keyword = rawKeyword == null ? "" : rawKeyword.trim();
        if (keyword.isEmpty()) {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                // 作废在途请求：契约要求空关键词时 rows/sourceKeyword/phase 不变，
                // 光靠 async.cancel() 在假实现里不足以保证，必须同时让迟到回调对不上版本。
                version++;
                message = TEXT_EMPTY_KEYWORD;
                publishLocked();
            }
            async.cancel(); // 锁外调用，避免与 LatestTask 的监视器形成反向锁序
            return;
        }
        long requestVersion;
        synchronized (lock) {
            if (closed) {
                return;
            }
            requestVersion = ++version;
            playing = false;
            pendingPlayRequest = 0;
            phase = Phase.SEARCHING;
            message = TEXT_SEARCHING;
            requestedKeyword = keyword;
            searchRetryable = false;
            lastSearchKeyword = keyword;
            publishLocked();
        }
        try {
            async.run(() -> NeteaseApi.search(keyword, LIMIT),
                    result -> onSearchOk(requestVersion, keyword, result),
                    error -> onSearchFail(requestVersion, error));
        } catch (Throwable dispatchError) {
            // 通道拒绝投递（例如关闭后 RejectedExecutionException）：按搜索失败处理，不留死状态。
            onSearchFail(requestVersion, dispatchError);
        }
    }

    private void onSearchOk(long requestVersion, String keyword, List<Dto.Song> result) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (requestVersion != version) {
                PluginLog.d(TAG, "丢弃迟到的搜索结果（v" + requestVersion + " ≠ v" + version + "）：" + keyword);
                return;
            }
            List<Dto.Song> next = freeze(result);
            int keepRow = selectedSongId == null ? -1 : indexOf(next, selectedSongId);
            rows = next;
            sourceKeyword = keyword;
            if (keepRow >= 0) {
                selectedRow = keepRow;
            } else {
                selectedSongId = null;
                selectedRow = -1;
            }
            phase = next.isEmpty() ? Phase.EMPTY : Phase.RESULTS;
            message = next.isEmpty() ? TEXT_EMPTY_RESULT : "找到 " + next.size() + " 条";
            searchRetryable = true;
            publishLocked();
        }
    }

    private void onSearchFail(long requestVersion, Throwable error) {
        String detail = safeMessage(error);
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (requestVersion != version) {
                PluginLog.d(TAG, "丢弃迟到的搜索失败（v" + requestVersion + " ≠ v" + version + "）：" + detail);
                return;
            }
            phase = Phase.FAILED;
            message = "搜索失败：" + detail + "（保留上次结果）";
            searchRetryable = true;
            publishLocked();
        }
        PluginLog.w(TAG, "搜索失败：" + detail);
    }

    /** 宿主异常文案：空/空白给兜底，否则去掉 \r \n（契约 §4）。 */
    private static String safeMessage(Throwable error) {
        String raw = error == null ? null : error.getMessage();
        if (raw == null || raw.isBlank()) {
            return TEXT_FALLBACK_ERROR;
        }
        return raw.replace("\r", "").replace("\n", "");
    }

    // ------------------------------------------------------------------ 选中

    /** 按歌曲 id 选中；当前列表里没有就忽略。 */
    public void select(long songId) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            int row = indexOf(rows, songId);
            if (row < 0) {
                return;
            }
            if (selectedSongId != null && selectedSongId == songId && selectedRow == row) {
                return;
            }
            selectedSongId = songId;
            selectedRow = row;
            publishLocked();
        }
    }

    /** 按行选中；越界忽略。 */
    public void selectRow(int row) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (row < 0 || row >= rows.size()) {
                return;
            }
            long songId = rows.get(row).id();
            if (selectedRow == row && selectedSongId != null && selectedSongId == songId) {
                return;
            }
            selectedRow = row;
            selectedSongId = songId;
            publishLocked();
        }
    }

    // ------------------------------------------------------------------ 播放

    /** 播放当前选中；只投递请求并回显「正在准备」，不声称已出声。 */
    public void playSelected() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            Dto.Song song = selectedSongLocked();
            if (song == null) {
                message = TEXT_NO_SELECTION;
                publishLocked();
                return;
            }
            if (!song.playable()) {
                message = TEXT_UNPLAYABLE;
                publishLocked();
                return;
            }
            startPlaybackLocked(song);
            publishLocked();
        }
    }

    /** 重新投递<b>上次播放的那首</b>（与当前选中无关）。 */
    public void retryPlayback() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            Long target = lastPlaySongId;
            if (target == null) {
                message = TEXT_NO_PLAYBACK_RETRY;
                publishLocked();
                return;
            }
            int row = indexOf(rows, target);
            if (row < 0) {
                message = TEXT_PLAY_GONE;
                publishLocked();
                return;
            }
            selectedRow = row;
            selectedSongId = target;
            startPlaybackLocked(rows.get(row));
            publishLocked();
        }
    }

    private void startPlaybackLocked(Dto.Song song) {
        int requestId = playback.playOnline(song, audioLevel.get());
        lastPlaySongId = song.id();
        playbackStart = clock.getAsLong();
        lastPlaybackReport = null; // 新请求 = 新窗口，不复用上个请求的反馈
        playbackText = "播放请求：正在准备「" + safeName(song) + "」";
        message = playbackText;
        pendingPlayRequest = requestId;
        playing = true;
        playRetryable = true; // 契约：playRetryable = (lastPlaySongId != null)
    }

    // ------------------------------------------------------------------ 状态推进

    /**
     * UI 每 250ms 调一次：在 30 秒窗口内持续轮询宿主反馈并回显，绝不伪造进度。
     *
     * <p>窗口内每来一条不同的 report 就更新文案并通知一次（同值不重复通知）；到
     * {@link #PLAYBACK_TIMEOUT_MS} 才离开窗口——有反馈时保留最后一条 report，从未收到反馈才用超时文案。
     * 窗口结束后 {@code pendingPlayRequest} 仍保留原编号供 UI 显示/重试，tick 不再轮询它。</p>
     */
    public void tick() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (!playing || pendingPlayRequest <= 0) {
                return;
            }
            String report;
            try {
                report = playback.report(pendingPlayRequest);
            } catch (Throwable readError) {
                PluginLog.w(TAG, "读取播放反馈失败：" + readError);
                report = null;
            }
            boolean changed = false;
            if (report != null && !report.isBlank()) {
                lastPlaybackReport = report;
                String text = "播放请求：" + report;
                if (!text.equals(playbackText)) {
                    playbackText = text;
                    message = text;
                    changed = true;
                }
            }
            if (clock.getAsLong() - playbackStart >= PLAYBACK_TIMEOUT_MS) {
                playing = false;
                if (lastPlaybackReport == null || lastPlaybackReport.isBlank()) {
                    playbackText = TEXT_PLAYBACK_TIMEOUT;
                    message = TEXT_PLAYBACK_TIMEOUT;
                }
                changed = true;
            }
            if (changed) {
                publishLocked();
            }
        }
    }

    /** 重试上次搜索（用上次提交的关键词）。 */
    public void retrySearch() {
        String keyword;
        synchronized (lock) {
            if (closed) {
                return;
            }
            keyword = lastSearchKeyword;
            if (keyword == null || keyword.isEmpty()) {
                message = TEXT_NO_SEARCH_RETRY;
                publishLocked();
                return;
            }
        }
        search(keyword); // 锁外：search 自己会加锁，并把投递放到锁外
    }

    /**
     * 账号状态发生变化（批次 3；docs/PLAN.md §8.2）：作废在途搜索结果与旧播放反馈。
     *
     * <p>旧窗口在账号 epoch 变化时会整页重载；原生页保留已拿到的列表与选中（搜索结果本身是匿名数据），
     * 但把「在途请求的回调」和「旧播放请求编号」清零：它们属于旧账号，继续展示会被误读成本账号的结果。
     * 若搜索仍在途，phase 回退（有旧列表回 RESULTS，否则 IDLE），避免永久停在「搜索中…」。</p>
     */
    public void onAccountChanged() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            version++; // 在途回调对不上版本 → onSearchOk/onSearchFail 直接丢弃
            boolean searchInFlight = phase == Phase.SEARCHING;
            if (searchInFlight) {
                phase = rows.isEmpty() ? Phase.IDLE : Phase.RESULTS;
                searchRetryable = !lastSearchKeyword.isEmpty();
            }
            boolean hadPlayback = playing || pendingPlayRequest > 0
                    || lastPlaySongId != null || playRetryable;
            pendingPlayRequest = 0;
            playing = false;
            lastPlaySongId = null;
            lastPlaybackReport = null;
            playbackText = "";
            playRetryable = false;
            if (searchInFlight || hadPlayback) {
                message = TEXT_ACCOUNT_CHANGED;
                publishLocked();
            }
        }
        // 锁外取消（与 search/close 相同的锁序理由）。
        try {
            async.cancel();
        } catch (Throwable cancelError) {
            PluginLog.w(TAG, "账号变化时取消搜索请求失败：" + cancelError);
        }
    }

    /** 关闭：此后回调与 tick 都不再改状态、不再通知。 */
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        try {
            async.close();
        } catch (Throwable closeError) {
            PluginLog.w(TAG, "关闭搜索请求通道失败：" + closeError);
        }
    }

    // ------------------------------------------------------------------ 内部工具

    private Dto.Song selectedSongLocked() {
        int row = selectedRow;
        if (row < 0 || row >= rows.size() || selectedSongId == null) {
            return null;
        }
        Dto.Song song = rows.get(row);
        return song.id() == selectedSongId ? song : null;
    }

    private State snapshotLocked() {
        return new State(phase, message, rows, sourceKeyword, requestedKeyword,
                searchRetryable, playRetryable, selectedSongId, selectedRow,
                pendingPlayRequest, playbackText, playing);
    }

    private void publishLocked() {
        if (closed) {
            return;
        }
        State next = snapshotLocked();
        if (next.equals(current)) {
            return;
        }
        current = next;
        try {
            listener.onState(next);
        } catch (Throwable listenerError) {
            PluginLog.e(TAG, "搜索页状态回调失败", listenerError);
        }
    }

    private static List<Dto.Song> freeze(List<Dto.Song> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<Dto.Song> copy = new ArrayList<>(source.size());
        for (Dto.Song song : source) {
            if (song != null) {
                copy.add(song);
            }
        }
        return copy.isEmpty() ? List.of() : List.copyOf(copy);
    }

    private static int indexOf(List<Dto.Song> list, long songId) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id() == songId) {
                return i;
            }
        }
        return -1;
    }

    private static String safeName(Dto.Song song) {
        String name = song.name();
        return name == null ? "" : name;
    }

    // ------------------------------------------------------------------ 生产适配器

    /** {@link Async} → {@link LatestTask}（latest-wins + 20 秒超时由它负责）。 */
    private static final class LatestTaskAsync implements Async {
        private final LatestTask task;

        LatestTaskAsync(LatestTask task) {
            this.task = task;
        }

        @Override
        public <T> void run(Fetcher<T> work, Consumer<T> success, Consumer<Throwable> failure) {
            Callable<T> call = work::fetch;
            task.run(call, success, failure);
        }

        @Override
        public void cancel() {
            task.cancelCurrent();
        }

        @Override
        public void close() {
            task.close();
        }
    }

    /** {@link Playback} → 宿主桥（返回本次请求编号，反馈按编号取）。 */
    private static final class BridgePlayback implements Playback {
        @Override
        public int playOnline(Dto.Song song, String level) {
            return VoxzenBridge.playOnlineRequest(song, level);
        }

        @Override
        public String report(int requestId) {
            return VoxzenBridge.playbackReport(requestId);
        }
    }
}
