package com.example.netease.net;

import java.util.ArrayList;
import java.util.List;

/**
 * 登录业务码解析探针（离线，零网络）——<b>0.11.29 坑 39 的离屏证据</b>。
 *
 * <p>真机排障（2026-10-02）：用户报「退出登录后重新用验证码登录总是网络请求失败，但我网没问题」。
 * 实测伺服端答复是 {@code code=-462}（网易云要求先完成行为验证），而 {@code codeFromMessage}
 * 只吃连续数字、负号被跳过 ⇒ 取不到码 ⇒ {@code LoginOutcome.code = -1} ⇒ 界面显示
 * 「网络请求失败（请检查网络后重试）」，把用户引到查网络上。本探针把这条规则钉死在离线证据里：
 * 负数码必须解析出来、真网络失败仍须是网络文案、风控码必须能给出「改用扫码登录」的判读。</p>
 *
 * <p>用法：{@code java -cp "build\skin-check;<编译期 classpath>" com.example.netease.net.LoginCodeParseProbe}
 * （PowerShell 里 {@code -Dstdout.encoding=UTF-8} 要加引号）。退出码：0 = 全通过，1 = 有失败。</p>
 */
public final class LoginCodeParseProbe {

    private static int pass;
    private static int fail;
    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("=== LoginCodeParseProbe：登录业务码解析与判读（离线，零网络）===");
        System.out.println();

        // ---------------------------------------------------------------- 一、业务码解析
        System.out.println("一、weapi 异常消息 → 业务码");
        // 真机原话（RiskProbe 在真机上抓到的那一条，逐字）
        String real = "weapi/login/cellphone 失败：code=-462 msg=验证成功后，可进行下一步操作哦~";
        check("负数风控码 -462 能解析出来（真机原话；0.11.28 及以前会解析成 null ⇒ -1）",
                Integer.valueOf(-462).equals(NeteaseApi.codeFromMessage(real)),
                "实测=" + NeteaseApi.codeFromMessage(real));
        check("正数码不受影响（503 发送太频繁）",
                Integer.valueOf(503).equals(NeteaseApi.codeFromMessage("weapi/sms/captcha 失败：code=503 msg=发送太频繁")),
                "实测=" + NeteaseApi.codeFromMessage("weapi/sms/captcha 失败：code=503 msg=发送太频繁"));
        check("网络失败消息里没有 code= ⇒ null（调用方据此给 -1）",
                NeteaseApi.codeFromMessage("weapi/login/cellphone 请求失败：connect timed out") == null,
                "实测=" + NeteaseApi.codeFromMessage("weapi/login/cellphone 请求失败：connect timed out"));
        check("只有 code= 没有数字 ⇒ null",
                NeteaseApi.codeFromMessage("weapi/login/cellphone 失败：code= msg=") == null,
                "实测=" + NeteaseApi.codeFromMessage("weapi/login/cellphone 失败：code= msg="));
        check("只有负号没有数字 ⇒ null",
                NeteaseApi.codeFromMessage("weapi/login/cellphone 失败：code=- msg=") == null,
                "实测=" + NeteaseApi.codeFromMessage("weapi/login/cellphone 失败：code=- msg="));
        check("伺服端原话能从消息里取回（含 -462 那条）",
                "验证成功后，可进行下一步操作哦~".equals(NeteaseApi.msgFromMessage(real)),
                "实测=[" + NeteaseApi.msgFromMessage(real) + "]");

        // ---------------------------------------------------------------- 二、判读文案
        System.out.println();
        System.out.println("二、LoginOutcome 判读（界面文案直接取自这里）");
        NeteaseApi.LoginOutcome risk = new NeteaseApi.LoginOutcome(false, -462, "验证成功后，可进行下一步操作哦~");
        NeteaseApi.LoginOutcome net = new NeteaseApi.LoginOutcome(false, -1, "connect timed out");
        NeteaseApi.LoginOutcome freq = new NeteaseApi.LoginOutcome(false, 503, "发送太频繁");
        NeteaseApi.LoginOutcome qr = new NeteaseApi.LoginOutcome(false, 8821, "需要行为验证码验证");
        check("-462 判读含「-462」且指向扫码（实测：" + risk.hint() + "）",
                risk.hint().contains("-462") && risk.hint().contains("扫码"),
                "hint()=" + risk.hint());
        check("-462 不再被说成网络问题（旧缺陷就是这句）",
                !risk.hint().contains("网络"), "hint()=" + risk.hint());
        check("-1（真网络失败）仍是网络文案：" + net.hint(),
                net.hint().contains("网络"), "hint()=" + net.hint());
        check("riskBlocked()：-462 与 8821 为真（登录框据此把状态行变成可点入口）",
                risk.riskBlocked() && qr.riskBlocked(), " -462=" + risk.riskBlocked() + " 8821=" + qr.riskBlocked());
        check("riskBlocked()：-1 / 503 为假（别把网络失败和限流也说成风控）",
                !net.riskBlocked() && !freq.riskBlocked(),
                " -1=" + net.riskBlocked() + " 503=" + freq.riskBlocked());
        check("503 仍是「太频繁」判读：" + freq.hint(),
                freq.tooFrequent() && freq.hint().contains("频繁"), "hint()=" + freq.hint());

        System.out.println();
        System.out.println("PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) {
            System.out.println("失败项：");
            for (String s : FAILURES) {
                System.out.println("  - " + s);
            }
            System.out.println("LOGIN_CODE_RESULT=FAILED");
        } else {
            System.out.println("LOGIN_CODE_RESULT=ALL_PASS");
        }
        System.out.flush();
        System.exit(fail > 0 ? 1 : 0);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            FAILURES.add(name + " —— " + detail);
            System.out.println("  [FAIL] " + name + " —— " + detail);
        }
    }

    private LoginCodeParseProbe() {
    }
}
