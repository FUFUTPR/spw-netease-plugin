package com.example.netease.smoke;

import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.Window;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * VerifyHost 进程协议探针（0.12.0 批次 4）。
 *
 * <p>直接拿真实的 VerifyHost.exe 与真实的 AWT 父窗跑协议，覆盖：
 * 父窗唯一识别、候选歧义拒绝、指定 HWND 核对、超时、rect/show/hide/info/close 命令、
 * 父窗销毁自杀、stdin EOF 自杀、旧模式（SunAwtDialog + --rect）兼容。</p>
 *
 * <p>用法：java -cp <classes> com.example.netease.smoke.VerifyHostProtocolProbe &lt;VerifyHost.exe&gt; &lt;临时目录&gt;</p>
 * <p>成功退出 0 并打印 {@code VERIFY_HOST_PROTOCOL_RESULT=<n> PASS}；失败退出 1 并打印失败断言。</p>
 */
public final class VerifyHostProtocolProbe {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: VerifyHostProtocolProbe <VerifyHost.exe> <temp-dir>");
            System.exit(2);
        }
        Path exe = Path.of(args[0]).toAbsolutePath();
        Path tmp = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(tmp);
        if (!Files.isRegularFile(exe)) {
            System.out.println("FAIL 找不到 VerifyHost.exe: " + exe);
            System.exit(1);
        }
        System.out.println("verify-host = " + exe);
        System.out.println("temp-dir   = " + tmp);

        normalHandshake(exe, tmp);
        ambiguousParents(exe, tmp);
        invalidHwnd(exe, tmp);
        parentTimeout(exe, tmp);
        parentGone(exe, tmp);
        stdinEof(exe, tmp);
        legacyDialogMode(exe, tmp);

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("VERIFY_HOST_PROTOCOL_RESULT=" + passed + " PASS");
            System.exit(0);
        }
        for (String f : failures) System.out.println("FAILED: " + f);
        System.out.println("VERIFY_HOST_PROTOCOL_RESULT=" + passed + " FAIL (" + failures.size() + " 处失败)");
        System.exit(1);
    }

    // ---------------------------------------------------------------- 场景

    /** 正常：唯一父窗 → 认领 → 物理尺寸核对 → 命令 → 有界关闭。 */
    private static void normalHandshake(Path exe, Path tmp) throws Exception {
        section("正常握手 + 命令协议");
        Frame frame = newFrame("协议探针父窗");
        try {
            GraphicsConfiguration gc = frame.getGraphicsConfiguration();
            double scale = gc.getDefaultTransform().getScaleX();
            java.awt.Insets ins = frame.getInsets();
            int cw = frame.getWidth() - ins.left - ins.right;
            int ch = frame.getHeight() - ins.top - ins.bottom;
            long[] expect = {Math.round(cw * scale), Math.round(ch * scale)};
            System.out.println("  parent content logical=" + cw + "x" + ch + " scale=" + scale
                    + " expect physical=" + expect[0] + "x" + expect[1]);

            Helper h = Helper.start(exe, tmp, "normal",
                    "--url", "about:blank",
                    "--pid", String.valueOf(ProcessHandle.current().pid()),
                    "--session", "S-normal",
                    "--parent-class", "SunAwtFrame",
                    "--parent-title", "协议探针父窗",
                    "--expect-client", expect[0] + "," + expect[1],
                    "--data-dir", tmp.resolve("wv2-normal").toString(),
                    "--timeout-ms", "15000");
            try {
                String parent = h.await("verify-host: parent ", 20000);
                check("parent 行有 hwnd", parent.matches("verify-host: parent \\d+ .*"));
                check("parent 行 class=SunAwtFrame", parent.contains("class=SunAwtFrame"));
                check("parent 行 pid 是本进程", parent.contains("pid=" + ProcessHandle.current().pid()));
                check("parent 行 title-match=1", parent.contains("title-match=1"));
                check("parent 行 count=1", parent.contains("count=1"));
                check("parent 行带 session", parent.contains("session=S-normal"));

                String adopted = h.await("verify-host: adopted ", 20000);
                check("严格模式 adopted 先 1x1 隐藏", adopted.contains("rect=0,0,1,1"));
                String client = h.await("verify-host: client ", 20000);
                check("client 行有 w/h", client.matches("verify-host: client w=\\d+ h=\\d+"));
                int gw = intOf(client, "w=");
                int gh = intOf(client, "h=");
                System.out.println("  client 实测=" + gw + "x" + gh + " expect=" + expect[0] + "x" + expect[1]);
                check("client 尺寸 = 父窗客户区物理尺寸（±2px）",
                        Math.abs(gw - expect[0]) <= 2 && Math.abs(gh - expect[1]) <= 2);
                System.out.println("  " + client + " / " + "parent=" + parent);
                check("没有 parent-client-mismatch", h.find("parent-client-mismatch") == null);

                String wv = h.await("verify-host: webview2 ", 30000);
                check("webview2 行有版本", wv.length() > "verify-host: webview2 ".length());
                h.await("verify-host: ready", 30000);
                check("ready 已到达", true);
                String nav = h.await("verify-host: nav-done ", 30000);
                System.out.println("  nav = " + nav);
                check("nav-done 成功", nav != null && nav.contains("nav-done true"));

                h.send("rect 10 20 320 240");
                check("rect-ack 回显坐标", h.await("verify-host: rect-ack ", 5000).contains("rect-ack 10,20,320,240"));
                h.send("show");
                check("show → shown", h.await("verify-host: shown", 5000) != null);
                h.send("hide");
                check("hide → hidden", h.await("verify-host: hidden", 5000) != null);
                h.send("info");
                String info = h.await("verify-host: info ", 5000);
                check("info 带 adopted=true", info.contains("adopted=true"));
                check("info 带 rect", info.contains("rect=10,20,320,240"));
                h.send("bogus-command");
                check("未知命令回 ignore-cmd", h.await("verify-host: ignore-cmd", 5000) != null);

                h.send("close");
                check("close → closing", h.await("verify-host: closing", 5000) != null);
                check("close 后进程有界退出", h.waitExit(6000));
                check("正常关闭退出码 0", h.exitCode() == 0);
            } finally {
                h.kill();
            }
        } finally {
            dispose(frame);
        }
    }

    /** 歧义：两个同类顶层窗、标题都不匹配 → parent-ambiguous，且绝不挂到第一个。 */
    private static void ambiguousParents(Path exe, Path tmp) throws Exception {
        section("父窗歧义拒绝挂载");
        Frame a = newFrame("歧义窗 A");
        Frame b = newFrame("歧义窗 B");
        try {
            Helper h = Helper.start(exe, tmp, "ambiguous",
                    "--url", "about:blank",
                    "--pid", String.valueOf(ProcessHandle.current().pid()),
                    "--session", "S-ambig",
                    "--parent-class", "SunAwtFrame",
                    "--parent-title", "根本不存在这个标题",
                    "--data-dir", tmp.resolve("wv2-ambig").toString(),
                    "--timeout-ms", "8000");
            try {
                String line = h.await("verify-host: parent-ambiguous", 20000);
                check("歧义 → parent-ambiguous count=2", line.contains("count=2"));
                check("歧义时没有 parent 行", h.find("verify-host: parent ") == null);
                check("歧义时没有 adopted", h.find("verify-host: adopted") == null);
                check("歧义进程退出", h.waitExit(8000));
            } finally {
                h.kill();
            }
        } finally {
            dispose(a);
            dispose(b);
        }
    }

    /** 指定 HWND 无效 → parent-invalid，且不挂载。 */
    private static void invalidHwnd(Path exe, Path tmp) throws Exception {
        section("指定 HWND 无效");
        Helper h = Helper.start(exe, tmp, "invalid",
                "--url", "about:blank",
                "--pid", String.valueOf(ProcessHandle.current().pid()),
                "--session", "S-invalid",
                "--parent-hwnd", "123456789",
                "--parent-class", "SunAwtFrame",
                "--data-dir", tmp.resolve("wv2-invalid").toString(),
                "--timeout-ms", "8000");
        try {
            String line = h.await("verify-host: parent-invalid", 15000);
            check("parent-invalid 有原因", line.length() > "verify-host: parent-invalid ".length());
            check("指定 HWND 无效时没有 adopted", h.find("verify-host: adopted") == null);
            check("指定 HWND 无效时进程退出", h.waitExit(8000));
        } finally {
            h.kill();
        }
    }

    /** 找不到父窗 → parent-timeout（不是无限等待，也不是随便挑一个）。 */
    private static void parentTimeout(Path exe, Path tmp) throws Exception {
        section("父窗超时");
        Helper h = Helper.start(exe, tmp, "timeout",
                "--url", "about:blank",
                "--pid", String.valueOf(ProcessHandle.current().pid()),
                "--session", "S-timeout",
                "--parent-class", "NoSuchWindowClass9384",
                "--data-dir", tmp.resolve("wv2-timeout").toString(),
                "--timeout-ms", "1500");
        try {
            check("1.5s 后 parent-timeout", h.await("verify-host: parent-timeout", 12000) != null);
            check("超时时没有 adopted", h.find("verify-host: adopted") == null);
            check("超时进程退出", h.waitExit(8000));
        } finally {
            h.kill();
        }
    }

    /** 父窗销毁 → parent-gone exit（不再向旧句柄发任何命令）。 */
    private static void parentGone(Path exe, Path tmp) throws Exception {
        section("父窗销毁");
        Frame frame = newFrame("会被销毁的父窗");
        Helper h = null;
        try {
            h = Helper.start(exe, tmp, "gone",
                    "--url", "about:blank",
                    "--pid", String.valueOf(ProcessHandle.current().pid()),
                    "--session", "S-gone",
                    "--parent-class", "SunAwtFrame",
                    "--parent-title", "会被销毁的父窗",
                    "--data-dir", tmp.resolve("wv2-gone").toString(),
                    "--timeout-ms", "15000");
            h.await("verify-host: adopted ", 20000);
            dispose(frame);
            frame = null;
            check("父窗销毁后回 parent-gone exit", h.await("verify-host: parent-gone", 8000) != null);
            check("父窗销毁后进程退出", h.waitExit(8000));
        } finally {
            if (h != null) h.kill();
            if (frame != null) dispose(frame);
        }
    }

    /** stdin EOF（Java 侧崩了）→ 助手自杀，不留孤儿进程。 */
    private static void stdinEof(Path exe, Path tmp) throws Exception {
        section("stdin EOF 清理");
        Helper h = Helper.start(exe, tmp, "eof",
                "--url", "about:blank",
                "--pid", String.valueOf(ProcessHandle.current().pid()),
                "--session", "S-eof",
                "--parent-class", "SunAwtFrame",
                "--data-dir", tmp.resolve("wv2-eof").toString(),
                "--timeout-ms", "15000");
        try {
            Thread.sleep(500);
            h.closeStdin();
            check("stdin EOF → stdin-eof exit", h.await("verify-host: stdin-eof", 6000) != null);
            check("stdin EOF 后进程退出", h.waitExit(8000));
        } finally {
            h.kill();
        }
    }

    /** 旧模式：--title + --rect 找 SunAwtDialog，行为与批次 3 兼容。 */
    private static void legacyDialogMode(Path exe, Path tmp) throws Exception {
        section("旧模式（SunAwtDialog）兼容");
        JDialog dialog = new JDialog((Frame) null, "旧验证窗协议测试", false);
        dialog.setSize(520, 400);
        dialog.setLocation(120, 120);
        SwingUtilities.invokeAndWait(() -> dialog.setVisible(true));
        try {
            Helper h = Helper.start(exe, tmp, "legacy",
                    "--title", "旧验证窗协议测试",
                    "--url", "about:blank",
                    "--pid", String.valueOf(ProcessHandle.current().pid()),
                    "--rect", "12,34,300,200",
                    "--data-dir", tmp.resolve("wv2-legacy").toString(),
                    "--timeout-ms", "15000");
            try {
                String parent = h.await("verify-host: parent ", 20000);
                check("旧模式 parent class=SunAwtDialog", parent.contains("class=SunAwtDialog"));
                check("旧模式 adopted 用 --rect", h.await("verify-host: adopted ", 10000).contains("rect=12,34,300,200"));
                check("旧模式也能 ready", h.await("verify-host: ready", 30000) != null);
                h.send("hide");
                check("旧模式 hide 生效", h.await("verify-host: hidden", 5000) != null);
                h.send("close");
                check("旧模式 close 退出", h.waitExit(6000));
            } finally {
                h.kill();
            }
        } finally {
            dispose(dialog);
        }
    }

    // ---------------------------------------------------------------- 工具

    private static Frame newFrame(String title) throws Exception {
        Frame[] out = new Frame[1];
        SwingUtilities.invokeAndWait(() -> {
            Frame f = new Frame(title);
            f.setSize(760, 560);
            f.setLocation(80, 80);
            f.setVisible(true);
            out[0] = f;
        });
        Thread.sleep(300);
        return out[0];
    }

    private static void dispose(Window w) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            w.setVisible(false);
            w.dispose();
        });
        Thread.sleep(150);
    }

    private static void section(String name) {
        System.out.println();
        System.out.println("== " + name + " ==");
    }

    private static int intOf(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return Integer.MIN_VALUE;
        int j = i + key.length();
        int k = j;
        while (k < line.length() && Character.isDigit(line.charAt(k))) k++;
        try { return Integer.parseInt(line.substring(j, k)); } catch (Exception e) { return Integer.MIN_VALUE; }
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS " + what);
        } else {
            failures.add(what);
            System.out.println("  FAIL " + what);
        }
    }

    /** 助手进程：stdout 行队列 + stdin 写入。 */
    private static final class Helper {
        private final Process process;
        private final BufferedWriter stdin;
        private final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final List<String> all = new ArrayList<>();
        private final Thread reader;
        private final String name;

        private Helper(Process process, String name) {
            this.process = process;
            this.name = name;
            this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (all) { all.add(line); }
                        lines.add(line);
                    }
                } catch (Exception ignored) {
                }
            }, "verify-host-stdout-" + name);
            this.reader.setDaemon(true);
            this.reader.start();
        }

        static Helper start(Path exe, Path tmp, String name, String... args) throws Exception {
            List<String> cmd = new ArrayList<>();
            cmd.add(exe.toString());
            for (String a : args) cmd.add(a);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(tmp.toFile());
            Process p = pb.start();
            return new Helper(p, name);
        }

        void send(String cmd) throws Exception {
            stdin.write(cmd);
            stdin.write("\n");
            stdin.flush();
        }

        void closeStdin() throws Exception {
            stdin.close();
        }

        String await(String prefix, long timeoutMs) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                String line = lines.poll(Math.max(20, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (line != null && line.startsWith(prefix)) return line;
            }
            return null;
        }

        String find(String needle) {
            synchronized (all) {
                for (String l : all) if (l.startsWith(needle)) return l;
            }
            return null;
        }

        boolean waitExit(long timeoutMs) throws Exception {
            return process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        }

        int exitCode() {
            return process.isAlive() ? Integer.MIN_VALUE : process.exitValue();
        }

        void kill() {
            if (process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }
        }
    }
}
