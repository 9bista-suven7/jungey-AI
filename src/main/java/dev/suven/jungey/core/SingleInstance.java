package dev.suven.jungey.core;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Makes sure there is only ever one Jungey, and that it is the newest one.
 *
 * <p>Two Jungeys means two voices answering every question and two microphones fighting
 * over the wake word. So the first to start listens on a socket, and every later start
 * asks it one question: which of us is newer? If the running one is at least as new, it
 * comes to the front - which is what makes Super+J summon Jungey rather than start
 * another - and the newcomer quits. If the newcomer was built later, from code pulled
 * since, the old one hands over and quits, so opening Jungey always opens the latest.
 */
public final class SingleInstance implements AutoCloseable {

    /** What the running Jungey does when another start asks after it. */
    public interface Handler {
        /** Someone opened Jungey again: come to the front. */
        void show();

        /** A newer build is starting: shut down so it can take over. */
        void handOver();
    }

    private static final String SHOWN = "shown";
    private static final String YIELD = "yield";

    /** Jungey's jar as the launchers name it - how a Jungey from before this socket is recognised. */
    private static final Pattern JUNGEY_JAR = Pattern.compile("(.*/)?jungey(-[0-9][\\w.-]*)?\\.jar$");

    private final ServerSocketChannel server;
    private final Path address;
    private final String build;
    private volatile Handler handler;
    private volatile boolean handOverPending;

