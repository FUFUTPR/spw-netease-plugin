package com.example.netease.ui.controller;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 探针替身助手（批次 4，契约 §4）：自己是一个 main 进程，解析与真助手相同的命令行，按
 * {@code --fake <scenario>} 演出一段 stdout 协议，并把收到的每条 stdin 命令原样写进
 * {@code --fake-log <path>}，供 {@link VerifyEmbedControllerProbe} 断言。
 *
 * <p>行为完全由 {@code --fake} 决定；真实参数（--session/--pid/--expect-client 等）只用于让
 * 输出的报文体与真助手一致（start 行回显 session、adopted 行回显期望客户区），不参与分支。</p>
 */
public final class FakeVerifyHost {

    private static final String PREFIX = "verify-host: ";

    private static PrintWriter out;
    private static BufferedWriter log;
    private static Path adoptionGate;

    private static String session = "00000000";
    private static String pid = "0";
    private static String expectW = "0";
    private static String expectH = "0";
    private static String parentTitle = "";

    private FakeVerifyHost() {
    }

    public static void main(String[] args) {
        String scenario = "normal";
        String logPath = null;
        for (int i = 0; i < args.length; i++) {
            if ("--fake".equals(args[i]) && i + 1 < args.length) {
                scenario = args[++i];
            } else if ("--fake-log".equals(args[i]) && i + 1 < args.length) {
                logPath = args[++i];
            }
        }
        try {
            openLog(logPath);
            if (logPath != null) adoptionGate = Path.of(logPath + ".adopt");
            parseRealArgs(args);
            out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
            switch (scenario) {
                case "normal" -> normal();
                case "client-correct" -> clientCorrect();
                case "delayed-adopt" -> delayedAdopt();
                case "wrong-session" -> wrongSession();
                case "nav-fail" -> navFail();
                case "nav-ok" -> navOk();
                case "parent-timeout" -> {
                    prelude();
                    say("parent-timeout");
                }
                case "parent-ambiguous" -> {
                    prelude();
                    say("parent-ambiguous count=2");
                }
                case "parent-invalid" -> {
                    prelude();
                    say("parent-invalid title-mismatch");
                }
                case "parent-client-mismatch" -> {
                    prelude();
                    say("parent-client-mismatch got=400x300 expect=375x250");
                }
                case "fail" -> {
                    prelude();
                    say("fail webview2-missing");
                }
                case "crash-before-ready" -> {
                    prelude();
                    sleep(80);
                    closeLog();
                    System.exit(3);
                }
                case "exit-after-ready" -> {
                    normal();
                    sleep(120);
                    closeLog();
                    System.exit(4);
                }
                case "parent-gone" -> {
                    normal();
                    sleep(120);
                    say("parent-gone exit");
                    sleep(60);
                    closeLog();
                    System.exit(0);
                }
                case "window-close-requested" -> {
                    normal();
                    sleep(120);
                    say("window-close-requested");
                }
                case "epoch-change" -> {
                    normal();
                    sleep(1_500);
                    say("cookies MUSIC_U=EPOCH_SECRET");
                }
                default -> normal();
            }
            pumpStdin();
        } catch (Throwable t) {
            // 不走 stdout（那里只放协议），失败时静默退出 2，探针会从“提前退出（退出码 2）”看出问题。
            System.exit(2);
        } finally {
            closeLog();
        }
    }

    // ---------------------------------------------------------------- 场景

    private static void normal() {
        prelude();
        say("adopted rect=0,0," + expectW + "," + expectH);
        say("client w=" + expectW + " h=" + expectH);
        say("webview2 153.0.0.0");
        say("ready");
        say("cookies MUSIC_U=PROBE_COOKIE_SECRET");
        say("cookie-in 3");
    }

    /** client 报 300x200：控制器 contentWidth=300、scale=1.25 时应把 scale 校正回 1.0。 */
    private static void clientCorrect() {
        prelude();
        say("adopted rect=0,0,300,200");
        say("client w=300 h=200");
        say("webview2 153.0.0.0");
        say("ready");
        say("cookies MUSIC_U=PROBE_COOKIE_SECRET");
    }

