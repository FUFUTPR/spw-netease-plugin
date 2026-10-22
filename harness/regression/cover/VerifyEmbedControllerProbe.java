package com.example.netease.ui.controller;

import com.example.netease.ui.controller.VerifyEmbedController.Phase;
import com.example.netease.ui.controller.VerifyEmbedController.State;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 批次 4 探针（契约 §4）：用 {@link FakeVerifyHost} 替代真助手，在真 JVM 里把
 * {@link VerifyEmbedController} 的启动 / 就绪 / 失败映射 / rect 换算 / 显隐 / retry / epoch /
 * 迟到回调 / 有界关闭逐条断言一遍。
 *
 * <p>运行：{@code java -cp "<编译输出>;build/plugin-classes" com.example.netease.ui.controller.VerifyEmbedControllerProbe}；
 * 全过打印 {@code VERIFY_EMBED_RESULT=<n> PASS} 并 exit 0，否则打印 FAIL 说明并 exit 1。
 * 断言按契约分组 A-J，共 100+ 条。</p>
 */
public final class VerifyEmbedControllerProbe {

    private static final String URL = "https://probe.invalid/verify?token=PROBE_URL_SECRET";
    private static final String COOKIE = "MUSIC_U=PROBE_COOKIE_SECRET";
    private static final String URL_SECRET = "PROBE_URL_SECRET";
    private static final String COOKIE_SECRET = "PROBE_COOKIE_SECRET";
    private static final Pattern SESSION = Pattern.compile("[0-9a-f]{8}");

    private static int passed;
    private static int failed;
    private static String group = "";
    private static final AtomicInteger LOG_SEQ = new AtomicInteger();
    private static Path logRoot;

    private VerifyEmbedControllerProbe() {
    }

    public static void main(String[] args) {
        logRoot = Path.of("build", "_tmp", "verify-probe-logs");
        try {
            Files.createDirectories(logRoot);
        } catch (IOException ignored) {
            // 日志目录建不出来时探针照样跑，Fake 自己也会建
        }
        runAll();
        int total = passed + failed;
        if (failed == 0 && total >= 45) {
            System.out.println("VERIFY_EMBED_RESULT=" + total + " PASS");
            System.exit(0);
        } else if (failed == 0) {
            System.out.println("FAIL 断言数量不足（" + total + " < 45）");
            System.exit(1);
        } else {
            System.out.println("FAIL " + failed + " 条断言未通过（共 " + total + " 条）");
            System.out.println("VERIFY_EMBED_RESULT=" + total + " FAIL");
            System.exit(1);
        }
    }

    private static void runAll() {
        group("A 构造/初始态", () -> groupA(logRoot));
        group("B open 正常", () -> groupB(logRoot));
        group("C 幂等/关闭", () -> groupC(logRoot));
        group("D rect 换算与去重", () -> groupD(logRoot));
        group("E 可见性", () -> groupE(logRoot));
        group("F 失败映射", () -> groupF(logRoot));
        group("G retry", () -> groupG(logRoot));
        group("H epoch", () -> groupH(logRoot));
        group("I 迟到 cookie 回调", () -> groupI(logRoot));
        group("J 清理", () -> groupJ(logRoot));
    }

    // ---------------------------------------------------------------- A 构造/初始态

    private static void groupA(Path root) {
        Env env = new Env("normal");
        try {
            State s = env.c.state();
            checkEquals("初始 phase=IDLE", Phase.IDLE, s.phase());
            checkEquals("初始 message 为空串", "", s.message());
            checkEquals("初始 sessionId 为空串", "", s.sessionId());
            check("初始 isOpen=false", !env.c.isOpen());
            check("初始 width=0", s.width() == 0);
            check("初始 height=0", s.height() == 0);
            check("初始 embedded=false", !s.embedded());
            check("初始 retryable=false", !s.retryable());
            check("未 open 时没有状态通知", env.rec.snapshot().isEmpty());

            env.c.retry();
            checkEquals("未 open 直接 retry ⇒ FAILED", Phase.FAILED, env.c.state().phase());
            checkEquals("未 open retry 文案", VerifyEmbedController.TEXT_URL_EMPTY, env.c.state().message());
            check("未 open retry 不启动进程", env.launcher.count() == 0);

            env.c.open("", "扫码", "", 300, 200, 1.25);
            checkEquals("空 url open ⇒ FAILED", Phase.FAILED, env.c.state().phase());
            checkEquals("空 url open 文案", VerifyEmbedController.TEXT_URL_EMPTY, env.c.state().message());
            check("空 url open 不启动进程", env.launcher.count() == 0);
        } finally {
            env.dispose();
        }
    }

