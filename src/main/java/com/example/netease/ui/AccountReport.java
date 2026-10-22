package com.example.netease.ui;

import com.example.netease.svc.AccountService;
import com.example.netease.svc.SmsLogin;

import java.util.List;

/**
 * 「当前账号」文案的<b>唯一真源</b>（0.12.0 / 批次 5：从旧 Swing 窗口 {@code AccountStatusWindow}
 * 抽出的纯函数，无 Swing / AWT 依赖）。
 *
 * <p>为什么单独成类：原生账号页（Compose）与自动检查（harness）都必须渲染同一段文本；
 * 旧窗口删除后，这段「未登录一行 / 已登录三行」的规则不能再藏在某个窗口类里，否则探针只能
 * 反向验证「窗口没被建起来」，商品文案与断言会各算一套。</p>
 *
 * <p>规则（0.11.36 起未变）：未登录 / 非 LOGGED_IN ⇒ 恰好一行 {@link #NOT_LOGGED_IN}；
 * 已登录 ⇒ 恰好三行「账号名字：… / 账号 ID：… / 会员信息：…」。会员信息优先显示档位名
 * （黑胶SVIP / 黑胶VIP / 音乐包），档位说不清时通用「会员」，没会员「无会员」，uid 未知「未知」。</p>
 */
public final class AccountReport {

    /** 未登录时的唯一一行（原生页与探针共用；不要再在别处写字面量）。 */
    public static final String NOT_LOGGED_IN = "当前未登录";

    /** 三行明细的标签（顺序 = {@link #rows} 里已登录三行的顺序）。 */
    public static final List<String> FACT_LABELS = List.of("账号名字", "账号 ID", "会员信息");

    private AccountReport() {
    }

    /**
     * 把登录态渲染成要显示的行：未登录 / 非 LOGGED_IN ⇒ 恰好一行；已登录 ⇒ 恰好三行「标签：值」。
     */
    public static List<String> rows(AccountService.LoginStatus st) {
        if (st == null || st.state() != AccountService.State.LOGGED_IN) {
            return List.of(NOT_LOGGED_IN);
        }
        List<String> vals = values(st);
        return List.of(
                FACT_LABELS.get(0) + "：" + vals.get(0),
                FACT_LABELS.get(1) + "：" + vals.get(1),
                FACT_LABELS.get(2) + "：" + vals.get(2));
    }

    /** 三行的值（下标与 {@link #FACT_LABELS} 一一对应）。 */
    public static List<String> values(AccountService.LoginStatus st) {
        long uid = st.uid();
        String nick = st.nickname() == null ? "" : st.nickname().trim();
        String name = SmsLogin.accountName(st);
        // uid 未知时不拿「UID 0」冒充名字：昵称空白、或昵称是号码形态而被 accountName 退成
        // 「UID <uid>」时，与同行的「账号 ID：未知」保持一致给「未知」。
        if (uid <= 0L && (name.isEmpty() || name.equals("UID " + uid))) {
            name = "未知";
        } else if (name.isEmpty()) {
            name = "未知";
        } else if (nick.isEmpty() && uid <= 0L) {
            name = "未知";
        }
        return List.of(
                name,
                uid > 0L ? String.valueOf(uid) : "未知",
                vipText(st));
    }

    /** 「会员信息」那一行的值（见类注释的优先级说明）。 */
    public static String vipText(AccountService.LoginStatus st) {
        if (st.uid() <= 0L) {
            return "未知";
        }
        if (!st.vip()) {
            return "无会员";
        }
        String tier = st.vipName() == null ? "" : st.vipName().trim();
        return tier.isEmpty() ? "会员" : tier;
    }

    /** 多行文本（原生页逐行显示、探针按行断言）。 */
    public static String render(AccountService.LoginStatus st) {
        return String.join("\n", rows(st));
    }

    /** 单行化（日志用；行间用「 ｜ 」连接）。 */
    public static String oneLine(AccountService.LoginStatus st) {
        return render(st).replace("\n", " ｜ ");
    }
}
