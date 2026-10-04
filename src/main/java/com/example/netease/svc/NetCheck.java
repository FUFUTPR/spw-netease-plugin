package com.example.netease.svc;

import com.example.netease.cfg.PluginConfig;
import com.example.netease.core.DataPaths;
import com.example.netease.core.HostBridgeWorker;
import com.example.netease.core.Notifier;
import com.example.netease.core.PluginLog;
import com.example.netease.net.Dto;
import com.example.netease.net.NeteaseApi;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网络与环境自检（插件设置页「测试连接」的后端）。
 *
 * <p>四项检查：① 数据目录可写 ② 网易云接口可达（真实搜索）③ 登录态 ④ 插件本地状态
 * （登录凭据 / 音质档位 / 是否自动接入客户端）。结果拼成多行报告记日志，单行摘要给 toast。</p>
 *
 * <p><b>线程</b>：{@code diagnose()} 会联网（阻塞 1~10s），必须跑在事件线程或 HostBridgeWorker 上；
 * {@code runAsync()} 负责把调用方（宿主 UI 线程/EDT）的工作丢到 HostBridgeWorker，并由
 * {@link AtomicBoolean} 防重入 + 3 秒最小间隔防连点。</p>
 */
public final class NetCheck {

    /** 两次自检的最小间隔（防用户连点按钮打爆接口）。 */
    private static final long MIN_INTERVAL_MS = 3_000L;

    /** 自检用的探针关键词（免登录、极小 limit）。 */
    private static final String PROBE_KEYWORD = "周杰伦 晴天";

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicLong LAST_AT = new AtomicLong(0L);

    private static volatile String lastReport = "";

    private NetCheck() {
    }

    public static String lastReport() {
        return lastReport;
    }

    /**
     * 异步自检：把工作投到 HostBridgeWorker（唯一宿主交互线程），立刻返回。
     * 宿主 UI 线程调用安全；重复调用在运行中/间隔内会被忽略。
     *
     * @return 是否真的启动了本次自检
     */
    public static boolean runAsync() {
        long now = System.currentTimeMillis();
        if (now - LAST_AT.get() < MIN_INTERVAL_MS) {
            PluginLog.d("net", "自检过于频繁，忽略");
            return false;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            PluginLog.d("net", "自检进行中，忽略重复请求");
            return false;
        }
        LAST_AT.set(now);
        boolean ok = false;
        try {
            ok = HostBridgeWorker.get().submit(() -> {
                try {
                    String report = diagnose();
                    lastReport = report;
                    PluginLog.i("net", "网络自检结果：\n" + report);
                    Notifier.success(summaryLine(report));
                } catch (Throwable t) {
                    PluginLog.w("net", "网络自检异常 :: " + t);
                    Notifier.warn("网络自检异常：" + t.getClass().getSimpleName());
                } finally {
                    RUNNING.set(false);
                }
            });
        } catch (Throwable t) {
            RUNNING.set(false);
            PluginLog.w("net", "网络自检投递失败 :: " + t);
        }
        if (!ok) {
            RUNNING.set(false);
        }
        return ok;
    }

    /**
     * 同步自检（**会联网，1~10 秒**）：只能在事件线程 / HostBridgeWorker / 离线自检里调用。
     *
     * @return 多行报告，绝不抛异常
     */
    public static String diagnose() {
        StringBuilder sb = new StringBuilder(256);
        boolean netOk = false;

        // ① 数据目录可写
        Path dataDir = null;
        String writeResult;
        try {
            dataDir = DataPaths.data();
            Path probe = dataDir.resolve("netcheck.tmp");
            Files.writeString(probe, "ok", StandardCharsets.UTF_8);
            Files.deleteIfExists(probe);
            writeResult = "通过";
        } catch (Throwable t) {
            writeResult = "失败：" + msg(t);
        }
        sb.append("[1/4] 数据目录可写：").append(writeResult)
          .append(dataDir == null ? "" : "  (" + dataDir + ")").append('\n');

        // ② 网易云接口可达
        long t0 = System.currentTimeMillis();
        String netResult;
        try {
            List<Dto.Song> songs = NeteaseApi.search(PROBE_KEYWORD, 3);
            long cost = System.currentTimeMillis() - t0;
            netOk = songs != null && !songs.isEmpty();
            if (netOk) {
                Dto.Song first = songs.get(0);
                netResult = "通过（" + cost + "ms，命中 " + songs.size() + " 条，首位 "
                        + first.artists() + " - " + first.name() + "）";
            } else {
                netResult = "无结果（" + cost + "ms）—— 接口可达但返回空，可能被风控";
            }
        } catch (Throwable t) {
            netResult = "失败（" + (System.currentTimeMillis() - t0) + "ms）：" + msg(t);
        }
        sb.append("[2/4] 网易云接口：").append(netResult).append('\n');

        // ③ 登录态
        String login;
        try {
            login = NeteaseApi.isLoggedIn() ? "已登录（搜索与在线取流都可用）" : "未登录（免登录接口仍可用）";
        } catch (Throwable t) {
            login = "检测失败：" + msg(t);
        }
        sb.append("[3/4] 登录态：").append(login).append('\n');

        // ④ 插件本地状态（0.5.0 瘦身版：歌词/匹配缓存随功能删除，这里只剩凭据与音质档位）
        String local;
        try {
            local = (CookieVault.exists() ? "已保存登录凭据（启动可自动恢复）" : "未保存登录凭据")
                    + "；在线播放音质档位=" + PluginConfig.audioLevel()
                    + "；启动时自动登录=" + (PluginConfig.autoLogin() ? "开" : "关")
                    + "；登录手机号=" + (PluginConfig.phone().isBlank() ? "未填" : "已填");
        } catch (Throwable t) {
            local = "读取失败：" + msg(t);
        }
        sb.append("[4/4] 插件本地状态：").append(local).append('\n');

        sb.append("结论：").append(netOk ? "网络可用" : "网络异常（搜索与在线取流会失败）");
        return sb.toString();
    }

    /** 报告 → 单行 toast 摘要。 */
    public static String summaryLine(String report) {
        if (report == null || report.isBlank()) {
            return "网络自检完成";
        }
        String tail = report.trim();
        int idx = tail.lastIndexOf("结论：");
        if (idx >= 0) {
            tail = tail.substring(idx);
        }
        for (String line : report.split("\n")) {
            if (line.startsWith("[2/4]")) {
                String body = line.substring(5).trim();
                if (body.length() > 60) {
                    body = body.substring(0, 60) + "…";
                }
                return "网络自检：" + body + " / " + tail.replace("结论：", "");
            }
        }
        return "网络自检：" + tail;
    }

    private static String msg(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isBlank()) {
            m = t.getClass().getSimpleName();
        }
        return String.format(Locale.ROOT, "%s", m);
    }
}