    // ---------------------------------------------------------------- B open 正常

    private static void groupB(Path root) {
        Env env = new Env("normal");
        env.host.cookie = COOKIE;
        try {
            env.c.open(URL, "扫码", "", 300, 200, 1.25);
            check("open 后 isOpen=true", env.c.isOpen());
            check("open 先发 STARTING", env.rec.saw(Phase.STARTING));
            check("STARTING 文案正确", sawState(env.rec, Phase.STARTING, VerifyEmbedController.TEXT_STARTING));

            State ready = awaitPhase(env.rec, Phase.READY, 8_000);
            check("助手 ready ⇒ READY", ready != null);
            State s = env.c.state();
            checkEquals("READY 文案", VerifyEmbedController.TEXT_READY, s.message());
            check("READY 后 embedded=true", s.embedded());
            check("READY 后 retryable=false", !s.retryable());
            check("READY 后 isOpen=true", env.c.isOpen());
            check("sessionId 为 8 位小写十六进制", SESSION.matcher(s.sessionId()).matches());
            check("STARTING 通知早于 READY",
                    firstPhaseIndex(env.rec, Phase.STARTING) < firstPhaseIndex(env.rec, Phase.READY));

            check("启动次数=1", env.launcher.count() == 1);
            if (env.launcher.count() == 1) {
                List<String> a = env.launcher.args(0);
                checkEquals("参数[0]=--url", "--url", at(a, 0));
                checkEquals("参数[1]=url", URL, at(a, 1));
                checkEquals("参数[2]=--pid", "--pid", at(a, 2));
                checkEquals("参数[3]=JVM pid", String.valueOf(ProcessHandle.current().pid()), at(a, 3));
                checkEquals("参数[4]=--session", "--session", at(a, 4));
                checkEquals("参数[5]=状态 sid", s.sessionId(), at(a, 5));
                checkEquals("参数[6]=--parent-class", "--parent-class", at(a, 6));
                checkEquals("参数[7]=SunAwtFrame", "SunAwtFrame", at(a, 7));
                checkEquals("参数[8]=--expect-client", "--expect-client", at(a, 8));
                checkEquals("参数[9]=375x250", "375x250", at(a, 9));
                checkEquals("参数[10]=--cookie", "--cookie", at(a, 10));
                checkEquals("参数[11]=cookie 值", COOKIE, at(a, 11));
                check("参数含 --data-dir 且值非空", nonEmptyValueAfter(a, "--data-dir"));
                checkEquals("参数 --timeout-ms=15000", "15000", valueAfter(a, "--timeout-ms"));
                checkEquals("参数 --bg=262626", "262626", valueAfter(a, "--bg"));
                check("空标题时不发 --parent-title", !a.contains("--parent-title"));
            }

            check("WebView2 日志只记长度",
                    await(() -> env.host.hasLog("i|WebView2 运行时长度=9"), 3_000));
            check("cookie-in 日志按项数记录",
                    await(() -> env.host.hasLog("i|登录 cookie 已灌进验证页：3 项"), 3_000));
            check("cookies 头交给 host.mergeCookies",
                    await(() -> env.host.merged.contains(COOKIE), 3_000));
            check("日志不含 cookie 值", !anyLogContains(env.host, COOKIE_SECRET));
            check("日志不含 url 原文", !anyLogContains(env.host, URL_SECRET));
            check("日志不含 stdout 整行原文",
                    !anyLogContains(env.host, "verify-host: ")
                            && !anyLogContains(env.host, "adopted rect=")
                            && !anyLogContains(env.host, "client w="));

            int launchesBefore = env.launcher.count();
            int notificationsBefore = env.rec.snapshot().size();
            env.c.open(URL, "扫码", "", 300, 200, 1.25);
            sleep(200);
            check("同一 url 重复 open 不重开", env.launcher.count() == launchesBefore);
            check("同一 url 重复 open 不重复 notify", env.rec.snapshot().size() == notificationsBefore);
            check("重复 open 后仍是 READY", env.c.state().phase() == Phase.READY);
        } finally {
            env.dispose();
        }

        Env titled = new Env("normal");
        titled.host.cookie = COOKIE;
        try {
            titled.c.open(URL, "扫码", "网易云音乐主窗口", 300, 200, 1.25);
            check("带标题也能 READY", awaitPhase(titled.rec, Phase.READY, 8_000) != null);
            if (titled.launcher.count() == 1) {
                List<String> a = titled.launcher.args(0);
                int pc = a.indexOf("--parent-class");
                checkEquals("--parent-title 紧跟 parent-class 块", "--parent-title", at(a, pc + 2));
                checkEquals("--parent-title 值", "网易云音乐主窗口", at(a, pc + 3));
                checkEquals("--expect-client 在标题之后", "--expect-client", at(a, pc + 4));
            }
        } finally {
            titled.dispose();
        }
    }

