package dev.suven.jungeynotepad;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One window at a time: a second launch - a file opened from the file manager, or Jungey
 * asked to open a note - hands its files to the window already open and leaves, so they
 * arrive as tabs there rather than in a window of their own.
 *
 * <p>The window listens on a Unix socket in the runtime directory, readable by this user only.
 * A launch sends the absolute paths, one to a line; none at all means "come to the front".
 */
public final class Instance {

    private static final int MAX_MESSAGE = 64 * 1024;

    private Instance() {
    }

    static Path socket() {
        String name = "jungey-notepad-" + System.getProperty("user.name") + ".sock";
        String run = System.getenv("XDG_RUNTIME_DIR");
        if (run != null && !run.isBlank() && Files.isDirectory(Path.of(run))) {
            Path p = Path.of(run).resolve(name);
            // A socket's path has to fit in about a hundred bytes.
            if (p.toString().getBytes(StandardCharsets.UTF_8).length < 100) return p;
        }
        return Path.of(System.getProperty("java.io.tmpdir")).resolve(name);
    }

    /** Asks the open window to show {@code files}. False when there is no window to ask. */
    public static boolean handOff(List<Path> files) {
        Path socket = socket();
        if (!Files.exists(socket)) return false;
        StringBuilder message = new StringBuilder();
        for (Path f : files) message.append(f.toAbsolutePath().normalize()).append('\n');
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            // A socket left by a window that crashed refuses at once, and a new one is started.
            channel.connect(UnixDomainSocketAddress.of(socket));
            ByteBuffer out = ByteBuffer.wrap(message.toString().getBytes(StandardCharsets.UTF_8));
            while (out.hasRemaining()) channel.write(out);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Takes the hand-offs of later launches, passing each one's files to {@code show}. */
    public static void listen(Consumer<List<Path>> show) {
        Path socket = socket();
        ServerSocketChannel server;
        try {
            // Anything still here is left over: a live window would have taken the hand-off.
            Files.deleteIfExists(socket);
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socket));
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"));
            socket.toFile().deleteOnExit();
        } catch (IOException | UnsupportedOperationException e) {
            // Without it, a second launch opens a second window: worse, not broken.
            return;
        }
        Thread t = new Thread(() -> {
            while (server.isOpen()) {
                try (SocketChannel client = server.accept()) {
                    show.accept(paths(read(client)));
                } catch (IOException e) {
                    if (!server.isOpen()) return;
                }
            }
        }, "jungey-notepad-listen");
        t.setDaemon(true);
        t.start();
    }

    private static String read(SocketChannel client) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        ByteBuffer buf = ByteBuffer.allocate(4096);
        while (client.read(buf) > 0 && all.size() < MAX_MESSAGE) {
            all.write(buf.array(), 0, buf.position());
            buf.clear();
        }
        return all.toString(StandardCharsets.UTF_8);
    }

    static List<Path> paths(String message) {
        List<Path> out = new ArrayList<>();
        for (String line : message.split("\n")) {
            if (line.startsWith("/")) out.add(Path.of(line));
        }
        return out;
    }
}
