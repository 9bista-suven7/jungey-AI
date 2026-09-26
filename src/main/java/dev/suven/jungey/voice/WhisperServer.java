package dev.suven.jungey.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.suven.jungey.net.Http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * whisper.cpp's server, started once and kept running beside Jungey.
 *
 * <p>whisper-cli loads its model afresh for every command: 140 MB for base.en, read back
 * off the disk each time something is said. The server loads it once, at startup, and then
 * answers on the loopback interface, so a command costs only the transcribing.
 *
 * <p>It runs under a small shell that waits on Jungey's end of a pipe. When Jungey exits,
 * however it exits, the pipe closes and the shell takes the server down with it; a server
 * left behind would hold a few hundred megabytes until the next reboot.
 */
final class WhisperServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Runs the server, and stops it when stdin closes. The watcher reads a copy of stdin on
     * fd 3, because a background job's own stdin is /dev/null. If the server dies first, the
     * shell exits too, which is how Jungey notices.
     */
    private static final String SUPERVISOR = """
            exec 3<&0
            "$@" </dev/null &
            server=$!
            { read _ <&3; kill $server; } &
            watcher=$!
            wait $server
            kill $watcher 2>/dev/null
            """;

    /** A cold model on a hard drive is slow; one that is not up after this never will be. */
    private static final long LOAD_TIMEOUT_MS = 180_000;

    /** A server that keeps dying is not worth restarting; whisper-cli is the fallback. */
    private static final int MAX_STARTS = 3;

    private final String binary;
    private final Path model;

    private volatile Process shell;
    private volatile int port;
    private volatile boolean ready;
    private int starts;

    WhisperServer(String binary, Path model) {
        this.binary = binary;
        this.model = model;
    }

    /** Where whisper.cpp keeps its server binary, or null if it is not installed. */
    static String find() {
        return Speaker.resolve("whisper-server");
    }

    /** Start the server and load the model in the background. Returns straight away. */
    synchronized void start() {
        Process running = shell;
        if (running != null && running.isAlive()) return;
        if (starts >= MAX_STARTS) return;
        starts++;
        ready = false;

        try {
            port = freePort();
            Process p = new ProcessBuilder("sh", "-c", SUPERVISOR, "jungey-whisper", binary,
                    "-m", model.toString(),
                    "--host", "127.0.0.1",
                    "--port", String.valueOf(port),
                    "-l", "en")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            shell = p;

            Thread wait = new Thread(() -> awaitReady(p), "jungey-whisper-start");
            wait.setDaemon(true);
            wait.start();
        } catch (IOException e) {
            System.err.println("[jungey] could not start whisper-server: " + e.getMessage());
        }
    }

    private void awaitReady(Process p) {
        long deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS;
        while (p.isAlive() && System.currentTimeMillis() < deadline) {
            try {
                Http.get(base() + "/health");
                if (shell == p) ready = true;
                return;
            } catch (Exception e) {
                // Still loading the model.
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            }
        }
        if (shell != p) return;   // replaced by a newer server while this one was loading
        System.err.println("[jungey] whisper-server did not come up; using whisper-cli instead");
        stop(p);
    }

    /** True once the model is loaded and the server is answering. */
    boolean ready() {
        return ready;
    }

    /**
     * @return what was said, or null if the server could not be asked and the caller
     *         should transcribe some other way
     */
    String transcribe(byte[] wav) {
        if (!ready) {
            start();   // restarts a server that died; a no-op while one is still loading
            return null;
        }
        try {
            String boundary = "jungey-" + UUID.randomUUID();
            Http.Reply reply = Http.post(base() + "/inference", multipart(boundary, wav),
                    "multipart/form-data; boundary=" + boundary, Map.of(), Duration.ofSeconds(30));
            if (!reply.ok()) {
                System.err.println("[jungey] whisper-server answered HTTP " + reply.status());
                return null;
            }
            return MAPPER.readTree(reply.body()).path("text").asText("");
        } catch (Exception e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            System.err.println("[jungey] whisper-server stopped answering (" + why + "); restarting it");
            Process p = shell;
            if (p != null) stop(p);
            start();
            return null;
        }
    }

    /** Ask the shell to stop the server: closing its stdin is what it is waiting for. */
    private void stop(Process p) {
        if (shell == p) ready = false;
        try {
            p.getOutputStream().close();
        } catch (IOException e) {
            // Already gone.
        }
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static byte[] multipart(String boundary, byte[] wav) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(wav.length + 512);
        out.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"command.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(wav);
        for (String[] field : new String[][]{{"response_format", "json"}, {"temperature", "0.0"}}) {
            out.writeBytes(("\r\n--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + field[0] + "\"\r\n\r\n"
                    + field[1]).getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
