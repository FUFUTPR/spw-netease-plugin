package com.example.netease.ui;

import javax.swing.JFrame;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * <b>离线</b>探针：把登录框<b>离屏渲染</b>成一张图，然后<b>逐像素</b>量它 —— 证明
 * 「皮肤与宿主同款」这件事不是注释里的一句话，而是真的落在界面像素上。
 *
 * <p>为什么需要它：宿主没法被自动化操作（点配置页要靠人），而皮肤又恰恰是最需要看的东西。
 * 本探针用与真窗<b>完全同一份</b>组件树（{@code LoginDialog.buildPreview()}）离屏渲染，
 * 于是每次改动都能在没有人参与的情况下被量一遍。真机那一半由用户在宿主里开一次窗完成
 * （截图 {@code build/diag/p328-dialog.png} 再量同样的令牌）。</p>
 *
 * <p>断言面（九组）：<b>遮罩</b>（整窗纯黑 60%，宿主实测 ×0.40）；<b>卡片</b>（580 宽、
 * #262626、1 px 边线 #2F3030、圆角 16）；<b>分段行</b>（0.11.30 需求①②：扫码 / 验证码登录两枚
 * 260×32 药丸，选中者刷主色 #4CC2FF + 白字）；<b>验证码档控件</b>（输入框 540/410 × 35 #2A2A2A、
 * 「获取验证码」120×35、两个动作按钮 260×32 药丸、主按钮 #4CC2FF + 纯白字、按钮间距 20）；
 * <b>三档结构与文案</b>（扫码 / 验证码 / 风控共用同一张卡片：输入框个数 2/0/0、主按钮
 * 「登录」/「刷新二维码」/「打开验证窗」、卡高 274 / 456 / 314、扫码档主体区画着二维码而
 * <b>风控档已不再画码</b>（0.11.33 用户裁定：安全验证在<b>电脑上</b>完成 + 0.11.34：验证面盖成
 * 本软件自己的窗口，主体区换成两行说明））；<b>排版</b>
 * （标题 #EBEEF1、标题与分段行之间<b>没有</b>说明段 —— 0.11.28 起用户要求删掉整段说明、
 * 无旧皮肤颜色残留、文案全部为短句）；<b>快照档</b>（0.11.28 真机默认外观：不透明窗 + 静态底图 +
 * 遮罩，底图被压暗但仍可分辨）；<b>重绘幂等</b>（0.11.28 修的就是这条：有清底者 ⇒ 同一张图上
 * 重复绘制 / 局部重绘都逐像素不变；透明档复现旧缺陷的叠影机制）；<b>底图裁剪</b>（0.11.29 坑 40：
 * 宿主窗口矩形含 8px 不可见边框，越界要按屏幕求交而不是整块放弃）。</p>
 *
 * <p><b>本文件不属于插件产物</b>（{@code tools/} 下的开发期探针）；放在 {@code com.example.netease.ui}
 * 包内是为了直接调用包内可见的 {@code LoginDialog.buildPreview()}，不做反射。</p>
 *
 * <p>构建与运行（仓库根）：</p>
 * <pre>
 * $cp = "tools\.cache\spw-workshop-api-host.jar;tools\.cache\pf4j-3.12.0.jar;libs\sqlite-jdbc-3.41.2.2.jar"
 * Remove-Item -Recurse -Force build\skin-check -ErrorAction SilentlyContinue
 * javac --release 21 -encoding UTF-8 -cp $cp -d build\skin-check `
 *   (Get-ChildItem src\main\java\com\example\netease -Recurse -Filter *.java | % FullName) `
 *   tools\smoke\DialogSkinProbe.java
 * java "-Dstdout.encoding=UTF-8" -cp "build\skin-check;$cp" com.example.netease.ui.DialogSkinProbe [输出PNG]
 * </pre>
 * <p>退出码：0 = 全部 PASS，1 = 有 FAIL。默认把渲染图写到 {@code build/diag/p328-render.png}
 * （透明档），快照档写到同名 {@code -snapshot.png}。</p>
 */
public final class DialogSkinProbe {

    private static final List<String> FAILURES = new ArrayList<>();
    private static int pass;
    private static int fail;

    /** 卡片内容宽（= 卡宽 − 左右内边距），与 {@code LoginDialog.CONTENT_W} 同一个算法。 */
    private static final int CONTENT_W = HostTheme.CARD_W - HostTheme.CARD_PAD_X * 2;
    /** 验证码框宽（宿主实测 410 = 内容宽 − 按钮宽 − 间距），与 {@code LoginDialog.CODE_W} 同一个算法。 */
    private static final int CODE_W = CONTENT_W - HostTheme.SEND_W - HostTheme.SEND_GAP;

    private DialogSkinProbe() {
    }

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "build/diag/p328-render.png";
        System.out.println("=== DialogSkinProbe：登录框皮肤（离线离屏渲染，零网络）===");
        System.out.println("字体族：" + HostTheme.family());

        JPanel smsPane = LoginDialog.buildPreview();                 // 验证码档（0.11.27 起的老皮肤）
        BufferedImage img = renderTo(smsPane);
        File f = new File(out);
        if (f.getParentFile() != null) {
            f.getParentFile().mkdirs();
        }
        ImageIO.write(img, "png", f);
        System.out.println("渲染图：" + f.getAbsolutePath() + "（" + img.getWidth() + "x" + img.getHeight() + "）");

        // 0.11.30 起同一张卡片里还有两档：扫码档（真窗打开时默认落在这一档）与风控档（-462 行为验证）。
        // 三档都得量：只量老那一档的话，另外两档的布局 / 文案 / 尺寸回退没有任何门禁看得见。
        JPanel qrPane = LoginDialog.buildPreview(null, "qr");
        BufferedImage qrImg = renderTo(qrPane);
        File fq = new File(out.replace(".png", "-qr.png"));
        ImageIO.write(qrImg, "png", fq);
        System.out.println("扫码档渲染图：" + fq.getAbsolutePath() + "（" + qrImg.getWidth() + "x" + qrImg.getHeight() + "）");
        JPanel riskPane = LoginDialog.buildPreview(null, "risk");
        BufferedImage riskImg = renderTo(riskPane);
        File fr = new File(out.replace(".png", "-risk.png"));
        ImageIO.write(riskImg, "png", fr);
        System.out.println("风控档渲染图：" + fr.getAbsolutePath() + "（" + riskImg.getWidth() + "x" + riskImg.getHeight() + "）");
        // 0.11.33 用户报障①：扫码被风控时，验证态必须**还站在「扫码」那一枚上**（以前串到「验证码登录」）。
        // 这一档是「同一个风控档的第二条来路」，几何与文案与 risk 档同款，只有分段枚的选择态不同。
        JPanel qrRiskPane = LoginDialog.buildPreview(null, "qrrisk");
        BufferedImage qrRiskImg = renderTo(qrRiskPane);
        File fqr = new File(out.replace(".png", "-qrrisk.png"));
        ImageIO.write(qrRiskImg, "png", fqr);
        System.out.println("扫码引来的风控档渲染图：" + fqr.getAbsolutePath() + "（" + qrRiskImg.getWidth() + "x" + qrRiskImg.getHeight() + "）");
        System.out.println();

        // ---------------------------------------------------------------- 一、遮罩
        section("一、整窗遮罩（宿主同款：纯黑 60% ⇒ alpha 153）");
        int corner = alpha(img, 4, 4);
        check("遮罩 alpha = 153 ± 2（实测 " + corner + "）", Math.abs(corner - 153) <= 2,
                "左上角像素 alpha=" + corner + "，期望约 " + Math.round(HostTheme.SCRIM_ALPHA * 255));
        check("遮罩是纯黑（R=G=B=0）",
                rgb(img, 4, 4) == 0 && rgb(img, img.getWidth() - 5, img.getHeight() - 5) == 0,
                "角落 rgb=" + Integer.toHexString(rgb(img, 4, 4)));