    // ---------------------------------------------------------------- C 幂等/关闭

    private static void groupC(Path root) {
        Env env = new Env("normal");
        env.host.cookie = COOKIE;
        try {
            env.c.open(URL, "扫码", "", 300, 200, 1.25);
            check("清理用例先 READY", awaitPhase(env.rec, Phase.READY, 8_000) != null);
            long t0 = System.currentTimeMillis();
            env.c.close();
            long cost = System.currentTimeMillis() - t0;

            State s = env.c.state();
            checkEquals("close 后 phase=CLOSED", Phase.CLOSED, s.phase());
            checkEquals("close 后文案", VerifyEmbedController.TEXT_CLOSED, s.message());
            check("close 后 isOpen=false", !env.c.isOpen());
            check("close 后 embedded=false", !s.embedded());
            check("CLOSED 只通知一次（首次 close）", env.rec.count(Phase.CLOSED) == 1);
            check("close 耗时有界", cost < 2_000, "cost=" + cost);
            check("Fake 收到 close 命令", awaitLine(env.launcher.logPath, "close", 2_000));
            check("Fake 进程 2 秒内退出",
                    env.launcher.count() >= 1 && await(() -> !env.launcher.process(0).isAlive(), 2_000));

            int statesAfter = env.rec.snapshot().size();
            int linesAfter = env.lines().size();
            boolean threw = false;
            try {
                env.c.close();
            } catch (Throwable t) {
                threw = true;
            }
            check("重复 close 不抛异常", !threw);
            sleep(200);
            check("重复 close 不再通知 CLOSED", env.rec.count(Phase.CLOSED) == 1);
            check("重复 close 通知数不变", env.rec.snapshot().size() == statesAfter);
            check("重复 close 不写进程", env.lines().size() == linesAfter);

            env.c.setBounds(10, 20, 300, 200, 1.25);
            env.c.setVisible(false);
            sleep(250);
            check("关闭后 setBounds/setVisible 不写进程", env.lines().size() == linesAfter);
            check("关闭后 setBounds/setVisible 不 notify", env.rec.snapshot().size() == statesAfter);
        } finally {
            env.dispose();
        }
    }

    // ---------------------------------------------------------------- D rect 换算 / 去重

