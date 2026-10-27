package com.example.netease.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.jar.JarFile;

/** Define callback dependencies before PF4J can close/move this loader's backing files.
 * Class definitions only: do not initialize services, native libraries or worker threads.
 */
public final class UiCallbackLifetime {
    private static boolean primed;
    private UiCallbackLifetime() { }

    public static synchronized int prime(Class<?> anchor) throws Exception {
        if (primed) return 0;
        long started = System.nanoTime();
        ClassLoader loader = anchor.getClassLoader();
        Path location = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        var names = new ArrayList<String>();
        if (Files.isDirectory(location)) {
            Path root = location.resolve("com/example/netease");
            try (var entries = Files.walk(root)) {
                entries.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".class"))
                        .forEach(path -> names.add(location.relativize(path).toString().replace('\\', '.').replace('/', '.')
                                .replaceAll("\\.class$", "")));
            }
        } else {
            try (var jar = new JarFile(location.toFile())) {
                jar.stream().map(java.util.jar.JarEntry::getName)
                        .filter(name -> name.startsWith("com/example/netease/") && name.endsWith(".class"))
                        .forEach(name -> names.add(name.substring(0, name.length() - 6).replace('/', '.')));
            }
        }
        if (!names.contains("com.example.netease.nativeui.FocusReturn"))
            throw new IllegalStateException("UI callback dependency inventory is incomplete");
        names.sort(Comparator.naturalOrder());
        for (String name : names) Class.forName(name, false, loader);
        primed = true;
        PluginLog.i("native-ui", "回调依赖已定义（不执行静态初始化）：" + names.size() + " 类 / "
                + (System.nanoTime() - started) / 1_000_000L + " ms；允许旧 UI 在类加载器关闭后退场");
        return names.size();
    }
}
