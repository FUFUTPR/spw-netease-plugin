import com.example.netease.core.DataPaths;
import com.example.netease.svc.NativeStreamServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real loopback sockets, including requests waiting in the four-worker service queue. */
public class StreamShutdownProbe {
    public static void main(String[] args) throws Exception {
        DataPaths.init("com.example.netease");
        if (NativeStreamServer.start() <= 0) throw new AssertionError("stream did not start");
        var sockets = new ArrayList<Socket>();
        try {
            int port = URI.create(NativeStreamServer.baseUrl()).getPort();
            for (int i = 0; i < 6; i++) {
                var socket = new Socket("127.0.0.1", port); socket.setSoTimeout(2000);
                socket.getOutputStream().write("GET /health HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));
                sockets.add(socket);
            }
            // Both old and fixed packages can be tested without a new production API.
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().equals("netease-stream")).count() < 4
                    && System.nanoTime() < until) Thread.sleep(10);
            NativeStreamServer.stop();
            for (var socket : sockets) {
                try { if (socket.getInputStream().read() != -1) throw new AssertionError("stopped stream returned data"); }
                catch (SocketException closed) { /* TCP may reset an incomplete request. */ }
            }
            if (NativeStreamServer.start() >= 0) throw new AssertionError("late start revived a stopped service");
            System.out.println("STREAM_SHUTDOWN_RESULT=7 PASS (six sockets closed; late start stays stopped)");
        } finally {
            NativeStreamServer.stop();
            for (var socket : sockets) socket.close();
        }
    }
}
