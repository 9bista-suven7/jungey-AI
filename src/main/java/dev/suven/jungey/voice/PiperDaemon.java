package dev.suven.jungey.voice;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One Piper process kept running for the whole session.
 *
 * <p>Starting Piper loads a 60 MB voice into ONNX Runtime, and that costs more than the
 * speaking does: on a test machine a fresh process took 0.45 s over a sentence that a
 * running one said in 0.15 s, and a laptop's hard drive only widens the gap. A streamed
 * reply reaches the speaker a sentence at a time, so the load used to be paid again before
 * every one of them.
 *
 * <p>Given --output_dir, Piper's C++ build reads text a line at a time, writes each line to
 * its own WAV file and prints that file's path once it is complete. The path is how we know
 * a sentence is finished, so exactly one line goes in for each path read back. The Python
 * build logs its paths rather than printing them, so it keeps the process-per-line way.
 */
final class PiperDaemon {

    /** A Piper that keeps dying is not worth restarting; the caller has a fallback. */
    private static final int MAX_STARTS = 3;

    private final List<String> command;
    private Path dir;
    private volatile Process process;
    private BufferedWriter in;
    private BufferedReader out;
    private int starts;

    /** @param command piper with its voice, speed and pause flags, but no output flag */
    PiperDaemon(List<String> command) {
        this.command = List.copyOf(command);
    }

    /** True if this Piper build can be kept running - see the class comment. */
    static boolean supported(String help) {
        // argparse, and so every Python build, opens its usage line with [-h].
        return help.contains("--output_dir") && !help.contains("[-h]");
    }

    /** Start Piper now, so the first sentence does not wait for the voice to load. */
    synchronized void warmUp() {
        try {
            ensureRunning();
        } catch (IOException e) {
            System.err.println("[jungey] could not start piper: " + e.getMessage());
        }
    }

    /**
     * Synthesise one line.
     *
     * @return the line as a WAV clip, or null if Piper is not running and the caller should
     *         speak the line some other way
     */
    synchronized byte[] synthesise(String text) {
        // A line break inside the text would be two requests, and two paths back for one.
        String line = text.replaceAll("[\\r\\n\\u0085\\u2028\\u2029]+", " ").strip();
        if (line.isEmpty()) return new byte[0];

        try {
            if (!ensureRunning()) return null;
            in.write(line);
            in.newLine();
            in.flush();

            Path wav = nextClip();
            try {
                return Files.readAllBytes(wav);
            } finally {
                Files.deleteIfExists(wav);
            }
        } catch (IOException e) {
            System.err.println("[jungey] piper stopped (" + e.getMessage() + "); it restarts on the next line");
            kill();
            return null;
        }
    }

    /** The next WAV Piper reports. Anything else on stdout is skipped rather than trusted. */
    private Path nextClip() throws IOException {
        String reported;
        while ((reported = out.readLine()) != null) {
            reported = reported.strip();
            if (reported.endsWith(".wav") && Files.isRegularFile(Path.of(reported))) {
                return Path.of(reported);
            }
        }
        throw new IOException("piper exited");
    }

    private boolean ensureRunning() throws IOException {
        Process p = process;
        if (p != null && p.isAlive()) return true;
        if (starts >= MAX_STARTS) return false;
        starts++;

        if (dir == null) {
            dir = Files.createTempDirectory("jungey-piper-");
            dir.toFile().deleteOnExit();
        }
        List<String> cmd = new ArrayList<>(command);
        cmd.add("--output_dir");
        cmd.add(dir.toString());

        p = new ProcessBuilder(cmd)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        in = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
        out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        process = p;
        return true;
    }

    /**
     * End Piper, so a line it is stuck on gives up. Not synchronized on purpose: it is how a
     * caller unblocks a {@link #synthesise} that is holding the lock while it waits.
     */
    void kill() {
        Process p = process;
        if (p != null && p.isAlive()) p.destroyForcibly();
    }

    /** Stop Piper for good and tidy up its scratch directory. */
    void close() {
        kill();
        Path d = dir;
        if (d == null) return;
        try (var files = Files.list(d)) {
            for (Path f : files.toList()) Files.deleteIfExists(f);
            Files.deleteIfExists(d);
        } catch (IOException e) {
            // Temp files; the OS clears them eventually.
        }
    }
}