    private static void groupD(Path root) {
        Env env = new Env("normal");
        env.host.cookie = COOKIE;
        try {
            env.c.open(URL, "扫码", "", 300, 200, 1.25);
            check("D 用例 READY", awaitPhase(env.rec, Phase.READY, 8_000) != null);
            env.c.setBounds(10, 20, 300, 200, 1.25);
            check("1.25 缩放 rect 换算", awaitLine(env.launcher.logPath, "rect 13 25 375 250", 2_000));
            check("首次 rect 只发一次", env.rectLines().size() == 1);
            State s = env.c.state();
            check("state 宽=300", s.width() == 300);
            check("state 高=200", s.height() == 200);
            env.c.setBounds(10, 20, 300, 200, 1.25);
            sleep(200);
            check("相同 rect 不重复发", env.rectLines().size() == 1);
            env.c.setBounds(20, 30, 400, 300, 1.25);
            check("新 rect 换算", awaitLine(env.launcher.logPath, "rect 25 38 500 375", 2_000));
            check("第二个 rect 只发一次", env.rectLines().size() == 2);
        } finally {
            env.dispose();
        }

        Env corrected = new Env("client-correct");
        corrected.host.cookie = COOKIE;
        try {
            corrected.c.open(URL, "扫码", "", 300, 200, 1.25);
            check("client 校正日志出现", await(() -> corrected.host.anyLogContains("缩放校正"), 5_000));
            corrected.c.setBounds(10, 20, 300, 200, 1.0);
            check("校正后按 1.0 换算", awaitLine(corrected.launcher.logPath, "rect 10 20 300 200", 2_000));
            check("校正后不再用旧 1.25", !corrected.lines().contains("rect 13 25 375 250"));
        } finally {
            corrected.dispose();
        }

        Env delayed = new Env("delayed-adopt");
        delayed.host.cookie = COOKIE;
        try {
            delayed.c.open(URL, "扫码", "", 300, 200, 1.25);
            delayed.c.setBounds(1, 2, 100, 100, 1.25);
            delayed.c.setBounds(10, 20, 300, 200, 1.25);
            sleep(250);
            check("adopted 之前只缓存不发 rect", delayed.rectLines().isEmpty());
            check("延迟 adopted 后 READY", awaitPhase(delayed.rec, Phase.READY, 8_000) != null);
            check("adopted 后补发最新 rect", awaitLine(delayed.launcher.logPath, "rect 13 25 375 250", 2_000));
            check("adopted 后只补发一次", delayed.rectLines().size() == 1);
            check("被覆盖的旧 rect 不发", !delayed.lines().contains("rect 1 3 125 125"));
        } finally {
            delayed.dispose();
        }
    }

    // ---------------------------------------------------------------- E 可见性

    private static void groupE(Path root) {
        Env env = new Env("normal");
        env.host.cookie = COOKIE;
        try {
            env.c.open(URL, "扫码", "", 300, 200, 1.25);
            check("E 用例 READY", awaitPhase(env.rec, Phase.READY, 8_000) != null);
            check("adopted 后自动 show", awaitLine(env.launcher.logPath, "show", 2_000));
            check("show 只发一次", env.lineCount("show") == 1);

            env.c.setVisible(false);
            check("setVisible(false) 发 hide", awaitLine(env.launcher.logPath, "hide", 2_000));
            check("hide 只发一次", env.lineCount("hide") == 1);
            env.c.setVisible(false);
            sleep(200);
            check("重复 hide 不重发", env.lineCount("hide") == 1);

            env.c.setVisible(true);
            check("setVisible(true) 再发 show", awaitLine(env.launcher.logPath, "show", 2_000));
            sleep(100);
            check("show 累计两次", env.lineCount("show") == 2);
            env.c.setVisible(true);
            sleep(200);
            check("重复 show 不重发", env.lineCount("show") == 2);
        } finally {
            env.dispose();
        }

        Env delayed = new Env("delayed-adopt");
        delayed.host.cookie = COOKIE;
        try {
            delayed.c.open(URL, "扫码", "", 300, 200, 1.25);
            delayed.c.setVisible(false);
            sleep(250);
            check("adopted 之前不发 hide", delayed.lineCount("hide") == 0);
            check("adopted 之前不发 show", delayed.lineCount("show") == 0);
            check("延迟 adopted 后 READY", awaitPhase(delayed.rec, Phase.READY, 8_000) != null);
            check("adopted 后按期望补发 hide", awaitLine(delayed.launcher.logPath, "hide", 2_000));
            check("adopted 后不补发 show", delayed.lineCount("show") == 0);
            check("adopted 后 hide 只发一次", delayed.lineCount("hide") == 1);
            delayed.c.setVisible(false);
            sleep(200);
            check("重复期望不重发 hide", delayed.lineCount("hide") == 1);
        } finally {
            delayed.dispose();
        }
    }

