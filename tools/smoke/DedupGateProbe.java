import com.example.netease.host.HostTrackListGate;

import java.util.ArrayList;
import java.util.List;

/**
 * 「歌曲列表门」0.11.47 两条纯判定的离线探针（不碰宿主、纯内存）：
 *
 * <ul>
 *   <li>{@link HostTrackListGate#dedupHiddenMask} —— 三要素（歌名/作者/标签专辑）完全相同的分组里
 *       只保留「最优」一行：占用空间最大 &gt; 本地行（非 netease-）&gt; 时长长 &gt; 顺序靠前；</li>
 *   <li>{@link HostTrackListGate#shouldBlockUnreadableWrite} —— 扫库「标不可读」批次里凡是
 *       {@code netease-*} 的 id 一律拦下（本地 id 恒放行）；</li>
 *   <li>{@link HostTrackListGate#isReadableMarkClass} —— 只认扫库用的不可读标记类（U+0D37）。</li>
 * </ul>
 *
 * <p>用法：</p>
 * <pre>
 * javac --release 21 -encoding UTF-8 -nowarn -d build\probe-classes -cp build\classes tools\smoke\DedupGateProbe.java
 * java "-Dfile.encoding=UTF-8" -cp "build\probe-classes;build\classes" DedupGateProbe
 * </pre>
 */
public final class DedupGateProbe {

    private static final List<String> FAILS = new ArrayList<>();
    private static final String MARK_CLASS = "androidx.compose.ui." + (char) 0x0D37;

    private DedupGateProbe() {
    }

