package com.example.netease.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;

/** Private files on POSIX; Windows keeps the user's inherited ACL. */
public final class PrivateFiles {
    private PrivateFiles() { }
    public static void restrict(Path path, boolean directory) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("私有文件不能是符号链接");
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null)
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    }
    public static Path directory(Path path) throws IOException {
        if (Files.getFileAttributeView(path.getParent(), PosixFileAttributeView.class) != null)
            Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        else Files.createDirectories(path);
        restrict(path, true);
        return path;
    }
    public static Path temporary(Path directory, String prefix) throws IOException {
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null)
            return Files.createTempFile(directory, prefix, ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        return Files.createTempFile(directory, prefix, ".tmp");
    }
    public static void atomicWrite(Path file, byte[] bytes) throws IOException {
        if (Files.isSymbolicLink(file)) throw new IOException("私有文件不能是符号链接");
        Path temporary = temporary(file.getParent(), file.getFileName()+"-");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            restrict(file, false);
        } finally { Files.deleteIfExists(temporary); }
    }
}
