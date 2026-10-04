package com.example.netease.core;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * QrEncoder 离线自验证探针（不联网、零依赖）。
 *
 * <p>编译（工作区根目录）：</p>
 * <pre>
 * $jdk='C:\Program Files\Java\jdk-21.0.10'
 * &amp; "$jdk\bin\javac.exe" -encoding UTF-8 --release 21 -cp "tools\.cache\spw-workshop-api-host.jar;tools\.cache\pf4j-3.12.0.jar" -d build\qr-check (src 下全部 .java) tools\smoke\QrEncoderProbe.java
 * &amp; "$jdk\bin\java.exe" -Dfile.encoding=UTF-8 -cp "build\qr-check;tools\.cache\spw-workshop-api-host.jar;tools\.cache\pf4j-3.12.0.jar" com.example.netease.core.QrEncoderProbe
 * </pre>
 *
 * <p>退出码 0 = 全绿，1 = 有 FAIL；最后一行固定 <code>QR_ENCODER_RESULT=ALL_PASS</code> 或 <code>QR_ENCODER_RESULT=FAILED</code>。</p>
 *
 * <p>六节内容：一 结构断言（定位/时序/固定深模块/对齐/格式信息/版本信息，均与本页抄下的权威位串比对）；
 * 二 功能模块布局与数据容量（v1–20 全量，用 nayuki 的解析式独立算一遍数据模块数）；
 * 三 自写独立解码器回环（不去掩码、拆块、RS 伴随式全零、取 byte mode 载荷）；
 * 四 容量边界（每版本刚好装满 + 1 字节升版）；五 image() 与异常契约；
 * 六 外部交叉验证（解别人生成的 PNG；与 segno 逐位比对矩阵）。</p>
 *
 * <p>第六节读 <code>tools\smoke\out-qr-ext\</code>：<code>*.png</code> 配同名 <code>.txt</code> 边车（<code>level=</code>/<code>text=</code>），
 * <code>mat-&lt;例&gt;.meta</code> 配 <code>mat-&lt;例&gt;-mask&lt;k&gt;.matrix</code>（0/1 文本矩阵，不含静默区）。
 * 目录不存在或无文件 ⇒ 打印 SKIP 并在结尾注明「外部交叉验证未完成」，不算失败。</p>
 */
public final class QrEncoderProbe {

    // ------------------------------------------------------------ 计数与输出

