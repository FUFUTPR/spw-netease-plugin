package com.example.netease.core;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/**
 * 零依赖二维码编码器（byte mode / UTF-8 字节；版本 1–20，纠错等级 M，自动选掩码）。
 *
 * <p>为什么自己写：宿主 {@code WorkshopApi} 没有二维码能力，插件运行期 classpath 只有
 * {@code libs\sqlite-jdbc-*.jar}，而登录窗要把网易云 encrypt-pages 的验证链接画进自绘窗口
 * 让手机扫 ⇒ 不能引第三方依赖，只用 JDK 标准库。</p>
 *
 * <p><b>数值表来源</b>：ECC 块结构（每块 EC 码字数、两组块数与各组数据码字数）与对齐图形中心
 * 取自 thonky 的公开表（<a href="https://www.thonky.com/qr-code-tutorial/error-correction-table">error-correction-table</a>
 * / <a href="https://www.thonky.com/qr-code-tutorial/alignment-pattern-locations">alignment-pattern-locations</a>），
 * 与本类逐行核对过。格式信息 15 位 / 版本信息 18 位不抄位串，按 ISO/IEC 18004 的 BCH(15,5)/BCH(18,6)
 * 现算（生成多项式 0x537 / 0x1F25，格式区再异或 0x5412）；探针
 * {@code tools\smoke\QrEncoderProbe.java} 持有权威页抄下的 32 条格式串与 v7–20 版本串，逐位断言「现算 == 权威页」。</p>
 *
 * <p><b>独立验收记录（2026-10-02，全部离线可复核）</b></p>
 * <ul>
 *   <li>功能图形：按本类代码自建功能模块图后逐版本核对，数据模块数与本机标准容量表 v1–20 全部一致
 *       （26/44/70/…/1085 码字 + 余数位 0/7/0/3 全对）——这条同时也是「时序区范围必须是
 *       {@code [8, size-8)}」的回归断言。</li>
 *   <li>码字流：与 segno 6.4.1（{@code segno.make(text, error='m', mask=k, boost_error=False, micro=False, mode='byte')}）
 *       按同一掩码反读出的码字流**从第 0 位起逐位一致**直到补位区；唯一差异是 segno 在终止符+字节对齐后
 *       多写一个 {@code 0x00}，本类按 ISO 18004 §8.4.9 只写 {@code 0xEC}/{@code 0x11} 交替填充。</li>
 *   <li>纠错码字：独立自写 GF(256)（多项式 0x11D）逐块复算，v1-M / v5-M / v7-M / v11-M 每一块的 ECC 与本类
 *       输出逐字节相同；其中 v11-M 覆盖「5 块、每块 30 EC、短块（50）在最前」这条最易错的短块路径。</li>
 *   <li>端到端：本类产出的 PNG 交 OpenCV 4.10.0 {@code QRCodeDetector} 解码，8 个用例（含 88 字节 codekey、
 *       650 字节长链接、中文载荷）全部解出原文。</li>
 * </ul>
 *
 * <p><b>等级策略：只做 M，不做 L 回退。</b>V20-M 上限 666 字节；插件现有载荷以扫码 codekey（88 字节）为主，
 * 而风控验证链接的长度尚无实测样本（拿到样本后按 {@link #versionFor(int)} 复核即可）。「M 装不下才用 L」
 * 会让版本选择在 666/667 字节处从 V20 掉回 L 的 V18（非单调），且 L 的低版号段永远进不到、探针覆盖不到
 * ⇒ 宁可不实现。要更长的载荷时按同一张表结构补 L，并同步扩探针。</p>
 *
 * <p>编码流程：byte mode → 版本/容量 → 数据码字（模式+长度+载荷+终止符+0xEC/0x11 填充）→ 按表分块做
 * RS(纠错) → 交织 → 画功能图形与保留区 → 蛇形填位（含余数位）→ 8 种掩码逐一算惩罚取最小 → 写格式信息。</p>
 *
 * <p>用法：{@link #modules(String)} 拿矩阵（不含静默区，true=深色）；{@link #image(String, int)} 拿可直接画的图（含静默区）；
 * {@link #png(String, int)} 拿 PNG 字节（换 {@code Dto.QrSession} 一类的字节载体用，避免把 AWT 图塞进数据层）。</p>
 */
public final class QrEncoder {

    /** 支持的最小/最大版本号（表只抄到 20）。 */
    private static final int MIN_VERSION = 1;
    private static final int MAX_VERSION = 20;

