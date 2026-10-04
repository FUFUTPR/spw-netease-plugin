package com.example.netease.net;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.example.netease.core.QrEncoder;

/**
 * 二维码自证探针：生成 PNG + 把「期望内容」一起落盘，交给 `tools\verify-qr.py`（OpenCV）真解码比对。
 *
 * <p>为什么需要它：0.2.0 的 `QrCode` 产出的是一张纯白图（黑像素 0），UI 看不出异常、
 * 日志也写着「二维码已生成」，只有真解码才能发现。此探针 + 解码器组成回归网。</p>
 *
 * <p>0.11.30 起编码器统一到 {@code core.QrEncoder}（M 等级 / 版本 1–20），旧的 {@code net.QrCode}
 * 已退役；本探针的产物与期望文件格式不变，`verify-qr.py` 的判读方式也不变。</p>
 *
 * <p>用法（由 `tools\smoke-qr.ps1` 驱动）：</p>
 * <pre>
 *   java -cp tools/smoke/out-qr com.example.netease.net.QrProbe            # 离线：合成 payload + 中文载荷
 *   java -cp ... com.example.netease.net.QrProbe --live                    # 联网：真实 unikey（走 NeteaseApi.qrCreate）
 * </pre>
 *
 * <p>产物目录 `tools\smoke\out-qr\`：`qr-&lt;case&gt;.png` + `expect-&lt;case&gt;.txt`。</p>
 */
public final class QrProbe {

    private static final String DIR = "tools/smoke/out-qr";
    private static int failures = 0;

    private QrProbe() {
    }

    public static void main(String[] args) throws Exception {
        try {
            Files.createDirectories(Path.of(DIR));
        } catch (Exception e) {
            // 目录已存在
        }
        boolean live = false;
        for (String a : args) {
            if ("--live".equals(a)) {
                live = true;
            }
        }

        emit("synthetic-url", "https://music.163.com/login?codekey="
                + "0123456789abcdef0123456789abcdef0123");
        emit("cjk-bytes", "网易云音乐接入 · 二维码自证（中文载荷 byte 模式）");

        if (live) {
            liveCase();
        } else {
            System.out.println("[QR] case=live state=SKIP （未加 --live）");
        }
        System.out.println("[QR] emitted=" + (live ? 3 : 2) + " probeFailures=" + failures);
    }

    /** 真实链路：走 {@link NeteaseApi#qrCreate()} 拿 unikey 与 PNG，期望内容由 {@link NeteaseApi#qrContent} 决定。 */
    private static void liveCase() {
        try {
            Dto.QrSession qr = NeteaseApi.qrCreate();
            if (qr == null || qr.key() == null || qr.key().isBlank()) {
                failures++;
                System.out.println("[QR] case=live state=FAIL reason=unikey 为空");
                return;
            }
            String expect = NeteaseApi.qrContent(qr.key());
            int pngLen = qr.pngBytes() == null ? -1 : qr.pngBytes().length;
            System.out.println("[QR] case=live unikeyLen=" + qr.key().length() + " pngBytes=" + pngLen);
            if (qr.pngBytes() == null) {
                failures++;
                System.out.println("[QR] case=live state=FAIL reason=PNG 为 null（QrEncoder.png 失败）");
                return;
            }
            write("live", qr.pngBytes(), expect);
            report("live", qr.pngBytes());
        } catch (Throwable t) {
            failures++;
            System.out.println("[QR] case=live state=FAIL reason=" + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
    }

    private static void emit(String name, String payload) throws Exception {
        long t0 = System.nanoTime();
        byte[] png = QrEncoder.png(payload, 232);
        long us = (System.nanoTime() - t0) / 1000;
        if (png == null) {
            failures++;
            System.out.println("[QR] case=" + name + " state=FAIL reason=QrEncoder.png 返回 null（ImageIO 写内存流失败）");
            return;
        }
        write(name, png, payload);
        report(name, png);
        System.out.println("[QR] case=" + name + " bytes=" + png.length + " genUs=" + us);
    }

    private static void write(String name, byte[] png, String payload) throws Exception {
        Files.write(Path.of(DIR, "qr-" + name + ".png"), png);
        Files.write(Path.of(DIR, "expect-" + name + ".txt"), payload.getBytes(StandardCharsets.UTF_8));
    }

    /** 生成端自检：像素黑占比（正常 QR 约 25%~55%；0 = 纯白图 = 0.2.0 的失效形态）。 */
    private static void report(String name, byte[] png) throws Exception {
        File f = new File(DIR, "qr-" + name + ".png");
        BufferedImage img = javax.imageio.ImageIO.read(f);
        if (img == null) {
            failures++;
            System.out.println("[QR] case=" + name + " state=FAIL reason=ImageIO 读不出 PNG");
            return;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        long black = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) == 0) {
                    black++;
                }
            }
        }
        double ratio = black * 1.0 / (w * h);
        String state = ratio >= 0.10 ? "OK" : "FAIL";
        if (!"OK".equals(state)) {
            failures++;
        }
        System.out.println("[QR] case=" + name + " state=" + state + " size=" + w + "x" + h
                + " blackPixels=" + black + " ratio=" + String.format("%.3f", ratio));
    }
}
