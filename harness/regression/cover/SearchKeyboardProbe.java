import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 搜索结果区键盘导航离线探针（REVIEW issue 7）。
 *
 * <p>被测对象是 NativeSearchPage.kt 里的 {@code internal object SearchKeyNav}：不依赖 Compose、
 * 不联网、不创建窗口，只用反射调用 move / enterAction 两个纯函数，覆盖方向、边界夹取、
 * 空列表、越界选中与 Enter 归约。另外对源码做静态接线检查，确认焦点请求、onKeyEvent、
 * 自动滚动、搜索框 Enter 隔离与鼠标单击/双击都真的接在页面上——真实 Compose 聚焦和按键
 * 无法离线自动化，那部分见交付简报里的人工验收步骤。</p>
 *
 * <p>运行前提：build/plugin-classes 已包含最新编译结果，且工作目录是仓库根（或通过
 * {@code -Dsearch.page.source=<NativeSearchPage.kt 路径>} 指定源码）。跑通后打印
 * {@code SEARCH_KEYBOARD_RESULT=<N> PASS} 并以 0 退出。</p>
 *
 * <p>人工验收（真实 Compose 页面，无窗口的 Java 探针无法执行真实聚焦与按键）：</p>
 * <ol>
 *   <li>打开原生搜索页，在搜索框输入关键词并回车：只触发搜索，不播放，焦点仍在输入框。</li>
 *   <li>鼠标单击任一结果行：该行高亮，结果区边框变粗（2.dp primary），提示变为「结果区已获得键盘焦点」。</li>
 *   <li>按 ↑/↓：选中行逐行移动，首尾夹取不回绕；选中行在可视区外时列表自动滚动到可见。</li>
 *   <li>按 Enter：播放当前选中行（宿主反馈 + 实际声音）；有结果但无选中时第一次 Enter 只选第一行。</li>
 *   <li>双击结果行仍直接播放，单击仍只选中；空结果下按 ↑/↓/Enter 不崩溃、不播放。</li>
 *   <li>结果区有焦点时输入普通字符不应改动搜索框；回到搜索框按 Enter 仍只搜索。</li>
 * </ol>
 *
 * <p>残余风险：Compose Desktop 默认 Tab 焦点遍历行为未在本仓验证，界面文案不承诺 Tab；
 * 真实焦点与按键链路由上述人工验收覆盖，本探针只验证纯函数与源码接线。</p>
 */
public final class SearchKeyboardProbe {

    private static int passed;
    private static Class<?> navClass;
    private static Object nav;
    private static Method moveMethod;
    private static Method enterActionMethod;
    private static Class<?> enterActionClass;

    private SearchKeyboardProbe() {
    }