    // ---------------------------------------------------------------- F 失败映射

    private static void groupF(Path root) {
        assertFailure("nav-fail", "验证页加载失败（错误码 -100），可在页面内重试", true, false);
        assertFailure("parent-timeout", VerifyEmbedController.TEXT_PARENT_TIMEOUT, true, false);
        assertFailure("parent-ambiguous", "宿主主窗口匹配到 2 个（要求唯一），已拒绝挂载", true, false);
        assertFailure("parent-invalid", "宿主主窗口身份核对失败：title-mismatch，已拒绝挂载", true, false);
        assertFailure("parent-client-mismatch",
                "宿主窗口尺寸核对失败（got 400x300，expect 375x250），已拒绝挂载", true, false);
        assertFailure("fail", "验证组件失败：webview2-missing", true, false);
        assertFailure("crash-before-ready", "验证组件提前退出（退出码 3）", true, false);
        assertFailure("exit-after-ready", "验证组件已退出（退出码 4），可重试", true, true);
        assertFailure("parent-gone", VerifyEmbedController.TEXT_PARENT_GONE, false, true);

        Env wc = new Env("window-close-requested");
        wc.host.cookie = COOKIE;
        try {
            wc.c.open(URL, "扫码", "", 300, 200, 1.25);
            check("window-close-requested 先 READY", awaitPhase(wc.rec, Phase.READY, 8_000) != null);
            check("window-close-requested ⇒ CLOSED",
                    await(() -> wc.c.state().phase() == Phase.CLOSED, 5_000));
            checkEquals("window-close-requested 文案", VerifyEmbedController.TEXT_CLOSED, wc.c.state().message());
            check("window-close-requested 只通知一次 CLOSED", wc.rec.count(Phase.CLOSED) == 1);
            check("window-close-requested 后进程退出",
                    wc.launcher.count() >= 1 && await(() -> !wc.launcher.process(0).isAlive(), 2_000));
        } finally {
            wc.dispose();
        }

        Env ws = new Env("wrong-session");
        ws.host.cookie = COOKIE;
        try {
            ws.c.open(URL, "扫码", "", 300, 200, 1.25);
            check("wrong-session 仍 READY", awaitPhase(ws.rec, Phase.READY, 8_000) != null);
            check("wrong-session 的 start 行被丢弃并告警",
                    await(() -> ws.host.anyLogContains("会话 id 不符"), 3_000));
            check("wrong-session 不落 FAILED", ws.c.state().phase() == Phase.READY);
        } finally {
            ws.dispose();
        }
    }

    private static void assertFailure(String scenario, String expectedMessage, boolean expectedRetryable,
                                      boolean readyFirst) {
        Env env = new Env(scenario);
        env.host.cookie = COOKIE;
        try {
            env.c.open(URL, "扫码", "", 300, 200, 1.25);
            if (readyFirst) {
                check(scenario + " 先 READY", awaitPhase(env.rec, Phase.READY, 8_000) != null);
            }
            check(scenario + " 最终 FAILED",
                    await(() -> env.c.state().phase() == Phase.FAILED, 8_000));
            checkEquals(scenario + " 文案", expectedMessage, env.c.state().message());
            checkEquals(scenario + " retryable", expectedRetryable, env.c.state().retryable());
            check(scenario + " isOpen=false", !env.c.isOpen());
        } finally {
            env.dispose();
        }
    }

    // ---------------------------------------------------------------- G retry

