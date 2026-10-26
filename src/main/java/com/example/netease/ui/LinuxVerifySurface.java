package com.example.netease.ui;

import com.example.netease.core.DataPaths;
import com.example.netease.core.PrivateFiles;
import java.awt.*;
import java.nio.file.*;

/** The only native surface is the verification rectangle inside the existing login dialog. */
public final class LinuxVerifySurface {
    private static volatile Canvas surface;
    private static volatile boolean loaded;
    private LinuxVerifySurface() { }
    public static boolean supported() {
        if (!DataPaths.nativeLinux() || GraphicsEnvironment.isHeadless()) return false;
        String architecture = System.getProperty("os.arch", "");
        if (!architecture.equals("amd64") && !architecture.equals("x86_64")) return false;
        return Toolkit.getDefaultToolkit().getClass().getName().contains("XToolkit");
    }
    public static Canvas create() {
        Canvas canvas = new Canvas() {
            @Override public void paint(Graphics graphics) { }
            @Override public void update(Graphics graphics) { }
        };
        canvas.setIgnoreRepaint(true);
        canvas.setBackground(new Color(38,38,38));
        canvas.setFocusable(true);
        surface = canvas;
        return canvas;
    }
    public static void release() { surface = null; }
    public static long handle() throws Exception {
        if (!supported()) return 0;
        long[] value = {0};
        Runnable read = () -> {
            Canvas canvas = surface;
            if (canvas == null || !canvas.isDisplayable()) return;
            try {
                synchronized (LinuxVerifySurface.class) {
                    if (!loaded) {
                        System.load(VerifyHostPaths.linuxFile("libverify-surface.so").toString());
                        loaded = true;
                    }
                }
                value[0] = windowId(canvas, Path.of(System.getProperty("java.home"), "lib", "libjawt.so").toString());
            } catch (Exception error) { throw new IllegalStateException("Linux 验证承载组件不可用", error); }
        };
        if (EventQueue.isDispatchThread()) read.run(); else EventQueue.invokeAndWait(read);
        return value[0];
    }
    private static native long windowId(Canvas canvas, String jawtLibrary);
}