    /** Explicit gate keeps pre-adoption checks independent of machine load. */
    private static void delayedAdopt() {
        prelude();
        long deadline=System.nanoTime()+10_000_000_000L;
        while(adoptionGate!=null && !Files.exists(adoptionGate) && System.nanoTime()<deadline) sleep(10);
        if(adoptionGate!=null && !Files.exists(adoptionGate)) { say("fail fixture-adoption-not-released"); return; }
        say("adopted rect=0,0," + expectW + "," + expectH);
        say("client w=" + expectW + " h=" + expectH);
        say("webview2 153.0.0.0");
        sleep(50);
        say("ready");
        say("cookies MUSIC_U=PROBE_COOKIE_SECRET");
    }

    /** start 行故意报错 session（控制器必须丢掉该行，但后续行照常工作）。 */
    private static void wrongSession() {
        say("start pid=" + pid + " session=deadbeef");
        say("parent 424242 class=SunAwtFrame pid=" + pid);
        say("adopted rect=0,0," + expectW + "," + expectH);
        say("client w=" + expectW + " h=" + expectH);
        say("webview2 153.0.0.0");
        say("ready");
        say("cookies MUSIC_U=PROBE_COOKIE_SECRET");
    }

    private static void navFail() {
        prelude();
        say("adopted rect=0,0," + expectW + "," + expectH);
        say("client w=" + expectW + " h=" + expectH);
        say("webview2 153.0.0.0");
        say("nav-start len=1000");
        sleep(150);
        say("nav-done False -100");
    }

    private static void navOk() {
        prelude();
        say("adopted rect=0,0," + expectW + "," + expectH);
        say("client w=" + expectW + " h=" + expectH);
        say("webview2 153.0.0.0");
        say("nav-start len=1000");
        sleep(400);
        say("nav-done True 200");
    }

    // ---------------------------------------------------------------- 协议 / stdin

    private static void prelude() {
        say("start pid=" + pid + " session=" + session);
        say("parent 424242 class=SunAwtFrame pid=" + pid
                + (parentTitle.isEmpty() ? "" : " title=" + parentTitle));
    }

    private static void pumpStdin() {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String cmd;
            while ((cmd = in.readLine()) != null) {
                cmd = cmd.trim();
                if (cmd.isEmpty()) {
                    continue;
                }
                logCommand(cmd);
                if ("close".equals(cmd)) {
                    return;
                }
                if ("show".equals(cmd)) {
                    say("shown");
                } else if ("hide".equals(cmd)) {
                    say("hidden");
                } else if (cmd.startsWith("rect ")) {
                    say("rect-ack " + cmd.substring("rect ".length()).trim());
                }
            }
        } catch (Throwable ignored) {
            // stdin 断了就退出
        }
    }

    private static void parseRealArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--session" -> {
                    if (i + 1 < args.length) {
                        session = args[++i];
                    }
                }
                case "--pid" -> {
                    if (i + 1 < args.length) {
                        pid = args[++i];
                    }
                }
                case "--expect-client" -> {
                    if (i + 1 < args.length) {
                        String v = args[++i];
                        int x = v.indexOf('x');
                        if (x > 0) {
                            expectW = v.substring(0, x);
                            expectH = v.substring(x + 1);
                        }
                    }
                }
                case "--parent-title" -> {
                    if (i + 1 < args.length) {
                        parentTitle = args[++i];
                    }
                }
                case "--url", "--parent-class", "--cookie", "--data-dir", "--timeout-ms", "--bg" -> {
                    if (i + 1 < args.length) {
                        i++;
                    }
                }
                default -> {
                    // --fake / --fake-log 已单独解析，其它忽略
                }
            }
        }
    }

    private static void say(String payload) {
        if (out == null) {
            return;
        }
        out.println(PREFIX + payload);
        out.flush();
    }

    private static void openLog(String logPath) throws Exception {
        if (logPath == null || logPath.isEmpty()) {
            return;
        }
        Path p = Path.of(logPath);
        Path parent = p.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        log = Files.newBufferedWriter(p, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static void logCommand(String cmd) {
        if (log == null) {
            return;
        }
        try {
            synchronized (log) {
                log.write(cmd);
                log.newLine();
                log.flush();
            }
        } catch (Throwable ignored) {
            // 日志失败不影响协议
        }
    }

    private static void closeLog() {
        if (log == null) {
            return;
        }
        try {
            synchronized (log) {
                log.flush();
                log.close();
            }
        } catch (Throwable ignored) {
            // 已关闭
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