    private static void groupG(Path root) {
        Env env = new Env("nav-fail");
        env.host.cookie = COOKIE;
        try {
            env.c.open(URL, "扫码", "", 300, 200, 1.25);
            check("G 旧会话先 FAILED", await(() -> env.c.state().phase() == Phase.FAILED, 8_000));
            String oldSid = env.c.state().sessionId();
            check("旧 sessionId 合法", SESSION.matcher(oldSid).matches());

            env.launcher.scenario = "normal";
            env.c.retry();
            State afterRetry = env.c.state();
            check("retry 换新 sessionId", !oldSid.equals(afterRetry.sessionId()));
            check("retry 后 sid 合法", SESSION.matcher(afterRetry.sessionId()).matches());
            check("retry 后立即回 STARTING/READY",
                    afterRetry.phase() == Phase.STARTING || afterRetry.phase() == Phase.READY);
            check("retry 后最终 READY", awaitPhase(env.rec, Phase.READY, 8_000) != null);
            check("retry 起了第二个进程", env.launcher.count() == 2);
            check("旧 Fake 进程退出",
                    env.launcher.count() >= 1 && await(() -> !env.launcher.process(0).isAlive(), 2_000));
            if (env.launcher.count() == 2) {
                List<String> a2 = env.launcher.args(1);
                checkEquals("retry 复用 url", URL, valueAfter(a2, "--url"));
                checkEquals("retry 的 --session 是新 sid",
                        env.c.state().sessionId(), valueAfter(a2, "--session"));
            }
        } finally {
            env.dispose();
        }

        Env closed = new Env("normal");
        closed.host.cookie = COOKIE;
        try {
            closed.c.open(URL, "扫码", "", 300, 200, 1.25);
            check("关闭后 retry：先 READY", awaitPhase(closed.rec, Phase.READY, 8_000) != null);
            String oldSid = closed.c.state().sessionId();
            closed.c.close();
            closed.c.retry();
            check("关闭后 retry 换 sid", !oldSid.equals(closed.c.state().sessionId()));
            check("关闭后 retry 再 READY", awaitPhase(closed.rec, Phase.READY, 8_000) != null);
            check("关闭后 retry 起第二个进程", closed.launcher.count() == 2);
        } finally {
            closed.dispose();
        }

        Env fresh = new Env("normal");
        try {
            fresh.c.retry();
            check("未 open 的 retry 不启动进程", fresh.launcher.count() == 0);
            checkEquals("未 open 的 retry ⇒ FAILED", Phase.FAILED, fresh.c.state().phase());
        } finally {
            fresh.dispose();
        }
    }

    // ---------------------------------------------------------------- H epoch

    private static void groupH(Path root) {
        Env env = new Env("epoch-change");
        env.host.cookie = COOKIE;
        try {
            env.c.open(URL, "扫码", "", 300, 200, 1.25);
            check("H 用例 READY", awaitPhase(env.rec, Phase.READY, 8_000) != null);
            State before = env.c.state();
            int mergedBefore = env.host.merged.size();
            env.host.epoch = 99L;
            check("epoch 变化后助手输出被丢弃",
                    await(() -> env.host.anyLogContains("账号纪元已变化"), 4_000));
            sleep(2_200);
            check("epoch 变化后 cookies 不回灌", env.host.merged.size() == mergedBefore);
            State after = env.c.state();
            check("epoch 变化后 phase 不变", before.phase() == after.phase());
            check("epoch 变化后 message 不变", before.message().equals(after.message()));
            check("epoch 变化后 embedded 不变", before.embedded() == after.embedded());
        } finally {
            env.dispose();
        }
    }

    // ---------------------------------------------------------------- I 迟到 cookie 回调

    private static void groupI(Path root) {
        Env env = new Env("normal");
        env.host.autoCookie = false;
        try {
            long t0 = System.currentTimeMillis();
            env.c.open(URL, "扫码", "", 300, 200, 1.25);
            long cost = System.currentTimeMillis() - t0;
            check("等 cookie 超时后放行", cost >= 1_200, "cost=" + cost);
            check("超时后仍启动一次助手", env.launcher.count() == 1);
            check("超时启动参数不含 --cookie",
                    env.launcher.count() == 1 && !env.launcher.args(0).contains("--cookie"));
            check("cookie 回调只请求一次", env.host.cookieRequests.get() == 1);
            check("回调已被暂存", env.host.storedCallback != null);

            env.c.close();
            check("关闭后 state=CLOSED", env.c.state().phase() == Phase.CLOSED);
            if (env.host.storedCallback != null) {
                env.host.storedCallback.accept("MUSIC_U=late-secret");
            }
            sleep(300);
            check("迟到的回调不再启动进程", env.launcher.count() == 1);
            check("迟到的回调不改状态", env.c.state().phase() == Phase.CLOSED);
            check("迟到的回调不回灌 cookie", env.host.merged.isEmpty());
        } finally {
            env.dispose();
        }
    }

