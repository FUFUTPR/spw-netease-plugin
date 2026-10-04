package spw.harness;

import com.xuncorp.spw.workshop.api.config.ConfigHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * {@code ConfigHelper} 桩：内存 map + 临时 json 文件（harness 不引入任何 JSON 库，
 * 序列化/反序列化为此处手写的最小实现，足够的类型：String / Number / Boolean / String[]）。
 *
 * <p><b>⚠ 严格对齐宿主语义（0.10.1 踩过的坑）</b>：宿主实现 {@code androidx.compose.ui.hw#get}
 * 是**按 default 参数的运行时类型**决定怎么解释 JSON 的 —— 只有 {@code String} /
 * {@code Boolean} / {@code Integer} / {@code Long} / {@code Float} / {@code Double} 六种默认值
 * 会真的去取存储值，**其它类型（含 {@code null}）直接原样返回 default**。插件若传 {@code null}
 * 读配置，在真实宿主上永远拿默认值；桩如果宽容处理（旧版行为），这类缺陷在 harness 里永远测不出来。
 *
 * <p>{@link #set(String, Object)} 会回调所有 listener（模拟宿主配置页改值）。
 */
public final class StubConfigHelper implements ConfigHelper {

    private final Path file;
    private final Map<String, Object> values = new ConcurrentHashMap<>();
    final List<Consumer<ConfigHelper>> listeners = new CopyOnWriteArrayList<>();
    /**
     * {@link #set} 的调用计数（0.11.31 / 坑 41：自激写盘循环的判据）。
     *
     * <p>真机上「写 current_account → 宿主配置变更回调 → 账号组钩子 → 又写 current_account」曾打成
     * 死循环（每秒上千行日志、宿主 CPU 283 s）。桩里 {@code set} 是唯一会惊动监听器的入口，
     * 所以数它就能把这类回环钉住：同一个动作重复 N 次，写盘次数必须有界。</p>
     */
    private final java.util.concurrent.atomic.AtomicInteger sets = new java.util.concurrent.atomic.AtomicInteger();

    StubConfigHelper(Path dir, String name) {
        this.file = dir.resolve(name + ".json");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ get
    @SuppressWarnings("unchecked")
    @Override
    public <T> T get(String key, T defaultValue) {
        // ⚠ 宿主 androidx.compose.ui.hw#get 的真实语义：按 default 的运行时类型分派，
        // 其它类型（含 null）直接 `return default`，**不查存储值**。桩必须一样严格。
        if (!(defaultValue instanceof String || defaultValue instanceof Boolean
                || defaultValue instanceof Integer || defaultValue instanceof Long
                || defaultValue instanceof Double || defaultValue instanceof Float)) {
            return defaultValue;
        }
        Object v = values.get(key);
        if (v == null) {
            return defaultValue;
        }
        // 常见类型漂移的宽容处理：宿主真实实现也会做同样的事
        if (defaultValue instanceof Boolean) {
            return (T) (Object) ((v instanceof Boolean) ? v : Boolean.parseBoolean(String.valueOf(v)));
        }
        if (defaultValue instanceof Integer) {
            return (T) (Object) Integer.valueOf((int) asLong(v));
        }
        if (defaultValue instanceof Long) {
            return (T) (Object) Long.valueOf(asLong(v));
        }
        if (defaultValue instanceof Double) {
            return (T) (Object) Double.valueOf(asDouble(v));
        }
        if (defaultValue instanceof Float) {
            return (T) (Object) Float.valueOf((float) asDouble(v));
        }
        if (defaultValue instanceof String) {
            return (T) (Object) String.valueOf(v);
        }
        if (defaultValue instanceof String[] && v instanceof String[]) {
            return (T) (Object) ((String[]) v).clone();
        }
        return (T) v;
    }

    private static long asLong(Object v) {
        return (v instanceof Number) ? ((Number) v).longValue() : Long.parseLong(String.valueOf(v).trim());
    }

    private static double asDouble(Object v) {
        return (v instanceof Number) ? ((Number) v).doubleValue() : Double.parseDouble(String.valueOf(v).trim());
    }

    // ------------------------------------------------------------------ set
    @Override
    public void set(String key, Object value) {
        if (key == null) {
            return;
        }
        sets.incrementAndGet();
        values.put(key, value == null ? "" : value);
        for (Consumer<ConfigHelper> l : listeners) {
            try {
                l.accept(this);
            } catch (Throwable t) {
                System.out.println("  [config] listener 抛异常（已记录，不算 harness 失败）: " + t);
            }
        }
    }

    /** 已发生的 {@link #set} 次数（写盘尝试；顺带数到的宿主式监听器回调不算）。 */
    int setCount() {
        return sets.get();
    }

    /** 计数清零并返回清前值 —— 供「同一动作重复 N 次、总共写了几次」这类判据起算。 */
    int resetSetCount() {
        return sets.getAndSet(0);
    }

    // ------------------------------------------------------------ save/load
    @Override
    public boolean save() {
        try {
            StringBuilder sb = new StringBuilder("{\n");
            boolean first = true;
            for (Map.Entry<String, Object> e : new LinkedHashMap<>(values).entrySet()) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                sb.append("  \"").append(esc(e.getKey())).append("\": ").append(jsonValue(e.getValue()));
            }
            sb.append("\n}\n");
            Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Throwable t) {
            System.out.println("  [config] save() 失败（已记录）: " + t);
            return false;
        }
    }

    @Override
    public boolean reload() {
        try {
            if (!Files.isRegularFile(file)) {
                return true;
            }
            String s = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            if (s.isEmpty()) {
                return true;
            }
            values.putAll(MiniJson.parseObject(s));
            return true;
        } catch (Throwable t) {
            System.out.println("  [config] reload() 失败（已记录）: " + t);
            return false;
        }
    }

    @Override
    public Path getConfigPath() {
        return file;
    }

    // ------------------------------------------------------------------ util
    /** 调试用：把 set 过的键列出来（报告里能看到插件真的写配置了）。 */
    public Map<String, Object> snapshot() {
        return new LinkedHashMap<>(values);
    }

    private static String jsonValue(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        if (v instanceof String[]) {
            String[] a = (String[]) v;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < a.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('"').append(esc(a[i])).append('"');
            }
            return sb.append(']').toString();
        }
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(jsonValue(o));
            }
            return sb.append(']').toString();
        }
        return '"' + esc(String.valueOf(v)) + '"';
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** harness 内部用的极简 JSON 读取（object / array / string / number / bool，支持嵌套）。 */
    static final class MiniJson {
        private final String s;
        private int i;

        private MiniJson(String s) {
            this.s = s;
        }

        static Map<String, Object> parseObject(String s) {
            MiniJson p = new MiniJson(s);
            p.ws();
            Map<String, Object> m = p.object();
            return m;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            expect('{');
            ws();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                ws();
                m.put(k, value());
                ws();
                char c = peek();
                if (c == ',') {
                    i++;
                    continue;
                }
                expect('}');
                return m;
            }
        }

        private Object value() {
            char c = peek();
            if (c == '"') {
                return string();
            }
            // 嵌套对象（preference_config.json 的 configs[]/preferences[] 就是「数组里装对象」）
            if (c == '{') {
                return object();
            }
            if (c == '[') {
                i++;
                java.util.List<Object> l = new java.util.ArrayList<>();
                ws();
                if (peek() == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    ws();
                    l.add(value());
                    ws();
                    if (peek() == ',') {
                        i++;
                        continue;
                    }
                    expect(']');
                    return l;
                }
            }
            if (s.startsWith("true", i))  { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i))  { i += 4; return ""; }
            int st = i;
            while (i < s.length() && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String num = s.substring(st, i);
            try {
                return Long.valueOf(num);
            } catch (NumberFormatException e) {
                try {
                    return Double.valueOf(num);
                } catch (NumberFormatException e2) {
                    return num;
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalStateException("JSON 字符串未闭合");
        }

        private char peek() {
            if (i >= s.length()) {
                throw new IllegalStateException("JSON 意外结束");
            }
            return s.charAt(i);
        }

        private void expect(char c) {
            if (peek() != c) {
                throw new IllegalStateException("JSON 期望 '" + c + "' 但得到 '" + peek() + "' @" + i);
            }
            i++;
        }
    }
}
