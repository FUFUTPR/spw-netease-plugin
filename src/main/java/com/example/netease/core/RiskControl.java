package com.example.netease.core;

/**
 * 风控退避与并发口径 —— <b>全插件共用一套</b>。
 *
 * <p>依据任务书 `docs/50-修复与重制任务书-给AI.md` §13：「所有网络流共用一套『风控退避』常量
 * （不在各自模块里各写一份）」与 R15「速度优先、并发拉满、无人工节流（保留风控退避即可）」。</p>
 *
 * <p><b>为什么单独一个类</b>：歌词（{@code svc.LyricService}）、封面（{@code svc.CoverStore} /
 * {@code svc.CoverPrimer}）、播放预取（{@code svc.StreamPrefetcher}）三条管线都会打网易云的接口。
 * 各自写一份退避常量的结果是：一处改了别处不知道，风控来了三处一起撞。这里只放**常量与判定**，
 * 不放状态、不持有连接、可被任意线程调用。</p>
 *
 * <p>注意：本类**不管** {@code net.Http} 的 300ms 全局节流（那是给宿主界面交互留的）；
 * 批量预热走各自的并发池，按本类的阶梯做退避。</p>
 */
public final class RiskControl {

    private RiskControl() {
    }

    /** 封面 / 歌词预热默认并发（R15「并发拉满」的工程值；三条预热管线共用同一口径）。 */
    public static final int WARM_CONCURRENCY = 6;

    /** 建连超时（毫秒）。 */
    public static final int CONNECT_TIMEOUT_MS = 5000;

    /** 读超时（毫秒）。 */
    public static final int READ_TIMEOUT_MS = 10000;

    /** 判定「被风控/被拦」的 HTTP 码。 */
    public static boolean isRiskCode(int httpCode) {
        return httpCode == 403 || httpCode == 412 || httpCode == 429;
    }

    /** 风控退避首档（毫秒）：10 分钟。 */
    public static final long RISK_BACKOFF_FIRST_MS = 600_000L;

    /** 风控退避上限（毫秒）：1 小时。 */
    public static final long RISK_BACKOFF_MAX_MS = 3_600_000L;

    /** 普通网络失败退避首档（毫秒）：2 秒。 */
    public static final long NET_BACKOFF_FIRST_MS = 2_000L;

    /** 普通网络失败退避上限（毫秒）：30 秒。 */
    public static final long NET_BACKOFF_MAX_MS = 30_000L;

    /**
     * 风控退避时长：首档 {@link #RISK_BACKOFF_FIRST_MS} 起逐次翻倍，封顶 {@link #RISK_BACKOFF_MAX_MS}。
     *
     * @param attempt 第几次（1 起；&lt;1 视为 1）
     */
    public static long riskBackoffMs(int attempt) {
        return ladder(RISK_BACKOFF_FIRST_MS, RISK_BACKOFF_MAX_MS, attempt);
    }

    /**
     * 普通网络失败退避时长：首档 {@link #NET_BACKOFF_FIRST_MS} 起逐次翻倍，封顶 {@link #NET_BACKOFF_MAX_MS}。
     *
     * @param attempt 第几次（1 起；&lt;1 视为 1）
     */
    public static long netBackoffMs(int attempt) {
        return ladder(NET_BACKOFF_FIRST_MS, NET_BACKOFF_MAX_MS, attempt);
    }

    private static long ladder(long first, long max, int attempt) {
        long v = first;
        for (int i = 1; i < Math.max(1, attempt) && v < max; i++) {
            v = Math.min(max, v * 2);
        }
        return Math.min(max, v);
    }

    /** 把毫秒写成给人看的一行（日志里统一口径，便于 grep/对比）。 */
    public static String human(long ms) {
        if (ms < 1000L) {
            return ms + "ms";
        }
        if (ms < 60_000L) {
            return String.format("%.1fs", ms / 1000.0);
        }
        return String.format("%.1fmin", ms / 60_000.0);
    }
}