    // ---------------------------------------------------------------- J 清理

    private static void groupJ(Path root) {
        Env env = new Env("normal");
        env.host.cookie = COOKIE;
        try {
            env.c.open(URL, "扫码", "", 300, 200, 1.25);
            check("J 用例 READY", awaitPhase(env.rec, Phase.READY, 8_000) != null);
            long t0 = System.currentTimeMillis();
            env.c.close();
            env.c.close();
            long cost = System.currentTimeMillis() - t0;
            check("close 有界（<2000ms）", cost < 2_000, "cost=" + cost);
            check("close 后 Fake 进程 2 秒内退出",
                    env.launcher.count() == 1 && await(() -> !env.launcher.process(0).isAlive(), 2_000));
            check("Fake 只收到一次 close", env.lineCount("close") == 1);

            List<String> linesNow = new ArrayList<>(env.lines());
            int statesNow = env.rec.snapshot().size();
            sleep(300);
            check("关闭后不再产生进程输出", env.lines().equals(linesNow));
            check("关闭后不再产生状态通知", env.rec.snapshot().size() == statesNow);
        } finally {
            env.dispose();
        }
    }

    // ---------------------------------------------------------------- 环境 / 工具

    private static final class Env {
        final FakeHost host = new FakeHost();
        final Recorder rec = new Recorder();
        final ProbeLauncher launcher;
        final VerifyEmbedController c;

        Env(String scenario) {
            this.launcher = new ProbeLauncher(logRoot, scenario);
            this.c = new VerifyEmbedController(rec, launcher, host);
        }

        void dispose() {
            try {
                c.close();
            } catch (Throwable ignored) {
                // 探针收尾不抛
            }
            for (Process p : launcher.processesCopy()) {
                try {
                    if (p.isAlive()) {
                        p.destroyForcibly();
                    }
                } catch (Throwable ignored) {
                    // 已经退出
                }
            }
        }

        List<String> lines() {
            return readLines(launcher.logPath);
        }

        List<String> rectLines() {
            List<String> out = new ArrayList<>();
            for (String line : lines()) {
                if (line.startsWith("rect ")) {
                    out.add(line);
                }
            }
            return out;
        }

        int lineCount(String exact) {
            int n = 0;
            for (String line : lines()) {
                if (line.equals(exact)) {
                    n++;
                }
            }
            return n;
        }
    }

    private static final class FakeHost implements VerifyEmbedController.Host {
        final List<String> logs = Collections.synchronizedList(new ArrayList<>());
        final List<String> merged = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger cookieRequests = new AtomicInteger();
        volatile long epoch = 42L;
        volatile boolean autoCookie = true;
        volatile String cookie = "";
        volatile Consumer<String> storedCallback;

        @Override
        public void requestCookies(Consumer<String> callback) {
            cookieRequests.incrementAndGet();
            if (!autoCookie) {
                storedCallback = callback;
                return;
            }
            callback.accept(cookie);
        }

        @Override
        public void mergeCookies(String header) {
            merged.add(header);
        }

        @Override
        public long accountEpoch() {
            return epoch;
        }

        @Override
        public void log(String level, String message) {
            logs.add(level + "|" + message);
        }

        List<String> logsCopy() {
            synchronized (logs) {
                return new ArrayList<>(logs);
            }
        }

        boolean hasLog(String exact) {
            return logsCopy().contains(exact);
        }

