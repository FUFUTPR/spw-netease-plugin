package com.example.netease.core;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Opens a local directory through the user's desktop, without a shell or UI-thread waits. */
public final class DirectoryOpener {
    private DirectoryOpener() { }

    @FunctionalInterface interface Command { void open(List<String> arguments) throws IOException, InterruptedException; }
    @FunctionalInterface interface DesktopOpen { void open(Path directory) throws IOException; }

    public static String open(Path directory) throws IOException, InterruptedException {
        return open(directory, System.getProperty("os.name", ""), DirectoryOpener::command, path -> {
            if (!Desktop.isDesktopSupported()) throw new IOException("桌面接口不可用");
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.OPEN)) throw new IOException("桌面接口不支持打开目录");
            desktop.open(path.toFile());
        });
    }

    static String open(Path directory, String os, Command command, DesktopOpen desktop)
            throws IOException, InterruptedException {
        Path path = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) throw new IOException("数据目录不存在或不是文件夹：" + path);
        if (os.toLowerCase(Locale.ROOT).contains("linux")) {
            IOException failure = new IOException("系统文件管理器无法打开目录");
            // A single encoded URI argument preserves spaces, Chinese and shell metacharacters.
            String uri = path.toUri().toASCIIString();
            for (List<String> arguments : List.of(List.of("xdg-open", uri), List.of("gio", "open", uri))) {
                try { command.open(arguments); return arguments.getFirst(); }
                catch (IOException unavailable) { failure.addSuppressed(unavailable); }
            }
            throw failure;
        }
        desktop.open(path);
        return "Desktop.OPEN";
    }

    private static void command(List<String> arguments) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(arguments)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(8, TimeUnit.SECONDS)) throw new IOException(arguments.getFirst() + " 响应超时");
            if (process.exitValue() != 0) throw new IOException(arguments.getFirst() + " 退出码 " + process.exitValue());
        } finally {
            if (process.isAlive()) process.destroy();
        }
    }
}