        // ---------------------------------------------------------------- 二、卡片
        section("二、卡片（宿主模态窗实测：580 宽 / #262626 / 圆角 16 / 1px 边线 #2F3030）");
        Rectangle card = bbox(img, HostTheme.CARD_BG, 1);
        check("卡片底色 #262626 存在", card != null, card == null ? "未找到" : "bbox=" + card);
        if (card == null) {
            summary();
            return;
        }
        check("卡片（填充 " + card.width + " + 两侧各 1px 边线）= 580 ± 2",
                Math.abs(card.width + 2 - HostTheme.CARD_W) <= 2, "bbox=" + card);
        check("卡片水平居中（左 " + card.x + " / 右空隙 " + (img.getWidth() - card.x - card.width)
                        + "，差 ≤ 2）",
                Math.abs(card.x - (img.getWidth() - card.x - card.width)) <= 2, "bbox=" + card);
        int edgeX = card.x + card.width / 2;
        check("圆角：卡片左上角 1px 处不是卡片色（被圆角切掉）",
                !near(img.getRGB(card.x + 1, card.y + 1), HostTheme.CARD_BG, 1),
                "(" + (card.x + 1) + "," + (card.y + 1) + ")="
                        + Integer.toHexString(img.getRGB(card.x + 1, card.y + 1)));
        check("圆角：卡片内 8px 处是卡片色",
                near(img.getRGB(card.x + 8, card.y + 8), HostTheme.CARD_BG, 2),
                "(" + (card.x + 8) + "," + (card.y + 8) + ")="
                        + Integer.toHexString(img.getRGB(card.x + 8, card.y + 8)));
        check("卡片 1px 边线 #2F3030（顶边中点亮一档）",
                near(img.getRGB(edgeX, card.y - 1), HostTheme.CARD_EDGE, 8),
                "y=" + (card.y - 1) + " 处=" + Integer.toHexString(img.getRGB(edgeX, card.y - 1)));

        // 内容几何（后面几节共用）：内容左缘 / 内容宽 / 内容右缘
        int contentX = card.x + HostTheme.CARD_PAD_X;        // 内容左缘（宿主实测：卡片 + 20）
        int contentW = HostTheme.CARD_W - HostTheme.CARD_PAD_X * 2;
        int expectRight = contentX + contentW;               // 内容右缘（不含）

        // ---------------------------------------------------------------- 三、分段行
        // 0.11.30 需求①②：扫码与验证码是「同一张卡片里的分段切换」，真窗打开时默认落在扫码档。
        // 定位办法：选中的那一枚药丸整颗刷主色 #4CC2FF ⇒ 在它的列上竖扫这条色带，就得到分段行的 y 区间；
        // 扫 contentX + 320 这一列正好落在右枚（SMS 档 = 选中的「验证码登录」，QR 档 = 未选的「验证码登录」）。
        section("三、分段行（0.11.30 需求①②：扫码 / 验证码登录 同一张卡片内切换，打开默认扫码）");
        int[] segBand = verticalRunAt(img, contentX + 320, card.y, card.y + card.height, HostTheme.ACCENT, 3);
        check("分段行 高 = 32 ± 3（实测 " + len(segBand) + "）",
                Math.abs(len(segBand) - HostTheme.BTN_H) <= 3, "band=" + band(segBand));
        if (segBand != null) {
            List<int[]> segs = segments(img, segBand[0] + 1, segBand[1] - 1,
                    contentX - 4, expectRight + 4, HostTheme.CARD_BG, 2);
            check("分段行 = 两枚等宽药丸（扫码 + 验证码登录）", segs.size() == 2, "行内片段=" + describe(segs));
            if (segs.size() == 2) {
                int[] left = segs.get(0);
                int[] right = segs.get(1);
                int lw = left[1] - left[0] + 1;
                int rw = right[1] - right[0] + 1;
                int gap = right[0] - left[1] - 1;
                check("两枚各 260 ± 3（实测 " + lw + " / " + rw + "）",
                        Math.abs(lw - HostTheme.BTN_W) <= 3 && Math.abs(rw - HostTheme.BTN_W) <= 3,
                        "BTN_W=" + HostTheme.BTN_W);
                check("两枚间距 = 20 ± 3（实测 " + gap + "）", Math.abs(gap - HostTheme.BTN_GAP) <= 3,
                        "BTN_GAP=" + HostTheme.BTN_GAP);
                check("分段行左贴内容左缘、右贴内容右缘（" + left[0] + " ≈ " + contentX + " / "
                                + (right[1] + 1) + " ≈ " + expectRight + "）",
                        Math.abs(left[0] - contentX) <= 2 && Math.abs(right[1] + 1 - expectRight) <= 2,
                        "左=" + left[0] + " 右=" + (right[1] + 1));
                double la = ratio(img, left, segBand, HostTheme.BTN2_BG, 3);
                double ra = ratio(img, right, segBand, HostTheme.ACCENT, 3);
                check("验证码档：「扫码」未选（#232323 占比 " + pct(la) + "）、「验证码登录」选中（#4CC2FF 占比 " + pct(ra) + "）",
                        la > 0.4 && ra > 0.4, "BTN2_BG / ACCENT 各占其面积 " + pct(la) + " / " + pct(ra));
                check("选中那枚的文字是纯白（宿主白字同款）", hasNear(img, rect(right, segBand), Color.WHITE, 12),
                        "在 #4CC2FF 区域内寻找接近 (255,255,255) 的像素");
            }
        }

        // ---------------------------------------------------------------- 四、验证码档控件
        section("四、验证码档控件（宿主实测：输入框 540/410 × 35 #2A2A2A；「获取验证码」120×35；按钮药丸 260×32；主按钮 #4CC2FF）");

        // 竖扫点刻意避开「占位文字」与「圆角」：手机号框扫内容左缘 +200，验证码框扫 +350；
        // 两条都必须从「分段行以下」起扫 —— 否则同一列上先撞到的是分段行的药丸 / 手机号框（第一版就是这么误判的）
        int afterSeg = segBand == null ? card.y : segBand[1] + 6;
        int[] phoneBand = verticalRunAt(img, contentX + 200, afterSeg, card.y + card.height,
                HostTheme.FIELD_BG, 2);
        int[] codeBand = phoneBand == null ? null
                : verticalRunAt(img, contentX + 350, phoneBand[1] + 6, card.y + card.height,
                HostTheme.FIELD_BG, 2);
        check("手机号框 填充高 = 33（35 减去上下各 1px 描边）± 2（实测 " + len(phoneBand) + "）",
                Math.abs(len(phoneBand) - (HostTheme.FIELD_H - 2)) <= 2, "band=" + band(phoneBand));
        check("验证码框 填充高 = 33 ± 2（实测 " + len(codeBand) + "）",
                Math.abs(len(codeBand) - (HostTheme.FIELD_H - 2)) <= 2, "band=" + band(codeBand));

