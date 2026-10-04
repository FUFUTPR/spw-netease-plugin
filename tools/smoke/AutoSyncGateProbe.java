import com.example.netease.core.PluginLog;
import com.example.netease.svc.NativeLibrary;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动同步闸（P2 交付物④）离线探针：验证 {@link NativeLibrary} 上那四个静态入口的语义。
 *
 * <p>被测面（Lead 批准的名字与语义）：</p>
 * <ul>
 *   <li>{@code autoSyncBegin(reason)} —— 取闸；被挡时<b>必须 INFO 说明原因</b>（在飞 / 还差 N 秒），
 *       不许静默 return false；{@code reason} 以 {@code force:} 开头才跳过 30 分钟间隔闸；</li>
 *   <li>{@code autoSyncEnd(summary)} —— 放闸；{@code null} 不许炸，异常路径也必须能被调到；</li>
 *   <li>{@code autoSyncNextDueMs()} —— 下次最早可跑时刻 = 上次放闸时刻 + 30 分钟；</li>
 *   <li>{@code autoSyncState()} —— 一行状态摘要。</li>
 * </ul>
 *
 * <p>用法（不碰宿主、不碰曲库，纯内存状态机）：</p>
 * <pre>
 * javac --release 21 -encoding UTF-8 -nowarn -d build\probe-classes ^
 *     -cp "build\classes" tools\smoke\AutoSyncGateProbe.java
 * java "-Dfile.encoding=UTF-8" -cp "build\probe-classes;build\classes" AutoSyncGateProbe
 * </pre>
 */
public final class AutoSyncGateProbe {

    private static final List<String> FAILS = new ArrayList<>();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private AutoSyncGateProbe() {
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Paths.get("build", "probe", "p2-gate", "logs"));
        PluginLog.init(Paths.get("build", "probe", "p2-gate", "logs"));
        PluginLog.setLevel("debug");

        System.out.println("=== P2 自动同步闸探针 AutoSyncGateProbe ===");
        System.out.println("间隔常量 AUTO_SYNC_INTERVAL_MS = " + NativeLibrary.AUTO_SYNC_INTERVAL_MS
                + " ms（" + (NativeLibrary.AUTO_SYNC_INTERVAL_MS / 60_000L) + " 分钟）");
        System.out.println("[0] 初始状态：" + NativeLibrary.autoSyncState());
        check("0: 初始「下次最早」= 立刻可跑（无上次记录）", NativeLibrary.autoSyncNextDueMs() == 0L);
        System.out.println();

        System.out.println("[1] 登录成功后的第一轮：autoSyncBegin(\"登录成功\")");
        boolean b1 = NativeLibrary.autoSyncBegin("登录成功");
        System.out.println("    返回 = " + b1 + "；状态：" + NativeLibrary.autoSyncState());
        check("1: 首轮取闸成功", b1);
        System.out.println();

        System.out.println("[2] 同一时刻再来一轮（模拟 30 分钟定时器撞上在飞的一轮）");
        boolean b2 = NativeLibrary.autoSyncBegin("30 分钟定时器");
        System.out.println("    返回 = " + b2 + "（期望 false，且日志里必须有「已有同步在飞」）");
        check("2: 在飞时取闸失败（不并发两轮）", !b2);
        System.out.println();

        System.out.println("[3] 第一轮收口：autoSyncEnd(结果摘要)");
        NativeLibrary.autoSyncEnd("歌单 4 个 / 曲目 3267 首 / 关联 5935 行（探针）");
        long endMs = System.currentTimeMillis();
        long due = NativeLibrary.autoSyncNextDueMs();
        System.out.println("    状态：" + NativeLibrary.autoSyncState());
        System.out.println("    autoSyncNextDueMs() = " + due + "；距放闸 " + (due - endMs) + " ms（期望 ≈ "
                + NativeLibrary.AUTO_SYNC_INTERVAL_MS + "）");
        check("3: 放闸后在飞=否", NativeLibrary.autoSyncState().contains("在飞=否"));
        check("3: 下次最早 = 放闸时刻 + 30 分钟（误差 ≤ 300ms）",
                Math.abs((due - endMs) - NativeLibrary.AUTO_SYNC_INTERVAL_MS) <= 300L);
        System.out.println();

