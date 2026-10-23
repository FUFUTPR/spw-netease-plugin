package com.example.netease.core;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;

/** sqlite-jdbc 3.41.2.2 VersionHolder leaves pom.properties' stream open.
 * Its cached URLJarFile is separate from URLClassLoader's own archive handle,
 * so closing the plugin loader alone cannot release it on Windows. */
public final class JdbcResources {
    private JdbcResources() {}

    /** Close only the cached resource archive owned by this plugin loader.
     * Never close the host's driver/archive or the plugin class loader itself. */
    public static int releaseCachedArchive(ClassLoader owner) throws IOException {
        if (!(owner instanceof URLClassLoader urls)) return 0;
        URL resource = owner.getResource("META-INF/maven/org.xerial/sqlite-jdbc/pom.properties");
        if (resource == null || !resource.getProtocol().equals("jar")) return 0;
        JarURLConnection connection = (JarURLConnection) resource.openConnection();
        URL archive = connection.getJarFileURL();
        if (Arrays.stream(urls.getURLs()).noneMatch(archive::equals)) return 0;
        // Explicitly request the same cached URLJarFile used by VersionHolder's
        // URL.openStream(). close() also closes its leaked entry stream and
        // removes this archive from JarFileFactory's cache.
        connection.setUseCaches(true);
        connection.getJarFile().close();
        return 1;
    }
}