        if (phoneBand != null) {
            List<int[]> segs = segments(img, phoneBand[0] + 1, phoneBand[1] - 1,
                    contentX - 4, expectRight + 4, HostTheme.CARD_BG, 2);
            check("手机号框 宽 = 540 ± 3（实测 " + width(segs) + "）",
                    segs.size() == 1 && Math.abs(width(segs) - contentW) <= 3, "行内片段=" + describe(segs));
            check("手机号框贴左边距（左缘 ≈ 卡片 + 20 = " + contentX + "）",
                    !segs.isEmpty() && Math.abs(segs.get(0)[0] - contentX) <= 2,
                    "左缘=" + (segs.isEmpty() ? "无" : segs.get(0)[0]));
        }
        if (codeBand != null) {
            List<int[]> segs = segments(img, codeBand[0] + 1, codeBand[1] - 1,
                    contentX - 4, expectRight + 4, HostTheme.CARD_BG, 2);
            check("验证码行 = 两个控件（框 + 按钮）", segs.size() == 2, "行内片段=" + describe(segs));
            if (segs.size() == 2) {
                int w1 = segs.get(0)[1] - segs.get(0)[0] + 1;
                int w2 = segs.get(1)[1] - segs.get(1)[0] + 1;
                int gap = segs.get(1)[0] - segs.get(0)[1] - 1;
                check("验证码框 宽 = 410 ± 3（实测 " + w1 + "）", Math.abs(w1 - CODE_W) <= 3, "CODE_W=" + CODE_W);
                check("「获取验证码」宽 = 120 ± 3（实测 " + w2 + "）",
                        Math.abs(w2 - HostTheme.SEND_W) <= 3, "SEND_W=" + HostTheme.SEND_W);
                check("验证码框与按钮 间距 = 10 ± 3（实测 " + gap + "）",
                        Math.abs(gap - HostTheme.SEND_GAP) <= 3, "SEND_GAP=" + HostTheme.SEND_GAP);
                check("这一行右缘贴内容右缘（" + (segs.get(1)[1] + 1) + " ≈ " + expectRight + "）",
                        Math.abs(segs.get(1)[1] + 1 - expectRight) <= 2, "右缘=" + (segs.get(1)[1] + 1));
            }
        }

        int[] btnBand = verticalSpanNotBg(img, contentX + 20, mid(codeBand) + 20, card.y + card.height - 2,
                HostTheme.CARD_BG, 2);
        check("底部按钮 高 = 32 ± 3（实测 " + len(btnBand) + "）",
                Math.abs(len(btnBand) - HostTheme.BTN_H) <= 3, "band=" + band(btnBand));
        if (btnBand != null) {
            List<int[]> segs = segments(img, btnBand[0] + 1, btnBand[1] - 1,
                    contentX - 4, expectRight + 4, HostTheme.CARD_BG, 2);
            check("底部 = 两个等宽药丸（取消 + 登录）", segs.size() == 2, "行内片段=" + describe(segs));
            if (segs.size() == 2) {
                int[] left = segs.get(0);
                int[] right = segs.get(1);
                int lw = left[1] - left[0] + 1;
                int rw = right[1] - right[0] + 1;
                int gap = right[0] - left[1] - 1;
                check("两个按钮各 260 ± 3（实测 " + lw + " / " + rw + "）",
                        Math.abs(lw - HostTheme.BTN_W) <= 3 && Math.abs(rw - HostTheme.BTN_W) <= 3,
                        "BTN_W=" + HostTheme.BTN_W);
                check("两个按钮间距 = 20 ± 3（实测 " + gap + "）",
                        Math.abs(gap - HostTheme.BTN_GAP) <= 3, "BTN_GAP=" + HostTheme.BTN_GAP);
                check("按钮行右缘贴内容右缘（" + (right[1] + 1) + " ≈ " + expectRight + "）",
                        Math.abs(right[1] + 1 - expectRight) <= 2, "右缘=" + (right[1] + 1));
                double la = ratio(img, left, btnBand, HostTheme.BTN2_BG, 3);
                double ra = ratio(img, right, btnBand, HostTheme.ACCENT, 3);
                check("左边「取消」是次要样式 #232323（占比 " + pct(la) + "）", la > 0.4,
                        "BTN2_BG 占该按钮面积 " + pct(la));
                check("右边「登录」是主样式 #4CC2FF（占比 " + pct(ra) + "）", ra > 0.4,
                        "宿主强调色占该按钮面积 " + pct(ra));
                check("主按钮文字是纯白（宿主就是白字）", hasNear(img, rect(right, btnBand), Color.WHITE, 12),
                        "在 #4CC2FF 区域内寻找接近 (255,255,255) 的像素");
            }
        }

