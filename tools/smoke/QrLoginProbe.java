package com.example.netease.net;

import com.example.netease.core.Json;

import javax.imageio.ImageIO;
import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 二维码登录**端到端**探针（真机联调用，不属于插件产物）。
 *
 * <p>背景：0.2.2 真机实测「手机确认后匿名 {@code /api/} 通道开始返回 8821」，
 * 界面永远停在「已扫码，请在手机上确认」。本探针把**两个通道**放进同一轮轮询里对照，
 * 用来回答一个问题：8821 是「通道选错了」还是「账号/IP 被风控」。</p>
 *
 * <ul>
 *   <li>{@code weapi=} —— 生产路径 {@link NeteaseApi#qrPoll(String)}（0.2.3 起 weapi 优先），
 *       即官网扫码页走的那条通道；</li>
 *   <li>{@code anon=} —— 原始匿名接口 {@code /api/login/qrcode/client/login?key=&type=1}，
 *       0.2.2 的主路径（当年就是它回 8821）。</li>
 * </ul>
 *
 * <p>与宿主、插件完全无关：直接调 net 层 API。用法：
 * {@code java -cp <classes> com.example.netease.net.QrLoginProbe [输出目录] [超时秒数]}。</p>
 *
 * <p>输出：{@code qr.png}、{@code qr-4x.png}（4 倍最近邻放大，给手机扫屏幕用），并尝试打开看图器。
 * 退出码：0 = 拿到 803 且账号信息完整；2 = 拿到 803 但账号信息缺失；1 = 超时/过期/失败。</p>
 *
 * <p><b>绝不打印 cookie 值</b>：只打印名字是否存在。</p>
 */
public final class QrLoginProbe {

    private static final String[] WATCH = {"MUSIC_U", "__csrf", "__remember_me", "MUSIC_A", "NMTID"};

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : "tools/smoke/out-qrlogin");
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 240;
        long pollMs = 1500L;
        Files.createDirectories(outDir);

        System.out.println("[QL] 开始：输出目录=" + outDir.toAbsolutePath() + " 超时=" + seconds + "s 间隔=" + pollMs + "ms");
        Dto.QrSession s = NeteaseApi.qrCreate();
        if (s == null || s.key() == null || s.key().isBlank()) {
            System.out.println("[QL] state=FAIL reason=qrCreate 未返回 key");
            System.exit(1);
        }
        byte[] png = s.pngBytes();
        if (png == null || png.length == 0) {
            System.out.println("[QL] state=FAIL reason=PNG 为空（QrCode 生成失败）");
            System.exit(1);
        }
        Path plain = outDir.resolve("qr.png");
        Path big = outDir.resolve("qr-4x.png");
        Files.write(plain, png);
        Files.write(big, upscale(png, 4));
        System.out.println("[QL] 二维码已生成：key 长度=" + s.key().length()
                + " png=" + png.length + "B 放大图=" + big.toAbsolutePath());
        System.out.println("[QL] 二维码内容=" + NeteaseApi.qrContent(s.key()));
        System.out.println("[QL] 请用手机网易云音乐 App 扫码并在手机上点确认；本探针每 " + pollMs
                + "ms 同时打两个通道的轮询码。");
        openQuietly(big.toFile());

        long deadline = System.currentTimeMillis() + seconds * 1000L;
        int lastProd = Integer.MIN_VALUE;
        int lastAnon = Integer.MIN_VALUE;
        int n = 0;
        while (System.currentTimeMillis() < deadline) {
            n++;
            String prodText;
            try {
                int prod = NeteaseApi.qrPoll(s.key());
                prodText = String.valueOf(prod);
                if (prod != lastProd) {
                    lastProd = prod;
                    System.out.println("[QL] poll#" + n + " weapi=" + prod + " " + label(prod)
                            + " t=" + elapsed(deadline, seconds) + "s");
                }
                if (prod == 803) {
                    report("weapi", prod);
                    return;
                }
                if (prod == 800) {
                    System.out.println("[QL] state=EXPIRED weapi 通道返回 800（二维码过期）");
                    System.exit(1);
                }
            } catch (Throwable t) {
                prodText = "ERR(" + t.getClass().getSimpleName() + ": " + msg(t) + ")";
            }

            String anonText;
            try {
                Object root = Json.parse(anon(s.key()));
                int anon = Json.integer(root, "code", Json.integer(root, "data.code", -1));
                anonText = String.valueOf(anon);
                if (anon != lastAnon) {
                    lastAnon = anon;
                    System.out.println("[QL] poll#" + n + " anon=" + anon + " " + label(anon)
                            + " msg=" + Json.str(root, "message", "")
                            + " t=" + elapsed(deadline, seconds) + "s");
                }
                if (anon == 803) {
                    report("anon", anon);
                    return;
                }
            } catch (Throwable t) {
                anonText = "ERR(" + t.getClass().getSimpleName() + ": " + msg(t) + ")";
            }
            if (n % 20 == 0) {
                System.out.println("[QL] poll#" + n + " 仍为 weapi=" + prodText + " anon=" + anonText
                        + "（" + elapsed(deadline, seconds) + "s）");
            }
            sleep(pollMs);
        }
        System.out.println("[QL] state=TIMEOUT 超时未拿到 803（weapi=" + lastProd + " anon=" + lastAnon
                + "，共轮询 " + n + " 次）");
        System.exit(1);
    }

    /** 原始匿名通道（0.2.2 的主路径）：包内可见的 {@code Http.get} 直接打，便于与 weapi 对照。 */
    private static String anon(String key) throws Exception {
        String url = Http.BASE + "/api/login/qrcode/client/login?key="
                + URLEncoder.encode(key, StandardCharsets.UTF_8) + "&type=1";
        return Http.get(url, null);
    }

    private static void report(String channel, int code) {
        System.out.println("[QL] code=" + code + " 扫码成功（通道=" + channel + "）");
        String cookies;
        try {
            cookies = NeteaseApi.exportCookies();
        } catch (Throwable t) {
            System.out.println("[QL] state=FAIL exportCookies 抛异常：" + t.getClass().getSimpleName() + " " + msg(t));
            System.exit(2);
            return;
        }
        System.out.println("[QL] cookie 串长度=" + (cookies == null ? -1 : cookies.length()));
        for (String name : WATCH) {
            System.out.println("[QL] cookie[" + name + "]=" + (has(cookies, name) ? "有" : "无"));
        }
        int exit = 2;
        try {
            System.out.println("[QL] isLoggedIn=" + NeteaseApi.isLoggedIn());
            Dto.Account acc = NeteaseApi.accountInfo();
            if (acc == null) {
                System.out.println("[QL] state=PARTIAL 803 成功但 accountInfo 为空（cookie 可能不完整）");
            } else {
                System.out.println("[QL] state=OK 昵称=" + acc.nickname() + " uid=" + acc.userId() + " vip=" + acc.vip());
                exit = 0;
            }
        } catch (Throwable t) {
            System.out.println("[QL] state=PARTIAL accountInfo 失败：" + t.getClass().getSimpleName() + " " + msg(t));
        }
        System.exit(exit);
    }

    private static boolean has(String cookies, String name) {
        if (cookies == null) {
            return false;
        }
        for (String part : cookies.split(";")) {
            String p = part.trim();
            if (p.startsWith(name + "=")) {
                return true;
            }
        }
        return false;
    }

    private static String label(int code) {
        switch (code) {
            case 800: return "(已过期)";
            case 801: return "(等待扫码)";
            case 802: return "(已扫码，待手机确认)";
            case 803: return "(登录成功)";
            case 8821: return "(风控：需要行为验证码验证)";
            default: return "(未知码)";
        }
    }

    private static byte[] upscale(byte[] png, int scale) throws Exception {
        BufferedImage src = ImageIO.read(new java.io.ByteArrayInputStream(png));
        if (src == null) {
            return png;
        }
        int w = src.getWidth() * scale;
        int h = src.getHeight() * scale;
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = dst.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        ImageIO.write(dst, "png", bos);
        return bos.toByteArray();
    }

    private static void openQuietly(File f) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(f);
            }
        } catch (Throwable t) {
            System.out.println("[QL] 未能自动打开看图器（不影响）：" + msg(t) + " —— 请手动打开上面的放大图路径");
        }
    }

    private static long elapsed(long deadline, int totalSeconds) {
        long remain = deadline - System.currentTimeMillis();
        return Math.max(0L, totalSeconds - remain / 1000L);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String msg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? "(无消息)" : m;
    }

    private QrLoginProbe() {
    }
}
