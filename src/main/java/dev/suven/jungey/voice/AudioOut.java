package dev.suven.jungey.voice;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The one place audio leaves Jungey.
 *
 * <p>Every speech engine ends up here, which is what makes "stop talking" work the same
 * way whoever is doing the talking. Playback goes through Java's own mixer rather than
 * letting each engine open the sound device itself: espeak-ng writing straight to ALSA
 * on a PulseAudio desktop is what made speech crackle and drop syllables.
 *
 * <p>One mixer line is kept open from sentence to sentence. Opening a fresh one for every
 * clip cost a gap each time, and a line that was still letting go of the last clip while
 * the next one opened is how two voices ended up talking over each other.
 */
final class AudioOut {

    /**
     * How far ahead of the listener the sound device is kept. Everything written is heard
     * whatever happens next, so this is also how long an interrupted voice runs on - short
     * enough to feel like being listened to, long enough that a busy CPU does not starve it.
     */
    private static final int BUFFER_MS = 250;

    /** Audio is handed over in slices this long, so an interruption is noticed promptly. */
    private static final int SLICE_MS = 20;

    /** An interrupted voice trails off over this long instead of stopping dead mid-syllable. */
    private static final int FADE_MS = 90;

    /** An idle line is given back to the sound system after this long without a sentence. */
    private static final long IDLE_CLOSE_MS = 4_000;

    /** What Piper emits and what we decode everything else into: 16-bit mono, little-endian. */
    private static AudioFormat pcm(float sampleRate) {
        return new AudioFormat(sampleRate, 16, 1, true, false);
    }

    /** Bumped by stop(), so a write loop from a cancelled line notices and gives up. */
    private volatile int generation;

    /** Whether the latest stop() asked for the voice to trail off rather than be cut. */
    private volatile boolean fade;

    private volatile SourceDataLine line;
    private volatile Process player;

    private final Object device = new Object();
    private SourceDataLine held;        // guarded by device
    private AudioFormat heldFormat;     // guarded by device
    private boolean busy;               // guarded by device
    private long lastUsed;              // guarded by device