    public static void main(String[] args) {
        System.out.println("=== 0.11.47 去重与扫库屏蔽纯判定探针 DedupGateProbe ===");

        System.out.println("[1] 无重复：五首各不相同 ⇒ 一行都不隐藏");
        boolean[] h1 = mask(
                new String[]{"A", "B", "C", "D", "E"},
                new String[]{"a1", "a2", "a3", "a4", "a5"},
                new String[]{"x", "x", "x", "x", "x"},
                new long[]{100, 100, 100, 100, 100},
                new long[]{1000, 1000, 1000, 1000, 1000},
                new boolean[]{false, false, false, false, false});
        check("1: 无重复不动", allFalse(h1));

        System.out.println("[2] 两首同名同标签同作者：占用空间小的隐藏（用户口径）");
        boolean[] h2 = mask(
                new String[]{"new chord md", "new chord md"},
                new String[]{"sp\u1d07\u1d04\u026a-\u1d00\u029f\u029f\u028f", "sp\u1d07\u1d04\u026a-\u1d00\u029f\u029f\u028f"},
                new String[]{"GAZE", "GAZE"},
                new long[]{0, 0},
                new long[]{78106, 78106},
                new boolean[]{true, true});
        check("2: 全并列时保先出现（第 2 行隐藏）", !h2[0] && h2[1]);

        System.out.println("[3] 插件 size=0 vs 本地 size>0 ⇒ 插件行隐藏（真机重复组的形状）");
        boolean[] h3 = mask(
                new String[]{"new chord md", "new chord md"},
                new String[]{"artist", "artist"},
                new String[]{"GAZE", "GAZE"},
                new long[]{0, 8_000_000},
                new long[]{78106, 78106},
                new boolean[]{true, false});
        check("3: 本地大文件保留、插件流隐藏", h3[0] && !h3[1]);

        System.out.println("[4] size 并列 ⇒ 本地优先（与顺序无关）");
        boolean[] h4a = mask(
                new String[]{"T", "T"},
                new String[]{"A", "A"},
                new String[]{"L", "L"},
                new long[]{1000, 1000},
                new long[]{5000, 5000},
                new boolean[]{true, false});
        check("4a: 插件在前也隐藏插件", h4a[0] && !h4a[1]);
        boolean[] h4b = mask(
                new String[]{"T", "T"},
                new String[]{"A", "A"},
                new String[]{"L", "L"},
                new long[]{1000, 1000},
                new long[]{5000, 5000},
                new boolean[]{false, true});
        check("4b: 插件在后隐藏插件（保本地）", !h4b[0] && h4b[1]);

        System.out.println("[5] size / 本地性并列 ⇒ 时长长者保留");
        boolean[] h5 = mask(
                new String[]{"T", "T"},
                new String[]{"A", "A"},
                new String[]{"L", "L"},
                new long[]{1000, 1000},
                new long[]{31000, 99000},
                new boolean[]{false, false});
        check("5: 时长短的隐藏", h5[0] && !h5[1]);

        System.out.println("[6] 三首同组 ⇒ 只留一行，其余全隐藏");
        boolean[] h6 = mask(
                new String[]{"T", "T", "T"},
                new String[]{"A", "A", "A"},
                new String[]{"L", "L", "L"},
                new long[]{1, 5, 9},
                new long[]{1, 1, 1},
                new boolean[]{false, false, false});
        int kept6 = 0;
        for (boolean x : h6) {
            if (!x) {
                kept6++;
            }
        }
        check("6: 三首组只保留 1 行", kept6 == 1 && h6[2] == false && h6[0] && h6[1]);

        System.out.println("[7] 三要素任一不同 ⇒ 不同组（作者不同不合并）");
        boolean[] h7 = mask(
                new String[]{"T", "T"},
                new String[]{"A1", "A2"},
                new String[]{"L", "L"},
                new long[]{1, 2},
                new long[]{1, 1},
                new boolean[]{false, false});
        check("7: 作者不同不合并", allFalse(h7));

        System.out.println("[8] 首尾空白按 trim 后比较（\"T \" 与 \"T\" 同组）");
        boolean[] h8 = mask(
                new String[]{" T ", "T", "A"},
                new String[]{"A", "A", "A"},
                new String[]{" L", "L ", "L"},
                new long[]{10, 20, 0},
                new long[]{1, 1, 1},
                new boolean[]{false, false, false});
        check("8: trim 后同组且保留大者", h8[0] && !h8[1] && !h8[2]);

        System.out.println("[9] 大文件优先于本地性（口径：先比占用空间）");
        boolean[] h9 = mask(
                new String[]{"T", "T"},
                new String[]{"A", "A"},
                new String[]{"L", "L"},
                new long[]{9_000_000, 0},
                new long[]{1, 1},
                new boolean[]{true, false});
        check("9: 大者胜出（即便它是插件行）", !h9[0] && h9[1]);

        System.out.println("[10] 扫库「不可读」批次：netease-* 恒拦、本地恒放行");
        check("10: netease-2125944148 拦下", HostTrackListGate.shouldBlockUnreadableWrite("netease-2125944148"));
        check("10: netease-pl-1 拦下（歌单关联行同前缀）", HostTrackListGate.shouldBlockUnreadableWrite("netease-pl-1"));
        check("10: netease- 裸前缀也拦", HostTrackListGate.shouldBlockUnreadableWrite("netease-"));
        check("10: 本地路径放行", !HostTrackListGate.shouldBlockUnreadableWrite("D:\\Music\\a.flac"));
        check("10: 别的 netease 词头（neteasex）放行", !HostTrackListGate.shouldBlockUnreadableWrite("neteasex-1"));
        check("10: null 放行", !HostTrackListGate.shouldBlockUnreadableWrite(null));

        System.out.println("[11] 只认扫库不可读标记类（U+0D37）");
        check("11: \u0d37 类名识别为标记类", HostTrackListGate.isReadableMarkClass(MARK_CLASS));
        check("11: Track 实体不是标记类", !HostTrackListGate.isReadableMarkClass("com.xuncorp.voxzen.data.entity.Track"));
        check("11: 相似名（U+0B37）不算", !HostTrackListGate.isReadableMarkClass("androidx.compose.ui." + (char) 0x0B37));
        check("11: null 不算", !HostTrackListGate.isReadableMarkClass(null));

        System.out.println();
        if (FAILS.isEmpty()) {
            System.out.println("全部通过 ✓（11 组断言）");
        } else {
            System.out.println("失败 " + FAILS.size() + " 项：");
            for (String f : FAILS) {
                System.out.println("  - " + f);
            }
            System.exit(1);
        }
    }

    private static boolean[] mask(String[] t, String[] a, String[] al, long[] size, long[] dur, boolean[] plugin) {
        return HostTrackListGate.dedupHiddenMask(t, a, al, size, dur, plugin);
    }

    private static boolean allFalse(boolean[] v) {
        for (boolean b : v) {
            if (b) {
                return false;
            }
        }
        return true;
    }

    private static void check(String what, boolean ok) {
        System.out.println("    " + (ok ? "[PASS] " : "[FAIL] ") + what);
        if (!ok) {
            FAILS.add(what);
        }
    }
}
