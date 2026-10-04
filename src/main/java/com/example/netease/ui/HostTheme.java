package com.example.netease.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.util.List;

/**
 * 宿主（Salt Player for Windows）主题令牌 —— 让插件自绘的窗口看起来就是宿主自己的窗口。
 *
 * <p><b>为什么需要它</b>：0.11.27 用户规格「这登录打开后的样式要和软件的样式一样，或者说直接调用
 * 软件内的ui层就行」。宿主是 Compose 应用（jpackage app-image：{@code app/Salt Player for
 * Windows.cfg} 记 {@code -XX:AOTCache}、{@code main-class MainKt}，{@code runtime/} 是完整 JDK 25），
 * 工坊 API 的 19 个类里没有任何 UI 能力（{@code WorkshopApi$Ui} 只有 toast），插件的构建链又只有
 * javac（无 Kotlin/Compose 编译器）⇒ <b>「调用宿主 UI 层」不可行</b>。可行的最高保真做法是把宿主的
 * 主题令牌量出来、用 Swing 复刻 —— 本类就是那份令牌表。</p>
 *
 * <p><b>令牌从哪来</b>：宿主<b>自己的</b> edittext 模态窗（配置页点开任意一行时弹出的那个）——
 * 真机截图 {@code build/diag/p323-config.png}（1920×1032）逐像素量取（Python/PIL+numpy），
 * 测量结论与脚本见 {@code docs/51-登录对话框-0.11.27.md} §2。量到的关键结论：</p>
 * <ul>
 *   <li><b>整窗遮罩 = 纯黑 60%</b>：同一像素在「有模态 / 无模态」两张截图里 199→80、35→14，
 *       同为 ×0.40（乘性 ⇒ 黑 α=0.60）。</li>
 *   <li>卡片 580 宽、底色 #262626、1 px 边线 #2F3030、圆角 16；内边距 左右 20、顶 19、底 16。</li>
 *   <li>输入框 540×35、底色 #2A2A2A、聚焦时描边 #4CC2FF。</li>
 *   <li>按钮 260×32 <b>药丸</b>（圆角 = 半高）：次要 #232323 + 文字 #B2B5B9，主要 #4CC2FF + 纯白。</li>
 *   <li>文字 #EBEEF1：标题 20 px、正文 15 px（行距 19）、按钮 15 px。</li>
 * </ul>
 *
 * <p><b>字体</b>：宿主 {@code app/resources} 里不带任何字体文件（只有 dll / ico / jpg）⇒ 宿主文字
 * 走系统字体；本类同样只从系统字体里挑，逐个降级到最后兜底 {@link Font#SANS_SERIF}。</p>
 *
 * <p>本类是纯常量 + 纯函数，无状态、无副作用（{@code pickFamily} 在无显示环境里也只是降级）。</p>
 */
public final class HostTheme {

    // ------------------------------------------------------------------ 颜色

    /** 页面底色（宿主配置页背景）。 */
    public static final Color PAGE_BG = new Color(0x12, 0x12, 0x12);
    /** 卡片底色（宿主模态窗 / 页面卡片）。 */
    public static final Color CARD_BG = new Color(0x26, 0x26, 0x26);
    /** 卡片 1 px 边线（比底色亮一档）。 */
    public static final Color CARD_EDGE = new Color(0x2F, 0x30, 0x30);
    /** 输入框底色。 */
    public static final Color FIELD_BG = new Color(0x2A, 0x2A, 0x2A);
    /** 输入框未聚焦时的边线。 */
    public static final Color FIELD_EDGE = new Color(0x3A, 0x3A, 0x3A);
    /** 宿主强调色（按钮主色 / 聚焦描边 / 文本选区）。 */
    public static final Color ACCENT = new Color(0x4C, 0xC2, 0xFF);
    /** 主文字色。 */
    public static final Color TEXT = new Color(0xEB, 0xEE, 0xF1);
    /** 次文字色（次要按钮上的字）。 */
    public static final Color TEXT_DIM = new Color(0xB2, 0xB5, 0xB9);
    /** 次要按钮底色。 */
    public static final Color BTN2_BG = new Color(0x23, 0x23, 0x23);
    /** 主要按钮文字色（宿主就是纯白）。 */
    public static final Color BTN1_TEXT = Color.WHITE;
    /** 输入框占位文字（宿主没有这个控件的量值；取页面级灰阶，不引入新色相）。 */
    public static final Color PLACEHOLDER = new Color(0x6E, 0x72, 0x76);
    /** 成功态文字（沿用插件既有语义色，不是宿主令牌）。 */
    public static final Color OK = new Color(0x3D, 0xDC, 0x84);
    /** 失败态文字（同上）。 */
    public static final Color ERR = new Color(0xFF, 0x8A, 0x80);

