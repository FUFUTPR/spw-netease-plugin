package com.example.netease.host;

import java.lang.reflect.*;
import java.net.JarURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Resolve a source-marked host screen, then require its complete Compose ABI. */
public final class HostDailyPlaylistAbi {
    public record Target(Method method, List<String> callers) {}
    private HostDailyPlaylistAbi() {}
    public static Target resolve() throws Exception {
        ClassLoader loader = HostDailyPlaylistAbi.class.getClassLoader();
        String route = "com.xuncorp.voxzen.ui.navigation.ScreenRoute$PlaylistContent";
        var resource = loader.getResource(route.replace('.', '/') + ".class");
        if (resource == null || !(resource.openConnection() instanceof JarURLConnection connection))
            throw new IllegalStateException("无法读取宿主歌单页面");
        try (var jar = new java.util.jar.JarFile(connection.getJarFileURL().toURI().getPath())) {
            var entries = jar.stream().filter(e -> (e.getName().startsWith("androidx/compose/ui/") || e.getName().startsWith("com/xuncorp/voxzen/")) && e.getName().endsWith(".class")).toList();
            Method screen = null;
            for (var entry : entries) {
                if (entry.getName().contains("$")) continue;
                String bytes;
                try (var in = jar.getInputStream(entry)) { bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1); }
                if (!bytes.contains("PlaylistContentScreen.kt")) continue;
                var type = Class.forName(entry.getName().replace('/', '.').replace(".class", ""), false, loader);
                for (var method : type.getDeclaredMethods()) {
                    var p = method.getParameterTypes();
                    if (Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())
                            && method.getReturnType() == void.class && p.length == 5 && p[0].getName().equals(route)
                            && p[2].getName().equals("androidx.compose.runtime.Composer") && p[3] == int.class && p[4] == int.class) {
                        if (screen != null) throw new IllegalStateException("宿主歌单页面签名不唯一");
                        screen = method;
                    }
                }
            }
            if (screen == null) throw new IllegalStateException("宿主歌单页面签名不兼容");
            String owner = screen.getDeclaringClass().getName().replace('.', '/');
            String encodedOwner = new String(owner.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
            var callers = new ArrayList<String>();
            for (var entry : entries) {
                String name = entry.getName().replace(".class", "");
                if (name.equals(owner) || name.startsWith(owner + "$")) continue;
                try (var in = jar.getInputStream(entry)) {
                    byte[] bytes = in.readAllBytes();
                    if (new String(bytes, StandardCharsets.ISO_8859_1).contains(encodedOwner)
                            && references(bytes, screen)) callers.add(name.replace('/', '.'));
                }
            }
            if (callers.isEmpty()) throw new IllegalStateException("找不到宿主歌单页面调用点");
            return new Target(screen, List.copyOf(callers));
        }
    }

    static boolean references(byte[] bytes, Method method) throws Exception {
        var in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
        in.readInt(); in.readUnsignedShort(); in.readUnsignedShort();
        int count = in.readUnsignedShort();
        Object[] pool = new Object[count]; int[] tags = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = tags[i] = in.readUnsignedByte();
            switch (tag) {
                case 1 -> pool[i] = in.readUTF();
                case 7, 8, 16, 19, 20 -> pool[i] = in.readUnsignedShort();
                case 9, 10, 11, 12, 17, 18 -> pool[i] = new int[]{in.readUnsignedShort(), in.readUnsignedShort()};
                case 3, 4 -> in.skipNBytes(4);
                case 5, 6 -> { in.skipNBytes(8); i++; }
                case 15 -> in.skipNBytes(3);
                default -> throw new IllegalStateException("不支持的宿主 class 常量");
            }
        }
        String owner = method.getDeclaringClass().getName().replace('.', '/');
        String descriptor = java.lang.invoke.MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString();
        for (int i = 1; i < count; i++) if (tags[i] == 10) {
            int[] ref = (int[])pool[i];
            int[] name = (int[])pool[ref[1]];
            if (owner.equals(pool[(Integer)pool[ref[0]]]) && method.getName().equals(pool[name[0]])
                    && descriptor.equals(pool[name[1]])) return true;
        }
        return false;
    }
}