        // ---------------------------------------------------------------- 五、三档结构与文案
        // 0.11.30 需求①②④：三档必须共用同一张卡片（同一套令牌、同一组按钮），换的只是主体区与主按钮文字；
        // 文案要短（用户要求「只留必要的、全部简化」），而且不能回潮出整段说明。
        section("五、三档结构与文案（扫码档 / 验证码档 / 风控档共用一张卡片；需求④ 文案精简）");
        List<String> tSms = textsOf(smsPane);
        List<String> tQr = textsOf(qrPane);
        List<String> tRisk = textsOf(riskPane);
        int fieldsSms = countFields(smsPane);
        int fieldsQr = countFields(qrPane);
        int fieldsRisk = countFields(riskPane);
        check("验证码档有 2 个输入框（手机号 + 验证码）；扫码档与风控档 0 个（实测 "
                        + fieldsSms + " / " + fieldsQr + " / " + fieldsRisk + "）",
                fieldsSms == 2 && fieldsQr == 0 && fieldsRisk == 0, "三档输入框个数");
        check("三档都有「扫码」「验证码登录」「取消」这三颗按钮（分段切换与取消在任何一档都在）",
                hasText(tSms, "扫码") && hasText(tSms, "验证码登录") && hasText(tSms, "取消")
                        && hasText(tQr, "扫码") && hasText(tQr, "验证码登录") && hasText(tQr, "取消")
                        && hasText(tRisk, "扫码") && hasText(tRisk, "验证码登录") && hasText(tRisk, "取消"),
                "验证码档=" + tSms);
        check("分段枚文案 = 「验证码登录」（0.11.32 需求③：不再写成「验证码」）",
                hasText(tSms, "验证码登录") && !hasText(tSms, "验证码"),
                "验证码档文案=" + tSms);
        int scanWords = 0;
        for (String t : tSms) {
            if (t.contains("扫")) {
                scanWords++;
            }
        }
        check("验证码档只剩分段枚「扫码」带「扫」字（0.11.32 需求②：切过去不留二维码提示）",
                scanWords == 1 && !containsAny(tSms, "二维码"),
                "带「扫」字的文案数=" + scanWords + "：验证码档文案=" + tSms);
        check("验证码档主体 = 两个输入框 + 「获取验证码」（主按钮「登录」）",
                hasText(tSms, "获取验证码") && hasText(tSms, "登录"), "验证码档文案=" + tSms);
        check("扫码档主按钮 = 「刷新二维码」（0.11.30 需求②：要有刷新入口）",
                hasText(tQr, "刷新二维码"), "扫码档文案=" + tQr);
        check("风控档主按钮 = 「打开验证窗」（0.11.33：验证要**在电脑上**完成；0.11.34：验证面盖成"
                        + "本软件自己的窗；交出去以后才变成「我已完成，重试」）",
                hasText(tRisk, "打开验证窗") && !containsAny(tRisk, "手机扫码"),
                "风控档文案=" + tRisk);
        check("风控档正文 = 两行本软件内验证说明（0.11.33 否掉手机扫码；0.11.34 否掉浏览器外壳）",
                hasText(tRisk, "请在验证窗口完成安全验证") && hasText(tRisk, "验证窗口会盖在本窗上")
                        && !containsAny(tRisk, "扫一下这个码") && !containsAny(tRisk, "手机扫码")
                        && !containsAny(tRisk, "浏览器"),
                "风控档文案=" + tRisk);
        // 0.11.33 用户报障①：扫码被风控时，验证态必须**还站在「扫码」那一枚上** —— 以前 showMode()
        // 无条件把 segSms 点亮，于是「一打开登录就跳到验证码登录那进行验证」。同一个风控档有两条来路，
        // 分段枚的选择态由**来源**决定，所以这一条只能靠像素量（组件树里两枚都在、字也不变）。
        List<String> tQrRisk = textsOf(qrRiskPane);
        Rectangle cardQrRisk = bbox(qrRiskImg, HostTheme.CARD_BG, 1);
        int[] akLeft = cardQrRisk == null ? null
                : verticalRunAt(qrRiskImg, contentX + 100, cardQrRisk.y, cardQrRisk.y + cardQrRisk.height,
                HostTheme.ACCENT, 3);
        int[] akRight = cardQrRisk == null ? null
                : verticalRunAt(qrRiskImg, contentX + 320, cardQrRisk.y, cardQrRisk.y + cardQrRisk.height,
                HostTheme.ACCENT, 3);
        Rectangle cardRiskBox = bbox(riskImg, HostTheme.CARD_BG, 1);
        int[] asLeft = cardRiskBox == null ? null
                : verticalRunAt(riskImg, contentX + 100, cardRiskBox.y, cardRiskBox.y + cardRiskBox.height,
                HostTheme.ACCENT, 3);
        int[] asRight = cardRiskBox == null ? null
                : verticalRunAt(riskImg, contentX + 320, cardRiskBox.y, cardRiskBox.y + cardRiskBox.height,
                HostTheme.ACCENT, 3);
        check("扫码被风控 ⇒ 分段行仍点亮左边那枚「扫码」（0.11.33 报障①：以前串到「验证码登录」）",
                akLeft != null && cardQrRisk != null && akLeft[0] < cardQrRisk.y + 150
                        && (akRight == null || akRight[0] > cardQrRisk.y + 200),
                "扫码档那枚的首个主色带=" + band(akLeft) + "（应在分段行 = 卡顶 + 60..92）；右枚="
                        + band(akRight) + "（只该在底部主按钮上 = 卡顶 + 200 以下）");
        check("验证码登录被风控 ⇒ 分段行点亮右边那枚「验证码登录」（同一档、按来源分）",
                asLeft == null && asRight != null && cardRiskBox != null && asRight[0] < cardRiskBox.y + 150,
                "左枚主色带=" + band(asLeft) + "（应为空）；右枚=" + band(asRight) + "（应在分段行）");
        check("两条来路的风控档文案同款（同一档、只是来源不同）",
                hasText(tQrRisk, "请在验证窗口完成安全验证") && hasText(tQrRisk, "打开验证窗"),
                "扫码引来的风控档文案=" + tQrRisk);
        check("三档文案都不再出现「浏览器」（0.11.34 用户裁定：验证面盖在本软件自己的窗里）",
                !containsAny(tSms, "浏览器") && !containsAny(tQr, "浏览器") && !containsAny(tRisk, "浏览器"),
                "三档文案=" + tSms + " / " + tQr + " / " + tRisk);
        int maxAll = maxTextLen(smsPane, qrPane, riskPane);
        int maxBtn = maxButtonLen(smsPane, qrPane, riskPane);
        check("文案都短（最长 " + maxAll + " 字 ≤ 22；按钮最长 " + maxBtn + " 字 ≤ 8）",
                maxAll <= 22 && maxBtn <= 8, "最长文案 = " + maxAll + " 字 / 最长按钮 = " + maxBtn + " 字");
        check("三档都没有整段说明回潮（不含「填写」「请先」这类引导长句）",
                !containsAny(tSms, "填写") && !containsAny(tQr, "填写") && !containsAny(tRisk, "填写")
                        && !containsAny(tSms, "请先") && !containsAny(tQr, "请先") && !containsAny(tRisk, "请先"),
                "整段说明 0.11.28 已按用户要求删除（HINT_HTML 不再存在）");

        int cardH = card.height + 2;                          // 含上下各 1px 边线
        Rectangle cardQr = bbox(qrImg, HostTheme.CARD_BG, 1);
        Rectangle cardRisk = bbox(riskImg, HostTheme.CARD_BG, 1);
        check("验证码档卡高 = 274 ± 3（实测 " + cardH + "）", Math.abs(cardH - 274) <= 3, "bbox=" + card);
        check("扫码档也渲染出卡片且宽 + 2 = 580 ± 2",
                cardQr != null && Math.abs(cardQr.width + 2 - HostTheme.CARD_W) <= 2,
                cardQr == null ? "未找到卡片色" : "bbox=" + cardQr);
        check("风控档也渲染出卡片且宽 + 2 = 580 ± 2",
                cardRisk != null && Math.abs(cardRisk.width + 2 - HostTheme.CARD_W) <= 2,
                cardRisk == null ? "未找到卡片色" : "bbox=" + cardRisk);
        check("扫码档比验证码档高 182 ± 3（主体从 78 高的两行输入换成 260 高的二维码；实测 "
                        + (cardQr == null ? "未量到" : String.valueOf(cardQr.height + 2)) + "）",
                cardQr != null && Math.abs(cardQr.height + 2 - (cardH + 182)) <= 3,
                "BODY_QR_H(260) − BODY_SMS_H(78) = 182");
        check("风控档比验证码档高 40 ± 3（0.11.33 主体从 78 高的两行输入换成 118 高的两行说明；实测 "
                        + (cardRisk == null ? "未量到" : String.valueOf(cardRisk.height + 2)) + "）",
                cardRisk != null && Math.abs(cardRisk.height + 2 - (cardH + 40)) <= 3,
                "BODY_RISK_H(118) − BODY_SMS_H(78) = 40");
        // 主体区里到底有没有那块二维码：量「墨色」像素（模块的黑 / 静默区的白）的外接框 ——
        // 二维码是黑白模块构成的，所以外接框必然近似正方形，且尺寸落在 150..260。
        //
        // 两个坑（都是探针自己的，不是界面的）：
        //   ①窗口必须按各档自己的分段行算：三档卡高不同 ⇒ 同一个 y 窗口在别的档里会落到按钮行上；
        //     窗口上面还要留够（body 顶 = 分段行底 + 14，再加 2px 余量），否则二维码下缘被裁掉。
        //   ②判据用「墨色」而不是「不是卡片底色」：卡片底色 #262626 与描边 #2F3030 只差 9 级，
        //     逐像素比对时会零星撞上描边/阴影的中间色（实测 20 来个 (44,44,44)~(51,51,51) 的像素
        //     把 225 宽的外接框撑到 251）—— 用黑白双阈值就完全免疫。
        // 定位列也随选中枚走：扫码档选中的是左枚「扫码」（扫 contentX + 100），另两档是右枚（+320）。
        int[] segBandQr = cardQr == null ? null
                : verticalRunAt(qrImg, contentX + 100, cardQr.y, cardQr.y + cardQr.height, HostTheme.ACCENT, 3);
        int[] segBandRisk = cardRisk == null ? null
                : verticalRunAt(riskImg, contentX + 320, cardRisk.y, cardRisk.y + cardRisk.height,
                HostTheme.ACCENT, 3);
        Rectangle qrBody = segBandQr == null ? null
                : new Rectangle(contentX, segBandQr[1] + 16, contentW, 252);
        Rectangle inkQr = qrBody == null ? null : inkBox(qrImg, qrBody);
        int qrBlack = qrBody == null ? -1 : countPureBlack(qrImg, qrBody);
        check("扫码档主体区里有二维码图形（墨色外接框 " + inkQr + "，近似正方形；纯黑像素 " + qrBlack + " 个）",
                inkQr != null && Math.abs(inkQr.width - inkQr.height) <= 3
                        && inkQr.width >= 150 && inkQr.width <= 260 && qrBlack > 500,
                "扫的是扫码档自己的分段行以下 " + qrBody + "；假码由 buildPreview(base,\"qr\") 点入");
        Rectangle riskBody = segBandRisk == null ? null
                : new Rectangle(contentX, segBandRisk[1] + 16, contentW, 252);
        int riskBlack = riskBody == null ? -1 : countPureBlack(riskImg, riskBody);
        check("风控档主体区里**没有**二维码了（0.11.33 用户裁定：验证改到电脑上做；纯黑像素 "
                        + riskBlack + " 个）",
                riskBlack == 0,
                "旧版这里画的是 205×205 的验证二维码；现在换成两行文字 #EBEEF1 / #B2B5B9（都不是纯黑）");

