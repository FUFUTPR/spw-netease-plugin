package spw.upgrade;

import com.sun.tools.attach.VirtualMachine;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Official JDK Attach API; never kills, restarts, installs or removes anything. */
public final class PrepareLiveUpgrade {
    public static void main(String[] args) throws Exception {
        Path agent = stageAgent(Path.of(args[0]));
        Path evidence = Path.of(args[1]).toRealPath();
        boolean inspect = args.length > 2 && args[2].equals("inspect");
        int hosts = 0;
        int released = 0;
        for (var descriptor : VirtualMachine.list()) {
            if (!descriptor.displayName().equals("MainKt") && !descriptor.displayName().startsWith("MainKt ")) continue;
            VirtualMachine vm = VirtualMachine.attach(descriptor);
            try {
                var system = vm.getSystemProperties();
                String executable = system.getProperty("jpackage.app-path", "");
                if (executable.isEmpty() || !Path.of(executable).getFileName().toString().equalsIgnoreCase("Salt Player for Windows.exe")) continue;
                hosts++;
                Path report = evidence.resolve("process-" + descriptor.id() + ".properties");
                Path config = evidence.resolve("request-" + descriptor.id() + ".properties");
                Properties request = new Properties();
                request.setProperty("report", report.toString());
                request.setProperty("inspect", Boolean.toString(inspect));
                try (Writer output = Files.newBufferedWriter(config, StandardCharsets.UTF_8)) {
                    request.store(output, "Live upgrade preparation");
                }
                vm.loadAgent(agent.toString(), config.toString());
                Properties result = new Properties();
                try (Reader input = Files.newBufferedReader(report, StandardCharsets.UTF_8)) { result.load(input); }
                if (!result.getProperty("status", "").equals(inspect ? "INSPECTED" : "READY")) {
                    throw new IllegalStateException("PID " + descriptor.id() + ": " + result.getProperty("error", "No completion report"));
                }
                released += Integer.parseInt(result.getProperty("released", "0"));
                System.out.println("LIVE_UPGRADE_PROCESS=" + descriptor.id() + " matches=" + result.getProperty("matches")
                        + " released=" + result.getProperty("released"));
                for (int i = 0; i < Integer.parseInt(result.getProperty("matches", "0")); i++) {
                    System.out.println("  " + result.getProperty("archive." + i));
                }
            } finally { vm.detach(); }
        }
        if (hosts == 0) throw new IllegalStateException("No running Salt Player JVM found; keep the player open.");
        System.out.println("LIVE_UPGRADE_RESULT=" + (inspect ? "INSPECTED" : "READY") + " hosts=" + hosts + " released=" + released);
    }

    /** Windows' native agent loader does not accept every non-ASCII filename. */
    public static Path stageAgent(Path source) throws Exception {
        Path directory = Files.createTempDirectory("spw-live-upgrade-").toRealPath();
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(directory.toString())) {
            throw new IllegalStateException("Agent staging needs an ASCII temporary path; set java.io.tmpdir to one.");
        }
        return Files.copy(source.toRealPath(), directory.resolve("helper.jar"));
    }
}