    /** 静默区宽度（模块数），image() 会在四周各留这么多。 */
    private static final int QUIET_ZONE = 4;

    // ---------------------------------------------------------------- M 等级表（索引 = 版本 - 1）
    /** 每版本数据码字总数（thonky 表 col.8 「Total Data Codewords」）。 */
    private static final int[] DATA_CODEWORDS = {
            16, 28, 44, 64, 86, 108, 124, 154, 182, 216,
            254, 290, 334, 365, 415, 453, 507, 563, 627, 669};

    /** 每块纠错码字数（thonky 表「EC Codewords Per Block」）。 */
    private static final int[] ECC_PER_BLOCK = {
            10, 16, 26, 18, 24, 16, 18, 22, 22, 26,
            30, 22, 22, 24, 24, 28, 28, 26, 26, 26};

    /** 第 1 组的块数 / 每块数据码字数。 */
    private static final int[] G1_BLOCKS = {
            1, 1, 1, 2, 2, 4, 4, 2, 3, 4,
            1, 6, 8, 4, 5, 7, 10, 9, 3, 3};
    private static final int[] G1_DATA = {
            16, 28, 44, 32, 43, 27, 31, 38, 36, 43,
            50, 36, 37, 40, 41, 45, 46, 43, 44, 41};

    /** 第 2 组的块数 / 每块数据码字数（第 2 组每块比第 1 组多 1 字节；块数 0 表示只有一组）。 */
    private static final int[] G2_BLOCKS = {
            0, 0, 0, 0, 0, 0, 0, 2, 2, 1,
            4, 2, 1, 5, 5, 3, 1, 4, 11, 13};
    private static final int[] G2_DATA = {
            0, 0, 0, 0, 0, 0, 0, 39, 37, 44,
            51, 37, 38, 41, 42, 46, 47, 44, 45, 42};

    /** 对齐图形中心的行/列坐标（索引 = 版本 - 1；v1 无）。 */
    private static final int[][] ALIGN = {
            {},
            {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34},
            {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50}, {6, 30, 54},
            {6, 32, 58}, {6, 34, 62}, {6, 26, 46, 66}, {6, 26, 48, 70}, {6, 26, 50, 74},
            {6, 30, 54, 78}, {6, 30, 56, 82}, {6, 30, 58, 86}, {6, 34, 62, 90}};

    /** M 等级在格式信息里的 2 位纠错等级编号（L=01 / M=00 / Q=11 / H=10）。 */
    private static final int M_LEVEL_BITS = 0b00;

    // ---------------------------------------------------------------- 公开 API

    /**
     * 编码为模块矩阵：边长 {@code 4*version+17}，{@code true} = 深色模块，<b>不含静默区</b>。
     * 文本按 UTF-8 取字节走 byte mode（中文等多字节字符自动按字节数占容量）。
     *
     * @throws IllegalArgumentException 文本为 null，或 UTF-8 字节数超过本实现上限（V20-M 666 字节）；
     *                                  消息里带所需字节数与支持上限
     */
    public static boolean[][] modules(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text 不能为 null");
        }
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        int version = versionFor(payload.length);
        int size = sizeFor(version);

        boolean[][] fn = new boolean[size][size];       // 功能图形 / 保留区（不参与掩码与填位）
        boolean[][] base = new boolean[size][size];
        drawFunctionPatterns(base, fn, version);

        byte[] codewords = buildCodewords(payload, version);