        System.out.println("[4] 间隔没到就再取闸（30 分钟定时器早触发）");
        boolean b3 = NativeLibrary.autoSyncBegin("30 分钟定时器");
        System.out.println("    返回 = " + b3 + "（期望 false，且日志里必须有「还差 N 秒」）");
        check("4: 间隔未到时取闸失败（不偷跑）", !b3);
        check("4: 取闸失败后没有把在飞标志留在原地（后面 force 还能取到）",
                NativeLibrary.autoSyncState().contains("在飞=否"));
        System.out.println();

        System.out.println("[5] 配置页「立即重新同步」：autoSyncBegin(\"force:手动\")");
        boolean b4 = NativeLibrary.autoSyncBegin("force:手动");
        System.out.println("    返回 = " + b4 + "（期望 true：force: 跳过间隔闸）");
        check("5: force: 前缀能跳过 30 分钟间隔闸", b4);
        System.out.println();

        System.out.println("[6] in-flight 闸对 force 同样有效");
        boolean b5 = NativeLibrary.autoSyncBegin("force:并发的那一轮");
        System.out.println("    返回 = " + b5 + "（期望 false：force 只绕间隔闸，不绕在飞闸）");
        check("6: force 也不能绕过在飞闸", !b5);
        System.out.println();

        System.out.println("[7] 异常路径收口：autoSyncEnd(null) 不许炸");
        String before = NativeLibrary.autoSyncState();
        boolean threw = false;
        try {
            NativeLibrary.autoSyncEnd(null);
        } catch (Throwable t) {
            threw = true;
            System.out.println("    !! 抛了：" + t);
        }
        String after = NativeLibrary.autoSyncState();
        System.out.println("    收口前：" + before);
        System.out.println("    收口后：" + after);
        check("7: autoSyncEnd(null) 不抛异常", !threw);
        check("7: null 摘要被记成占位文案", after.contains("（本轮未给出结果摘要）"));
        check("7: 收口后在飞=否", after.contains("在飞=否"));
        System.out.println();

        System.out.println("[8] 重复收口（end 被调两次）不许把状态机搞坏");
        NativeLibrary.autoSyncEnd("重复收口");
        System.out.println("    状态：" + NativeLibrary.autoSyncState());
        check("8: 重复收口后在飞仍=否", NativeLibrary.autoSyncState().contains("在飞=否"));
        System.out.println();

        System.out.println("[9] 收口后非 force 的下一轮仍受限、force 仍可行");
        boolean b6 = NativeLibrary.autoSyncBegin("定时器-非force");
        boolean b7 = NativeLibrary.autoSyncBegin("force:立即");
        System.out.println("    非 force 返回 = " + b6 + "（期望 false）；force 返回 = " + b7 + "（期望 true）");
        check("9: 非 force 仍受间隔限制", !b6);
        check("9: force 仍可立即跑", b7);
        NativeLibrary.autoSyncEnd("force 一轮结束");
        System.out.println();

        System.out.println("=== 汇总 ===");
        if (FAILS.isEmpty()) {
            System.out.println("PASS：自动同步闸 4 个静态入口语义全部符合预期"
                    + "（首轮可取 / 在飞挡 / 间隔挡 / force 绕间隔不绕在飞 / end(null) 不炸）");
            System.out.println("日志（含 INFO 说明与 WARN 重复收口的话术）："
                    + Paths.get("build", "probe", "p2-gate", "logs").toAbsolutePath());
        } else {
            System.out.println("FAIL：");
            for (String f : FAILS) {
                System.out.println("  - " + f);
            }
        }
        System.exit(FAILS.isEmpty() ? 0 : 1);
    }

    private static void check(String what, boolean ok) {
        System.out.println("    " + (ok ? "[OK]" : "[!!]") + " " + what);
        if (!ok) {
            FAILS.add(what);
        }
    }
}
