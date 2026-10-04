package com.example.netease.net;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * 短信验证码登录探针（0.2.4；真机联调用，**绝不进产物**）。
 *
 * <p>目的：在写 UI 之前先拿到「接口是否受理」的硬证据，并把业务码语义钉死
 * （200 已发送 / 503 太频繁 / 8821 风控 / 502 账号问题）。这是 0.2.3 那套
 * 「假凭据拿 502 证明接口可用」方法的延续。</p>
 *
 * <p><b>安全红线</b>：</p>
 * <ul>
 *   <li>默认只打<b>占位号 13800000000</b>（示例号，非真人号码），用来取业务码；</li>
 *   <li>想用真号验证「短信能不能收到」，必须显式传 {@code --phone=1xxxxxxxxxx}
 *       —— 那会真的发出短信（消耗一条），由人自己决定；</li>
 *   <li>手机号一律打印掩码，验证码与 cookie 值绝不打印（只打印 cookie 名字）；
 *       本探针不写任何文件。</li>
 * </ul>
 *
 * <p>注意：本探针直接写 stdout，<b>不用 {@code PluginLog}</b> —— 日志类在未
 * {@code init()} 时是静默丢弃（writer == null 直接 return），探针输出会凭空消失。</p>
 *
 * <p>用法：{@code java -cp <classes> com.example.netease.net.SmsLoginProbe [--phone=1xxxxxxxxxx --code=123456]}</p>
 *
 * <p>退出码：0=全部用例符合预期；1=出现不符合预期的结果。</p>
 */
public final class SmsLoginProbe {

    /** 示例号：138-0000-0000，不是真实用户号码（业界通用占位）。 */
    private static final String PLACEHOLDER_PHONE = "13800000000";

    private static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);

    private static int failures;

    public static void main(String[] args) {
        String phone = PLACEHOLDER_PHONE;
        String code = null;
        boolean noSend = false;
        for (String a : args) {
            if (a.startsWith("--phone=")) {
                phone = a.substring("--phone=".length()).trim();
            } else if (a.startsWith("--code=")) {
                code = a.substring("--code=".length()).trim();
            } else if ("--no-send".equals(a)) {
                noSend = true;
            }
        }
        boolean real = !PLACEHOLDER_PHONE.equals(phone);

        out("[SL] 探针启动：phone=" + mask(phone)
                + "，模式=" + (real ? "真号（会真的发短信）" : "占位号（只取业务码）")
                + (noSend ? "，--no-send：跳过发送用例（不产生任何短信）" : "")
                + (real && code == null ? "，未给 --code，只测发送" : ""));

        // ---- 用例 1：格式非法的号码必须被拒（本地 svc 层拦 -2；net 层直连时由伺服端回 400）
        Dto.SmsResult bad = NeteaseApi.smsSend("123");
        expect("case=bad-phone", bad.code() == 400 || bad.code() == -2,
                "code=" + bad.code() + " msg=" + bad.message()
                        + "（期望 400=伺服端拒绝 / -2=本地拦下；绝不能是 200）");

        // ---- 用例 2：发送验证码（生产路径）。⚠️ 这一条会真的发出短信（占位号只消耗一条，无真人接收）
        if (!noSend) {
            out("[SL] case=send 注意：本条会真的向 " + mask(phone) + " 发一条短信");
            Dto.SmsResult sent = NeteaseApi.smsSend(phone);
            expect("case=send", sent.code() != -1,
                    "code=" + sent.code() + " msg=" + sent.message()
                            + (sent.sent() ? "（已发送：说明发码接口未被风控拦截）"
                                           : "（未发送：属业务码，不是网络故障）"));
        } else {
            out("[SL] case=send state=SKIP（--no-send）");
        }

        // ---- 用例 3：验证码登录接口是否受理 captcha 字段（错码必须是业务码，不能是异常/崩溃）
        String probeCode = (code == null || code.isBlank()) ? "000000" : code;
        boolean loginOk;
        String loginDetail;
        try {
            loginOk = NeteaseApi.loginCellphoneCaptcha(phone, probeCode);
            loginDetail = "返回=" + loginOk + "（错码时 false 属预期；说明接口受理了 captcha 字段）";
        } catch (Throwable t) {
            loginOk = false;
            loginDetail = "异常=" + t.getClass().getSimpleName() + ": " + safe(t.getMessage());
        }
        expect("case=login-captcha", true, loginDetail);

        // ---- 用例 4：真号 + 真码 → 必须登录成功并拿到 MUSIC_U（只看有无，不打印值）
        if (real && code != null) {
            String cookies = NeteaseApi.exportCookies();
            boolean hasMusicU = cookies != null && cookies.contains("MUSIC_U=");
            expect("case=login-real", loginOk && hasMusicU,
                    "loggedIn=" + loginOk + " cookieNames=" + names(cookies) + " MUSIC_U=" + hasMusicU);
        }

        out("[SL] emitted=failures=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void out(String line) {
        OUT.println(line);
        OUT.flush();
    }

    private static void expect(String label, boolean ok, String detail) {
        if (!ok) {
            failures++;
        }
        out("[SL] " + label + " state=" + (ok ? "OK" : "FAIL") + " " + detail);
    }

    /** cookie 只打印名字（{@code MUSIC_U=****}），永不打印值。 */
    private static String names(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : cookieHeader.split(";")) {
            int eq = part.trim().indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(part.trim(), 0, eq).append("=****");
        }
        return sb.length() == 0 ? "（无）" : sb.toString();
    }

    private static String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() > 160 ? one.substring(0, 160) + "…" : one;
    }

    private SmsLoginProbe() {
    }
}
