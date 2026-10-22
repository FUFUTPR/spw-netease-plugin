package com.example.netease.ui.controller;

import com.example.netease.cfg.PluginConfig;
import com.example.netease.core.PluginLog;
import com.example.netease.host.VoxzenBridge;
import com.example.netease.net.Dto;
import com.example.netease.net.NeteaseApi;
import com.example.netease.svc.AccountService;
import com.example.netease.svc.LocalPlaylists;
import com.example.netease.ui.LatestTask;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 歌单页业务控制器（批次 2 从 旧 {@code PlaylistWindow}（0.12.0 已删除） 抽出；无 Swing / AWT / Kotlin / Compose 依赖）。
 *
 * <p>职责：左栏歌单清单、右栏曲目来源、载入失败重试（原参数）、账号 epoch 失效、
 * 整单连播请求与宿主播放反馈。线程模型由注入的 {@link Async} 决定；本控制器所有状态
 * 变更都在 {@code synchronized} 方法内完成，并在状态真正变化后同步通知 {@link Listener}。
 * {@link #close()} 之后不再通知。</p>
 */
public final class PlaylistController {

    private static final String TAG = "ui.playlist";

    /** 歌单来源分类。 */
    public enum SourceKind { MINE, LIKED, DAILY, PLAYLIST, LOCAL }

    /** 一次载入请求（不可变；失败重试必须复用原参数，含 playlistId / localKey）。 */
    public static final class Request {
        public final SourceKind kind;
        public final long playlistId;
        public final String localKey;
        public final String label;

        private Request(SourceKind kind, long playlistId, String localKey, String label) {
            this.kind = kind;
            this.playlistId = playlistId;
            this.localKey = localKey;
            this.label = label;
        }

        public static Request mine() {
            return new Request(SourceKind.MINE, 0L, "", "我的歌单");
        }

        public static Request liked() {
            return new Request(SourceKind.LIKED, 0L, "", "我喜欢的音乐");
        }

        public static Request daily() {
            return new Request(SourceKind.DAILY, 0L, "", "每日推荐");
        }

        public static Request playlist(long id, String label) {
            return new Request(SourceKind.PLAYLIST, id, "", label == null ? "" : label);
        }

        public static Request local(String key, String name) {
            return new Request(SourceKind.LOCAL, 0L, key == null ? "" : key, name == null ? "" : name);
        }

        public String describe() {
            return switch (kind) {
                case MINE -> "我的歌单";
                case LIKED -> "我喜欢的音乐";
                case DAILY -> "每日推荐";
                case PLAYLIST -> "歌单「" + label + "」";
                case LOCAL -> "本地歌单「" + label + "」";
            };
        }
    }

    /** 对外只读状态快照（列表永不为 null）。 */
    public record State(
            List<Dto.PlaylistBrief> briefs,
            boolean briefsLoaded,
            List<Dto.Song> tracks,
            String sourceName,
            String message,
            boolean busy,
            String pendingLabel,
            String retryLabel,
            boolean canRetry,
            int pendingPlayRequest,
            String playbackText,
            boolean playing,
            long accountEpoch) { }

    public interface Listener {
        void onState(State state);
    }

    public interface Fetcher<T> {
        T fetch() throws Exception;
    }

    public interface Async {
        <T> void run(Fetcher<T> work, Consumer<T> success, Consumer<Throwable> failure);

        void cancel();

        void close();
    }

    public interface Playback {
        int playPlaylist(List<Dto.Song> songs, int startRow, String level, String source);

        String report(int requestId);
    }

    public interface Api {
        List<Dto.PlaylistBrief> userPlaylists(long uid, int limit) throws Exception;

        List<Dto.Song> likedSongs(long uid) throws Exception;

        List<Dto.Song> dailySongs() throws Exception;

        Dto.Playlist playlist(long id) throws Exception;

        List<Dto.Song> localPlaylist(String key) throws Exception;

        boolean loggedIn();

        long uid();

        long accountEpoch();
    }

    /** 「我的歌单」清单上限。 */
    public static final int BRIEF_LIMIT = 200;

    /** 整单播放后等待宿主反馈的超时（毫秒）。 */
    public static final long PLAYBACK_TIMEOUT_MS = 30_000L;

    private final Listener listener;
    private final Async async;
    private final Api api;
    private final Playback playback;
    private final Supplier<String> audioLevel;
    private final LongSupplier clock;

    private List<Dto.PlaylistBrief> briefs = List.of();
    private boolean briefsLoaded;
    private List<Dto.Song> tracks = List.of();
    private String sourceName = "";
    private String message = "就绪";
    private boolean busy;
    private String pendingLabel = "";
    private String retryLabel = "";
    private boolean canRetry;
    private int pendingPlayRequest;
    private String playbackText = "";
    /** 是否仍在 30 秒播放反馈窗口内（与“能否重试”解耦）。 */
    private boolean playing;
    private long accountEpoch;

    /** 控制器自身的迟到结果防护（LatestTask 的 latest-wins 只是第二道保险）。 */
    private long version;
    /** 本次载入发起时的账号 epoch；回调时不一致即丢弃。 */
    private long epochSnapshot;
    private boolean closed;
    private Request retryRequest;
    private Runnable retrySubmit = this::loadMine;
    private long playStart;
    private int lastPlayRow = -1;
    private Long lastPlaySongId;
    /** 本次播放窗口内宿主返回的最后一条非空 report。 */
    private String lastPlaybackReport = "";

    public PlaylistController(Listener listener, Async async, Api api, Playback playback,
                              Supplier<String> audioLevel, LongSupplier clock) {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.async = Objects.requireNonNull(async, "async");
        this.api = Objects.requireNonNull(api, "api");
        this.playback = Objects.requireNonNull(playback, "playback");
        this.audioLevel = Objects.requireNonNull(audioLevel, "audioLevel");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.accountEpoch = api.accountEpoch();
    }

    /**
     * 生产装配：{@code LatestTask}（注入 {@code Runnable::run}，避免 headless 依赖 SwingUtilities）
     * + {@code NeteaseApi} / {@code LocalPlaylists} + {@code VoxzenBridge}。
     */
    public static PlaylistController createDefault(Listener listener) {
        return new PlaylistController(
                listener,
                new DefaultAsync(),
                new DefaultApi(),
                new DefaultPlayback(),
                PluginConfig::audioLevel,
                System::currentTimeMillis);
    }

    public synchronized State state() {
        return snapshot();
    }

    // ------------------------------------------------------------------ 载入动作

    public synchronized void loadMine() {
        if (closed) {
            return;
        }
        long uid = uid();
        if (uid <= 0) {
            messageOnly("请先登录；公开歌单仍可通过 ID 载入");
            return;
        }
        submit(Request.mine(), () -> api.userPlaylists(uid, BRIEF_LIMIT), value -> {
            List<Dto.PlaylistBrief> list = briefsOrEmpty(value);
            briefs = list;
            briefsLoaded = true;
            long created = list.stream().filter(Dto.PlaylistBrief::mine).count();
            message = "已载入 " + list.size() + " 个歌单（创建 " + created + " / 收藏 "
                    + (list.size() - created) + "），点左侧列表切换";
        });
    }

    public synchronized void loadLiked() {
        if (closed) {
            return;
        }
        long uid = uid();
        if (uid <= 0) {
            messageOnly("请先登录后载入我喜欢的音乐");
            return;
        }
        submit(Request.liked(), () -> api.likedSongs(uid), value -> {
            tracks = songsOrEmpty(value);
            sourceName = "我喜欢的音乐";
            message = "我喜欢的音乐 " + tracks.size() + " 首";
        });
    }

    public synchronized void loadDaily() {
        if (closed) {
            return;
        }
        submit(Request.daily(), api::dailySongs, value -> {
            tracks = songsOrEmpty(value);
            sourceName = "每日推荐";
            message = "每日推荐 " + tracks.size() + " 首（今日）";
        });
    }

    public synchronized void loadPlaylist(long playlistId, String label) {
        if (closed) {
            return;
        }
        String safeLabel = label == null ? "" : label;
        submit(Request.playlist(playlistId, safeLabel), () -> api.playlist(playlistId), value -> {
            if (value == null) {
                throw new IllegalStateException("歌单 " + playlistId + " 返回空数据");
            }
            String name = value.name() == null || value.name().isBlank() ? safeLabel : value.name();
            tracks = songsOrEmpty(value.tracks());
            sourceName = "歌单「" + name + "」";
            message = "歌单「" + name + "」声明 " + value.trackCount() + " 首，已载入 "
                    + tracks.size() + " 首";
        });
    }

    /** 用户输入歌单 ID / 链接：解析失败只改提示，不发请求。 */
    public synchronized void loadPlaylistText(String text) {
        if (closed) {
            return;
        }
        long id = PlaylistIds.parse(text);
        if (id <= 0) {
            messageOnly("请填写有效歌单 ID 或链接");
            return;
        }
        loadPlaylist(id, "歌单 #" + id);
    }

    public synchronized void loadLocal(String key, String name) {
        if (closed) {
            return;
        }
        String safeName = name == null ? "" : name;
        submit(Request.local(key, safeName), () -> api.localPlaylist(key), value -> {
            tracks = songsOrEmpty(value);
            sourceName = "本地歌单「" + safeName + "」";
            message = "本地快照 " + tracks.size() + " 首；播放时需要联网";
        });
    }

    /** 失败重试：复用发起时的同一请求参数（playlistId / localKey 不重新解析）。 */
    public synchronized void retry() {
        if (closed) {
            return;
        }
        if (retryRequest == null) {
            messageOnly("没有可重试的载入");
            return;
        }
        retrySubmit.run();
    }

    // ------------------------------------------------------------------ 播放

    /** 从第 row 行开始整单连播（行号夹到 [0, n-1]）。 */
    public synchronized void playFrom(int row) {
        if (closed) {
            return;
        }
        if (tracks.isEmpty()) {
            messageOnly("曲目表是空的，请先载入一张歌单");
            return;
        }
        int index = Math.max(0, Math.min(row, tracks.size() - 1));
        Dto.Song song = tracks.get(index);
        if (song == null || !song.playable()) {
            messageOnly("这首歌当前无版权或不可播放");
            return;
        }
        startPlayback(index);
    }

    /** 重试上次的播放目标：在当前列表里按歌曲 id 重新定位行（找不到就只改提示）。 */
    public synchronized void retryPlayback() {
        if (closed) {
            return;
        }
        if (lastPlaySongId == null) {
            messageOnly("没有可重试的播放请求");
            return;
        }
        int index = indexOf(lastPlaySongId);
        if (index < 0) {
            messageOnly("上次播放的歌曲已不在当前列表中，无法重试");
            return;
        }
        startPlayback(index);
    }

    private void startPlayback(int index) {
        Dto.Song song = tracks.get(index);
        String level = audioLevel.get();
        String source = sourceName;
        int requestId = playback.playPlaylist(tracks, index, level, source);
        playbackText = "播放请求：" + source + " 自第 " + (index + 1) + " 首「" + song.name()
                + "」起（音质 " + level + "；失败原因见日志 bridge）";
        message = playbackText;
        pendingPlayRequest = requestId;
        playing = true;
        playStart = clock.getAsLong();
        lastPlaybackReport = "";
        lastPlayRow = index;
        lastPlaySongId = song.id();
        PluginLog.i(TAG, "请求整单连播 source=" + source + " start=" + (index + 1) + "/"
                + tracks.size() + " songId=" + song.id() + " level=" + level);
        publish();
    }

    // ------------------------------------------------------------------ 定时轮询

    /**
     * UI 定时调用（搜索页契约同样 250ms 节奏）：先看账号 epoch，再看整单播放反馈。
     * 无变化时绝不通知监听器。
     */
    public synchronized void tick() {
        if (closed) {
            return;
        }
        long epochNow = api.accountEpoch();
        if (epochNow != accountEpoch) {
            // 插件启动早期账号态尚未稳定，而页面还没载入过任何内容（没有可失效的列表/在途请求）：
            // 只静默同步快照，不把内部 epoch 抖动显示成用户可见的「账号状态变化」。
            boolean untouched = !briefsLoaded && briefs.isEmpty() && tracks.isEmpty()
                    && !busy && pendingPlayRequest <= 0;
            if (untouched) {
                accountEpoch = epochNow;
                playbackTick();
                return;
            }
            async.cancel();
            busy = false;
            briefs = List.of();
            briefsLoaded = false;
            playing = false;
            pendingPlayRequest = 0;
            lastPlaybackReport = "";
            pendingLabel = "";
            message = "账号状态变化，请重新载入；当前仍显示上次载入的曲目";
            canRetry = true;
            retryLabel = "重新载入我的歌单";
            retryRequest = Request.mine();
            retrySubmit = this::loadMine;
            accountEpoch = epochNow;
            publish();
            return;
        }
        playbackTick();
    }

    private void playbackTick() {
        if (!playing || pendingPlayRequest <= 0) {
            return;
        }
        String report;
        try {
            report = playback.report(pendingPlayRequest);
        } catch (Throwable t) {
            PluginLog.d(TAG, "读取播放反馈失败（忽略）：" + t);
            report = null;
        }
        if (report != null && !report.isBlank()) {
            lastPlaybackReport = report;
            String text = "播放请求：" + report;
            if (!text.equals(playbackText)) {
                playbackText = text;
                message = text;
                publish();
            }
        }
        if (clock.getAsLong() - playStart >= PLAYBACK_TIMEOUT_MS) {
            playing = false;
            if (lastPlaybackReport.isEmpty()) {
                playbackText = "播放请求：等待宿主反馈超时（30 秒），可重试";
                message = playbackText;
            }
            pendingPlayRequest = 0;
            publish();
        }
    }

    // ------------------------------------------------------------------ 生命周期

    /** 关闭：之后不再接受请求、不再通知监听器。 */
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        async.close();
    }

    // ------------------------------------------------------------------ 内部

    private synchronized <T> void submit(Request request, Fetcher<T> work, Consumer<T> apply) {
        if (closed) {
            return;
        }
        playing = false;
        pendingPlayRequest = 0;
        busy = true;
        pendingLabel = request.describe();
        message = "正在载入「" + request.describe() + "」…（保留上次列表）";
        canRetry = false;
        retryLabel = "";
        long myVersion = ++version;
        long myEpoch = api.accountEpoch();
        epochSnapshot = myEpoch;
        retryRequest = request;
        retrySubmit = () -> submit(request, work, apply);
        async.run(work,
                value -> onLoadSuccess(request, myVersion, myEpoch, apply, value),
                error -> onLoadFailure(request, myVersion, error));
        publish();
    }

    private synchronized <T> void onLoadSuccess(Request request, long myVersion, long myEpoch,
                                                Consumer<T> apply, T value) {
        if (closed) {
            return;
        }
        if (myVersion != version) {
            PluginLog.d(TAG, "丢弃过期的歌单载入结果：" + request.describe());
            return;
        }
        if (api.accountEpoch() != myEpoch) {
            PluginLog.d(TAG, "丢弃账号变化后的歌单载入结果：" + request.describe());
            return;
        }
        busy = false;
        pendingLabel = "";
        Throwable applyError = null;
        try {
            apply.accept(value);
        } catch (Throwable t) {
            applyError = t;
            PluginLog.w(TAG, "歌单载入结果应用失败：" + request.describe() + " :: " + t);
        }
        canRetry = true;
        retryLabel = "重试「" + request.describe() + "」";
        if (applyError != null) {
            message = "载入「" + request.describe() + "」失败：" + briefError(applyError)
                    + "；保留上次列表，可重试";
        }
        publish();
    }

    private synchronized void onLoadFailure(Request request, long myVersion, Throwable error) {
        if (closed) {
            return;
        }
        if (myVersion != version) {
            PluginLog.d(TAG, "丢弃过期的歌单载入失败：" + request.describe());
            return;
        }
        busy = false;
        pendingLabel = "";
        canRetry = true;
        retryLabel = "重试「" + request.describe() + "」";
        message = "载入「" + request.describe() + "」失败：" + briefError(error)
                + "；保留上次列表，可重试";
        publish();
    }

    private void publish() {
        if (closed) {
            return;
        }
        State snapshot = snapshot();
        try {
            listener.onState(snapshot);
        } catch (Throwable t) {
            PluginLog.w(TAG, "歌单控制器状态回调失败（已忽略）：" + t);
        }
    }

    private State snapshot() {
        return new State(briefs, briefsLoaded, tracks, sourceName, message, busy, pendingLabel,
                retryLabel, canRetry, pendingPlayRequest, playbackText, playing, accountEpoch);
    }

    private void messageOnly(String newMessage) {
        if (Objects.equals(message, newMessage)) {
            return;
        }
        message = newMessage;
        publish();
    }

    private long uid() {
        try {
            return api.uid();
        } catch (Throwable t) {
            PluginLog.d(TAG, "读取登录 uid 失败（按未登录处理）：" + t);
            return -1L;
        }
    }

    private int indexOf(long songId) {
        for (int i = 0; i < tracks.size(); i++) {
            Dto.Song song = tracks.get(i);
            if (song != null && song.id() == songId) {
                return i;
            }
        }
        return -1;
    }

    private static List<Dto.Song> songsOrEmpty(List<Dto.Song> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return list.stream().filter(Objects::nonNull).toList();
    }

    private static List<Dto.PlaylistBrief> briefsOrEmpty(List<Dto.PlaylistBrief> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return list.stream().filter(Objects::nonNull).toList();
    }

    private static String briefError(Throwable error) {
        if (error instanceof TimeoutException) {
            return "请求超时";
        }
        return "网络或服务暂不可用";
    }

    /** 生产 API 适配：数据来源保持 NeteaseApi / LocalPlaylists 既有方法不变。 */
    private static final class DefaultApi implements Api {
        @Override
        public List<Dto.PlaylistBrief> userPlaylists(long uid, int limit) throws Exception {
            return NeteaseApi.userPlaylists(uid, limit);
        }

        @Override
        public List<Dto.Song> likedSongs(long uid) throws Exception {
            return NeteaseApi.likedSongs(uid);
        }

        @Override
        public List<Dto.Song> dailySongs() throws Exception {
            return NeteaseApi.dailySongs();
        }

        @Override
        public Dto.Playlist playlist(long id) throws Exception {
            return NeteaseApi.playlist(id);
        }

        @Override
        public List<Dto.Song> localPlaylist(String key) throws Exception {
            return LocalPlaylists.load(key);
        }

        @Override
        public boolean loggedIn() {
            return AccountService.loggedIn();
        }

        @Override
        public long uid() {
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

        @Override
        public long accountEpoch() {
            return AccountService.sessionEpoch();
        }
    }

    /**
     * 生产异步适配：复用 {@link LatestTask} 的 latest-wins / 超时语义；
     * 注入 {@code Runnable::run} 以避免 headless 环境依赖 {@code SwingUtilities}。
     */
    private static final class DefaultAsync implements Async {
        private final LatestTask task = new LatestTask("native-playlist", 20_000L, Runnable::run);

        @Override
        public <T> void run(Fetcher<T> work, Consumer<T> success, Consumer<Throwable> failure) {
            task.run(work::fetch, success, failure);
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

    /** 生产播放适配：沿用 VoxzenBridge 的整单播放与反馈查询。 */
    private static final class DefaultPlayback implements Playback {
        @Override
        public int playPlaylist(List<Dto.Song> songs, int startRow, String level, String source) {
            return VoxzenBridge.playPlaylistRequest(songs, startRow, level, source);
        }

        @Override
        public String report(int requestId) {
            return VoxzenBridge.playbackReport(requestId);
        }
    }
}
