package com.example.netease.host;

import java.io.DataInputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/** Resolve the toast actually referenced by RepoService, including obfuscated Windows/Linux builds. */
public final class HostLibraryNoticeAbi {
    private static final ClassLoader LOADER = HostLibraryNoticeAbi.class.getClassLoader();
    private static final ConcurrentHashMap<String, Boolean> MANUAL_CALLERS = new ConcurrentHashMap<>();
    private static volatile Object scanFlow;
    private static volatile Method scanValue;
    private HostLibraryNoticeAbi() { }

    public static Method refreshMethod() throws ReflectiveOperationException {
        Host119Profile.Profile profile = Host119Profile.resolve();
        if (profile == null) throw new IllegalStateException("No validated refresh controller ABI");
        Method found = null;
        for (Method method : profile.refreshController().getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0
                    && method.getReturnType() == void.class && !method.isSynthetic()) {
                if (found != null) throw new IllegalStateException("Ambiguous refresh command ABI");
                found = method;
            }
        }
        if (found == null) throw new IllegalStateException("Refresh command ABI missing");
        return found;
    }

    public static boolean scanning() throws ReflectiveOperationException {
        Object current = scanFlow;
        if (current != null) return Boolean.TRUE.equals(scanValue.invoke(current));
        var profile = Host119Profile.resolve();
        if (profile == null) throw new IllegalStateException("Refresh controller unavailable");
        var field = profile.refreshController().getDeclaredField("\u052c");
        field.setAccessible(true);
        Object flow = field.get(null);
        Method getter = Class.forName("kotlinx.coroutines.flow.StateFlow", false, LOADER).getMethod("getValue");
        scanValue = getter;
        scanFlow = flow;
        return Boolean.TRUE.equals(getter.invoke(flow));
    }

    public static void clear() { scanFlow = null; MANUAL_CALLERS.clear(); }

    public static boolean manualCaller() {
        return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame -> {
            String name = frame.getClassName();
            if (name.equals("com.xuncorp.voxzen.service.hotkey.HotkeyActionHandlerDesktopKt")) return true;
            if (!name.startsWith("androidx.compose.ui.")) return false;
            return MANUAL_CALLERS.computeIfAbsent(name, owner -> {
                try (var input = LOADER.getResourceAsStream(owner.replace('.', '/') + ".class")) {
                    if (input == null) return false;
                    String constants = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
                    return constants.contains("MusicLibraryScreen.kt") || constants.contains("QuickCaptionButton.kt");
                } catch (Exception error) { return false; }
            });
        }));
    }

    public static boolean libraryToastCaller() {
        return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                frame.getClassName().equals("com.xuncorp.voxzen.service.RepoService")
                        && frame.getMethodName().startsWith("updatePlaybackAndOtherRepos")));
    }

    public static Method toastMethod() throws Exception {
        try (var resource = LOADER.getResourceAsStream("com/xuncorp/voxzen/service/RepoService.class")) {
            if (resource == null) throw new IllegalStateException("RepoService class bytes missing");
            var input = new DataInputStream(resource);
            if (input.readInt() != 0xCAFEBABE) throw new IllegalStateException("Invalid host class");
            input.readUnsignedShort(); input.readUnsignedShort();
            int count = input.readUnsignedShort();
            Object[] pool = new Object[count];
            int[] tags = new int[count];
            for (int i = 1; i < count; i++) {
                int tag = tags[i] = input.readUnsignedByte();
                pool[i] = switch (tag) {
                    case 1 -> input.readUTF();
                    case 7, 8, 16, 19, 20 -> input.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> new int[]{input.readUnsignedShort(), input.readUnsignedShort()};
                    case 3, 4 -> input.readInt();
                    case 5, 6 -> { i++; yield input.readLong(); }
                    case 15 -> { input.readUnsignedByte(); yield input.readUnsignedShort(); }
                    default -> throw new IllegalStateException("Unsupported constant pool tag " + tag);
                };
            }
            Method found = null;
            for (int i = 1; i < count; i++) {
                if (tags[i] != 10) continue;
                int[] ref = (int[]) pool[i], type = (int[]) pool[ref[1]];
                if (!"(Ljava/lang/String;)V".equals(pool[type[1]])) continue;
                String owner = ((String) pool[(Integer) pool[ref[0]]]).replace('/', '.');
                Class<?> candidate = Class.forName(owner, false, LOADER);
                boolean bus = java.util.Arrays.stream(candidate.getDeclaredFields()).anyMatch(field ->
                        Modifier.isStatic(field.getModifiers())
                                && field.getType().getName().equals("kotlinx.coroutines.flow.MutableSharedFlow"));
                if (!bus) continue;
                Method method = candidate.getDeclaredMethod((String) pool[type[0]], String.class);
                if (!Modifier.isStatic(method.getModifiers())) continue;
                if (found != null && !found.equals(method)) throw new IllegalStateException("Ambiguous library toast ABI");
                found = method;
            }
            if (found == null) throw new IllegalStateException("Library toast ABI missing");
            return found;
        }
    }
}