        // ---------------------------------------------------------------- 六、排版
        section("六、排版（标题 #EBEEF1；0.11.28 起「说明段」已按用户要求删除）");
        Rectangle card2 = card;
        int titleTop = card2.y + HostTheme.CARD_PAD_TOP;        // 标题盒顶 = 卡片顶 + 19
        int titleBottom = titleTop + HostTheme.TITLE_PX + 8;    // 标题盒底（盒高 28；不含）
        check("标题文字色 #EBEEF1 出现在标题盒里（" + titleTop + ".." + (titleBottom - 1) + "）",
                textBands(img, contentX, contentX + contentW, titleTop, titleBottom).length >= 1,
                "titleBands=" + join2(textBands(img, contentX, contentX + contentW, titleTop, titleBottom)));
        // 标题盒底 + 14 = 分段行顶部。这条「间距」才是真正的机器判据：只要有人往标题与分段行之间
        // 塞回说明段，分段行就被推下去 ⇒ 立刻 FAIL（比「某个窗口里没有文字」结实得多）。
        check("标题盒底 + 14 = 分段行顶部（" + (segBand == null ? "未量到" : String.valueOf(segBand[0]))
                        + " ≈ " + (titleBottom + 14) + "）",
                segBand != null && Math.abs(segBand[0] - (titleBottom + 14)) <= 2,
                "标题盒底=" + titleBottom + "（= 卡片顶 + 19 + 28）；说明段若要回潮，只能把分段行往下挤");
        int gapTop = titleBottom;                              // 标题盒底以下（标题的字全在盒内）
        int gapBottom = segBand == null ? (phoneBand == null ? titleBottom + 14 : phoneBand[0] - 4)
                : segBand[0] - 3;
        int[][] gapBands = textBands(img, contentX, contentX + contentW, gapTop, gapBottom);
        check("标题与分段行之间没有文字（整段说明已删；填法提示在框内的占位文字里）",
                gapBands.length == 0, "扫描窗 " + gapTop + ".." + gapBottom + " 内 bands=" + join2(gapBands));
        check("无旧皮肤灰 #9AA0A6（0.11.26 的 C_HINT）残留",
                bbox(img, new Color(0x9A, 0xA0, 0xA6), 2) == null,
                "整图搜索旧提示色 ⇒ " + (bbox(img, new Color(0x9A, 0xA0, 0xA6), 2) == null ? "0 处" : "有残留"));
        check("字体族可用且不是 Dialog（" + HostTheme.family() + "）",
                !HostTheme.family().isBlank() && !"Dialog".equals(HostTheme.family()), HostTheme.family());

        // ---------------------------------------------------------------- 七、快照档
        // 0.11.28 的真机默认外观：不透明窗 + 静态底图 + 60% 遮罩。用一张自造底图（四象限不同灰度
        // + 一个纯红块）代替真机截图 —— 期望值能算准，且不依赖任何屏幕内容。
        section("七、快照档（0.11.28 真机默认外观：静态底图 + 不透明窗 + 遮罩）");
        BufferedImage base = syntheticBase(img.getWidth(), img.getHeight());
        JPanel snapPane = LoginDialog.buildPreview(base);
        BufferedImage snap = renderTo(snapPane);
        File f2 = new File(out.replace(".png", "-snapshot.png"));
        ImageIO.write(snap, "png", f2);
        System.out.println("  快照档渲染图：" + f2.getAbsolutePath());
        check("快照档全图不透明（角落 alpha=255 ⇒ 不透明根才有清底者）",
                alpha(snap, 4, 4) == 255 && alpha(snap, snap.getWidth() - 5, snap.getHeight() - 5) == 255,
                "左上 alpha=" + alpha(snap, 4, 4) + "，右下 alpha=" + alpha(snap, snap.getWidth() - 5, snap.getHeight() - 5));
        int white = (rgb(snap, 4, 4) >> 16) & 0xFF;
        check("底图白 255 过 60% 遮罩 ⇒ 102 ± 3（实测 " + white + "）", Math.abs(white - 102) <= 3,
                "左上角 = 底图白 × 0.40，期望 102（只比红色通道：rgb() 返回打包值，比整数会被 0x666666 = 6710886 迷惑）");
        int dark = (rgb(snap, snap.getWidth() - 5, 20) >> 16) & 0xFF;
        check("底图暗块 0x20 过遮罩 ⇒ 13 ± 3（实测 " + dark + "，证明遮罩是乘性的不是实心黑）",
                Math.abs(dark - 13) <= 3, "右上角 = 32 × 0.40 ≈ 13（同样只比红色通道）");
        int red = snap.getRGB(20, 20) & 0xFF0000;
        check("底图红块过遮罩只掉亮度不掉色相（R≈102 / G=B=0）",
                Math.abs(red - (102 << 16)) <= (3 << 16) && (snap.getRGB(20, 20) & 0x00FFFF) <= 0x000303,
                "(20,20)=" + Integer.toHexString(snap.getRGB(20, 20)));
        Rectangle cardSnap = bbox(snap, HostTheme.CARD_BG, 1);
        check("卡片仍画在底图与遮罩之上（#262626 bbox 宽 + 2 = 580 ± 2）",
                cardSnap != null && Math.abs(cardSnap.width + 2 - HostTheme.CARD_W) <= 2,
                cardSnap == null ? "未找到卡片色" : "bbox=" + cardSnap);
        int[] snapPhone = cardSnap == null ? null : verticalRunAt(snap, cardSnap.x + HostTheme.CARD_PAD_X + 200,
                cardSnap.y, cardSnap.y + cardSnap.height, HostTheme.FIELD_BG, 2);
        check("快照档里同样能量到输入框（手机号框填充高 33 ± 2，实测 " + len(snapPhone) + "）",
                Math.abs(len(snapPhone) - 33) <= 2, "band=" + band(snapPhone));
        // 0.11.39（用户 m00221 ③/④「四角还是有黑色的角」）的两条回归守卫：
        // ①窗口不再切形 —— 四角必须是不透明的底图角落（切形 / 硬裁剪 ⇒ 角落 alpha=0 或一道硬边）；
        // ②卡片**没有**投影 —— 卡片外那一圈必须正好等于「底图 × 0.40」，不能比它更暗
        //   （0.11.38 那 6 层假投影把紧贴卡片的一圈压暗、四角最厚 ⇒ 用户看到的就是黑角）。
        boolean cornersSolid = true;
        int[][] corners = {{0, 0}, {snap.getWidth() - 1, 0}, {0, snap.getHeight() - 1},
                {snap.getWidth() - 1, snap.getHeight() - 1}};
        StringBuilder cornerDetail = new StringBuilder();
        for (int[] c : corners) {
            int a = alpha(snap, c[0], c[1]);
            if (a != 255) {
                cornersSolid = false;
            }
            cornerDetail.append("(").append(c[0]).append(",").append(c[1]).append(") alpha=").append(a).append(" ");
        }
        int corner0 = (rgb(snap, 0, 0) >> 16) & 0xFF;              // 底图左上 = 纯白 ⇒ 期望 102
        check("四角不切形（0.11.39：根面板铺满整块矩形，角落 = 底图角落 × 0.40，不是透明/硬边）",
                cornersSolid && Math.abs(corner0 - 102) <= 2,
                cornerDetail + "；(0,0) 红通道=" + corner0 + "，期望 102（底图白 × 0.40）");
        if (cardSnap != null) {
            int hy = cardSnap.y + cardSnap.height - 20;            // 卡片偏下：底图那一带是**均匀**中灰
            int worst = 0;
            StringBuilder halo = new StringBuilder();
            // i=1 那列是卡片**自己的 1 px 边线**（#2F3030 画在边界上，实测 bbox 里的 CARD_BG 比它右一列）⇒
            // 从 i=2 起才是「卡片之外」。0.11.38 的假投影最远铺到 6 px，且内层层层叠加 ⇒ i=2..6 最暗，
            // 留出 i=1 不影响判定。
            for (int i = 2; i <= 8; i++) {                         // 卡片左边外侧（跳过边线那列）
                int x = cardSnap.x - i;
                if (x < 0) {
                    break;
                }
                int want = (int) Math.round(((base.getRGB(x, hy) >> 16) & 0xFF) * 0.40);
                int got = (snap.getRGB(x, hy) >> 16) & 0xFF;
                worst = Math.max(worst, Math.abs(got - want));
                halo.append(x).append(":").append(got).append("/").append(want).append(" ");
            }
            int edge = cardSnap.x > 0 ? ((snap.getRGB(cardSnap.x - 1, hy) >> 16) & 0xFF) : -1;
            check("卡片外没有投影暗环（0.11.39 删掉的 6 层假投影；卡片边线之外 = 底图 × 0.40）",
                    worst <= 2, "卡片左缘外侧实测/期望 = " + halo + "｜最大偏差 " + worst
                            + "｜边线那列 " + edge + "（= CARD_EDGE #2F）");
        }