    /** 整窗遮罩：纯黑 60%（实测 ×0.40）。 */
    public static final float SCRIM_ALPHA = 0.60f;

    // ------------------------------------------------------------------ 尺寸（像素）

    /** 卡片宽度（宿主模态窗实测 580）。 */
    public static final int CARD_W = 580;
    public static final int CARD_PAD_X = 20;
    public static final int CARD_PAD_TOP = 19;
    public static final int CARD_PAD_BOTTOM = 16;
    public static final int CARD_RADIUS = 16;
    /** 输入框高度（宿主实测 35）。 */
    public static final int FIELD_H = 35;
    public static final int FIELD_RADIUS = 10;
    /** 按钮高度 / 宽度 / 两个按钮之间的间距（宿主实测 32 / 260 / 20）。 */
    public static final int BTN_H = 32;
    public static final int BTN_W = 260;
    public static final int BTN_GAP = 20;
    /** 「获取验证码」按钮宽度（宿主没有对应控件，按同一视觉语言的次要按钮定尺寸）。 */
    public static final int SEND_W = 120;
    public static final int SEND_GAP = 10;

    // ------------------------------------------------------------------ 字号

    public static final int TITLE_PX = 20;
    public static final int BODY_PX = 15;
    public static final int SMALL_PX = 13;
    public static final int BTN_PX = 15;
    /** 正文行距（宿主模态窗 5 行说明实测 19 px；Swing 的 Label 行高由字体推得，这里按 19 留白）。 */
    public static final int LINE_H = 19;

    private static final String FAMILY = pickFamily();

    private HostTheme() {
    }

    /** 挑一个系统里真的有的字体族（宿主不带字体 ⇒ 两边都吃系统字体）。 */
    private static String pickFamily() {
        String[] want = {"Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI"};
        try {
            List<String> have = List.of(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
            for (String w : want) {
                for (String h : have) {
                    if (h.equalsIgnoreCase(w)) {
                        return h;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 无显示环境（离屏探针 / 服务化）⇒ 用兜底族，不抛
        }
        return Font.SANS_SERIF;
    }

    /** 实际选中的字体族名（自检用）。 */
    public static String family() {
        return FAMILY;
    }

    public static Font font(int px) {
        return new Font(FAMILY, Font.PLAIN, px);
    }

    public static Font bold(int px) {
        return new Font(FAMILY, Font.BOLD, px);
    }

    /** 按钮文字：宿主按钮不加粗。 */
    public static Font button() {
        return font(BTN_PX);
    }

    /** 遮罩色（{@link #SCRIM_ALPHA} 对应的 {@link Color}）。 */
    public static Color scrim() {
        return new Color(0f, 0f, 0f, SCRIM_ALPHA);
    }

    /** 把颜色按比例往目标色靠（{@code t}=0 原色，{@code t}=1 目标色）—— 用于悬停 / 按下 / 禁用态。 */
    public static Color mix(Color base, Color toward, float t) {
        float k = Math.max(0f, Math.min(1f, t));
        return new Color(
                Math.round(base.getRed() + (toward.getRed() - base.getRed()) * k),
                Math.round(base.getGreen() + (toward.getGreen() - base.getGreen()) * k),
                Math.round(base.getBlue() + (toward.getBlue() - base.getBlue()) * k));
    }

    /**
     * 文字渲染提示（0.11.28）：显式开<b>灰度</b>抗锯齿。
     *
     * <p>为什么需要：Swing 在 Windows 上默认吃桌面字体提示（次像素 LCD 抗锯齿），字形边缘会带
     * 彩色边；而宿主自己的弹窗是灰度抗锯齿。更要紧的是 0.11.27 的<b>逐像素透明窗</b> ——
     * 透明表面上走 LCD 抗锯齿时字形会发虚。所以凡是画字的地方，都在 {@code super.paintComponent} /
     * {@code drawString} <b>之前</b>把这条提示设进 {@link Graphics2D}。</p>
     */
    public static void textHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    /** 变亮 / 变暗（{@code k} 为 -1..1，正数往白走、负数往黑走）。 */
    public static Color shade(Color base, float k) {
        return mix(base, k >= 0 ? Color.WHITE : Color.BLACK, Math.abs(k));
    }
}