    private static int pass;
    private static int fail;
    private static int skip;
    private static final List<String> FAILURES = new ArrayList<>();

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("PASS  " + name + (detail.isEmpty() ? "" : "  |  " + detail));
        } else {
            fail++;
            FAILURES.add(name + "  |  " + detail);
            System.out.println("FAIL  " + name + "  |  " + detail);
        }
    }

    private static void info(String msg) {
        System.out.println("INFO  " + msg);
    }

    private static void skipping(String name, String why) {
        skip++;
        System.out.println("SKIP  " + name + "  |  " + why);
    }

    // ------------------------------------------------------------ 权威位串（thonky format-version-tables 页面正文抄下）
    // 索引 = 等级序号*8 + 掩码；等级顺序 L=0, M=1, Q=2, H=3（与页面「List of all Format Information Strings」一致）
    private static final String[] THONKY_FMT = {
            // L
            "111011111000100", "111001011110011", "111110110101010", "111100010011101",
            "110011000101111", "110001100011000", "110110001000001", "110100101110110",
            // M
            "101010000010010", "101000100100101", "101111001111100", "101101101001011",
            "100010111111001", "100000011001110", "100111110010111", "100101010100000",
            // Q
            "011010101011111", "011000001101000", "011111100110001", "011101000000110",
            "010010010110100", "010000110000011", "010111011011010", "010101111101101",
            // H
            "001011010001001", "001001110111110", "001110011100111", "001100111010000",
            "000011101100010", "000001001010101", "000110100001100", "000100000111011"};

    /** 版本信息 18 位（v1–6 无，占位空串）；来源同上页。 */
    private static final String[] THONKY_VER = {
            "", "", "", "", "", "",
            "000111110010010100", "001000010110111100", "001001101010011001", "001010010011010011",
            "001011101111110110", "001100011101100010", "001101100001000111", "001110011000001101",
            "001111100100101000", "010000101101111000", "010001010001011101", "010010101000010111",
            "010011010100110010", "010100100110100110"};

    /** 对齐图形中心（python-qrcode qrcode/util.py PATTERN_POSITION_TABLE；与 thonky 页比对 0 处不一致）。 */
    private static final int[][] ALIGN = {
            {},
            {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34},
            {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50}, {6, 30, 54},
            {6, 32, 58}, {6, 34, 62}, {6, 26, 46, 66}, {6, 26, 48, 70}, {6, 26, 50, 74},
            {6, 30, 54, 78}, {6, 30, 56, 82}, {6, 30, 58, 86}, {6, 34, 62, 90}};

    private static final int MAX_VERSION = 20;

    // ------------------------------------------------------------ main

    public static void main(String[] args) {
        System.out.println("== QrEncoderProbe：QrEncoder 结构与回环自验证 ==");
        sectionOne();
        sectionTwo();
        sectionThree();
        sectionFour();
        sectionFive();
        sectionSix();

        System.out.println();
        System.out.println("---- 汇总：" + pass + " PASS / " + fail + " FAIL / " + skip + " SKIP ----");
        for (String f : FAILURES) {
            System.out.println("  失败项：" + f);
        }
        System.out.println("QR_ENCODER_RESULT=" + (fail == 0 ? "ALL_PASS" : "FAILED"));
        System.exit(fail > 0 ? 1 : 0);
    }

    // ============================================================ 一 结构断言

    private static void sectionOne() {
        System.out.println();
        System.out.println("== 一、结构断言（含与权威页位串的逐位比对）==");
        int[] versions = {1, 2, 6, 7, 10, 14, 20};
        for (int v : versions) {
            String text = fillFor( v);
            boolean[][] m = QrEncoder.modules(text);
            String p = "v" + v + "(" + text.length() + "B) ";
            check("一.边长 " + p, m.length == 4 * v + 17 && m[0].length == m.length,
                    "边长=" + m.length + "，期望 " + (4 * v + 17));

            check("一.定位图形 " + p, findersOk(m), finderDetail(m));
            check("一.分隔符浅色 " + p, separatorsLight(m), separatorsDetail(m));
            check("一.时序图形交替 " + p, timingOk(m), "第 6 行/列 在 8.." + (m.length - 9) + " 区间应严格交替且首位深色");
            check("一.固定深模块 " + p, m[m.length - 8][8], "(行 size-8, 列 8) 必须恒深");

            int[] fmt = readFormat(m);
            String fbits = fmt == null ? "两处副本不一致或查表失败" : toBits15(fmt[2], 15);
            check("一.格式信息 = 权威表 " + p, fmt != null && fmt[0] == 1,
                    "解出=" + fbits + " 等级序号=" + (fmt == null ? "-" : String.valueOf(fmt[0]))
                            + "(1=M) 掩码=" + (fmt == null ? "-" : String.valueOf(fmt[1]))
                            + "，权威页 M" + (fmt == null ? "-" : fmt[1]) + "=" + (fmt == null ? "-" : THONKY_FMT[8 + fmt[1]]));
            check("一.格式信息两处副本一致 " + p, formatCopiesEqual(m), "左上臂与左下/右上臂必须同值");

            if (v >= 7) {
                String verBits = readVersionBits(m);
                check("一.版本信息 = 权威表 " + p, verBits != null && verBits.equals(THONKY_VER[v - 1]),
                        "矩阵读出=" + verBits + "，权威页 v" + v + "=" + THONKY_VER[v - 1]);
            }

            int[] pos = ALIGN[v - 1];
            boolean alignOk = true;
            StringBuilder alignDetail = new StringBuilder();
            for (int i = 0; i < pos.length; i++) {
                for (int j = 0; j < pos.length; j++) {
                    boolean corner = (i == 0 && j == 0) || (i == 0 && j == pos.length - 1) || (i == pos.length - 1 && j == 0);
                    if (!corner && !alignOk(m, pos[i], pos[j])) {
                        alignOk = false;
                        alignDetail.append("缺失/错位@(").append(pos[i]).append(",").append(pos[j]).append(") ");
                    }
                }
            }
            if (!alignOkAtFinderCorners(m, pos)) {
                alignOk = false;
                alignDetail.append("与定位图形重叠处被误画成对齐图形 ");
            }
            check("一.对齐图形 " + p, alignOk,
                    pos.length == 0 ? "v1 无对齐图形" : ("中心=" + java.util.Arrays.toString(pos) + " 共 "
                            + (pos.length * pos.length - 3) + " 处 " + alignDetail));
        }
    }

    /** 造一个「刚好必须用该版本」的串：'A' * (上一版本容量 + 1)。 */
    private static String fillFor(int version) {
        if (version == 1) {
            return "A";
        }
        return repeat('A', Decoder.byteCapacity(version - 1) + 1);
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean findersOk(boolean[][] m) {
        int size = m.length;
        int[][] corners = {{0, 0}, {0, size - 7}, {size - 7, 0}};
        for (int[] c : corners) {
            for (int dr = 0; dr < 7; dr++) {
                for (int dc = 0; dc < 7; dc++) {
                    boolean want = Math.max(Math.abs(dr - 3), Math.abs(dc - 3)) != 2;
                    if (m[c[0] + dr][c[1] + dc] != want) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static String finderDetail(boolean[][] m) {
        int size = m.length;
        return "三个 7×7 @(0,0),(0," + (size - 7) + "),(" + (size - 7) + ",0)，期望 实心3×3+浅环+实心框";
    }

    private static boolean separatorsLight(boolean[][] m) {
        int size = m.length;
        for (int i = 0; i < 8; i++) {
            if (m[7][i] || m[i][7]) {
                return false;
            }
            if (m[7][size - 1 - i] || m[i][size - 8]) {
                return false;
            }
            if (m[size - 8][i] || m[size - 1 - i][7]) {
                return false;
            }
        }
        return true;
    }

    private static String separatorsDetail(boolean[][] m) {
        return "三个定位图形外一圈 8×8 边界（除时序入口）必须全浅";
    }

    private static boolean timingOk(boolean[][] m) {
        int size = m.length;
        for (int i = 8; i <= size - 9; i++) {
            if (m[6][i] != (i % 2 == 0) || m[i][6] != (i % 2 == 0)) {
                return false;
            }
        }
        return true;
    }

    /** 对齐图形：中心 + 环（切比雪夫距离 2 深、1 浅、0 深）。 */
    private static boolean alignOk(boolean[][] m, int cr, int cc) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                boolean want = Math.max(Math.abs(dx), Math.abs(dy)) != 1;
                if (m[cr + dy][cc + dx] != want) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 与三个定位图形重叠的三个对齐位不该被画成对齐图形：取一处时序/定位图形独有值来区分。 */
    private static boolean alignOkAtFinderCorners(boolean[][] m, int[] pos) {
        if (pos.length < 2) {
            return true;
        }
        int size = m.length;
        // 左上：若在 (6,6) 画了对齐图形，(5,6) 会变成浅色；但它是时序图形，必须深
        if (!m[5][6]) {
            return false;
        }
        // 右上：若在 (6,size-7) 画了对齐图形，(5,size-7) 会变浅；定位图形该处为深
        if (!m[5][size - 7]) {
            return false;
        }
        // 左下：对称
        return m[size - 7][5];
    }

    /** 读第一份格式信息副本的 15 位（bit i 的位置与 QrEncoder 相反方向即可视为独立读法）。 */
    private static int formatBitsAt(boolean[][] m, boolean second) {
        int size = m.length;
        int bits = 0;
        if (!second) {
            for (int i = 0; i <= 5; i++) {
                if (m[i][8]) {
                    bits |= 1 << i;
                }
            }
            if (m[7][8]) {
                bits |= 1 << 6;
            }
            if (m[8][8]) {
                bits |= 1 << 7;
            }
            if (m[8][7]) {
                bits |= 1 << 8;
            }
            for (int i = 9; i < 15; i++) {
                if (m[8][14 - i]) {
                    bits |= 1 << i;
                }
            }
        } else {
            for (int i = 0; i < 8; i++) {
                if (m[8][size - 1 - i]) {
                    bits |= 1 << i;
                }
            }
            for (int i = 8; i < 15; i++) {
                if (m[size - 15 + i][8]) {
                    bits |= 1 << i;
                }
            }
        }
        return bits;
    }

    private static boolean formatCopiesEqual(boolean[][] m) {
        return formatBitsAt(m, false) == formatBitsAt(m, true);
    }

    /** 查 thonky 的 32 条格式串 ⇒ {等级序号, 掩码号, 15 位串}；查不到（BCH 不成立）返回 null。 */
    private static int[] readFormat(boolean[][] m) {
        if (!formatCopiesEqual(m)) {
            return null;
        }
        int bits = formatBitsAt(m, false);
        String s = toBits15(bits, 15);
        for (int i = 0; i < THONKY_FMT.length; i++) {
            if (THONKY_FMT[i].equals(s)) {
                return new int[]{i / 8, i % 8, bits};
            }
        }
        return null;
    }

    private static String toBits15(int bits, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = n - 1; i >= 0; i--) {
            sb.append(((bits >>> i) & 1));
        }
        return sb.toString();
    }

    /** 读版本信息（两处 6×3 块），返回 18 位串；两处不一致返回 null。 */
    private static String readVersionBits(boolean[][] m) {
        int size = m.length;
        int a = 0;
        int b = 0;
        for (int i = 0; i < 18; i++) {
            int x = size - 11 + i % 3;
            int y = i / 3;
            if (m[y][x]) {
                a |= 1 << i;
            }
            if (m[x][y]) {
                b |= 1 << i;
            }
        }
        if (a != b) {
            return null;
        }
        return toBits15(a, 18);
    }

    // ============================================================ 二 功能模块布局 / 数据容量

    private static void sectionTwo() {
        System.out.println();
        System.out.println("== 二、功能模块布局与数据容量（v1–20 全量）==");
        boolean allOk = true;
        boolean remOk = true;
        StringBuilder detail = new StringBuilder();
        for (int v = 1; v <= 20; v++) {
            boolean[][] fn = Decoder.functionMap(v);
            int nonFn = 0;
            for (boolean[] row : fn) {
                for (boolean b : row) {
                    if (!b) {
                        nonFn++;
                    }
                }
            }
            int expect = rawDataModules(v);
            int totalCw = Decoder.totalCodewords(v);
            int remainder = nonFn - totalCw * 8;
            if (nonFn != expect) {
                allOk = false;
                detail.append("v").append(v).append(":").append(nonFn).append("!=").append(expect).append(" ");
            }
            if (remainder != 0 && remainder != 3 && remainder != 7) {
                remOk = false;
                detail.append("v").append(v).append(" 余数位=").append(remainder).append(" ");
            }
            if (v <= 3 || v == 20) {
                info("v" + v + " 数据模块=" + nonFn + "（nayuki 解析式 " + expect + "）余数位=" + remainder
                        + " 总码字=" + totalCw);
            }
        }
        check("二.数据模块数 = nayuki 解析式 (v1–20)", allOk,
                allOk ? "20 个版本逐一相符（功能图形布局：定位+分隔、时序、对齐、版本信息、格式臂、固定深模块）" : detail.toString());
        check("二.余数位数 ∈ {0,3,7} (v1–20)", remOk, remOk ? "与「数据模块总数 − 8×总码字数」自洽" : detail.toString());
    }

    /** nayuki QR-Code-generator（QrCode.getNumRawDataModules）的解析式，作为第三个独立来源。 */
    private static int rawDataModules(int ver) {
        int size = ver * 4 + 17;
        int result = size * size;
        result -= 8 * 8 * 3;
        result -= 15 * 2 + 1;
        result -= (size - 16) * 2;
        if (ver >= 2) {
            int numAlign = ver / 7 + 2;
            result -= (numAlign - 1) * (numAlign - 1) * 25;
            result -= (numAlign - 2) * 2 * 20;
            if (ver >= 7) {
                result -= 6 * 3 * 2;
            }
        }
        return result;
    }

    // ============================================================ 三 独立解码器回环

    private static void sectionThree() {
        System.out.println();
        System.out.println("== 三、独立解码器回环（decode(modules(s)) == s）==");
        // 233 字节左右的逼真样例（与 tools\smoke\out-qr-ext\ext-longurl.txt 里的串必须逐字符相同）
        String longUrl = "https://st.music.163.com/encrypt-pages?eventId=0b2f4c9a-7d31-4e58-9a6c-1f2e3d4c5b6a"
                + "&sign=QmFzZTY0U2lnbmF0dXJlRXhhbXBsZQ%3D%3D&verifyType=40"
                + "&verifyId=8f7e6d5c-4b3a-2918-7065-4f3e2d1c0b9a&blockText=%E9%AA%8C%E8%AF%81%E9%93%BE%E6%8E%A5";
        String cjk = "扫码登录：把这条链接画成二维码，手机扫一扫即可完成验证（中文多字节回归）。";

        List<String> cases = new ArrayList<>();
        cases.add("");
        cases.add("HELLO");
        cases.add(repeat('A', 100));
        cases.add(longUrl);
        cases.add(cjk);

        Set<Integer> hitVersions = new LinkedHashSet<>();
        for (String text : cases) {
            int bytes = text.getBytes(StandardCharsets.UTF_8).length;
            String label = "三.回环 " + describe(text) + " (" + bytes + "B)";
            try {
                boolean[][] m = QrEncoder.modules(text);
                int v = (m.length - 17) / 4;
                hitVersions.add(v);
                Decoder.Decoded d = Decoder.decode(m);
                boolean ok = d.text.equals(text) && d.rsOk && d.mode == 0b0100
                        && d.level == 1 && d.mask == Decoder.maskOf(m)
                        && d.declaredLength == bytes;
                check(label, ok, "版本 v" + v + " 边长=" + m.length + " 掩码=" + d.mask
                        + " 等级序号=" + d.level + " RS伴随式全零=" + d.rsOk + " 载体字节=" + d.declaredLength
                        + " 解出长度=" + d.text.getBytes(StandardCharsets.UTF_8).length
                        + (ok ? "" : " 期望长度=" + bytes));
                if (!ok) {
                    info("   解出内容前 80 字符：" + brief(d.text));
                }
            } catch (RuntimeException | CharacterCodingException e) {
                check(label, false, "解码抛错：" + e);
            }
        }
        check("三.命中 ≥3 个不同版本号", hitVersions.size() >= 3, "命中版本=" + hitVersions);

        // 长链接必须落在 v11（M 等级容量 251B）
        int urlVersion = QrEncoder.versionFor(longUrl.getBytes(StandardCharsets.UTF_8).length);
        check("三.长验证链接版本 = v11", urlVersion == 11,
                "链接 " + longUrl.getBytes(StandardCharsets.UTF_8).length + "B ⇒ v" + urlVersion + "（M v11 上限 251B）");
    }

    private static String describe(String text) {
        if (text.isEmpty()) {
            return "\"\"空串";
        }
        if (text.length() > 24) {
            return "\"" + text.substring(0, 12) + "…" + text.substring(text.length() - 6) + "\"";
        }
        return "\"" + text + "\"";
    }

    private static String brief(String s) {
        String t = s.replace("\n", "\\n");
        return t.length() > 80 ? t.substring(0, 80) + "…" : t;
    }

    // ============================================================ 四 容量边界

    private static void sectionFour() {
        System.out.println();
        System.out.println("== 四、容量边界（每版本刚好装满 / +1 字节升版）==");
        boolean allOk = true;
        StringBuilder detail = new StringBuilder();
        for (int v = 1; v <= MAX_VERSION; v++) {
            int cap = Decoder.byteCapacity(v);
            String exact = repeat('A', cap);
            int got = QrEncoder.versionFor(cap);
            int gotByMatrix = (QrEncoder.modules(exact).length - 17) / 4;
            boolean ok = got == v && gotByMatrix == v;
            if (!ok) {
                allOk = false;
                detail.append("v").append(v).append(" 装满(").append(cap).append("B)⇒versionFor=").append(got)
                        .append(",矩阵=").append(gotByMatrix).append(" ");
            }
            if (v < MAX_VERSION) {
                String over = repeat('A', cap + 1);
                int next = QrEncoder.versionFor(cap + 1);
                int matrixVer = (QrEncoder.modules(over).length - 17) / 4;
                if (next != v + 1 || matrixVer != v + 1) {
                    allOk = false;
                    detail.append("v").append(v).append(" +1B⇒").append(next).append("/").append(matrixVer)
                            .append(" 期望 ").append(v + 1).append(" ");
                }
            } else {
                boolean threw = false;
                String msg = "";
                try {
                    QrEncoder.modules(repeat('A', cap + 1));
                } catch (IllegalArgumentException e) {
                    threw = true;
                    msg = e.getMessage();
                }
                boolean msgOk = threw && msg.contains(String.valueOf(cap + 1)) && msg.contains(String.valueOf(cap));
                if (!msgOk) {
                    allOk = false;
                    detail.append("v20 +1B 未按契约抛错或消息缺数字（抛错=").append(threw).append(" msg=").append(msg).append("） ");
                }
                check("四.超上限抛错并带两个数字", msgOk, "message=\"" + msg + "\"");
            }
            if (v <= 3 || v == 7 || v == 20) {
                info("v" + v + " 字节容量=" + cap + "（装满串 " + cap + "B 落在 v" + gotByMatrix + "）");
            }
        }
        check("四.每版本刚好装满落在该版本 / +1 升一版", allOk,
                allOk ? "v1–20 共 20 个装满点 + 19 个升版点全部相符" : detail.toString());
    }

    // ============================================================ 五 image() 与异常契约

    private static void sectionFive() {
        System.out.println();
        System.out.println("== 五、image() 与异常契约 ==");
        boolean[][] m = QrEncoder.modules("HELLO");
        int size = m.length;
        int grid = size + 8;

        BufferedImage img300 = QrEncoder.image("HELLO", 300);
        int scale300 = 300 / grid;
        check("五.整数倍缩放 ≤ targetPixels", img300.getWidth() == grid * scale300 && img300.getWidth() <= 300,
                "边长 " + img300.getWidth() + " = (" + size + "+8)×" + scale300 + "，targetPixels=300");
        check("五.图像类型 TYPE_INT_RGB", img300.getType() == BufferedImage.TYPE_INT_RGB,
                "type=" + img300.getType());
        check("五.白底黑块 + 四边静默区", quietZoneWhite(img300, 4 * scale300) && cornersConsistent(img300, m, 4, scale300),
                "静默区 4 模块（" + (4 * scale300) + "px）全白；矩阵模块中心颜色与矩阵一致");

        BufferedImage img0 = QrEncoder.image("HELLO", 0);
        BufferedImage imgNeg = QrEncoder.image("HELLO", -7);
        BufferedImage imgSmall = QrEncoder.image("HELLO", grid - 1);
        check("五.targetPixels<=0 → 1:1", img0.getWidth() == grid && imgNeg.getWidth() == grid,
                "0 ⇒ " + img0.getWidth() + "px，-7 ⇒ " + imgNeg.getWidth() + "px（" + grid + " 模块 1:1）");
        check("五.targetPixels<边长 → 缩放因子仍 ≥1", imgSmall.getWidth() == grid,
                "targetPixels=" + (grid - 1) + " ⇒ " + imgSmall.getWidth() + "px");

        check("五.边长足够时正好整除", QrEncoder.image("HELLO", grid * 7).getWidth() == grid * 7,
                "targetPixels=" + (grid * 7) + " ⇒ 恰好 " + (grid * 7) + "px");

        check("五.versionFor(0)=1", QrEncoder.versionFor(0) == 1, "空串用 v1");
        check("五.versionFor(666)=20", QrEncoder.versionFor(666) == 20, "V20-M 上限");
        check("五.versionFor(667) 抛 IllegalArgumentException",
                throwsIae(() -> QrEncoder.versionFor(667)) && iaeMessage(() -> QrEncoder.versionFor(667)).contains("667"),
                "message=\"" + iaeMessage(() -> QrEncoder.versionFor(667)) + "\"");
        check("五.versionFor(-1) 抛 IllegalArgumentException", throwsIae(() -> QrEncoder.versionFor(-1)),
                "负数非法");
        check("五.modules(null) 抛 IllegalArgumentException", throwsIae(() -> QrEncoder.modules(null)),
                "null 非法");
    }

    private static boolean quietZoneWhite(BufferedImage img, int qz) {
        int n = img.getWidth();
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < qz; k++) {
                if ((img.getRGB(i, k) & 0xFFFFFF) != 0xFFFFFF
                        || (img.getRGB(i, n - 1 - k) & 0xFFFFFF) != 0xFFFFFF
                        || (img.getRGB(k, i) & 0xFFFFFF) != 0xFFFFFF
                        || (img.getRGB(n - 1 - k, i) & 0xFFFFFF) != 0xFFFFFF) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 抽查矩阵模块中心：深色模块必为黑、浅色模块必为白。 */
    private static boolean cornersConsistent(BufferedImage img, boolean[][] m, int qz, int scale) {
        for (int r = 0; r < m.length; r++) {
            for (int c = 0; c < m.length; c++) {
                int x = (qz + c) * scale + scale / 2;
                int y = (qz + r) * scale + scale / 2;
                int rgb = img.getRGB(x, y) & 0xFFFFFF;
                if (m[r][c] ? rgb != 0x000000 : rgb != 0xFFFFFF) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean throwsIae(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static String iaeMessage(Runnable r) {
        try {
            r.run();
            return "";
        } catch (IllegalArgumentException e) {
            return e.getMessage() == null ? "" : e.getMessage();
        }
    }

    // ============================================================ 六 外部交叉验证

    private static void sectionSix() {
        System.out.println();
        System.out.println("== 六、外部交叉验证（别人生成的二维码 / 逐位比对矩阵）==");
        Path dir = Paths.get(System.getProperty("qr.ext.dir", "tools/smoke/out-qr-ext"));
        if (!Files.isDirectory(dir)) {
            skipping("六.外部交叉验证", "目录不存在：" + dir.toAbsolutePath() + " ⇒ 外部交叉验证未完成");
            return;
        }
        int pngTotal = 0;
        int pngOk = 0;
        int levelLSeen = 0;
        List<Path> pngs = new ArrayList<>();
        List<Path> metas = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.endsWith(".png")) {
                    pngs.add(p);
                } else if (n.endsWith(".meta")) {
                    metas.add(p);
                }
            }
        } catch (IOException e) {
            check("六.列举外部参考文件", false, "IO 异常：" + e);
            return;
        }
        java.util.Collections.sort(pngs);
        java.util.Collections.sort(metas);

        for (Path png : pngs) {
            String base = png.getFileName().toString().replaceAll("\\.png$", "");
            Path side = png.resolveSibling(base + ".txt");
            if (!Files.isRegularFile(side)) {
                skipping("六.解外部图 " + base, "缺同名边车 " + base + ".txt（level=/text=），跳过");
                continue;
            }
            pngTotal++;
            List<String> lines;
            try {
                lines = Files.readAllLines(side, StandardCharsets.UTF_8);
            } catch (IOException e) {
                check("六.读边车 " + base, false, "IO 异常：" + e);
                continue;
            }
            String expLevel = "M";
            String expText = null;
            for (String line : lines) {
                String s = line.trim();
                if (s.startsWith("level=")) {
                    expLevel = s.substring(6).trim();
                } else if (s.startsWith("text=")) {
                    expText = s.substring(5);
                }
            }
            try {
                BufferedImage img = ImageIO.read(png.toFile());
                if (img == null) {
                    check("六.解外部图 " + base, false, "ImageIO 读不出像素");
                    continue;
                }
                Locator loc = locate(img);
                if (loc == null) {
                    check("六.解外部图 " + base, false, "找不到左上定位图形（图内无足够深的像素块）");
                    continue;
                }
                int version = 0;
                boolean[][] m = null;
                int found = 0;
                for (int v = 1; v <= MAX_VERSION; v++) {
                    boolean[][] cand = sample(img, loc, v);
                    if (cand == null || !structurallyValid(cand)) {
                        continue;
                    }
                    int[] cf = readFormat(cand);
                    boolean isM = cf != null && levelName(cf[0]).equals("M");
                    boolean decodable = false;
                    try {
                        // M 等级图必须真能解出来（格式合法 + RS 伴随式全零）才算候选；
                        // 非 M 等级图（负对照）走不通 M 的块结构，退化为纯结构判定
                        decodable = Decoder.decode(cand).rsOk;
                    } catch (Exception e) {
                        decodable = false;
                    }
                    if (isM && !decodable) {
                        continue;
                    }
                    found++;
                    version = v;
                    m = cand;
                }
                if (found != 1) {
                    check("六.解外部图 " + base, false, "候选版本数=" + found + "（期望唯一），符号包围盒="
                            + loc.w + "x" + loc.h + "px 起点=(" + loc.x0 + "," + loc.y0 + ")");
                    continue;
                }
                int[] fmt = readFormat(m);
                boolean levelOk = fmt != null && levelName(fmt[0]).equals(expLevel);
                if (fmt == null) {
                    check("六.解外部图 " + base, false, "格式信息查 thonky 表失败（读出的不是合法 BCH 格式串）");
                    continue;
                }
                if (!levelOk) {
                    check("六.解外部图 " + base, false, "等级=" + levelName(fmt[0]) + "，边车期望 " + expLevel);
                    continue;
                }
                if (!expLevel.equals("M")) {
                    // 负对照：证明等级位是真读出来的（不是恒等于 M 的假断言）
                    levelLSeen++;
                    check("六.解外部图 " + base + " 负对照", true,
                            "外部图为 " + expLevel + " 等级 ⇒ 解出等级=" + levelName(fmt[0]) + "（v" + version
                                    + " 掩码=" + fmt[1] + "），本实现只支持 M，不做解码");
                    continue;
                }
                Decoder.Decoded d = Decoder.decode(m);
                boolean ok = d.rsOk && d.text.equals(expText);
                if (ok) {
                    pngOk++;
                }
                check("六.解外部图 " + base + "（" + expLevel + " 等级）", ok,
                        "v" + version + " 掩码=" + d.mask + " RS伴随式全零=" + d.rsOk
                                + " 解出=" + describe(d.text) + (ok ? "" : " 期望=" + describe(expText == null ? "" : expText)));
            } catch (IOException | RuntimeException e) { // CharacterCodingException 是 IOException 子类，不能同列 multi-catch
                check("六.解外部图 " + base, false, "异常：" + e);
            }
        }

        int metaOk = 0;
        for (Path meta : metas) {
            String base = meta.getFileName().toString().replaceAll("\\.meta$", "");
            String text = null;
            int expVersion = 0;
            int segnoMask = -1;
            try {
                for (String line : Files.readAllLines(meta, StandardCharsets.UTF_8)) {
                    String s = line.trim();
                    if (s.startsWith("text=")) {
                        text = s.substring(5);
                    } else if (s.startsWith("version=")) {
                        expVersion = Integer.parseInt(s.substring(8).trim());
                    } else if (s.startsWith("mask=")) {
                        segnoMask = Integer.parseInt(s.substring(5).trim());
                    }
                }
            } catch (IOException | NumberFormatException e) {
                check("六.读 meta " + base, false, "异常：" + e);
                continue;
            }
            if (text == null) {
                check("六.读 meta " + base, false, "缺 text= 行");
                continue;
            }
            boolean[][] mine = QrEncoder.modules(text);
            int myVersion = (mine.length - 17) / 4;
            int[] fmt = readFormat(mine);
            int myMask = fmt == null ? -1 : fmt[1];
            check("六.版本选择与 segno 一致 " + base, myVersion == expVersion,
                    "我方 v" + myVersion + "，segno v" + expVersion + "（文本 " + text.getBytes(StandardCharsets.UTF_8).length + "B）");
            info("六.掩码选择 " + base + "：我方=" + myMask + "，segno=" + segnoMask
                    + "（掩码是编码器的自由选择项，不影响可扫性；比对的是解出的位与文本，不是矩阵本身）");
            int files = 0;
            int eq = 0;
            StringBuilder bad = new StringBuilder();
            for (int k = 0; k < 8; k++) {
                Path ref = meta.resolveSibling(base + "-mask" + k + ".matrix");
                if (!Files.isRegularFile(ref)) {
                    continue;
                }
                files++;
                boolean[][] rm = readMatrix(ref);
                String why = rm == null ? "矩阵格式错误" : Decoder.compareAgainstVersion(mine, rm);
                if (why == null) {
                    eq++;
                } else {
                    bad.append("mask").append(k).append(":").append(why).append(" ");
                }
            }
            if (files == 0) {
                skipping("六.与 segno 矩阵规范等价 " + base, "缺参考矩阵 " + base + "-mask*.matrix");
                continue;
            }
            boolean same = eq == files;
            if (same) {
                metaOk++;
            }
            check("六.与 segno 矩阵规范等价 " + base + "（全 " + files + " 个掩码）", same,
                    same ? ("功能图形逐位一致 + 模式/长度域/载荷位逐位一致；填充区与纠错码字按各自填充约定可不同（"
                            + mine.length + "x" + mine.length + " 矩阵）")
                            : ("不等价 " + (files - eq) + "/" + files + " 个：" + bad));
        }

        if (pngTotal == 0 && metas.isEmpty()) {
            skipping("六.外部交叉验证", "目录 " + dir + " 内没有 .png/.meta 参考文件 ⇒ 外部交叉验证未完成");
            return;
        }
        if (pngTotal > 0) {
            check("六.外部 PNG 解码合计", pngOk == pngTotal - levelLSeen && levelLSeen <= 1,
                    pngOk + "/" + (pngTotal - levelLSeen) + " 张外部图（非 M 等级负对照 " + levelLSeen + " 张）解出文本与边车一致");
        }
        if (!metas.isEmpty()) {
            check("六.与 segno 矩阵规范等价合计", metaOk == metas.size(),
                    metaOk + "/" + metas.size() + " 个用例与 segno 矩阵规范等价（功能图形 + 模式/长度域/载荷位逐位一致，8 个掩码全覆盖）");
        }
    }

    private static String levelName(int idx) {
        return new String[]{"L", "M", "Q", "H"}[idx];
    }

    /** 外部图定位：深色像素的包围盒。三个定位图形都贴着符号外框，故包围盒 = 符号区（不含静默区）。 */
    private static final class Locator {
        int x0;
        int y0;
        int w;
        int h;
    }

    private static Locator locate(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (isDark(img.getRGB(x, y))) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        // 至少得装得下 v1（21 模块）；再小就说明不是二维码图
        if (maxX < 0 || maxX - minX + 1 < 21 || maxY - minY + 1 < 21) {
            return null;
        }
        Locator loc = new Locator();
        loc.x0 = minX;
        loc.y0 = minY;
        loc.w = maxX - minX + 1;
        loc.h = maxY - minY + 1;
        return loc;
    }

    private static boolean isDark(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 < 128;
    }

    /**
     * 按候选版本采样成模块矩阵：模块边长 = 包围盒宽 / 边长（中心点取样）。
     * 用包围盒而不是「量 7 深 1 浅」的边长估计——后者对 scale=2/3 的图会估错。
     */
    private static boolean[][] sample(BufferedImage img, Locator loc, int version) {
        int size = version * 4 + 17;
        double module = loc.w / (double) size;
        if (module < 1.0 || Math.abs(loc.h / (double) size - module) > 1.0) {
            return null;
        }
        boolean[][] m = new boolean[size][size];
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                int x = (int) (loc.x0 + (c + 0.5) * module);
                int y = (int) (loc.y0 + (r + 0.5) * module);
                if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
                    return null;
                }
                m[r][c] = isDark(img.getRGB(x, y));
            }
        }
        return m;
    }

    /** 外部图的版本判定：三个定位图形 + 分隔符 + 时序 + 格式信息查表都要成立。 */
    private static boolean structurallyValid(boolean[][] m) {
        return findersOk(m) && separatorsLight(m) && timingOk(m) && readFormat(m) != null;
    }

    private static boolean[][] readMatrix(Path p) {
        try {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            List<String> rows = new ArrayList<>();
            for (String line : lines) {
                String s = line.trim();
                if (!s.isEmpty() && !s.startsWith("#")) {
                    rows.add(s);
                }
            }
            int n = rows.size();
            boolean[][] m = new boolean[n][n];
            for (int r = 0; r < n; r++) {
                String s = rows.get(r);
                if (s.length() != n) {
                    return null;
                }
                for (int c = 0; c < n; c++) {
                    char ch = s.charAt(c);
                    if (ch != '0' && ch != '1') {
                        return null;
                    }
                    m[r][c] = ch == '1';
                }
            }
            return m;
        } catch (IOException e) {
            return null;
        }
    }

    // ============================================================ 独立解码器

    /**
     * 独立最小解码器：<b>不与 QrEncoder 共享任何代码或表</b>——这里的两张表按 python-qrcode
     * （qrcode/base.py RS_BLOCK_TABLE、qrcode/util.py PATTERN_POSITION_TABLE）重抄一遍，
     * 于是「编码器按 thonky 写、解码器按 python-qrcode 读」本身构成一次跨来源交叉验证。
     */
    static final class Decoder {

        /** 每版本数据码字总数（python-qrcode RS_BLOCK_TABLE，M 等级块求和）。 */
        private static final int[] DATA_CW = {
                16, 28, 44, 64, 86, 108, 124, 154, 182, 216,
                254, 290, 334, 365, 415, 453, 507, 563, 627, 669};
        private static final int[] ECC_PER_BLOCK = {
                10, 16, 26, 18, 24, 16, 18, 22, 22, 26,
                30, 22, 22, 24, 24, 28, 28, 26, 26, 26};
        private static final int[] G1_BLOCKS = {
                1, 1, 1, 2, 2, 4, 4, 2, 3, 4,
                1, 6, 8, 4, 5, 7, 10, 9, 3, 3};
        private static final int[] G1_DATA = {
                16, 28, 44, 32, 43, 27, 31, 38, 36, 43,
                50, 36, 37, 40, 41, 45, 46, 43, 44, 41};
        private static final int[] G2_BLOCKS = {
                0, 0, 0, 0, 0, 0, 0, 2, 2, 1,
                4, 2, 1, 5, 5, 3, 1, 4, 11, 13};
        private static final int[] G2_DATA = {
                0, 0, 0, 0, 0, 0, 0, 39, 37, 44,
                51, 37, 38, 41, 42, 46, 47, 44, 45, 42};

        static int byteCapacity(int version) {
            return (DATA_CW[version - 1] * 8 - 4 - (version <= 9 ? 8 : 16)) / 8;
        }

        static int totalCodewords(int version) {
            return DATA_CW[version - 1] + ECC_PER_BLOCK[version - 1] * (G1_BLOCKS[version - 1] + G2_BLOCKS[version - 1]);
        }

        /** 功能图形/保留区布局（独立写一遍：定位+分隔符、时序、对齐、版本信息、格式臂、固定深模块）。 */
        static boolean[][] functionMap(int version) {
            int size = version * 4 + 17;
            boolean[][] fn = new boolean[size][size];
            for (int i = 0; i < size; i++) {
                fn[6][i] = true;
                fn[i][6] = true;
            }
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) {
                    fn[r][c] = true;
                    fn[r][size - 8 + c] = true;
                    fn[size - 8 + r][c] = true;
                }
            }
            int[] pos = ALIGN[version - 1];
            for (int i = 0; i < pos.length; i++) {
                for (int j = 0; j < pos.length; j++) {
                    boolean corner = (i == 0 && j == 0) || (i == 0 && j == pos.length - 1)
                            || (i == pos.length - 1 && j == 0);
                    if (corner) {
                        continue;
                    }
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dx = -2; dx <= 2; dx++) {
                            fn[pos[i] + dy][pos[j] + dx] = true;
                        }
                    }
                }
            }
            if (version >= 7) {
                for (int r = 0; r < 6; r++) {
                    for (int c = size - 11; c <= size - 9; c++) {
                        fn[r][c] = true;
                        fn[c][r] = true;
                    }
                }
            }
            for (int i = 0; i <= 5; i++) {
                fn[i][8] = true;
            }
            for (int i = 0; i < 8; i++) {
                // 第二份副本的横臂是 8 格（nayuki setFunctionModule(size-1-i, 8)）；只标 6 格会让自由格多 2 个
                fn[8][size - 1 - i] = true;
            }
            fn[7][8] = true;
            fn[8][8] = true;
            fn[8][7] = true;
            for (int i = 9; i < 15; i++) {
                fn[8][14 - i] = true;
            }
            for (int i = 8; i < 15; i++) {
                fn[size - 15 + i][8] = true;
            }
            fn[size - 8][8] = true;
            return fn;
        }

        /** 从矩阵读格式信息里的掩码号（供探针比对「解码器读到的掩码」）。 */
        static int maskOf(boolean[][] m) {
            int[] f = readFormat(m);
            return f == null ? -1 : f[1];
        }

        static final class Decoded {
            int level;
            int mask;
            boolean rsOk;
            int mode;
            int declaredLength;
            String text;
        }

        static Decoded decode(boolean[][] m) throws CharacterCodingException {
            int size = m.length;
            int version = (size - 17) / 4;
            if (size != version * 4 + 17 || version < 1 || version > MAX_VERSION) {
                throw new IllegalStateException("非法边长：" + size);
            }
            int[] fmt = readFormat(m);
            if (fmt == null) {
                throw new IllegalStateException("格式信息不合法（两处不一致或不在权威表内）");
            }
            boolean[][] fn = functionMap(version);
            byte[] cw = readCodewords(m, fn, fmt[1]);
            byte[][] dataBlocks = new byte[G1_BLOCKS[version - 1] + G2_BLOCKS[version - 1]][];
            boolean rsOk = deinterleaveAndCheck(cw, version, dataBlocks);

            int total = 0;
            for (byte[] b : dataBlocks) {
                total += b.length;
            }
            byte[] data = new byte[total];
            int p = 0;
            for (byte[] b : dataBlocks) {
                System.arraycopy(b, 0, data, p, b.length);
                p += b.length;
            }

            Decoded d = new Decoded();
            d.level = fmt[0];
            d.mask = fmt[1];
            d.rsOk = rsOk;
            d.mode = readBits(data, 0, 4);
            int lenBits = version <= 9 ? 8 : 16;
            d.declaredLength = readBits(data, 4, lenBits);
            int startBit = 4 + lenBits;
            if (d.mode != 0b0100) {
                throw new IllegalStateException("模式指示不是 byte(0100)：" + d.mode);
            }
            if (d.declaredLength < 0 || startBit + d.declaredLength * 8 > data.length * 8) {
                throw new IllegalStateException("长度域越界：" + d.declaredLength);
            }
            byte[] payload = new byte[d.declaredLength];
            for (int i = 0; i < d.declaredLength; i++) {
                payload[i] = (byte) readBits(data, startBit + i * 8, 8);
            }
            CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            d.text = dec.decode(ByteBuffer.wrap(payload)).toString();
            return d;
        }

        /**
         * 与第三方编码器的矩阵做「规范等价」比对（掩码无关：各自按格式信息里的掩码去掩）。
         * 判据：① 功能图形逐位一致；② 反交织后「模式指示 + 长度域 + 载荷位」逐位一致。
         * 填充区与纠错码字允许不同——segno 会多写 8 位终止符后直接进 EC/11 填充，
         * 规范/nayuki/python-qrcode 则是 0–4 位终止符 → 补零到字节边界 → EC/11 交替。
         * 返回 null 表示等价，否则返回第一处不一致的说明。
         */
        static String compareAgainstVersion(boolean[][] mine, boolean[][] ref) {
            if (mine.length != ref.length) {
                return "边长 " + mine.length + " vs " + ref.length;
            }
            int size = mine.length;
            int version = (size - 17) / 4;
            boolean[][] fn = functionMap(version);
            int mB = maskOf(ref);
            int mA = maskOf(mine);
            for (int r = 0; r < size; r++) {
                for (int c = 0; c < size; c++) {
                    // 格式信息格里写着「等级+掩码」：两边掩码不同则该处必然不同，而掩码是编码器的自由选择项，
                    // 故掩码不同时跳过格式格（格式信息的正确性由 §一 的权威表断言与「能解出等级/掩码」保证）
                    if (!fn[r][c] || (mA != mB && isFormatCell(r, c, size))) {
                        continue;
                    }
                    if (mine[r][c] != ref[r][c]) {
                        return "功能图形第(" + r + "," + c + ")格不同";
                    }
                }
            }
            try {
                byte[] a = dataBytes(mine, version);
                byte[] b = dataBytes(ref, version);
                int lenBits = version <= 9 ? 8 : 16;
                if (readBits(a, 0, 4) != 0b0100 || readBits(b, 0, 4) != 0b0100) {
                    return "模式指示不是 byte(0100)";
                }
                int la = readBits(a, 4, lenBits);
                int lb = readBits(b, 4, lenBits);
                if (la != lb) {
                    return "长度域 " + la + " vs " + lb;
                }
                int end = 4 + lenBits + la * 8;
                if (end > a.length * 8 || end > b.length * 8) {
                    return "载荷越界 " + end;
                }
                for (int i = 0; i < end; i++) {
                    if (((a[i >>> 3] >>> (7 - (i & 7))) & 1) != ((b[i >>> 3] >>> (7 - (i & 7))) & 1)) {
                        return "载荷第 " + i + " 位不同";
                    }
                }
                return null;
            } catch (RuntimeException e) {
                return "异常：" + e;
            }
        }

        /** 格式信息两份副本 + 固定深模块占的格。 */
        private static boolean isFormatCell(int r, int c, int size) {
            return (c == 8 && r <= 8) || (r == 8 && (c <= 8 || c >= size - 8)) || (c == 8 && r >= size - 8);
        }

        /** 反交织后按块顺序拼接的数据码字（不含纠错码字），供逐位比对用。 */
        private static byte[] dataBytes(boolean[][] m, int version) {
            int[] fmt = readFormat(m);
            if (fmt == null) {
                throw new IllegalStateException("格式信息不合法");
            }
            byte[] cw = readCodewords(m, functionMap(version), fmt[1]);
            byte[][] blocks = new byte[G1_BLOCKS[version - 1] + G2_BLOCKS[version - 1]][];
            deinterleaveAndCheck(cw, version, blocks);
            int total = 0;
            for (byte[] b : blocks) {
                total += b.length;
            }
            byte[] data = new byte[total];
            int p = 0;
            for (byte[] b : blocks) {
                System.arraycopy(b, 0, data, p, b.length);
                p += b.length;
            }
            return data;
        }

        private static int readBits(byte[] data, int startBit, int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                int bit = startBit + i;
                v = (v << 1) | ((data[bit >>> 3] >>> (7 - (bit & 7))) & 1);
            }
            return v;
        }

        /** 蛇形读回码字（用格式信息里的掩码去掩），返回全部码字（数据+纠错，交织顺序）。 */
        private static byte[] readCodewords(boolean[][] m, boolean[][] fn, int mask) {
            int size = m.length;
            int total = 0;
            for (boolean[] row : fn) {
                for (boolean b : row) {
                    if (!b) {
                        total++;
                    }
                }
            }
            int cwCount = total / 8;
            byte[] cw = new byte[cwCount];
            int bit = 0;
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
                        boolean v = m[row][col] ^ maskBit(mask, row, col);
                        if (bit < cwCount * 8 && v) {
                            cw[bit >>> 3] |= (byte) (1 << (7 - (bit & 7)));
                        }
                        bit++;
                    }
                }
                upward = !upward;
            }
            if (bit != total) {
                throw new IllegalStateException("数据格计数不一致：" + bit + " != " + total);
            }
            return cw;
        }

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
                default: throw new IllegalArgumentException("非法掩码：" + mask);
            }
        }

        /** 反交织并做 RS 伴随式校验（全部为零 = 纠错码字正确），把数据码字填进 dataBlocks。 */
        private static boolean deinterleaveAndCheck(byte[] cw, int version, byte[][] dataBlocks) {
            int idx = version - 1;
            int eccLen = ECC_PER_BLOCK[idx];
            int g1 = G1_BLOCKS[idx];
            int d1 = G1_DATA[idx];
            int g2 = G2_BLOCKS[idx];
            int d2 = G2_DATA[idx];
            int blocks = g1 + g2;
            byte[][] ecc = new byte[blocks][];
            for (int i = 0; i < blocks; i++) {
                dataBlocks[i] = new byte[i < g1 ? d1 : d2];
                ecc[i] = new byte[eccLen];
            }
            int p = 0;
            int maxLen = Math.max(d1, d2);
            for (int i = 0; i < maxLen; i++) {
                for (int b = 0; b < blocks; b++) {
                    if (i < dataBlocks[b].length) {
                        dataBlocks[b][i] = cw[p++];
                    }
                }
            }
            for (int i = 0; i < eccLen; i++) {
                for (int b = 0; b < blocks; b++) {
                    ecc[b][i] = cw[p++];
                }
            }
            if (p != cw.length) {
                throw new IllegalStateException("反交织长度不符：" + p + " != " + cw.length);
            }
            boolean all = true;
            for (int b = 0; b < blocks; b++) {
                byte[] full = new byte[dataBlocks[b].length + eccLen];
                System.arraycopy(dataBlocks[b], 0, full, 0, dataBlocks[b].length);
                System.arraycopy(ecc[b], 0, full, dataBlocks[b].length, eccLen);
                all &= syndromesZero(full, eccLen);
            }
            return all;
        }

        // GF(2^8)/0x11D 的指数对数表（与编码器的俄罗斯农民乘法是两种独立实现）
        private static final int[] EXP = new int[512];
        private static final int[] LOG = new int[256];

        static {
            int x = 1;
            for (int i = 0; i < 255; i++) {
                EXP[i] = x;
                LOG[x] = i;
                x <<= 1;
                if ((x & 0x100) != 0) {
                    x ^= 0x11D;
                }
            }
            for (int i = 255; i < 512; i++) {
                EXP[i] = EXP[i - 255];
            }
        }

        private static int mul(int a, int b) {
            return (a == 0 || b == 0) ? 0 : EXP[LOG[a] + LOG[b]];
        }

        private static int pow(int a, int e) {
            return EXP[(LOG[a] * e) % 255];
        }

        /** S_j = C(α^j) 是否全零。 */
        private static boolean syndromesZero(byte[] codeword, int eccLen) {
            for (int j = 0; j < eccLen; j++) {
                int a = pow(2, j);
                int s = 0;
                for (byte b : codeword) {
                    s = mul(s, a) ^ (b & 0xFF);
                }
                if (s != 0) {
                    return false;
                }
            }
            return true;
        }
    }

    private QrEncoderProbe() {
    }
}
