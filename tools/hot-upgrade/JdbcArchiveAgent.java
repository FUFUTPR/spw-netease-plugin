package spw.upgrade;

import java.io.Reader;
import java.io.Writer;
import java.lang.instrument.Instrumentation;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/** One-time migration for versions predating stop() archive cleanup.
 * No transformers, bytecode changes, class-loader close, database or directory writes.
 * The only host-side change is closing this plugin's cached SQLite resource JAR.
 */
public final class JdbcArchiveAgent {
    private static final String RESOURCE = "META-INF/maven/org.xerial/sqlite-jdbc/pom.properties";

    public static void agentmain(String argument, Instrumentation instrumentation) throws Exception {
        Properties request = new Properties();
        try (Reader input = Files.newBufferedReader(Path.of(argument), StandardCharsets.UTF_8)) {
            request.load(input);
        }
        Properties report = new Properties();
        report.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        try {
            boolean sandbox = "sandbox".equals(request.getProperty("mode"));
            Path root;
            if (sandbox) {
                String allowed = System.getProperty("spw.hotUpgrade.testRoot");
                if (allowed == null) throw new IllegalArgumentException("Not a sandbox JVM");
                root = Path.of(allowed).toRealPath();
            } else {
                if (!isSaltProcess()) throw new IllegalArgumentException("Not Salt Player for Windows");
                root = Path.of(System.getenv("APPDATA"), "Salt Player for Windows", "workshop", "plugins").toRealPath();
            }
            Set<Path> archives = new LinkedHashSet<>();
            for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                if (!type.getName().equals("org.sqlite.JDBC")) continue;
                if (!(type.getClassLoader() instanceof URLClassLoader loader)) continue;
                var domain = type.getProtectionDomain();
                if (domain == null || domain.getCodeSource() == null) continue;
                URL location = domain.getCodeSource().getLocation();
                if (!"file".equals(location.getProtocol())) continue;
                Path candidate = Path.of(location.toURI()).toAbsolutePath().normalize();
                if (!isOwnedArchive(root, candidate) || !Files.isRegularFile(candidate)) continue;
                Path archive = candidate.toRealPath();
                if (!isOwnedArchive(root, archive)) continue;
                boolean owned = false;
                for (URL url : loader.getURLs()) {
                    if ("file".equals(url.getProtocol())
                            && Path.of(url.toURI()).toAbsolutePath().normalize().equals(candidate)
                            && Files.isSameFile(Path.of(url.toURI()), archive)) {
                        owned = true;
                        break;
                    }
                }
                if (owned) archives.add(archive);
            }
            report.setProperty("matches", Integer.toString(archives.size()));
            boolean inspect = Boolean.parseBoolean(request.getProperty("inspect", "false"));
            int count = 0;
            for (Path archive : archives) {
                report.setProperty("archive." + count, archive.toString());
                if (!inspect) {
                    JarURLConnection connection = (JarURLConnection) URI.create("jar:" + archive.toUri() + "!/" + RESOURCE).toURL().openConnection();
                    connection.setUseCaches(true);
                    connection.getJarFile().close();
                }
                count++;
            }
            report.setProperty("released", inspect ? "0" : Integer.toString(count));
            report.setProperty("status", inspect ? "INSPECTED" : "READY");
        } catch (Throwable failure) {
            report.setProperty("status", "FAILED");
            report.setProperty("error", failure.toString());
        }
        try (Writer output = Files.newBufferedWriter(Path.of(request.getProperty("report")), StandardCharsets.UTF_8)) {
            report.store(output, "One-time live upgrade preparation; no installation performed");
        }
    }

    static boolean isSaltProcess() {
        String executable = System.getProperty("jpackage.app-path", "");
        String command = System.getProperty("sun.java.command", "");
        return !executable.isEmpty()
                && Path.of(executable).getFileName().toString().equalsIgnoreCase("Salt Player for Windows.exe")
                && (command.equals("MainKt") || command.startsWith("MainKt "));
    }

    static boolean isOwnedArchive(Path root, Path archive) {
        if (!archive.startsWith(root)) return false;
        Path relative = root.relativize(archive);
        return relative.getNameCount() == 3
                && relative.getName(0).toString().matches("plugin-com\\.example\\.netease-[0-9][0-9A-Za-z.+_-]*")
                && relative.getName(1).toString().equals("lib")
                && relative.getName(2).toString().matches("sqlite-jdbc-[0-9][0-9A-Za-z.+_-]*\\.jar");
    }
}