        // ---------------------------------------------------------------- 八、重绘幂等
        section("八、重绘幂等（0.11.28 修的正是这条：有清底者 ⇒ 重绘不留痕）");
        int fullTrans = redrawDiff(LoginDialog.buildPreview(), null);
        check("透明档整窗重画一遍会叠影（旧缺陷机制可复现：差异像素 " + fullTrans + " > 0）",
                fullTrans > 0, "透明档没有不透明祖先 ⇒ 遮罩/描边/投影逐层累加");
        int fullSnap = redrawDiff(snapPane, null);
        check("快照档整窗重画一遍逐像素不变（差异像素 " + fullSnap + " = 0）",
                fullSnap == 0, "不透明根每遍开头先清底 ⇒ 重绘是覆盖");
        Rectangle cornerClip = new Rectangle(8, 8, 260, 60);          // 卡片之外的遮罩区
        int clipTrans = redrawDiff(LoginDialog.buildPreview(), cornerClip);
        check("透明档局部重绘也在该块留下痕迹（差异像素 " + clipTrans + " > 0）",
                clipTrans > 0, "用户看到的「焦点残影 / 渲染位移」就是这一类局部重绘");
        int clipSnap = redrawDiff(snapPane, cornerClip);
        check("快照档局部重绘同样逐像素不变（差异像素 " + clipSnap + " = 0）",
                clipSnap == 0, "同一块重画：先清底⇒底图⇒遮罩⇒卡片，结果必与首帧相同");

        // ---------------------------------------------------------------- 九、底图裁剪（0.11.29 坑 40）
        section("九、静态底图裁剪（0.11.29 坑 40：宿主窗口矩形含 8px 不可见边框）");
        Rectangle hostWin = new Rectangle(-1928, -8, 1936, 1048);      // 真机实测（副屏在左侧 ⇒ 负坐标）
        Rectangle screens = new Rectangle(-1920, 0, 3968, 1152);       // 真机实测：所有屏幕的并集
        Rectangle clip = LoginDialog.clipToScreen(hostWin, screens);
        check("真机窗口矩形被裁到屏幕内（clip=" + clip + "）",
                clip != null && clip.x == -1920 && clip.y == 0
                        && clip.width == 1928 && clip.height == 1040,
                "期望 Rectangle[x=-1920,y=0,width=1928,height=1040]；0.11.28 的「必须完整落在屏内」判据"
                        + "在这里被 100% 拒绝 ⇒ 真机一直回退逐像素透明窗（坑 40）");
        check("底图仍按窗口原尺寸产出（越界 8px 由补底色吃掉，贴回时 1:1）",
                hostWin.width == 1936 && hostWin.height == 1048 && clip != null
                        && clip.width * clip.height < hostWin.width * hostWin.height,
                "窗=" + hostWin + "，屏内=" + clip);
        check("完全在屏内时原样返回（不误裁；单屏场景）",
                new Rectangle(100, 120, 800, 600).equals(
                        LoginDialog.clipToScreen(new Rectangle(100, 120, 800, 600), screens)),
                "常见单屏场景");
        check("完全在屏外返回 null（宁可回退，也不抓一张全黑底图）",
                LoginDialog.clipToScreen(new Rectangle(-5000, -5000, 800, 600), screens) == null,
                "调用方据此回退：透明窗 → 带标题栏小窗");