    private static void check(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label);
        }
        passed++;
        System.out.println("PASS " + label);
    }

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        loadSearchKeyNav();
        moveFromNoSelection();
        moveWithinList();
        moveClampsAtEdges();
        moveHandlesOutOfRangeAndZeroDelta();
        moveHandlesEmptyAndNegativeCount();
        enterActionContract();
        staticWiring();
        System.out.println("SEARCH_KEYBOARD_RESULT=" + passed + " PASS");
    }

    // ------------------------------------------------------------------ 反射装载

    private static void loadSearchKeyNav() throws Exception {
        navClass = Class.forName("com.example.netease.nativeui.SearchKeyNav");
        check(Modifier.isFinal(navClass.getModifiers()), "SearchKeyNav 是 Kotlin object（final class）");

        Field instance = navClass.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        nav = instance.get(null);
        check(nav != null, "SearchKeyNav.INSTANCE 可访问");

        moveMethod = findMethod(navClass, "move", int.class, int.class, int.class);
        enterActionMethod = findMethod(navClass, "enterAction", int.class, int.class);
        enterActionClass = Class.forName("com.example.netease.nativeui.SearchKeyNav$EnterAction");
        System.out.println("INFO move=" + moveMethod.getName() + " enterAction=" + enterActionMethod.getName());

        Field noSelection = navClass.getDeclaredField("NO_SELECTION");
        noSelection.setAccessible(true);
        check(noSelection.getInt(null) == -1, "NO_SELECTION 是 -1，与 SearchController 的 selectedRow 空值一致");

        Field currentField = navClass.getDeclaredField("NO_SELECTION");
        check(Modifier.isStatic(currentField.getModifiers()), "NO_SELECTION 是静态常量");
    }

    /**
     * Kotlin internal 成员在某些编译形态下会带 {@code $模块名} 后缀；这里同时接受原名与后缀名，
     * 避免探针因名称修饰而误报「方法不存在」。
     */
    private static Method findMethod(Class<?> type, String base, Class<?>... parameterTypes) {
        for (Method method : type.getDeclaredMethods()) {
            String name = method.getName();
            boolean nameMatches = name.equals(base) || name.startsWith(base + "$");
            if (!nameMatches || !Arrays.equals(method.getParameterTypes(), parameterTypes)) {
                continue;
            }
            method.setAccessible(true);
            return method;
        }
        throw new AssertionError("找不到方法 " + base + Arrays.toString(parameterTypes));
    }

    private static int move(int current, int count, int delta) {
        try {
            return ((Integer) moveMethod.invoke(nav, current, count, delta)).intValue();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("调用 move 失败", e);
        }
    }

    private static String enter(int rowCount, int selectedRow) {
        try {
            Object result = enterActionMethod.invoke(nav, rowCount, selectedRow);
            if (!enterActionClass.isInstance(result)) {
                throw new AssertionError("enterAction 返回类型不是 EnterAction：" + result);
            }
            return ((Enum<?>) result).name();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("调用 enterAction 失败", e);
        }
    }

    // ------------------------------------------------------------------ move

    private static void moveFromNoSelection() {
        check(move(-1, 5, 1) == 0, "无选中（-1）按 ↓ 选第一行");
        check(move(-1, 5, -1) == 4, "无选中（-1）按 ↑ 选最后一行");
        check(move(-9, 5, 1) == 0, "无选中且为任意负数时 ↓ 仍选第一行");
        check(move(-9, 5, -1) == 4, "无选中且为任意负数时 ↑ 仍选最后一行");
    }

    private static void moveWithinList() {
        check(move(0, 5, 1) == 1, "中间行 ↓ 前进一行");
        check(move(4, 5, -1) == 3, "中间行 ↑ 后退一行");
        check(move(2, 5, 1) == 3, "第 3 行 ↓ 到第 4 行");
        check(move(2, 5, -1) == 1, "第 3 行 ↑ 到第 2 行");
        check(move(1, 2, 1) == 1, "两行列表从第 2 行 ↓ 停在末行");
        check(move(1, 2, -1) == 0, "两行列表从第 2 行 ↑ 到第 1 行");
    }

    private static void moveClampsAtEdges() {
        check(move(0, 5, -1) == 0, "首行继续 ↑ 夹取在首行，不回绕到末行");
        check(move(4, 5, 1) == 4, "末行继续 ↓ 夹取在末行，不回绕到首行");
        check(move(0, 1, 1) == 0, "单行列表 ↓ 停在唯一一行");
        check(move(0, 1, -1) == 0, "单行列表 ↑ 停在唯一一行");
    }

    private static void moveHandlesOutOfRangeAndZeroDelta() {
        check(move(2, 5, 0) == 2, "delta=0 保持当前合法行");
        check(move(-1, 5, 0) == 0, "delta=0 且无选中时夹取到第一行");
        check(move(99, 5, 0) == 4, "delta=0 且行号越界时夹取到末行");
        check(move(5, 5, 1) == 4, "当前行号等于 count 时 ↓ 停在末行");
        check(move(5, 5, -1) == 3, "当前行号等于 count 时 ↑ 从末行退一行");
        check(move(99, 5, 1) == 4, "当前行号远大于 count 时 ↓ 停在末行");
        check(move(99, 5, -1) == 3, "当前行号远大于 count 时 ↑ 从末行退一行");
    }

    private static void moveHandlesEmptyAndNegativeCount() {
        check(move(0, 0, 1) == -1, "空列表 ↓ 返回 NO_SELECTION");
        check(move(0, 0, -1) == -1, "空列表 ↑ 返回 NO_SELECTION");
        check(move(-1, 0, 1) == -1, "空列表且本来无选中时仍返回 NO_SELECTION");
        check(move(3, 0, -1) == -1, "空列表且传入旧行号时返回 NO_SELECTION");
        check(move(0, -2, 1) == -1, "负数行数按空列表处理，不抛异常");
    }

    // ------------------------------------------------------------------ enterAction

    private static void enterActionContract() {
        Set<String> names = new LinkedHashSet<>();
        for (Object constant : enterActionClass.getEnumConstants()) {
            names.add(((Enum<?>) constant).name());
        }
        check(names.equals(Set.of("PLAY_SELECTED", "SELECT_FIRST", "NO_ROWS")),
                "EnterAction 恰好三种：PLAY_SELECTED / SELECT_FIRST / NO_ROWS");

        check("PLAY_SELECTED".equals(enter(5, 0)), "有结果且选中第 1 行时 Enter=播放");
        check("PLAY_SELECTED".equals(enter(5, 4)), "有结果且选中末行时 Enter=播放");
        check("PLAY_SELECTED".equals(enter(1, 0)), "只有一行且已选中时 Enter=播放");
        check("SELECT_FIRST".equals(enter(5, -1)), "有结果但无选中时 Enter=先选第一行");
        check("SELECT_FIRST".equals(enter(5, 5)), "选中行等于行数（越界）时 Enter=先选第一行");
        check("SELECT_FIRST".equals(enter(5, 99)), "选中行远大于行数时 Enter=先选第一行");
        check("NO_ROWS".equals(enter(0, 0)), "空列表 Enter=无动作");
        check("NO_ROWS".equals(enter(0, -1)), "空列表且无选中时 Enter=无动作");
        check("NO_ROWS".equals(enter(-3, 2)), "负数行数按空列表处理，Enter=无动作");
    }

    // ------------------------------------------------------------------ 静态接线

    private static void staticWiring() throws Exception {
        Path source = locatePageSource();
        String text = Files.readString(source, StandardCharsets.UTF_8);
        System.out.println("INFO 源码接线检查：" + source);

        // 结果区焦点与按键：先定位 onKeyEvent 代码块，避免把页面上其他同名调用算进来。
        requireText(text, ".focusRequester(focusRequester)", "结果区挂 focusRequester");
        requireText(text, "SearchResultList(controller, state, resultFocusRequester", "页面把同一个 focusRequester 交给结果区");
        requireText(text, ".onFocusChanged { focused = it.isFocused }", "结果区把焦点状态写回 focused");
        requireText(text, ".focusable()", "结果区可聚焦（focusable）");
        requireText(text, ".onKeyEvent { event ->", "结果区安装 onKeyEvent");
        requireText(text, "width = if (focused) 2.dp else 1.dp", "结果区有可见焦点边框（2.dp 高亮）");

        int keyStart = text.indexOf(".onKeyEvent { event ->");
        int keyEnd = text.indexOf(".focusable()", keyStart);
        check(keyStart >= 0 && keyEnd > keyStart, "能定位结果区 onKeyEvent 代码块");
        String keyHandler = text.substring(keyStart, keyEnd);
        check(keyHandler.contains("Key.DirectionUp"), "结果区 onKeyEvent 处理 ↑");
        check(keyHandler.contains("Key.DirectionDown"), "结果区 onKeyEvent 处理 ↓");
        check(keyHandler.contains("Key.Enter") && keyHandler.contains("Key.NumPadEnter"),
                "结果区 onKeyEvent 处理 Enter 与小键盘 Enter");
        check(keyHandler.contains("SearchKeyNav.move(current, rows.size, -1)"), "↑ 走 SearchKeyNav.move");
        check(keyHandler.contains("SearchKeyNav.move(current, rows.size, 1)"), "↓ 走 SearchKeyNav.move");
        check(keyHandler.contains("SearchKeyNav.enterAction(rows.size, current)"), "Enter 走 SearchKeyNav.enterAction");
        check(keyHandler.contains("controller.selectRow(target)"), "方向键移动按行号走 controller.selectRow");
        check(keyHandler.contains("EnterAction.PLAY_SELECTED") && keyHandler.contains("controller.playSelected()"),
                "Enter 的 PLAY_SELECTED 分支调用 playSelected");
        check(keyHandler.contains("EnterAction.SELECT_FIRST"), "Enter 的无效选中分支先选第一行");

        requireText(text, "listState.animateScrollToItem(target)", "选中项不可见时 animateScrollToItem");
        check(countOccurrences(text, "keyboardScrollRequest++") >= 2,
                "方向键与 Enter 都会递增滚动请求编号（边界重复按键也能滚到可见）");

        // 搜索框 Enter 只搜索，不能被结果区键位或播放劫持
        int previewStart = text.indexOf("onPreviewKeyEvent");
        int placeholder = text.indexOf("placeholder =", previewStart);
        check(previewStart >= 0 && placeholder > previewStart, "能定位搜索框 onPreviewKeyEvent 代码块");
        String searchBox = text.substring(previewStart, placeholder);
        check(searchBox.contains("controller.search(NativeUiState.searchKeyword)"), "搜索框 Enter 仍然只提交搜索");
        check(!searchBox.contains("playSelected"), "搜索框 Enter 不会触发播放");
        check(!searchBox.contains("SearchKeyNav"), "搜索框按键不会被结果区键位归约劫持");

        // 鼠标操作保持
        requireText(text, "onPress = { onFocusRequest() }", "结果行按下即请求键盘焦点");
        requireText(text, "onTap = {", "结果行保留单击");
        requireText(text, "onDoubleTap = {", "结果行保留双击");
        requireText(text, "controller.select(song.id())", "单击选中按歌曲 ID 走 controller.select");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static void requireText(String text, String needle, String label) {
        if (!text.contains(needle)) {
            throw new AssertionError(label + "（缺少：" + needle + "）");
        }
        passed++;
        System.out.println("PASS " + label);
    }

    /**
     * 以工作目录为起点向上找 NativeSearchPage.kt；也允许用 -Dsearch.page.source 显式指定。
     * 之所以查源码而不是只测纯函数：focusRequester / onKeyEvent / 自动滚动这些 Compose 接线
     * 无法在无窗口的 Java 探针里执行，至少要证明它们没有被改回「只有鼠标」的旧实现。
     */
    private static Path locatePageSource() {
        String relative = "src/main/kotlin/com/example/netease/nativeui/NativeSearchPage.kt";
        List<Path> candidates = new ArrayList<>();
        String override = System.getProperty("search.page.source");
        if (override != null && !override.isBlank()) {
            candidates.add(Path.of(override));
        }
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            candidates.add(dir.resolve(relative));
            dir = dir.getParent();
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("找不到 " + relative + "；请在仓库根运行，或用 -Dsearch.page.source=<路径> 指定");
    }
}
