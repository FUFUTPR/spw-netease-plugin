package spw.upgrade;

import com.sun.tools.attach.VirtualMachine;
import java.io.Reader;
import java.io.Writer;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Separate persistent JVM: reproduce an old leak, attach, keep JDBC working,
 * move old directory, load a fresh driver, and move its directory after cleanup.
 * Foreign plugin archive remains locked throughout. No real host installation. */
public final class LiveUpgradeProbe {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("child")) { child(Path.of(args[1]), Path.of(args[2])); return; }
        Path agent = PrepareLiveUpgrade.stageAgent(Path.of(args[0]));
        Path sqlite = Path.of(args[1]).toRealPath();
        Path root = Files.createTempDirectory(Path.of(args[2]).toRealPath(), "live-");
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        Process child = new ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED",
                "-Dspw.hotUpgrade.testRoot=" + root, "-cp", System.getProperty("java.class.path"),
                LiveUpgradeProbe.class.getName(), "child", root.toString(), sqlite.toString())
                .redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile()).start();
        try {
            await(root.resolve("ready"), child);
            for (boolean inspect : new boolean[]{true, false}) {
                Path report = root.resolve(inspect ? "inspect.properties" : "release.properties");
                Path request = root.resolve(inspect ? "inspect-request.properties" : "release-request.properties");
                Properties config = new Properties();
                config.setProperty("mode", "sandbox");
                config.setProperty("inspect", Boolean.toString(inspect));
                config.setProperty("report", report.toString());
                try (Writer output = Files.newBufferedWriter(request, StandardCharsets.UTF_8)) { config.store(output, "test"); }
                VirtualMachine vm = VirtualMachine.attach(Long.toString(child.pid()));
                try { vm.loadAgent(agent.toString(), request.toString()); } finally { vm.detach(); }
                Properties result = new Properties();
                try (Reader input = Files.newBufferedReader(report, StandardCharsets.UTF_8)) { result.load(input); }
                require(result.getProperty("status").equals(inspect ? "INSPECTED" : "READY"), result.toString());
                require(result.getProperty("matches").equals("2"), "Exactly two owned archives: " + result);
                require(result.getProperty("released").equals(inspect ? "0" : "2"), "Release count: " + result);
                Files.writeString(root.resolve(inspect ? "inspect-done" : "release-done"), "go");
                await(root.resolve(inspect ? "inspect-checked" : "release-checked"), child);
            }
            require(child.waitFor(15, TimeUnit.SECONDS) && child.exitValue() == 0, Files.readString(root.resolve("child.log")));
            System.out.println(Files.readString(root.resolve("child.log")));
            System.out.println("LIVE_UPGRADE_PROBE_RESULT=PASS pid=" + child.pid() + " evidence=" + root);
        } finally {
            if (child.isAlive()) { child.destroy(); if (!child.waitFor(3, TimeUnit.SECONDS)) child.destroyForcibly(); }
        }
    }

    private static void child(Path root, Path sqlite) throws Exception {
        Path old = makePlugin(root, sqlite, "com.example.netease", "0.12.3");
        Path stopped = makePlugin(root, sqlite, "com.example.netease", "0.12.2");
        Path foreign = makePlugin(root, sqlite, "another.plugin", "0.12.3");
        try (var stoppedLoader = loader(stopped);
             Connection connection = driver(stoppedLoader).connect("jdbc:sqlite::memory:", new Properties())) { }
        assertLocked(root, stopped);
        try (var ownLoader = loader(old); var foreignLoader = loader(foreign)) {
            Driver own = driver(ownLoader);
            Driver other = driver(foreignLoader);
            // Complete a full connection cycle first so post-close health uses loaded classes.
            try (Connection connection = own.connect("jdbc:sqlite::memory:", new Properties());
                 Statement statement = connection.createStatement()) { statement.execute("select 1"); }
            try (Connection connection = own.connect("jdbc:sqlite::memory:", new Properties());
                 Statement statement = connection.createStatement();
                 Connection foreignConnection = other.connect("jdbc:sqlite::memory:", new Properties())) {
                statement.execute("select 2");
                foreignLoader.close();
                assertLocked(root, old);
                assertLocked(root, foreign);
                Files.writeString(root.resolve("ready"), "ready");
                await(root.resolve("inspect-done"), null);
                assertLocked(root, old);
                Files.writeString(root.resolve("inspect-checked"), "ready");
                await(root.resolve("release-done"), null);
                try (ResultSet result = statement.executeQuery("select 3")) {
                    require(result.next() && result.getInt(1) == 3, "Existing JDBC connection survived release");
                }
                ownLoader.close();
                move(root, old, root.resolve("previous").resolve(old.getFileName()));
                move(root, stopped, root.resolve("previous").resolve(stopped.getFileName()));
                assertLocked(root, foreign);
                System.out.println("PASS legacy cached handle released; same JDBC connection works; foreign plugin untouched");
            }
        }
        Path next = makePlugin(root, sqlite, "com.example.netease", "0.12.4");
        try (var fresh = loader(next)) {
            try (Connection connection = driver(fresh).connect("jdbc:sqlite::memory:", new Properties());
                 Statement statement = connection.createStatement()) { statement.execute("select 4"); }
            require(com.example.netease.core.JdbcResources.releaseCachedArchive(fresh) == 1, "New stop cleanup");
        }
        move(root, next, root.resolve("previous").resolve(next.getFileName()));
        System.out.println("PASS fresh driver loaded and next directory moved without JVM restart; pid=" + ProcessHandle.current().pid());
        Files.writeString(root.resolve("release-checked"), "ready");
    }

    private static Path makePlugin(Path root, Path sqlite, String id, String version) throws Exception {
        Path plugin = root.resolve("plugin-" + id + "-" + version);
        Path lib = Files.createDirectories(plugin.resolve("lib"));
        Files.copy(sqlite, lib.resolve("sqlite-jdbc-3.41.2.2.jar"));
        return plugin;
    }
    private static URLClassLoader loader(Path plugin) throws Exception {
        // PF4J also lists classes/; a failed removal can already have deleted it.
        return new URLClassLoader(new URL[]{plugin.resolve("classes").toUri().toURL(),
                plugin.resolve("lib/sqlite-jdbc-3.41.2.2.jar").toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }
    private static Driver driver(ClassLoader loader) throws Exception {
        return (Driver) Class.forName("org.sqlite.JDBC", true, loader).getConstructor().newInstance();
    }
    private static void assertLocked(Path root, Path plugin) throws Exception {
        try { move(root, plugin, root.resolve("blocked-" + plugin.getFileName())); }
        catch (AccessDeniedException expected) { return; }
        throw new AssertionError("Expected Windows cached archive lock: " + plugin);
    }
    private static void move(Path root, Path from, Path to) throws Exception {
        root = root.toAbsolutePath().normalize(); from = from.toAbsolutePath().normalize(); to = to.toAbsolutePath().normalize();
        require(from.startsWith(root) && !from.equals(root) && to.startsWith(root), "Move must stay in sandbox");
        Files.createDirectories(to.getParent()); Files.move(from, to);
    }
    private static void await(Path file, Process process) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(file)) {
            if (process != null && !process.isAlive()) throw new AssertionError(Files.readString(file.getParent().resolve("child.log")));
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out: " + file);
            Thread.sleep(50);
        }
    }
    private static void require(boolean pass, String message) { if (!pass) throw new AssertionError(message); }
}