        summary();
    }

    // ------------------------------------------------------------------ 渲染

    /**
     * 把整棵组件树离屏画进一张 ARGB 图（不 {@code setVisible} ⇒ 不闪窗，也不需要屏幕）。
     *
     * <p>复用同一个 {@code pane} 多次调用是安全的（{@code pack()} 幂等）：第六组「重绘幂等」正是靠
     * 这一点在同一实例上画两遍。</p>
     */
    private static BufferedImage renderTo(JPanel pane) {
        JFrame frame = new JFrame();
        frame.setUndecorated(true);
        frame.setContentPane(pane);
        frame.pack();                                   // 完成整棵树布局；不 setVisible ⇒ 不闪窗
        Dimension d = pane.getSize();
        BufferedImage img = new BufferedImage(Math.max(1, d.width), Math.max(1, d.height),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        pane.printAll(g);
        g.dispose();
        frame.dispose();
        return img;
    }

    /**
     * 自造底图（代替真机截图）：四象限四种灰度 + 一个纯红块 —— 期望值可算准，且不依赖屏幕内容。
     * 白色区用来验「×0.40」、暗块用来验「乘性而非实心黑」、红块用来验「只掉亮度不掉色相」。
     */
    private static BufferedImage syntheticBase(int w, int h) {
        BufferedImage img = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0xFF, 0xFF, 0xFF));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(0x80, 0x80, 0x80));
        g.fillRect(0, h / 2, w, h - h / 2);              // 左下 = 中灰
        g.setColor(new Color(0x20, 0x20, 0x20));
        g.fillRect(w / 2, 0, w - w / 2, h / 2);          // 右上 = 暗
        g.setColor(new Color(0xFF, 0x00, 0x00));
        g.fillRect(8, 8, 40, 40);                        // 左上角 = 纯红（(20,20) 落在这里）
        g.dispose();
        return img;
    }

    /**
     * <b>「重绘能不能清底」的量尺</b>：在同一张图上把整棵树再画一遍（可选只画 {@code clip} 那一块），
     * 返回与再画之前的差异像素数。
     *
     * <p>为什么这就是量尺：快照档的根是<b>不透明</b>的，每遍开头先清底 ⇒ 结果与首帧逐像素相同（0）；
     * 透明档整条链都不透明=false，Swing 找不到清底者 ⇒ 遮罩 / 描边 / 投影在旧画面上再叠一层，
     * 差异必然 &gt; 0。用户在 0.11.27 看到的「文字显示有问题 / 点两个框出现渲染位移」就是这一条。</p>
     */
    private static int redrawDiff(JPanel pane, Rectangle clip) {
        BufferedImage before = renderTo(pane);
        BufferedImage after = new BufferedImage(before.getWidth(), before.getHeight(), before.getType());
        Graphics2D c = after.createGraphics();
        c.drawImage(before, 0, 0, null);
        c.dispose();
        Graphics2D g = after.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        if (clip != null) {
            g.setClip(clip);
        }
        pane.printAll(g);
        g.dispose();
        int diff = 0;
        for (int y = 0; y < before.getHeight(); y++) {
            for (int x = 0; x < before.getWidth(); x++) {
                if (before.getRGB(x, y) != after.getRGB(x, y)) {
                    diff++;
                }
            }
        }
        return diff;
    }

    // ------------------------------------------------------------------ 量组件树

    /**
     * 走组件树收集按钮 / 标签的文本（按树序，去掉空白、丢掉空串）。
     *
     * <p>像素量不出「这一档写的是哪几个字」，而 0.11.30 的三档恰恰是<b>同一套像素、不同的字</b>
     * （扫码档主按钮「刷新二维码」、风控档「打开验证窗」…）：所以文案这一类断言走组件树，
     * 几何那一类走像素。两条都要有，缺一条就等于把一半的界面承诺留在无人看管的状态。</p>
     */
    private static List<String> textsOf(java.awt.Component pane) {
        List<String> out = new ArrayList<>();
        collectTexts(pane, out);
        return out;
    }

    private static void collectTexts(java.awt.Component c, List<String> out) {
        String t = null;
        if (c instanceof javax.swing.AbstractButton) {
            t = ((javax.swing.AbstractButton) c).getText();
        } else if (c instanceof javax.swing.JLabel) {
            t = ((javax.swing.JLabel) c).getText();
        }
        if (t != null && !t.isBlank()) {
            out.add(t.trim());
        }
        if (c instanceof java.awt.Container) {
            for (java.awt.Component k : ((java.awt.Container) c).getComponents()) {
                collectTexts(k, out);
            }
        }
    }

    /** 输入框个数（{@code FlatField extends JTextField}）—— 验证码档 2 个、另外两档 0 个。 */
    private static int countFields(java.awt.Component c) {
        int n = c instanceof javax.swing.JTextField ? 1 : 0;
        if (c instanceof java.awt.Container) {
            for (java.awt.Component k : ((java.awt.Container) c).getComponents()) {
                n += countFields(k);
            }
        }
        return n;
    }

    private static boolean hasText(List<String> texts, String want) {
        for (String t : texts) {
            if (t.equals(want)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(List<String> texts, String needle) {
        for (String t : texts) {
            if (t.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** 最长文案字数（限 {@code maxLen} 的「需求④ 文案精简」量尺）。 */
    private static int maxTextLen(java.awt.Component... panes) {
        int max = 0;
        for (java.awt.Component p : panes) {
            for (String t : textsOf(p)) {
                max = Math.max(max, t.length());
            }
        }
        return max;
    }

    /** 最长<b>按钮</b>文案字数（按钮上的字必须极短：这排药丸很窄，长了会被截断）。 */
    private static int maxButtonLen(java.awt.Component... panes) {
        int max = 0;
        for (java.awt.Component p : panes) {
            for (String t : buttonTextsOf(p)) {
                max = Math.max(max, t.length());
            }
        }
        return max;
    }

    private static List<String> buttonTextsOf(java.awt.Component c) {
        List<String> out = new ArrayList<>();
        collectButtons(c, out);
        return out;
    }

    private static void collectButtons(java.awt.Component c, List<String> out) {
        if (c instanceof javax.swing.AbstractButton) {
            String t = ((javax.swing.AbstractButton) c).getText();
            if (t != null && !t.isBlank()) {
                out.add(t.trim());
            }
        }
        if (c instanceof java.awt.Container) {
            for (java.awt.Component k : ((java.awt.Container) c).getComponents()) {
                collectButtons(k, out);
            }
        }
    }

    /**
     * 某个矩形区域内「<b>墨色</b>」像素的最小外接框（{@code null} = 整块都没有墨）。
     *
     * <p>墨色 = 二维码那种「非纯黑即纯白」的实心像素（三通道全 &lt; 25，或三通道全 &gt; 200）。
     * 阈值必须卡得这么紧：卡片底 #262626(38,38,38) 与描边 #2F3030(47,48,48) 只差 9 级，若拿「不是
     * 卡片底色」或者宽松的暗阈值（&lt; 90）当判据，就会零星撞上描边 / 阴影的中间色 —— 实测二十来个
     * (44,44,44)~(51,51,51) 的像素把外接框从 225 撑到 251，白白误判成「二维码不方」。
     * 这是坑 37 的第四条：判据要挑「离背景足够远」的那种，别拿只差一个色阶的东西当边界。</p>
     */
    private static Rectangle inkBox(BufferedImage img, Rectangle clip) {
        int x0 = Integer.MAX_VALUE;
        int y0 = Integer.MAX_VALUE;
        int x1 = -1;
        int y1 = -1;
        for (int y = Math.max(0, clip.y); y < Math.min(img.getHeight(), clip.y + clip.height); y++) {
            for (int x = Math.max(0, clip.x); x < Math.min(img.getWidth(), clip.x + clip.width); x++) {
                int p = img.getRGB(x, y);
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                if ((r < 25 && g < 25 && b < 25) || (r > 200 && g > 200 && b > 200)) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        return x1 < 0 ? null : new Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    // ------------------------------------------------------------------ 量像素

    /**
     * 数「纯黑且不透明」像素个数（三通道全 &lt; 25 且 alpha ≥ 250）—— 0.11.33 用它区分
     * 「主体区里有没有二维码」。
     *
     * <p>比 {@link #inkBox} 更严，而且必须连 alpha 一起看：透明档的遮罩是 (0,0,0,153)，
     * 只比 RGB 的话整块遮罩都会被算成「纯黑」（实测误报 24840 个）。二维码模块是**不透**
     * 的纯 (0,0,0)，新版风控档的两行文字是 #EBEEF1 / #B2B5B9（一个通道都不黑）——「纯黑 == 0」
     * 就是「这里没有码」的硬判据。</p>
     */
    private static int countPureBlack(BufferedImage img, Rectangle clip) {
        int n = 0;
        for (int y = Math.max(0, clip.y); y < Math.min(img.getHeight(), clip.y + clip.height); y++) {
            for (int x = Math.max(0, clip.x); x < Math.min(img.getWidth(), clip.x + clip.width); x++) {
                int p = img.getRGB(x, y);
                if (((p >>> 24) & 0xFF) >= 250
                        && ((p >> 16) & 0xFF) < 25 && ((p >> 8) & 0xFF) < 25 && (p & 0xFF) < 25) {
                    n++;
                }
            }
        }
        return n;
    }

    private static int rgb(BufferedImage img, int x, int y) {
        return img.getRGB(clamp(x, img.getWidth()), clamp(y, img.getHeight())) & 0xFFFFFF;
    }

    private static int alpha(BufferedImage img, int x, int y) {
        return (img.getRGB(clamp(x, img.getWidth()), clamp(y, img.getHeight())) >>> 24) & 0xFF;
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max - 1, v));
    }

    private static boolean near(int argb, Color c, int tol) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int a = (argb >>> 24) & 0xFF;
        return a >= 250
                && Math.abs(r - c.getRed()) <= tol
                && Math.abs(g - c.getGreen()) <= tol
                && Math.abs(b - c.getBlue()) <= tol;
    }

    private static Rectangle bbox(BufferedImage img, Color c, int tol) {
        int x0 = Integer.MAX_VALUE;
        int y0 = Integer.MAX_VALUE;
        int x1 = -1;
        int y1 = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (near(img.getRGB(x, y), c, tol)) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        return x1 < 0 ? null : new Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    private static boolean hasNear(BufferedImage img, Rectangle box, Color c, int tol) {
        for (int y = box.y; y < box.y + box.height; y++) {
            for (int x = box.x; x < box.x + box.width; x++) {
                if (near(img.getRGB(x, y), c, tol)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 竖直线上的「<b>非</b>背景」像素条带，取<b>最后</b>一段 —— 卡片里最靠下的实体就是底部按钮行。
     * 从「验证码行以下」起扫，跳开上面的输入框；中间那行状态文字是空的，通体卡片底色，不会被算进来。
     */
    private static int[] verticalSpanNotBg(BufferedImage img, int x, int y0, int y1, Color bg, int tol) {
        int start = -1;
        int lastStart = -1;
        int lastEnd = -1;
        int end = Math.min(img.getHeight(), y1);
        for (int y = Math.max(0, y0); y < end; y++) {
            boolean hit = !near(img.getRGB(x, y), bg, tol);
            if (hit && start < 0) {
                start = y;
            } else if (!hit && start >= 0) {
                lastStart = start;
                lastEnd = y - 1;
                start = -1;
            }
        }
        if (start >= 0) {
            lastStart = start;
            lastEnd = end - 1;
        }
        return lastStart < 0 ? null : new int[]{lastStart, lastEnd};
    }

    private static int[] verticalRunAt(BufferedImage img, int x, int y0, int y1, Color c, int tol) {
        int start = -1;
        int end = Math.min(img.getHeight(), y1);
        for (int y = Math.max(0, y0); y < end; y++) {
            boolean hit = near(img.getRGB(x, y), c, tol);
            if (hit && start < 0) {
                start = y;
            } else if (!hit && start >= 0) {
                return new int[]{start, y - 1};
            }
        }
        return start < 0 ? null : new int[]{start, end - 1};
    }

    /**
     * 一行里的「<b>非</b>卡片底色」连续片段（<b>带版</b>：一列只要在 {@code y0..y1} 内有<b>任意</b>一个
     * 非背景像素就算这一列属于控件）。
     *
     * <p>为什么不直接量控件自身的颜色：输入框里有占位文字、按钮上有文字，颜色条带会被字切断，
     * 量出来的宽度就只剩半截（0.11.27 第一版探针就是这么误判的）。改成「凡不是卡片底色的像素都算控件」，
     * 控件之间的间隙（卡片底色）自然把它们分开 —— 既拿到宽度，也拿到间距。</p>
     *
     * <p>为什么还要<b>看一整条带</b>而不是只看一行：字形边缘的抗锯齿像素偶尔会与背景色 ±2 撞上。
     * 0.11.28 给自绘零件开了分数度量后，实测「获取验证码」那个按钮在扫描行上的一个像素正好落在
     * #262626 ± 2 里 ⇒ 120 宽的按钮被切成 9 段（探针误判，不是界面错）。带版对文字免疫：要让一列被
     * 误判成背景，得整列十几行都恰好撞上背景色。这是坑 37 的第三条（FAIL 先怀疑探针）的延伸。</p>
     */
    private static List<int[]> segments(BufferedImage img, int y0, int y1, int x0, int x1, Color bg, int tol) {
        List<int[]> out = new ArrayList<>();
        int start = -1;
        int end = Math.min(img.getWidth(), x1);
        int top = Math.max(0, y0);
        int bottom = Math.min(img.getHeight() - 1, y1);
        for (int x = Math.max(0, x0); x < end; x++) {
            boolean hit = false;
            for (int y = top; y <= bottom && !hit; y++) {
                hit = !near(img.getRGB(x, y), bg, tol);
            }
            if (hit && start < 0) {
                start = x;
            } else if (!hit && start >= 0) {
                out.add(new int[]{start, x - 1});
                start = -1;
            }
        }
        if (start >= 0) {
            out.add(new int[]{start, end - 1});
        }
        return out;
    }

    /** 片段 × 条带内，指定颜色占的面积比（用于判「这颗按钮确实刷成了主色/次要色」）。 */
    private static double ratio(BufferedImage img, int[] seg, int[] band, Color c, int tol) {
        int total = 0;
        int hit = 0;
        for (int y = band[0]; y <= band[1]; y++) {
            for (int x = seg[0]; x <= seg[1]; x++) {
                total++;
                if (near(img.getRGB(x, y), c, tol)) {
                    hit++;
                }
            }
        }
        return total == 0 ? 0 : (double) hit / total;
    }

    private static Rectangle rect(int[] seg, int[] band) {
        return new Rectangle(seg[0], band[0], seg[1] - seg[0] + 1, band[1] - band[0] + 1);
    }

    private static int len(int[] band) {
        return band == null ? 0 : band[1] - band[0] + 1;
    }

    private static int mid(int[] band) {
        return band == null ? 0 : (band[0] + band[1]) / 2;
    }

    private static int width(List<int[]> segs) {
        return segs.isEmpty() ? 0 : segs.get(0)[1] - segs.get(0)[0] + 1;
    }

    private static String band(int[] b) {
        return b == null ? "未量到" : b[0] + ".." + b[1];
    }

    private static String describe(List<int[]> segs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < segs.size(); i++) {
            int[] s = segs.get(i);
            sb.append(s[0]).append("..").append(s[1]).append("(宽 ").append(s[1] - s[0] + 1).append(')');
            if (i < segs.size() - 1) {
                sb.append(", ");
            }
        }
        return sb.append(']').toString();
    }

    private static String pct(double d) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", d * 100);
    }

    /** 文字带：某行上出现主文字色即算一行文字；连续行合并成段。 */
    private static int[][] textBands(BufferedImage img, int x0, int x1, int y0, int y1) {
        List<int[]> bands = new ArrayList<>();
        boolean in = false;
        int start = 0;
        for (int y = Math.max(0, y0); y < Math.min(img.getHeight(), y1); y++) {
            boolean hit = false;
            for (int x = Math.max(0, x0); x < Math.min(img.getWidth(), x1) && !hit; x++) {
                hit = near(img.getRGB(x, y), HostTheme.TEXT, 6);
            }
            if (hit && !in) {
                in = true;
                start = y;
            } else if (!hit && in) {
                in = false;
                bands.add(new int[]{start, y - 1});
            }
        }
        if (in) {
            bands.add(new int[]{start, Math.min(img.getHeight(), y1) - 1});
        }
        return bands.toArray(new int[0][]);
    }

    private static String join2(int[][] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            sb.append(arr[i][0]).append("..").append(arr[i][1]);
            if (i < arr.length - 1) {
                sb.append(", ");
            }
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------ 断言

    private static void section(String title) {
        System.out.println(title);
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

    private static void summary() {
        System.out.println();
        System.out.println("PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) {
            System.out.println("失败项：");
            for (String s : FAILURES) {
                System.out.println("  - " + s);
            }
            System.out.println("SKIN_RESULT=FAILED");
        } else {
            System.out.println("SKIN_RESULT=ALL_PASS");
        }
        System.out.flush();
        // 退出码是门禁的一部分：失败必须让调用方（脚本 / CI）看见非 0（同 LoginShapeProbe 的约定）
        System.exit(fail > 0 ? 1 : 0);
    }
}
