package com.example.netease.ui;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import com.example.netease.core.PluginLog;

/**
 * 内嵌安全验证宿主自检（0.11.34）——「VerifyHost.exe 真的把网页渲染进了 Java 窗」的机器判据。
 *
 * <p>它验的是整条内嵌链路，而不是文案：
 * <ol>
 *   <li><b>产物完整性</b>：{@code VerifyHost.exe} + {@code Microsoft.Web.WebView2.Core.dll}
 *       + {@code WebView2Loader.dll} + {@code VerifyHost.runtimeconfig.json} 四个文件都在随包目录里；
 *       {@code --selftest} 退出码 0 且 stdout 含 {@code selftest ok webview2=}（顺带打印本机 WebView2 版本）。</li>
 *   <li><b>认领父窗 + 内嵌渲染</b>：起一个 Java {@link JDialog}（窗口类名 {@code SunAwtDialog}，标题唯一），
 *       让宿主进程用 {@code --title} 认领它、{@code SetParent} 成子窗口；页面是本探针现写的
 *       <b>纯洋红 #FF00FF</b> HTML。随后用 {@link Robot} 抓 Java 窗客户区——抓到成片洋红即证明
 *       「Chromium 的渲染面确实画在 Java 窗里」，而不是弹了个外部浏览器窗。</li>
 *   <li><b>宿主窗没了就自杀</b>：{@code dispose()} 之后宿主进程应在数秒内自行退出并打印 {@code parent-gone exit}。</li>
 *   <li><b>产品路径</b>（0.11.34 新增）：不走自建的裸窗，直接用产品代码 {@code VerifyWindow.open(...)}——
 *       登录窗替身被验证窗<b>完整盖住</b>、窗内抓到洋红像素、状态行提到「完成验证」、助手是探针的子进程，
 *       最后 {@code closeIfOpen()} 必须关窗并收掉助手进程（不留孤儿）。</li>
 * </ol>
 *
 * <p>为什么必须这么验：内嵌成败取决于三件本机事实（WebView2 运行时在不在、跨进程 {@code SetParent} 成不成、
 * Chromium 渲染面会不会被 Swing 画覆盖），任何一条不成立都要走兜底；只看「有没有弹窗」是验不出来的。
 *
 * <p>运行（先全量 javac 到 build\classes-full，见 docs\00 §11 坑 42）：
 * <pre>
 *   java -Dstdout.encoding=UTF-8 -cp "build\classes-full;tools\.cache\spw-workshop-api-host.jar;tools\.cache\pf4j-3.12.0.jar;libs\sqlite-jdbc-3.41.2.2.jar" \
 *        com.example.netease.ui.VerifyEmbedProbe "C:\Users\<用户名>\Documents\ 椒盐插件"
 * </pre>
 * 参数可省（默认取 {@code user.dir}）。窗口会短暂出现在次屏；退出码 0 = 全过。
 */
public final class VerifyEmbedProbe {

