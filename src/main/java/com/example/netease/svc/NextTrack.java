package com.example.netease.svc;

import com.example.netease.core.PluginLog;
import com.example.netease.host.VoxzenBridge;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 「下一首是谁」—— W5 播放层（task-9 / 冻结入口见 docs/00 §6.20.5）。
 *
 * <p><b>来源优先级</b>（任务书 §4 D6=A + 探针报告 {@code docs/51-探针报告-P2P3.md} 结论）：</p>
 * <ol>
 *   <li><b>宿主播放队列</b>：队列长度 ≥ 2 时取「当前曲在队列里的下一格」，末首按宿主
 *       {@code PlaybackQueue$Mode=Circle} 回绕到首曲。队列由 {@link VoxzenBridge#queueTrackIds()}
 *       反射读出（P-3 已实证的路径；本类不自己写第二份反射）。</li>
 *   <li><b>当前曲所属歌单的下一首</b>：仅当队列长度 ≤ 1、或队列读失败、或当前曲不在队列里时退到这里。
 *       SQL 与探针在副本上验证过的一致：{@code SELECT playlistId, "order" FROM PlaylistTrack
 *       WHERE trackId = ?}（先完整 id 再裸 id）→ {@code SELECT trackId FROM PlaylistTrack
 *       WHERE playlistId = ? ORDER BY "order"} 取当前行的下一行。</li>
 * </ol>
 *
 * <p><b>返回值</b>：{@code > 0} = 下一首/上一首的裸 songId；{@code 0}（{@link #UNKNOWN}）= 未知。
 * 调用方**必须**区分「真没有下一首」与「读失败」——{@link #why()} 给出可读原因（同一份也进日志）。</p>
 *
 * <p><b>方向</b>：{@link #next(long)} 与 {@link #prev(long)} 同源同判据（队列前一格 / 歌单前一行），
 * 只是步进方向相反，两端都不擅自回绕。0.11.19 起两者都被「邻曲保温」在生产路径上调用 ——
 * 在此之前本类<b>全树零调用点</b>（能力写完没接线，与 docs/00 §11 坑 20 同型：
 * 「实现了」不等于「用户路径上会跑」）。</p>
 *
 * <p><b>线程</b>：本方法会读宿主库（磁盘 + 锁等待），**只能在自建线程上调用**
 * （当前唯一调用方是 {@code NeighborWarm} 的 {@code netease-warm-1}）。
 * <b>禁止</b>在宿主回调线程 / 宿主交互线程上直接调用。绝不抛异常：任何失败都退化成
 * {@link #UNKNOWN} 并把原因写进 {@link #why()} 与日志。</p>
 *
 * <p><b>禁止</b>用 {@code StreamingAudioCache.current()} 拼伪 id（未起播时会得到
 * {@code netease--1}）：本类只认真实 id，读不到就是 0。</p>
 */
public final class NextTrack {

    private static final String TAG = "next-track";

    /** 未知（读不到 / 真没有下一首）。 */
    public static final long UNKNOWN = 0L;

    /** 歌单兜底时最多扫几行（防御异常巨大的歌单：只取到当前行 + 下一行为止，不需要全表）。 */
    private static final int PLAYLIST_SCAN_MAX = 20000;

    private static volatile String lastWhy = "（本轮尚未解析过下一首）";
    /** 最近一次结果：0=未知 / >0=歌单号；与 {@link #lastWhy} 一起给人看。 */
    private static volatile long lastNext = UNKNOWN;

    private NextTrack() {
    }

    /**
     * 算下一首。
     *
     * @param currentSongId 当前曲的裸 songId（&gt; 0）
     * @return 下一首裸 songId；{@link #UNKNOWN} = 未知
     */
    public static long next(long currentSongId) {
        return resolve(currentSongId, 1);
    }

    /**
     * 算上一首（0.11.19「邻曲保温」用：点「上一首」和点「下一首」是同一个延迟，两首都要提前保温）。
     *
     * <p>与 {@link #next} 同源同判据，只是步进方向相反：先宿主队列往前一格，读不到退歌单前一行。
     * 到首格时<b>不回绕</b>（与 next 的末格策略一致：没读到宿主队列模式就不擅自回绕）。</p>
     *
     * @param currentSongId 当前曲的裸 songId（&gt; 0）
     * @return 上一首裸 songId；{@link #UNKNOWN} = 未知
     */
    public static long prev(long currentSongId) {
        return resolve(currentSongId, -1);
    }

    private static long resolve(long currentSongId, int dir) {
        String what = dir > 0 ? "下一首" : "上一首";
        if (currentSongId <= 0L) {
            if (dir > 0) {
                lastNext = UNKNOWN;
            }
            lastWhy = "当前曲=未知（入参 " + currentSongId + "；禁止用 audio-stream 缓存拼伪 id）";
            PluginLog.w(TAG, "解析" + what + "失败：" + lastWhy);
            return UNKNOWN;
        }
        long out = fromQueue(currentSongId, dir);
        if (out == UNKNOWN) {
            out = fromPlaylist(currentSongId, dir);
        }
        if (dir > 0) {
            lastNext = out;
        }
        PluginLog.i(TAG, what + " #" + currentSongId + " → " + (out > 0L ? "#" + out : "未知") + "；" + lastWhy);
        return out;
    }

    /** 最近一次解析的一行原因（可区分「真空」/「读失败」/「当前曲=未知」）。 */
    public static String why() {
        return lastWhy;
    }

    /** 最近一次解析结果（0 = 未知）。 */
    public static long lastNextId() {
        return lastNext;
    }

    // ------------------------------------------------------------------ ① 宿主队列

    private static long fromQueue(long currentSongId, int dir) {
        VoxzenBridge.QueueView q = VoxzenBridge.queueView();
        if (q.state() == VoxzenBridge.QueueState.FAILED) {
            // 读失败绝不能伪装成「没有下一首」：说明失败并退歌单。
            lastWhy = "队列=读失败（" + q.note() + "）→ 退歌单兜底";
            return UNKNOWN;
        }
        if (q.state() == VoxzenBridge.QueueState.EMPTY) {
            lastWhy = "队列=空 → 退歌单兜底";
            return UNKNOWN;
        }
        // 必须用逐位对齐表：队列里可能有解析不出的格子，紧凑表会让下标错位
        List<String> aligned = q.aligned();
        if (aligned.size() <= 1) {
            lastWhy = "队列=仅 " + aligned.size() + " 首（不足 2 首，没有可推的下一格）→ 退歌单兜底";
            return UNKNOWN;
        }
        String cur = "netease-" + currentSongId;
        int at = q.index();
        boolean byHostIndex = at >= 0 && at < aligned.size() && cur.equals(aligned.get(at));
        if (!byHostIndex) {
            at = aligned.indexOf(cur);
            if (at < 0) {
                lastWhy = "当前曲=未知（队列 " + aligned.size() + " 首，当前 #" + currentSongId
                        + " 不在队列里；宿主索引=" + q.index() + "）→ 退歌单兜底";
                return UNKNOWN;
            }
        }
        for (int step = 1; step <= aligned.size(); step++) {
            int i = at + step * dir;
            if (i < 0 || i >= aligned.size()) {
                // 末首 / 首首：宿主队列模式（Circle/Sequential/Random）读不到 ⇒ 不擅自回绕，交歌单兜底
                lastWhy = "队列=已到" + (dir > 0 ? "末" : "首") + "首（第 " + (at + 1) + "/" + aligned.size()
                        + " 首；未读到宿主队列模式，不擅自回绕）→ 退歌单兜底";
                return UNKNOWN;
            }
            String nxt = aligned.get(i);
            if (nxt == null) {
                continue;   // 该格解析不出 id：跳过继续往后/往前，绝不把它当「没有下一首/上一首」
            }
            long id = bareId(nxt);
            if (id > 0L) {
                lastWhy = "来源=宿主队列 " + (at + 1) + "/" + aligned.size() + "（"
                        + (dir > 0 ? "下一格=" : "上一格=") + (i + 1)
                        + "，判据=" + (byHostIndex ? "宿主索引" : "当前曲在队列中的位置") + "）"
                        + (q.note().isEmpty() ? "" : "；" + q.note());
                return id;
            }
        }
        lastWhy = "队列=读失败（第 " + (at + 1) + " 首" + (dir > 0 ? "之后 " + (aligned.size() - at - 1) : "之前 " + at)
                + " 格全部解析不出 id）→ 退歌单兜底";
        return UNKNOWN;
    }

    // ------------------------------------------------------------------ ② 歌单兜底

    private static long fromPlaylist(long currentSongId, int dir) {
        if (!NativeLibrary.available()) {
            lastWhy = lastWhy + "；歌单兜底不可用（宿主曲库不可写：" + NativeLibrary.describe() + "）";
            return UNKNOWN;
        }
        Connection c = null;
        try {
            c = openReadOnly();
            long playlistId = playlistOf(c, currentSongId);
            if (playlistId == 0L) {
                lastWhy = lastWhy + "；歌单兜底：PlaylistTrack 里没有 #" + currentSongId + " 的关联行";
                return UNKNOWN;
            }
            List<Long> order = orderOf(c, playlistId);
            if (order.isEmpty()) {
                lastWhy = lastWhy + "；歌单兜底：歌单 " + playlistId + " 没有曲目";
                return UNKNOWN;
            }
            int at = order.indexOf(currentSongId);
            if (at < 0) {
                lastWhy = lastWhy + "；歌单兜底：歌单 " + playlistId + " 的曲目序列里没有 #" + currentSongId;
                return UNKNOWN;
            }
            int i = at + dir;
            if (i < 0 || i >= order.size()) {
                // 末首 / 首首：**不回绕**（回绕只对宿主队列的 Circle 生效，见 D6）。如实说明。
                lastWhy = lastWhy + "；歌单兜底：歌单 " + playlistId + " 共 " + order.size()
                        + " 首，#" + currentSongId + " 已是" + (dir > 0 ? "末首" : "首首") + "（歌单顺序不回绕）";
                return UNKNOWN;
            }
            long nxt = order.get(i);
            lastWhy = lastWhy + "；来源=歌单 " + playlistId + " 的" + (dir > 0 ? "下一行" : "上一行")
                    + "（" + (i + 1) + "/" + order.size() + "）";
            return nxt;
        } catch (Throwable t) {
            lastWhy = lastWhy + "；歌单兜底失败：" + brief(t);
            PluginLog.w(TAG, "歌单兜底读库失败：" + brief(t));
            return UNKNOWN;
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                    // 尽力关
                }
            }
        }
    }

    /** 当前曲所属歌单：先用完整 id（{@code netease-<id>}）查，无行再用裸 id 重试（宿主历史行两种都出现过）。 */
    private static long playlistOf(Connection c, long currentSongId) throws Exception {
        for (String key : new String[]{"netease-" + currentSongId, String.valueOf(currentSongId)}) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT playlistId, \"order\" FROM PlaylistTrack WHERE trackId = ? ORDER BY \"order\" LIMIT 1")) {
                ps.setQueryTimeout(3);
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return numericId(rs.getString(1));
                    }
                }
            }
        }
        return 0L;
    }

    /** 该歌单的全序（裸 id，按 order 升序）。 */
    private static List<Long> orderOf(Connection c, long playlistId) throws Exception {
        List<Long> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT trackId FROM PlaylistTrack WHERE playlistId = ? ORDER BY \"order\" LIMIT " + PLAYLIST_SCAN_MAX)) {
            ps.setQueryTimeout(5);
            ps.setString(1, "netease-pl-" + playlistId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = bareId(rs.getString(1));
                    if (id > 0L) {
                        out.add(id);
                    }
                }
            }
        }
        if (out.isEmpty()) {
            // 兜底：宿主某些行 playlistId 不带 netease-pl- 前缀
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT trackId FROM PlaylistTrack WHERE playlistId = ? ORDER BY \"order\" LIMIT " + PLAYLIST_SCAN_MAX)) {
                ps.setQueryTimeout(5);
                ps.setString(1, String.valueOf(playlistId));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = bareId(rs.getString(1));
                        if (id > 0L) {
                            out.add(id);
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * 只读/只 SELECT 的连接。
     *
     * <p>为什么不加 {@code open_mode=1}（严格只读）：宿主库是 WAL 模式，严格只读连接在
     * {@code -shm} 不可写时会直接失败；本方法只执行 SELECT，绝不写任何一行（与同步写入
     * 那条路径的区别是「没有事务、没有 INSERT/UPDATE/DELETE」）。</p>
     */
    private static Connection openReadOnly() throws Exception {
        Driver d = (Driver) Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance();
        Properties p = new Properties();
        p.setProperty("busy_timeout", "3000");
        return d.connect("jdbc:sqlite:" + NativeLibrary.dbPath(), p);
    }

    // ------------------------------------------------------------------ 小工具

    /** {@code netease-123} / {@code netease-pl-123} → {@code 123}；解析不出返回 0。 */
    static long bareId(String trackId) {
        if (trackId == null || trackId.isBlank()) {
            return 0L;
        }
        String s = trackId.trim();
        int dash = s.lastIndexOf('-');
        String digits = dash >= 0 ? s.substring(dash + 1) : s;
        return numericId(digits);
    }

    private static long numericId(String s) {
        if (s == null || s.isBlank()) {
            return 0L;
        }
        String t = s.trim();
        for (int i = 0; i < t.length(); i++) {
            if (!Character.isDigit(t.charAt(i))) {
                return 0L;
            }
        }
        try {
            return Long.parseLong(t);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static String brief(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
    }
}