    private final ScheduledExecutorService closer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jungey-audio-idle");
        t.setDaemon(true);
        return t;
    });

    /** Play an encoded clip - WAV, FLAC, MP3, whatever the engine handed back. Blocks. */
    void play(byte[] audio) throws Exception {
        if (audio == null || audio.length == 0) return;

        // Java reads WAV and AU natively; anything else goes through ffmpeg first.
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(audio))) {
            AudioFormat format = in.getFormat();
            if (format.getEncoding() == AudioFormat.Encoding.PCM_SIGNED) {
                stream(in, format);
                return;
            }
        } catch (Exception e) {
            // Not a format Java knows. Fall through to ffmpeg.
        }

        byte[] decoded = decode(audio);
        if (decoded.length > 0) stream(new ByteArrayInputStream(decoded), pcm(22_050f));
    }

    /** Play raw 16-bit mono PCM as it arrives, so a long sentence starts before it is finished. */
    void stream(InputStream pcmStream, float sampleRate) throws Exception {
        stream(pcmStream, pcm(sampleRate));
    }

    private void stream(InputStream in, AudioFormat format) throws Exception {
        int mine = generation;
        SourceDataLine out = acquire(format);

        if (out == null) {
            // No mixer line - hand the bytes to whatever command-line player is installed.
            streamExternally(in, format, mine);
            return;
        }

        line = out;
        try {
            out.start();
            int frame = Math.max(1, format.getFrameSize());
            byte[] slice = new byte[Math.max(frame, (int) (format.getSampleRate() * SLICE_MS / 1000) * frame)];
            int read;
            // Whole frames only: the mixer refuses a write that ends halfway through a sample.
            while ((read = in.readNBytes(slice, 0, slice.length) / frame * frame) > 0) {
                if (mine != generation) {
                    if (fade) trailOff(in, out, format, slice, read);
                    return;
                }
                out.write(slice, 0, read);
            }
            if (mine == generation) out.drain();
        } catch (Exception e) {
            // A line that failed once - the sound server restarted, a headset went away -
            // is not trusted with the next sentence.
            discard(out);
            throw e;
        } finally {
            line = null;
            out.stop();
            out.flush();
            release();
        }
    }

    private void discard(SourceDataLine broken) {
        synchronized (device) {
            if (held == broken) held = null;
        }
        broken.close();
    }

    /**
     * Say the next few milliseconds with the volume falling to nothing, then let the line
     * empty. What the device already holds is heard first, so the voice runs on for a
     * moment and then trails away - the way a person stops when someone cuts in.
     */
    private static void trailOff(InputStream in, SourceDataLine out, AudioFormat format,
                                 byte[] first, int firstLength) throws IOException {
        if (format.getSampleSizeInBits() != 16 || format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
            out.drain();
            return;
        }
        int frame = format.getFrameSize();
        int frames = (int) (format.getSampleRate() * FADE_MS / 1000);
        byte[] tail = new byte[frames * frame];
        int have = Math.min(firstLength, tail.length);
        System.arraycopy(first, 0, tail, 0, have);
        if (have < tail.length) have += in.readNBytes(tail, have, tail.length - have);
        have = have / frame * frame;

        boolean big = format.isBigEndian();
        for (int i = 0; i + 1 < have; i += 2) {
            double gain = 1.0 - (double) (i / frame) / frames;
            int lo = tail[i + (big ? 1 : 0)] & 0xff;
            int hi = tail[i + (big ? 0 : 1)];
            int sample = (int) (((hi << 8) | lo) * gain);
            tail[i + (big ? 1 : 0)] = (byte) sample;
            tail[i + (big ? 0 : 1)] = (byte) (sample >> 8);
        }
        out.write(tail, 0, have);
        out.drain();
    }

    /** The open line, reused when the format matches, or a new one; null if none can be had. */
    private SourceDataLine acquire(AudioFormat format) {
        synchronized (device) {
            if (held != null && !format.matches(heldFormat)) {
                held.close();
                held = null;
            }
            if (held == null) {
                held = open(format);
                heldFormat = format;
            }
            busy = held != null;
            return held;
        }
    }

    private void release() {
        synchronized (device) {
            busy = false;
            lastUsed = System.currentTimeMillis();
        }
        closer.schedule(this::closeIfIdle, IDLE_CLOSE_MS, TimeUnit.MILLISECONDS);
    }

    private void closeIfIdle() {
        synchronized (device) {
            if (held != null && !busy && System.currentTimeMillis() - lastUsed >= IDLE_CLOSE_MS) {
                held.close();
                held = null;
            }
        }
    }

    private static SourceDataLine open(AudioFormat format) {
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) return null;
            SourceDataLine out = (SourceDataLine) AudioSystem.getLine(info);
            int frames = (int) (format.getSampleRate() * BUFFER_MS / 1000);
            out.open(format, frames * format.getFrameSize());
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private void streamExternally(InputStream in, AudioFormat format, int mine) throws IOException {
        boolean pulse = Speaker.onPath("paplay");
        String binary = pulse ? Speaker.resolve("paplay") : Speaker.resolve("aplay");
        if (binary == null) return;

        int rate = (int) format.getSampleRate();
        ProcessBuilder pb = pulse
                ? new ProcessBuilder(binary, "--raw", "--format=s16le",
                        "--rate=" + rate, "--channels=1")
                : new ProcessBuilder(binary, "-q", "-t", "raw", "-f", "S16_LE",
                        "-r", String.valueOf(rate), "-c", "1", "-");

        Process p = pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        player = p;
        try (OutputStream sink = p.getOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                if (mine != generation) return;
                sink.write(buffer, 0, read);
            }
        } catch (IOException e) {
            // The player exited early; nothing useful left to do with the rest of the audio.
        } finally {
            try {
                p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            player = null;
        }
    }

    /** Turn anything ffmpeg understands into the raw PCM the mixer wants. */
    private static byte[] decode(byte[] audio) {
        String ffmpeg = Speaker.resolve("ffmpeg");
        if (ffmpeg == null) return new byte[0];
        try {
            Process p = new ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error",
                    "-i", "pipe:0", "-f", "s16le", "-acodec", "pcm_s16le",
                    "-ar", "22050", "-ac", "1", "pipe:1")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();

            Thread feed = new Thread(() -> {
                try (OutputStream in = p.getOutputStream()) {
                    in.write(audio);
                } catch (IOException ignored) {
                    // ffmpeg rejected the input; the empty result speaks for itself.
                }
            }, "jungey-ffmpeg-in");
            feed.setDaemon(true);
            feed.start();

            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor();
            return out;
        } catch (IOException | InterruptedException e) {
            return new byte[0];
        }
    }

    /**
     * Stop what is playing. Safe to call from any thread, at any time.
     *
     * @param gently let the voice trail off over a fraction of a second rather than cutting
     *               it mid-word; the next clip still waits until it has finished
     */
    void stop(boolean gently) {
        fade = gently;
        generation++;

        if (!gently) {
            SourceDataLine out = line;
            if (out != null) {
                out.stop();
                out.flush();
            }
        }
        Process p = player;
        if (p != null && p.isAlive()) p.destroy();
    }

    /** Give the sound device back, for good. */
    void close() {
        stop(false);
        closer.shutdownNow();
        synchronized (device) {
            if (held != null) held.close();
            held = null;
        }
    }

    /** Read a process's stdout fully, on a thread, so a full pipe never deadlocks the writer. */
    static byte[] drain(InputStream in) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
