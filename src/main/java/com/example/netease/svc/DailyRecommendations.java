package com.example.netease.svc;

import com.example.netease.cfg.PluginConfig;
import com.example.netease.core.PluginLog;
import com.example.netease.host.HostSqlBridge;
import com.example.netease.host.VoxzenBridge;
import com.example.netease.net.Dto;
import com.example.netease.net.NeteaseApi;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;

/** One local, synthetic playlist. Cloud playlists and shared tracks are never deleted. */
public final class DailyRecommendations {
    public static final long PLAYLIST_ID = -101L;
    public static final String HOST_ID = NativeLibrary.PLAYLIST_PREFIX + PLAYLIST_ID;
    public record State(boolean busy, String message, List<String> dates) {}
    private static volatile State state = new State(false, "每天 6:00 更新", List.of());
    private static ScheduledExecutorService worker;
    private static volatile long generation;
    private static long account = Long.MIN_VALUE;
    private static boolean enabled;
    private static LocalDate loadedDay;
    private static long retryAfter;
    private DailyRecommendations() {}
    public static State state() { return state; }
    public static LocalDate recommendationDay(Instant now) {
        return now.atZone(ZoneId.of("Asia/Shanghai")).minusHours(6).toLocalDate();
    }
    public static synchronized void start() {
        if (worker != null) return;
        generation++;
        account = Long.MIN_VALUE; loadedDay = null; retryAfter = 0;
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "netease-daily"); t.setDaemon(true); return t;
        });
        worker.scheduleWithFixedDelay(DailyRecommendations::tick, 3, 15, TimeUnit.SECONDS);
    }
    public static synchronized void stop() {
        generation++;
        if (worker != null) worker.shutdownNow();
        worker = null;
        state = new State(false, "", List.of());
    }
    private static void tick() {
        try {
            long epoch = AccountService.sessionEpoch();
            boolean on = PluginConfig.dailyRecommendations();
            if (epoch != account || on != enabled) {
                generation++;
                resetPlaylist(on);
                account = epoch; enabled = on; loadedDay = null; retryAfter = 0;
                state = new State(false, on ? "请先登录网易云账号" : "每日推荐已关闭，可在插件配置页开启", List.of());
            }
            if (on && AccountService.loggedIn() && !recommendationDay(Instant.now()).equals(loadedDay)
                    && System.currentTimeMillis() >= retryAfter) {
                long token = generation;
                HostSqlBridge.onHostConnection(HostSqlBridge.guarded(() -> valid(token, epoch), sql ->
                        NativeLibrary.publishPlaylistHeadersOn(sql, List.of(new NativeLibrary.Entry(PLAYLIST_ID, "每日推荐", List.of())))));
                load("today", "");
            }
        } catch (Throwable error) { fail(error); }
    }
    private static synchronized void enqueue(Runnable action) {
        if (worker != null && !worker.isShutdown()) worker.execute(action);
    }
    public static synchronized void today() {
        if (state.busy() || worker == null || !PluginConfig.dailyRecommendations() || !AccountService.loggedIn()) return;
        state = new State(true, "正在刷新每日歌单…", List.of());
        enqueue(() -> load("today", ""));
    }
    public static void style() { enqueue(() -> load("style", "")); }
    public static void history(String date) { enqueue(() -> load("history", date)); }
    public static void historyDates() { enqueue(() -> {
        if (!PluginConfig.dailyRecommendations() || !AccountService.loggedIn()) return;
        long epoch = AccountService.sessionEpoch(), token = generation;
        state = new State(true, "正在读取历史日推…", List.of());
        try {
            var session = NeteaseApi.captureCommittedSession();
            var dates = session.call(NeteaseApi::dailyHistoryDates);
            if (valid(token, epoch)) state = new State(false,
                    dates.isEmpty() ? "暂无可用的历史日推；可用日期以网易云账号权限为准" : "选择日期；历史日推以网易云会员权限为准", dates);
        } catch (Throwable error) { if (valid(token, epoch)) fail(error); }
    }); }
    public static void disable() {
        state = new State(true, "正在关闭每日推荐…", List.of());
        PluginConfig.disableDailyRecommendations().whenComplete((unused, error) -> {
            if (error != null) { fail(error); return; }
            // Invalidate in-flight network results immediately, before queued removal.
            generation++;
            enqueue(DailyRecommendations::tick);
        });
    }
    private static boolean valid(long token, long epoch) {
        return generation == token && AccountService.sessionEpoch() == epoch
                && AccountService.loggedIn() && PluginConfig.dailyRecommendations() && worker != null;
    }
    private static void load(String mode, String date) {
        if (!PluginConfig.dailyRecommendations() || !AccountService.loggedIn()) return;
        long token = generation, epoch = AccountService.sessionEpoch();
        LocalDate day = recommendationDay(Instant.now());
        String label = mode.equals("style") ? "风格推荐" : mode.equals("history") ? "历史日推 · " + date : "今日日推 · " + day;
        state = new State(true, "正在刷新每日歌单 · " + label + "…", List.of());
        try {
            var session = NeteaseApi.captureCommittedSession();
            List<Dto.Song> songs = session.call(() -> switch (mode) {
                case "style" -> NeteaseApi.dailyStyleSongs();
                case "history" -> NeteaseApi.dailyHistorySongs(date);
                default -> NeteaseApi.dailySongs();
            });
            if (songs.isEmpty()) throw new IllegalStateException("网易云暂未返回推荐歌曲，请稍后重试");
            if (!valid(token, epoch)) return;
            DailySongVisibility.record(songs);
            NativeLibrary.backupOnce("daily");
            if (NativeStreamServer.start() <= 0) throw new IllegalStateException("本地播放服务未就绪");
            String cover = songs.stream().map(Dto.Song::picUrl).filter(url -> url != null && !url.isBlank()).findFirst().orElse("");
            var entry = new NativeLibrary.Entry(PLAYLIST_ID, "每日推荐", songs, cover);
            var result = HostSqlBridge.onHostConnection(HostSqlBridge.guarded(() -> valid(token, epoch), sql ->
                    NativeLibrary.writeSyncEntryOn(sql, entry, NativeStreamServer.baseUrl() + "/netease/", NativeLibrary.lastBackup(), 0)));
            if (result == null || !result.ok()) throw new IllegalStateException("每日推荐写入未完成，请重试");
            HostSqlBridge.onHostConnection(DailySongVisibility::refreshOn);
            if (!valid(token, epoch)) return;
            loadedDay = day;
            VoxzenBridge.refreshHostLibrary();
            try {
                var times = DailyRecommendationCover.prepare(day);
                if (valid(token, epoch)) HostSqlBridge.onHostConnection(HostSqlBridge.guarded(() -> valid(token, epoch), sql ->
                        NativeLibrary.writePlaylistCoverTimesOn(sql, times)));
            } catch (Throwable coverError) { PluginLog.w("daily", "每日推荐封面准备失败：" + coverError); }
            if (!valid(token, epoch)) return;
            state = new State(false, label + " · " + songs.size() + " 首", List.of());
            VoxzenBridge.refreshHostLibrary();
            // Cover preparation runs off the UI thread, after publishing songs.
            var entries = List.of(entry);
            CoverStore.plan(entries);
            CoverStore.startFill(com.example.netease.NeteasePlugin::onCoverBatch);
            var covers = CoverArt.collectReady(entries);
            if (valid(token, epoch)) HostSqlBridge.onHostConnection(HostSqlBridge.guarded(() -> valid(token, epoch), sql ->
                    NativeLibrary.writeCoversOn(sql, covers)));
        } catch (Throwable error) { if (valid(token, epoch)) fail(error); }
    }
    private static void resetPlaylist(boolean keepPosition) throws Exception {
        Boolean removed = HostSqlBridge.onHostConnection(sql -> {
            if(keepPosition)NativeLibrary.clearDailyPlaylistOn(sql);
            else NativeLibrary.removeDailyPlaylistOn(sql);
            return true;
        });
        if (removed == null) throw new IllegalStateException("宿主曲库尚未就绪，请稍后重试");
        VoxzenBridge.refreshHostLibrary();
    }
    private static void fail(Throwable error) {
        retryAfter = System.currentTimeMillis() + 60_000;
        String message = error.getMessage();
        state = new State(false, "每日推荐操作未完成：" + (message == null ? "请稍后重试" : message), List.of());
        PluginLog.w("daily", state.message());
    }
}