        // 8 种掩码逐一试算，取惩罚最小者（ISO/IEC 18004 §8.8.2 的四条惩罚规则）
        boolean[][] best = null;
        int bestPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            boolean[][] cand = copy(base);
            drawCodewords(cand, fn, codewords);
            applyMask(cand, fn, mask);
            drawFormatBits(cand, fn, version, mask);
            int penalty = penalty(cand);
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                best = cand;
            }
        }
        if (best == null) {
            throw new IllegalStateException("掩码试算未产出候选矩阵");
        }
        return best;
    }

    /**
     * 生成可直接绘制的 {@code TYPE_INT_RGB} 图：白底黑块、四周各 4 模块静默区、整数倍缩放。
     *
     * <p>缩放因子 {@code scale = max(1, targetPixels / (边长+8))} ⇒ 成品边长是「不超过 targetPixels 的
     * 最大整数倍」，边长不足一个模块时退回 1:1（1 像素/模块）。{@code targetPixels<=0} 按 1:1。</p>
     */
    public static BufferedImage image(String text, int targetPixels) {
        boolean[][] m = modules(text);
        int grid = m.length + QUIET_ZONE * 2;
        int scale = targetPixels <= 0 ? 1 : Math.max(1, targetPixels / grid);
        int px = grid * scale;

        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        int white = 0xFFFFFF;
        int black = 0x000000;
        int[] row = new int[px];
        for (int gr = 0; gr < grid; gr++) {
            int matrixRow = gr - QUIET_ZONE;
            for (int gc = 0; gc < grid; gc++) {
                int matrixCol = gc - QUIET_ZONE;
                boolean dark = matrixRow >= 0 && matrixRow < m.length
                        && matrixCol >= 0 && matrixCol < m.length
                        && m[matrixRow][matrixCol];
                int c = dark ? black : white;
                for (int k = 0; k < scale; k++) {
                    row[gc * scale + k] = c;
                }
            }
            for (int y = gr * scale; y < gr * scale + scale; y++) {
                img.setRGB(0, y, px, 1, row, 0, px);
            }
        }
        return img;
    }

    /**
     * 生成 PNG 字节（{@link #image(String, int)} 的字节形态）：白底黑块、四周 4 模块静默区、整数倍缩放。
     *
     * <p>给「数据层只装字节」的调用点用（如 {@code Dto.QrSession} 的 PNG 载荷）：登录窗换图时不必把
     * {@code BufferedImage} 塞进 record。编码失败只可能是 ImageIO 的 IO 异常（写内存流，实际不会发生），
     * 此时返回 null 并由调用方决定回退，不抛给 UI 线程。</p>
     */
    public static byte[] png(String text, int targetPixels) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image(text, targetPixels), "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 能容纳该字节数的最小版本号（与 {@link #modules(String)} 用的是同一张表：M 等级、版本 1–20）。
     * 空串返回 1。
     *
     * @throws IllegalArgumentException 字节数为负，或超过 V20-M 的 666 字节上限（消息里带两个数字）
     */
    public static int versionFor(int byteLength) {
        if (byteLength < 0) {
            throw new IllegalArgumentException("byteLength 不能为负：" + byteLength);
        }
        for (int v = MIN_VERSION; v <= MAX_VERSION; v++) {
            if (byteCapacity(v) >= byteLength) {
                return v;
            }
        }
        throw new IllegalArgumentException("内容过长：需要 " + byteLength + " 字节，M 等级 V" + MAX_VERSION
                + " 最多 " + byteCapacity(MAX_VERSION) + " 字节（本实现只支持版本 1–20 / 纠错等级 M）");
    }

    // ---------------------------------------------------------------- 容量 / 尺寸

    /** byte mode 下该版本的字节容量：数据码字去掉 4 位模式指示 + 8/16 位长度域。 */
    private static int byteCapacity(int version) {
        int lenBits = version <= 9 ? 8 : 16;
        return (DATA_CODEWORDS[version - 1] * 8 - 4 - lenBits) / 8;
    }

    /** 版本对应的矩阵边长（不含静默区）。 */
    private static int sizeFor(int version) {
        return version * 4 + 17;
    }

    // ---------------------------------------------------------------- 数据码字与纠错

    /** 模式(4) + 长度(8/16) + 载荷 + 终止符(≤4) + 补齐字节边界 + 0xEC/0x11 填充 → 数据码字。 */
    private static byte[] buildCodewords(byte[] payload, int version) {
        int dataCodewords = DATA_CODEWORDS[version - 1];
        int lenBits = version <= 9 ? 8 : 16;
        int capacityBits = dataCodewords * 8;

        boolean[] bits = new boolean[capacityBits];
        int pos = 0;
        // 模式指示 0100 = byte mode
        pos = putBits(bits, pos, 0b0100, 4);
        pos = putBits(bits, pos, payload.length, lenBits);
        for (byte b : payload) {
            pos = putBits(bits, pos, b & 0xFF, 8);
        }
        if (pos > capacityBits) {
            // versionFor 已经拦过，这里只是兜底：说明容量表与长度域算错
            throw new IllegalStateException("容量表异常：版本 " + version + " 需要 " + pos + " 位 > " + capacityBits);
        }
        // 终止符：最多 4 个 0，且不超过容量
        pos = Math.min(pos + 4, capacityBits);

        byte[] data = new byte[dataCodewords];
        for (int i = 0; i < capacityBits; i++) {
            if (bits[i]) {
                data[i >>> 3] |= (byte) (1 << (7 - (i & 7)));
            }
        }
        // 补齐到字节边界由「位数组本就按整字节」自然满足；随后交替填充码字
        int firstPad = (pos + 7) / 8;
        for (int i = firstPad, k = 0; i < dataCodewords; i++, k++) {
            data[i] = (byte) ((k % 2 == 0) ? 0xEC : 0x11);
        }
        return interleave(data, version);
    }

    /** 写 bits：把 {@code value} 的低 {@code n} 位按高位在前写入，返回新位置。 */
    private static int putBits(boolean[] bits, int pos, int value, int n) {
        for (int i = n - 1; i >= 0; i--) {
            if (pos < bits.length) {
                bits[pos] = ((value >>> i) & 1) != 0;
            }
            pos++;
        }
        return pos;
    }

    /** 按表分块 → 每块补 RS 纠错码字 → 数据码字与纠错码字分别交织。 */
    private static byte[] interleave(byte[] data, int version) {
        int idx = version - 1;
        int eccLen = ECC_PER_BLOCK[idx];
        int g1 = G1_BLOCKS[idx];
        int g1Data = G1_DATA[idx];
        int g2 = G2_BLOCKS[idx];
        int g2Data = G2_DATA[idx];
        int blocks = g1 + g2;

        byte[][] blockData = new byte[blocks][];
        byte[][] blockEcc = new byte[blocks][];
        byte[] divisor = rsDivisor(eccLen);
        int off = 0;
        for (int i = 0; i < blocks; i++) {
            int len = i < g1 ? g1Data : g2Data;
            byte[] blk = new byte[len];
            System.arraycopy(data, off, blk, 0, len);
            off += len;
            blockData[i] = blk;
            blockEcc[i] = rsRemainder(blk, divisor);
        }
        if (off != data.length) {
            throw new IllegalStateException("块结构表与数据码字数不符：版本 " + version + " 用了 " + off + " != " + data.length);
        }

        byte[] out = new byte[DATA_CODEWORDS[idx] + eccLen * blocks];
        int p = 0;
        int maxData = Math.max(g1Data, g2Data);
        for (int i = 0; i < maxData; i++) {
            for (int b = 0; b < blocks; b++) {
                if (i < blockData[b].length) {
                    out[p++] = blockData[b][i];
                }
            }
        }
        for (int i = 0; i < eccLen; i++) {
            for (int b = 0; b < blocks; b++) {
                out[p++] = blockEcc[b][i];
            }
        }
        return out;
    }

    /** RS 生成多项式（首一，按 GF(256)/0x11D 展开），长度为纠错码字数。 */
    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) gfMul(result[j] & 0xFF, root);
                if (j + 1 < degree) {
                    result[j] ^= result[j + 1];
                }
            }
            root = gfMul(root, 0x02);
        }
        return result;
    }

    /** 多项式除法取余（即纠错码字）。 */
    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) {
                result[i] ^= (byte) gfMul(divisor[i] & 0xFF, factor);
            }
        }
        return result;
    }

    /** GF(2^8) 乘法，本原多项式 0x11D（x^8+x^4+x^3+x^2+1，QR 规范指定）。 */
    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    // ---------------------------------------------------------------- 功能图形

    private static void drawFunctionPatterns(boolean[][] m, boolean[][] fn, int version) {
        int size = m.length;
        // 定位时序图形（先画，随后被定位图形/对齐图形覆盖）
        for (int i = 0; i < size; i++) {
            setFn(m, fn, 6, i, i % 2 == 0);
            setFn(m, fn, i, 6, i % 2 == 0);
        }
        // 三个 7×7 定位图形 + 1 模块分隔符（切比雪夫距离 4 = 分隔符，2 = 内白环）
        drawFinder(m, fn, 3, 3, size);
        drawFinder(m, fn, 3, size - 4, size);
        drawFinder(m, fn, size - 4, 3, size);
        // 对齐图形：位置表的两两组合，去掉与三个定位图形重叠的三个角
        int[] pos = ALIGN[version - 1];
        for (int i = 0; i < pos.length; i++) {
            for (int j = 0; j < pos.length; j++) {
                boolean corner = (i == 0 && j == 0) || (i == 0 && j == pos.length - 1) || (i == pos.length - 1 && j == 0);
                if (!corner) {
                    drawAlign(m, fn, pos[j], pos[i]);
                }
            }
        }
        // 版本信息（v7 起）：18 位两处 6×3 块
        if (version >= 7) {
            int bits = versionBits(version);
            for (int i = 0; i < 18; i++) {
                boolean bit = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3;
                int b = i / 3;
                setFn(m, fn, a, b, bit);
                setFn(m, fn, b, a, bit);
            }
        }
        // 格式信息区：先按掩码 0 占位（同时把这两条臂标成功能模块，否则填位会把它们覆盖掉），
        // 真正的位串在挑掩码时逐个重写；固定深模块在最后压上
        drawFormatBits(m, fn, version, 0);
        setFn(m, fn, size - 8, 8, true);
    }

    private static void drawFinder(boolean[][] m, boolean[][] fn, int cx, int cy, int size) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx;
                int y = cy + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) {
                    int dist = Math.max(Math.abs(dx), Math.abs(dy));
                    setFn(m, fn, y, x, dist != 2 && dist != 4);
                }
            }
        }
    }

    private static void drawAlign(boolean[][] m, boolean[][] fn, int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                setFn(m, fn, cy + dy, cx + dx, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    private static void setFn(boolean[][] m, boolean[][] fn, int row, int col, boolean dark) {
        m[row][col] = dark;
        fn[row][col] = true;
    }

    // ---------------------------------------------------------------- 填位 / 掩码 / 格式信息

    /** 蛇形填位：从右下角起，两列一组自下而上/自上而下交替，跳过第 6 列与功能模块。 */
    private static void drawCodewords(boolean[][] m, boolean[][] fn, byte[] codewords) {
        int size = m.length;
        int totalBits = codewords.length * 8;
        int bitIndex = 0;
        boolean upward = true;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5;
            }
            for (int vert = 0; vert < size; vert++) {
                int row = upward ? size - 1 - vert : vert;
                for (int j = 0; j < 2; j++) {
                    int col = right - j;
                    if (fn[row][col]) {
                        continue;
                    }
                    boolean dark = false;
                    if (bitIndex < totalBits) {
                        dark = ((codewords[bitIndex >>> 3] >>> (7 - (bitIndex & 7))) & 1) != 0;
                    }
                    bitIndex++;
                    m[row][col] = dark;   // 余数位（remainder bits）越界即保持浅色，随后照常参与掩码
                }
            }
            upward = !upward;
        }
    }

    private static void applyMask(boolean[][] m, boolean[][] fn, int mask) {
        int size = m.length;
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                if (!fn[r][c] && maskBit(mask, r, c)) {
                    m[r][c] = !m[r][c];
                }
            }
        }
    }

    /** 8 种掩码条件（ISO/IEC 18004 表 10，行 i / 列 j）。 */
    private static boolean maskBit(int mask, int i, int j) {
        switch (mask) {
            case 0: return (i + j) % 2 == 0;
            case 1: return i % 2 == 0;
            case 2: return j % 3 == 0;
            case 3: return (i + j) % 3 == 0;
            case 4: return (i / 2 + j / 3) % 2 == 0;
            case 5: return (i * j) % 2 + (i * j) % 3 == 0;
            case 6: return ((i * j) % 2 + (i * j) % 3) % 2 == 0;
            case 7: return ((i + j) % 2 + (i * j) % 3) % 2 == 0;
            default: throw new IllegalArgumentException("非法掩码号：" + mask);
        }
    }

    /**
     * 格式信息 15 位：BCH(15,5)（生成多项式 0x537）+ 固定异或 0x5412，写两处副本。
     *
     * <p>位的直线排布按 nayuki QR-Code-generator 的 {@code QrCode.drawFormatBits()} 核对（x=列 / y=行）：
     * 第一份 bit0–5 在第 8 列行 0–5、bit6 在第 8 列行 7、bit7 在 (8,8)、bit8 在第 8 行列 7、bit9–14 在第 8 行列 5–0；
     * 第二份 bit0–7 在第 8 行列 size-1…size-8、bit8–14 在第 8 列行 size-7…size-1（行 size-8 留给固定深模块）。
     * 坑：这两条臂的行/列是反的（不是对称的），照「行 8 放低位」写会得到镜像的、扫不出来的码；同时必须把
     * 这些格子标进 fn，否则蛇形填位会把格式信息当数据格盖掉。</p>
     */
    private static void drawFormatBits(boolean[][] m, boolean[][] fn, int version, int mask) {
        int size = m.length;
        int bits = formatBits(mask);
        for (int i = 0; i <= 5; i++) {
            putFn(m, fn, i, 8, bits, i);
        }
        putFn(m, fn, 7, 8, bits, 6);
        putFn(m, fn, 8, 8, bits, 7);
        putFn(m, fn, 8, 7, bits, 8);
        for (int i = 9; i < 15; i++) {
            putFn(m, fn, 8, 14 - i, bits, i);
        }
        for (int i = 0; i < 8; i++) {
            putFn(m, fn, 8, size - 1 - i, bits, i);
        }
        for (int i = 8; i < 15; i++) {
            putFn(m, fn, size - 15 + i, 8, bits, i);
        }
    }

    /** 写一位格式信息并把它标成功能模块。 */
    private static void putFn(boolean[][] m, boolean[][] fn, int row, int col, int bits, int i) {
        m[row][col] = ((bits >>> i) & 1) != 0;
        fn[row][col] = true;
    }

    /** 5 位（等级 2 位 + 掩码 3 位）→ 15 位格式串。 */
    private static int formatBits(int mask) {
        int data = (M_LEVEL_BITS << 3) | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        }
        return ((data << 10) | rem) ^ 0x5412;
    }

    /** 版本号 → 18 位版本信息串（BCH(18,6)，生成多项式 0x1F25）。 */
    private static int versionBits(int version) {
        int rem = version;
        for (int i = 0; i < 12; i++) {
            rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        }
        return (version << 12) | rem;
    }

    // ---------------------------------------------------------------- 掩码惩罚

    /** 四条惩罚规则之和（用于挑掩码；算错只会换个掩码，不影响可扫性）。 */
    private static int penalty(boolean[][] m) {
        int size = m.length;
        int p = 0;
        boolean[] line = new boolean[size];
        for (int k = 0; k < size; k++) {
            for (int i = 0; i < size; i++) {
                line[i] = m[k][i];
            }
            p += runPenalty(line);
            for (int i = 0; i < size; i++) {
                line[i] = m[i][k];
            }
            p += runPenalty(line);
        }
        for (int r = 0; r + 1 < size; r++) {
            for (int c = 0; c + 1 < size; c++) {
                boolean v = m[r][c];
                if (v == m[r][c + 1] && v == m[r + 1][c] && v == m[r + 1][c + 1]) {
                    p += 3;
                }
            }
        }
        for (int k = 0; k < size; k++) {
            for (int i = 0; i < size; i++) {
                line[i] = m[k][i];
            }
            p += finderLikePenalty(line);
            for (int i = 0; i < size; i++) {
                line[i] = m[i][k];
            }
            p += finderLikePenalty(line);
        }
        int dark = 0;
        for (boolean[] row : m) {
            for (boolean b : row) {
                if (b) {
                    dark++;
                }
            }
        }
        int total = size * size;
        p += Math.abs(dark * 100 - total * 50) / (total * 5) * 10;
        return p;
    }

    /** 规则 1：一行/一列里连续 ≥5 个同色模块，每段 3 + (长度-5) 分。 */
    private static int runPenalty(boolean[] line) {
        int p = 0;
        int run = 1;
        for (int i = 1; i < line.length; i++) {
            if (line[i] == line[i - 1]) {
                run++;
            } else {
                if (run >= 5) {
                    p += 3 + (run - 5);
                }
                run = 1;
            }
        }
        if (run >= 5) {
            p += 3 + (run - 5);
        }
        return p;
    }

    /** 规则 3：出现 1:1:3:1:1 的定位图形样式（两侧留 4 个浅色模块）每处 40 分。 */
    private static int finderLikePenalty(boolean[] line) {
        int p = 0;
        for (int i = 0; i + 11 <= line.length; i++) {
            if (matches(line, i, new boolean[]{true, false, true, true, true, false, true, false, false, false, false})
                    || matches(line, i, new boolean[]{false, false, false, false, true, false, true, true, true, false, true})) {
                p += 40;
            }
        }
        return p;
    }

    private static boolean matches(boolean[] line, int off, boolean[] pattern) {
        for (int i = 0; i < pattern.length; i++) {
            if (line[off + i] != pattern[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean[][] copy(boolean[][] src) {
        boolean[][] dst = new boolean[src.length][];
        for (int i = 0; i < src.length; i++) {
            dst[i] = src[i].clone();
        }
        return dst;
    }

    private QrEncoder() {
    }
}