    private static final int W = 540;
    private static final int H = 380;
    private static final String TITLE = "内嵌验证自检 · SPW " + System.nanoTime();

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : System.getProperty("user.dir"));
        Path helperDir = root.resolve("src").resolve("main").resolve("resources").resolve("verify-host");
        System.out.println("=== 内嵌安全验证宿主自检 ===");
        System.out.println("  宿主目录 = " + helperDir);

        section("一 产物完整性 + --selftest");
        Path exe = helperDir.resolve("VerifyHost.exe");
        check("VerifyHost.exe 在随包目录", Files.isRegularFile(exe), exe.toString());
        check("Microsoft.Web.WebView2.Core.dll 在", Files.isRegularFile(helperDir.resolve("Microsoft.Web.WebView2.Core.dll")), "");
        check("Microsoft.Web.WebView2.WinForms.dll 在", Files.isRegularFile(helperDir.resolve("Microsoft.Web.WebView2.WinForms.dll")), "");
        check("WebView2Loader.dll 在", Files.isRegularFile(helperDir.resolve("WebView2Loader.dll")), "");
        check("VerifyHost.runtimeconfig.json 在", Files.isRegularFile(helperDir.resolve("VerifyHost.runtimeconfig.json")), "");
        if (!Files.isRegularFile(exe)) {
            summary();
            return;
        }

        List<String> selfOut = run(exe, helperDir, List.of("--selftest"), 30000);
        boolean selfOk = selfOut.stream().anyMatch(l -> l.contains("selftest ok webview2="));
        check("--selftest 退出后可认领：stdout 含 selftest ok webview2=", selfOk, selfOut.toString());

        section("二 认领 Java 窗 + 内嵌渲染（纯洋红 HTML）");
        Path html = Files.createTempFile("spw-verify-probe", ".html");
        Files.writeString(html, "<!doctype html><html><head><meta charset=\"utf-8\"><title>probe</title>"
                + "<style>html,body{margin:0;height:100%;background:#FF00FF}"
                + "div{position:absolute;left:40px;top:40px;width:200px;height:120px;background:#000}</style>"
                + "</head><body><div></div></body></html>", StandardCharsets.UTF_8);

        JDialog dialog = new JDialog((java.awt.Frame) null, TITLE, false);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        JPanel pane = new JPanel();
        pane.setBackground(Color.WHITE);
        dialog.setContentPane(pane);
        // 放到次屏（宿主所在屏），别打扰主屏
        Rectangle target = pickSecondaryScreen();
        SwingUtilities.invokeAndWait(() -> {
            dialog.setBounds(target.x + 120, target.y + 120, W + 16, H + 48);
            dialog.setVisible(true);
        });
        Thread.sleep(600);
        check("Java 窗已显示（类名 SunAwtDialog）", dialog.isShowing(), dialog.getBounds().toString());

        Path dataDir = Files.createTempDirectory("spw-verify-data");
        List<String> out = new ArrayList<>();
        Process proc = launch(exe, helperDir, List.of(
                "--title", TITLE,
                "--url", html.toUri().toString(),
                "--rect", "0,0," + W + "," + H,
                "--data-dir", dataDir.toString(),
                "--timeout-ms", "8000"), out);

        boolean ready = awaitLine(out, "ready", 45000);
        check("宿主进程报 ready（WebView2 起来并已导航）", ready, tail(out));
        Thread.sleep(2500);   // 首次渲染落屏

        int magenta = countMagenta(dialog);
        check("Java 窗客户区里抓到洋红网页像素（Chromium 渲染面确实嵌在窗内）", magenta > 20000,
                "洋红像素=" + magenta + "（窗=" + dialog.getBounds() + "）");

        section("三 宿主窗关闭 ⇒ 宿主进程自杀");
        SwingUtilities.invokeAndWait(dialog::dispose);
        boolean gone = awaitLine(out, "parent-gone exit", 8000);
        boolean exited = proc.waitFor(6, TimeUnit.SECONDS);
        check("关 Java 窗后宿主进程自己退出（parent-gone exit）", ready && (gone || exited),
                "ready=" + ready + " gone=" + gone + " exited=" + exited);
        if (proc.isAlive()) {
            proc.destroyForcibly();
        }

        section("四 产品路径：VerifyWindow 内嵌（覆盖登录窗 + 关窗即收）");
        System.setProperty("spw.verifyhost", helperDir.toString());   // 探针里改用源码目录（装机后由 code source 推）
        // 把插件日志开到 debug 并落到临时目录：内嵌链的每一步（助手 stdout、降级原因）都进文件，
        // 这样这一节无论过不过，probe 自己就能说清「降级到哪儿、为什么」——不用再去翻真机日志。
        PluginLog.init(Files.createTempDirectory("spw-verify-log"));
        PluginLog.setLevel("debug");
        JDialog login = new JDialog((java.awt.Frame) null, "登录网易云音乐 · 探针", false);
        login.setUndecorated(true);
        login.setContentPane(new JPanel());
        SwingUtilities.invokeAndWait(() -> {
            login.setBounds(target.x + 300, target.y + 200, 760, 470);
            login.setVisible(true);
        });
        Thread.sleep(400);
        check("登录窗替身已显示（760×470）", login.isShowing(), login.getBounds().toString());

        Rectangle cover = login.getBounds();
        String vUrl = html.toUri().toString();
        // 走 EDT 调，开窗才是同步的（open() 从别的线程调会 invokeLater 异步开；探针要是抢在它前面断言，
        // 就会得到「窗还没建好」的假 FAIL —— 这是探针自己的时序坑，不是产品缺陷）。
        SwingUtilities.invokeAndWait(() -> VerifyWindow.open(login, vUrl, cover, null, "验证码登录", () -> { }));
        check("VerifyWindow 认下这份地址（isOpen + isOpenFor）",
                VerifyWindow.isOpen() && VerifyWindow.isOpenFor(vUrl),
                "isOpen=" + VerifyWindow.isOpen() + " openFor=" + VerifyWindow.isOpenFor(vUrl));
        boolean vReady = VerifyWindow.awaitReady(60000);
        check("内嵌验证窗走完整条链路（助手认领父窗 + WebView2 就绪）", vReady,
                "状态行=" + VerifyWindow.statusTextForTest());
        int vMagenta = awaitMagentaAt(cover, 20000, 20000);
        check("验证窗内抓到洋红网页像素（网页真的画在这个窗里，不是外部浏览器）", vMagenta > 20000,
                "洋红像素=" + vMagenta);
        Rectangle vb = VerifyWindow.boundsForTest();
        check("验证窗完整盖住登录窗（用户裁定：验证窗口要盖在登录窗之上）",
                vb != null && vb.contains(cover), "验证窗=" + vb + " 登录窗=" + cover);
        check("验证窗状态行提到「完成验证」（告诉用户做完回本窗点重试）",
                VerifyWindow.statusTextForTest().contains("完成验证"),
                "状态行=" + VerifyWindow.statusTextForTest());
        int kids = childHelperCount();
        check("助手进程是探针的子进程（产品路径真的起了 VerifyHost.exe）", kids >= 1, "子进程数=" + kids);

        VerifyWindow.closeIfOpen();
        boolean goneHelper = awaitNoHelper(6000);
        check("closeIfOpen ⇒ 窗关了且助手进程退出（不留孤儿进程）",
                !VerifyWindow.isOpen() && goneHelper,
                "isOpen=" + VerifyWindow.isOpen() + " 子进程数=" + childHelperCount());
        SwingUtilities.invokeAndWait(login::dispose);
        dumpVerifyLog();
        try {
            Files.deleteIfExists(html);
        } catch (Exception ignore) {
            // 临时文件清理失败不影响判据
        }
        summary();
    }

    /** 把这一次的插件日志里跟内嵌验证有关的行打出来（失败时现场就能看清降级原因）。 */
    private static void dumpVerifyLog() {
        Path file = PluginLog.file();
        PluginLog.init(null);          // 关掉写者，文件内容才算落定（再读就不会缺尾巴）
        if (file == null || !Files.isRegularFile(file)) {
            System.out.println("  [log] 没有日志文件可读");
            return;
        }
        try {
            int shown = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.contains("verify-win") || line.contains("宿主：")) {
                    if (shown++ < 40) {
                        System.out.println("  [log] " + line);
                    }
                }
            }
            System.out.println("  [log] 文件 = " + file + "（相关行 " + shown + "）");
        } catch (Exception e) {
            System.out.println("  [log] 读日志失败：" + e);
        }
    }

    /** 等到某块屏幕区域里的洋红像素够多（WebView2 首帧落屏要几百毫秒到几秒），最多 {@code timeoutMs}。 */
    private static int awaitMagentaAt(Rectangle r, int want, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        int last = 0;
        while (System.currentTimeMillis() < end) {
            last = countMagentaAt(r);
            if (last > want) {
                return last;
            }
            Thread.sleep(200);
        }
        return last;
    }

    /** 探针 JVM 直接拉起的助手进程数（VerifyWindow 是探针的子进程，可精确计数）。 */
    private static int childHelperCount() {
        int n = 0;
        for (ProcessHandle ph : ProcessHandle.current().children().toList()) {
            String cmd = ph.info().command().orElse("");
            if (cmd.toLowerCase().contains("verifyhost")) {
                n++;
            }
        }
        return n;
    }

    private static boolean awaitNoHelper(long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (childHelperCount() == 0) {
                return true;
            }
            Thread.sleep(150);
        }
        return childHelperCount() == 0;
    }

    // ---------------- 进程 ----------------

    private static List<String> run(Path exe, Path workDir, List<String> args, long timeoutMs) throws Exception {
        List<String> sink = new ArrayList<>();
        Process p = launch(exe, workDir, args, sink);
        p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        return sink;
    }

    private static Process launch(Path exe, Path workDir, List<String> args, List<String> sink) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (sink) {
                        sink.add(line);
                    }
                    System.out.println("  [host] " + line);
                }
            } catch (Exception ignore) {
                // 进程被杀时读流中断属正常
            }
        }, "verify-host-reader");
        t.setDaemon(true);
        t.start();
        return p;
    }

    private static boolean awaitLine(List<String> sink, String needle, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            synchronized (sink) {
                for (String l : sink) {
                    if (l.contains(needle)) {
                        return true;
                    }
                }
            }
            Thread.sleep(150);
        }
        return false;
    }

    private static String tail(List<String> sink) {
        synchronized (sink) {
            int from = Math.max(0, sink.size() - 6);
            return sink.subList(from, sink.size()).toString();
        }
    }

    // ---------------- 像素 ----------------

    private static int countMagenta(JDialog dialog) throws AWTException {
        Insets in = dialog.getInsets();
        Rectangle r = dialog.getBounds();
        Rectangle client = new Rectangle(r.x + in.left + 4, r.y + in.top + 4,
                Math.max(1, r.width - in.left - in.right - 8), Math.max(1, r.height - in.top - in.bottom - 8));
        return countMagentaAt(client);
    }

    private static int countMagentaAt(Rectangle client) throws AWTException {
        BufferedImage img = new Robot().createScreenCapture(client);
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int c = img.getRGB(x, y);
                int rr = (c >> 16) & 0xFF;
                int gg = (c >> 8) & 0xFF;
                int bb = c & 0xFF;
                if (rr > 200 && gg < 80 && bb > 200) {
                    n++;
                }
            }
        }
        return n;
    }

    private static Rectangle pickSecondaryScreen() {
        Rectangle best = new Rectangle(0, 0, 1280, 800);
        for (java.awt.GraphicsDevice gd : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle b = gd.getDefaultConfiguration().getBounds();
            if (b.x != 0 || b.y != 0) {
                return b;      // 非主屏优先（本项目开发期宿主就在次屏）
            }
            best = b;
        }
        return best;
    }

    // ---------------- 记账 ----------------

    private static void section(String name) {
        System.out.println();
        System.out.println("── " + name + " ──");
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  [PASS] " + what);
        } else {
            fail++;
            System.out.println("  [FAIL] " + what + "  ⇒ " + detail);
        }
    }

    private static void summary() {
        System.out.println();
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        System.out.println("EMBED_RESULT=" + (fail == 0 ? "ALL_PASS" : "HAS_FAIL"));
        System.exit(fail == 0 ? 0 : 1);
    }
}