    private SingleInstance(ServerSocketChannel server, Path address, String build) {
        this.server = server;
        this.address = address;
        this.build = build;
        if (server != null) {
            Thread t = new Thread(this::serve, "jungey-instance");
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * Become the running Jungey, or hand the job to the one already running.
     *
     * @param build this build's stamp, from {@link Build#stamp()}
     * @return this process's claim, or null if a Jungey at least as new is already running
     *         and has been brought to the front - in which case this process should exit
     */
    public static SingleInstance claim(String build) {
        try {
            return settle(build);
        } catch (RuntimeException e) {
            // Better two Jungeys than none.
            System.err.println("[jungey] could not check for another Jungey: " + e);
            return new SingleInstance(null, address(), build);
        }
    }

    private static SingleInstance settle(String build) {
        Path address = address();
        try {
            Files.createDirectories(address.getParent());
        } catch (IOException e) {
            return new SingleInstance(null, address, build);
        }

        // Two starts at the same moment - a double-click - must not both decide they are first.
        Path lockFile = address.resolveSibling("jungey.lock");
        try (FileChannel lockChannel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = lockChannel.lock()) {

            // A Jungey started from elsewhere - a terminal without XDG_RUNTIME_DIR, say - may
            // be listening at another of the usual places; ask there too before assuming none.
            String answer = null;
            for (Path candidate : candidates(address)) {
                answer = ask(candidate, build);
                if (SHOWN.equals(answer)) return null;
                if (YIELD.equals(answer)) {
                    waitForExit(candidate);
                    break;
                }
            }
            if (answer == null) retireLegacy();

            // Nobody answering: whatever is at the address is left over from a crash.
            Files.deleteIfExists(address);
            ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(address));
            return new SingleInstance(server, address, build);
        } catch (IOException e) {
            // Better two Jungeys than none.
            System.err.println("[jungey] could not check for another Jungey: " + e.getMessage());
            return new SingleInstance(null, address, build);
        }
    }

    /** Start acting on requests from later starts. */
    public void listen(Handler handler) {
        this.handler = handler;
        if (handOverPending) handler.handOver();
    }

    /** Every place a Jungey may be listening, this start's own first. */
    private static List<Path> candidates(Path own) {
        java.util.LinkedHashSet<Path> all = new java.util.LinkedHashSet<>();
        all.add(own);
        try {
            Object uid = Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid");
            all.add(Path.of("/run/user/" + uid, "jungey", "jungey.sock"));
        } catch (Exception e) {
            // Not a Unix filesystem attribute we can read; the others still apply.
        }
        all.add(Path.of(System.getProperty("user.home"), ".cache", "jungey", "jungey.sock"));
        all.add(Path.of(System.getProperty("java.io.tmpdir"),
                "jungey-" + System.getProperty("user.name", "user"), "jungey.sock"));
        return all.stream().filter(p -> p.toString().getBytes(StandardCharsets.UTF_8).length <= 100).toList();
    }

    private static Path address() {
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        Path preferred = runtime != null && !runtime.isBlank() && Files.isDirectory(Path.of(runtime))
                ? Path.of(runtime, "jungey", "jungey.sock")
                : Path.of(System.getProperty("user.home"), ".cache", "jungey", "jungey.sock");
        // A socket's path must fit in about a hundred bytes; a deep home directory can overrun it.
        if (preferred.toString().getBytes(StandardCharsets.UTF_8).length <= 100) return preferred;
        return Path.of(System.getProperty("java.io.tmpdir"),
                "jungey-" + System.getProperty("user.name", "user"), "jungey.sock");
    }

    /**
     * Tell the running Jungey our build and hear what it will do about it.
     *
     * @return {@link #SHOWN}, {@link #YIELD}, or null when nothing answers
     */
    private static String ask(Path address, String build) {
        if (!Files.exists(address)) return null;
        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(address))) {
            channel.write(ByteBuffer.wrap((build + "\n").getBytes(StandardCharsets.UTF_8)));
            // A Jungey that is stuck should not keep a new one from starting.
            return CompletableFuture.supplyAsync(() -> readLine(channel))
                    .get(4, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /** Wait for an older Jungey to finish shutting down and free the address. */
    private static void waitForExit(Path address) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try (SocketChannel ignored = SocketChannel.open(UnixDomainSocketAddress.of(address))) {
                Thread.sleep(150);
            } catch (IOException e) {
                return;   // refused, or gone: it has let go
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Close any Jungey from before this socket existed. It cannot be asked to step aside,
     * and left running it would answer every question alongside this one.
     */
    private static void retireLegacy() {
        ProcessHandle self = ProcessHandle.current();
        String user = self.info().user().orElse("");
        List<ProcessHandle> old = ProcessHandle.allProcesses()
                .filter(p -> p.pid() != self.pid())
                .filter(p -> p.info().user().orElse("?").equals(user))
                .filter(p -> p.info().command().orElse("").endsWith("/java"))
                .filter(SingleInstance::runsJungeyJar)
                .toList();

        for (ProcessHandle p : old) {
            System.out.println("[jungey] closing an older Jungey (pid " + p.pid() + ") so this one can take over");
            p.destroy();
        }
        for (ProcessHandle p : old) {
            try {
                p.onExit().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                p.destroyForcibly();
            }
        }
    }

    private static boolean runsJungeyJar(ProcessHandle p) {
        String[] args = p.info().arguments().orElse(new String[0]);
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equals("-jar") && JUNGEY_JAR.matcher(args[i + 1]).matches()) return true;
        }
        return false;
    }

    private void serve() {
        while (server.isOpen()) {
            try (SocketChannel client = server.accept()) {
                String theirs = readLine(client);
                // A start waiting for us to exit only checks the door is shut; it says nothing.
                if (theirs == null) continue;
                boolean newer = theirs.compareTo(build) > 0;
                client.write(ByteBuffer.wrap(((newer ? YIELD : SHOWN) + "\n").getBytes(StandardCharsets.UTF_8)));

                Handler h = handler;
                if (newer) {
                    System.out.println("[jungey] a newer build (" + theirs + ") is starting; handing over");
                    if (h != null) h.handOver();
                    else handOverPending = true;
                } else if (h != null) {
                    h.show();
                }
            } catch (IOException e) {
                if (!server.isOpen()) return;
            }
        }
    }

    private static String readLine(SocketChannel channel) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(256);
            StringBuilder line = new StringBuilder();
            while (channel.read(buffer) > 0 || buffer.position() > 0) {
                buffer.flip();
                String chunk = StandardCharsets.UTF_8.decode(buffer).toString();
                buffer.clear();
                int end = chunk.indexOf('\n');
                if (end >= 0) return line.append(chunk, 0, end).toString().trim();
                line.append(chunk);
                if (line.length() > 200) break;
            }
            return line.isEmpty() ? null : line.toString().trim();
        } catch (IOException e) {
            return null;
        }
    }

    /** Let go of the address so the next Jungey can take it. */
    @Override
    public void close() {
        if (server == null) return;
        try {
            server.close();
            Files.deleteIfExists(address);
        } catch (IOException e) {
            // Removed at the next start instead.
        }
    }
}