        boolean anyLogContains(String needle) {
            for (String line : logsCopy()) {
                if (line.contains(needle)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Recorder implements VerifyEmbedController.Listener {
        final List<State> states = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onStateChanged(State state) {
            states.add(state);
        }

        List<State> snapshot() {
            synchronized (states) {
                return new ArrayList<>(states);
            }
        }

        boolean saw(Phase phase) {
            for (State s : snapshot()) {
                if (s.phase() == phase) {
                    return true;
                }
            }
            return false;
        }

        long count(Phase phase) {
            long n = 0;
            for (State s : snapshot()) {
                if (s.phase() == phase) {
                    n++;
                }
            }
            return n;
        }
    }

    private static final class ProbeLauncher implements VerifyEmbedController.Launcher {
        final String javaExe;
        final String classPath;
        final Path logPath;
        final List<Process> processes = Collections.synchronizedList(new ArrayList<>());
        final List<List<String>> launches = Collections.synchronizedList(new ArrayList<>());
        final List<String> exePaths = Collections.synchronizedList(new ArrayList<>());
        volatile String scenario;

        ProbeLauncher(Path root, String scenario) {
            this.scenario = scenario;
            this.logPath = root.resolve(String.format("%03d-%s.log", LOG_SEQ.incrementAndGet(), scenario));
            this.javaExe = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
            this.classPath = System.getProperty("java.class.path");
        }

        @Override
        public Process start(String exePath, List<String> args) throws IOException {
            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe);
            cmd.add("-cp");
            cmd.add(classPath);
            cmd.add("com.example.netease.ui.controller.FakeVerifyHost");
            cmd.addAll(args);
            cmd.add("--fake");
            cmd.add(scenario);
            cmd.add("--fake-log");
            cmd.add(logPath.toString());
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            processes.add(p);
            launches.add(new ArrayList<>(args));
            exePaths.add(exePath == null ? "" : exePath);
            return p;
        }

        int count() {
            return launches.size();
        }

        List<String> args(int index) {
            synchronized (launches) {
                return new ArrayList<>(launches.get(index));
            }
        }

        Process process(int index) {
            synchronized (processes) {
                return processes.get(index);
            }
        }

        List<Process> processesCopy() {
            synchronized (processes) {
                return new ArrayList<>(processes);
            }
        }
    }

    // ---------------------------------------------------------------- 断言 / 等待

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    private static void group(String name, Body body) {
        group = name;
        try {
            body.run();
        } catch (Throwable t) {
            record(false, "组内未捕获异常", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void check(String name, boolean ok) {
        record(ok, name, "");
    }

    private static void check(String name, boolean ok, String detail) {
        record(ok, name, detail);
    }

    private static void checkEquals(String name, Object expected, Object actual) {
        record(Objects.equals(expected, actual), name, "expected=" + expected + " actual=" + actual);
    }

    private static void record(boolean ok, String name, String detail) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + group + " / " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + group + " / " + name
                    + (detail == null || detail.isEmpty() ? "" : " => " + detail));
        }
    }

    private static boolean await(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 条件里对象还没就绪：继续等
            }
            sleep(20);
        }
        try {
            return condition.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    private static State awaitPhase(Recorder rec, Phase phase, long timeoutMs) {
        if (!await(() -> rec.saw(phase), timeoutMs)) {
            return null;
        }
        return rec.snapshot().isEmpty() ? null : rec.snapshot().get(rec.snapshot().size() - 1);
    }

    private static boolean awaitLine(Path log, String exact, long timeoutMs) {
        return await(() -> readLines(log).contains(exact), timeoutMs);
    }

    private static List<String> readLines(Path log) {
        try {
            if (log == null || !Files.exists(log)) {
                return Collections.emptyList();
            }
            return Files.readAllLines(log, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private static boolean sawState(Recorder rec, Phase phase, String message) {
        for (State s : rec.snapshot()) {
            if (s.phase() == phase && message.equals(s.message())) {
                return true;
            }
        }
        return false;
    }

    private static int firstPhaseIndex(Recorder rec, Phase phase) {
        List<State> all = rec.snapshot();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).phase() == phase) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static boolean anyLogContains(FakeHost host, String needle) {
        return host.anyLogContains(needle);
    }

    private static String at(List<String> args, int index) {
        return index >= 0 && index < args.size() ? args.get(index) : null;
    }

    private static String valueAfter(List<String> args, String key) {
        int i = args.indexOf(key);
        return i >= 0 && i + 1 < args.size() ? args.get(i + 1) : "";
    }

    private static boolean nonEmptyValueAfter(List<String> args, String key) {
        String v = valueAfter(args, key);
        return !v.isEmpty();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
