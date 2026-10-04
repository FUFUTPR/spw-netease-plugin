package com.example.netease.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自写的极简 JSON 解析/序列化器（零第三方依赖）。
 *
 * <p>设计取舍：宿主运行时没有 java.net.http、没有 Nashorn，也不允许我们把第三方 jar 硬塞进
 * 插件包（体积 + 许可 + 类加载面）。JSON 只用于网易云接口响应与本地小配置，
 * 所以自己实现一个 ~200 行的递归下降解析器最省事、最可控。</p>
 *
 * <p>类型映射：object→LinkedHashMap、array→ArrayList、string→String、
 * number→Long（无小数）或 Double、true/false→Boolean、null→null。</p>
 *
 * <p>线程安全：全部是静态纯函数，可并发调用。</p>
 */
public final class Json {

    /** JSON 语法错误。运行时异常，调用方自己决定降级。 */
    public static class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }

    private Json() {
    }

    // ------------------------------------------------------------------ 解析

    /** 解析任意 JSON 文本。 */
    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException("json 文本为 null");
        }
        Parser p = new Parser(text);
        p.skipWs();
        Object value = p.readValue();
        p.skipWs();
        if (!p.eof()) {
            throw new JsonException("JSON 尾部有多余字符，位置 " + p.pos);
        }
        return value;
    }

    /** 解析并断言顶层是对象；不是对象则抛 JsonException。 */
    public static Map<String, Object> object(String text) {
        Object v = parse(text);
        if (v instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) m;
            return cast;
        }
        throw new JsonException("期望 JSON 对象，实际是 " + typeName(v));
    }

    // ------------------------------------------------------------------ 序列化

    /** 任意受支持的结构 → JSON 文本（紧凑格式，不美化）。 */
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder(256);
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                sb.append("null");
            } else if (d == Math.floor(d) && Math.abs(d) < 1e15) {
                sb.append(d.longValue());
            } else {
                sb.append(d);
            }
        } else if (value instanceof Number n) {
            sb.append(n);
        } else if (value instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else if (value instanceof Object[] arr) {
            write(sb, java.util.Arrays.asList(arr));
        } else if (value instanceof Enum<?> e) {
            writeString(sb, e.name());
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ 取值

    /**
     * 按点号路径取值：{@code get(node, "result.songs.0.name")}。
     * Map 用键、List 用十进制下标；任一层缺失/类型不符 → 返回 null。
     */
    public static Object get(Object node, String path) {
        if (node == null || path == null || path.isEmpty()) {
            return node;
        }
        Object cur = node;
        for (String seg : path.split("\\.")) {
            if (cur == null) {
                return null;
            }
            if (cur instanceof Map<?, ?> m) {
                cur = m.get(seg);
            } else if (cur instanceof List<?> l) {
                int idx = parseIntSafe(seg, -1);
                cur = (idx >= 0 && idx < l.size()) ? l.get(idx) : null;
            } else {
                return null;
            }
        }
        return cur;
    }

    public static String str(Object node, String path, String def) {
        Object v = get(node, path);
        if (v == null) {
            return def;
        }
        if (v instanceof String s) {
            return s;
        }
        return String.valueOf(v);
    }

    public static long lng(Object node, String path, long def) {
        Object v = get(node, path);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static int integer(Object node, String path, int def) {
        long v = lng(node, path, def);
        return (v > Integer.MAX_VALUE || v < Integer.MIN_VALUE) ? def : (int) v;
    }

    public static double dbl(Object node, String path, double def) {
        Object v = get(node, path);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static boolean bool(Object node, String path, boolean def) {
        Object v = get(node, path);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        return def;
    }

    /** 取子对象；不是对象返回空 Map（调用方无需判空）。 */
    public static Map<String, Object> map(Object node, String path) {
        Object v = get(node, path);
        if (v instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) m;
            return cast;
        }
        return new LinkedHashMap<>();
    }

    /** 取子数组；不是数组返回空 List。 */
    public static List<Object> list(Object node, String path) {
        Object v = get(node, path);
        if (v instanceof List<?> l) {
            @SuppressWarnings("unchecked")
            List<Object> cast = (List<Object>) l;
            return cast;
        }
        return new ArrayList<>();
    }

    // ------------------------------------------------------------------ 文件读写

    /**
     * 读 JSON 对象文件。文件不存在 / 读失败 / 内容损坏 → 返回空 Map，**绝不抛**。
     * （A2 验收：用户塞坏 JSON 也不能影响宿主。）
     */
    public static Map<String, Object> readObject(Path file) {
        if (file == null) {
            return new LinkedHashMap<>();
        }
        try {
            if (!Files.isRegularFile(file)) {
                return new LinkedHashMap<>();
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return new LinkedHashMap<>();
            }
            return object(text);
        } catch (Throwable t) {
            PluginLog.w("json", "读取失败（已按空配置降级）：" + file.getFileName() + " :: " + t);
            return new LinkedHashMap<>();
        }
    }

    /**
     * 原子写 JSON 对象文件：先写同目录临时文件，再 move 覆盖。
     * 失败只记日志不抛（写缓存失败不该影响播放）。
     */
    public static void writeObject(Path file, Object value) {
        if (file == null) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, stringify(value), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            PluginLog.w("json", "写入失败：" + file.getFileName() + " :: " + t);
        }
    }

    private static String typeName(Object v) {
        return v == null ? "null" : v.getClass().getSimpleName();
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ------------------------------------------------------------------ 解析器

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        boolean eof() {
            return pos >= src.length();
        }

        void skipWs() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            skipWs();
            if (eof()) {
                throw new JsonException("JSON 意外结束");
            }
            char c = src.charAt(pos);
            switch (c) {
                case '{':
                    return readObjectValue();
                case '[':
                    return readArrayValue();
                case '"':
                    return readStringValue();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return readNumberValue();
            }
        }

        private Map<String, Object> readObjectValue() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect("{");
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw new JsonException("对象键必须是字符串，位置 " + pos);
                }
                String key = readStringValue();
                skipWs();
                expect(":");
                map.put(key, readValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return map;
                } else {
                    throw new JsonException("对象里期望 , 或 }，位置 " + pos);
                }
            }
        }

        private List<Object> readArrayValue() {
            List<Object> list = new ArrayList<>();
            expect("[");
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw new JsonException("数组里期望 , 或 ]，位置 " + pos);
                }
            }
        }

        private String readStringValue() {
            expect("\"");
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) {
                    throw new JsonException("字符串未闭合");
                }
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (eof()) {
                    throw new JsonException("转义未完成");
                }
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > src.length()) {
                            throw new JsonException("\\u 转义不完整");
                        }
                        String hex4 = src.substring(pos, pos + 4);
                        pos += 4;
                        try {
                            sb.append((char) Integer.parseInt(hex4, 16));
                        } catch (NumberFormatException e) {
                            throw new JsonException("非法 \\u 转义：" + hex4);
                        }
                    }
                    default -> throw new JsonException("未知转义 \\" + esc);
                }
            }
        }

        private Object readNumberValue() {
            int start = pos;
            if (peek() == '-' || peek() == '+') {
                pos++;
            }
            boolean fraction = false;
            while (!eof()) {
                char c = src.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    fraction = fraction || c == '.' || c == 'e' || c == 'E';
                    pos++;
                } else {
                    break;
                }
            }
            String num = src.substring(start, pos);
            if (num.isEmpty()) {
                throw new JsonException("非法字面量，位置 " + start);
            }
            try {
                if (!fraction) {
                    return Long.parseLong(num);
                }
            } catch (NumberFormatException ignored) {
                // 溢出 → 退回 Double
            }
            try {
                return Double.parseDouble(num);
            } catch (NumberFormatException e) {
                throw new JsonException("非法数字：" + num);
            }
        }

        private char peek() {
            if (eof()) {
                throw new JsonException("JSON 意外结束");
            }
            return src.charAt(pos);
        }

        private void expect(String token) {
            if (!src.startsWith(token, pos)) {
                throw new JsonException("期望 " + token + "，位置 " + pos);
            }
            pos += token.length();
        }
    }

    /** 便捷入口：把不可变的结构包成 type 检查友好的 Map。 */
    public static Map<String, Object> newObject() {
        return new HashMap<>();
    }
}
