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

/**
 * The one place audio leaves Jungey.
 *
 * <p>Every speech engine ends up here, which is what makes "stop talking" work the same
 * way whoever is doing the talking. Playback goes through Java's own mixer rather than
 * letting each engine open the sound device itself: espeak-ng writing straight to ALSA
 * on a PulseAudio desktop is what made speech crackle and drop syllables.
 */
final class AudioOut {

    /** What Piper emits and what we decode everything else into: 16-bit mono, little-endian. */
    private static AudioFormat pcm(float sampleRate) {
        return new AudioFormat(sampleRate, 16, 1, true, false);
    }

    /** Bumped by stop(), so a write loop from a cancelled line notices and gives up. */
    private volatile int generation;

    private volatile SourceDataLine line;
    private volatile Process player;

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
        SourceDataLine out = open(format);

        if (out == null) {
            // No mixer line - hand the bytes to whatever command-line player is installed.
            streamExternally(in, format, mine);
            return;
        }

        line = out;
        try {
            out.start();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                if (mine != generation) return;   // stop() overtook us
                out.write(buffer, 0, read);
            }
            if (mine == generation) out.drain();
        } finally {
            line = null;
            out.stop();
            out.close();
        }
    }

    private static SourceDataLine open(AudioFormat format) {
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) return null;
            SourceDataLine out = (SourceDataLine) AudioSystem.getLine(info);
            // A generous buffer: speech is not latency-critical, and underruns are audible.
            out.open(format, (int) format.getSampleRate() / 2 * format.getFrameSize());
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

    /** Cut playback off mid-word. Safe to call from any thread, at any time. */
    void stop() {
        generation++;

        SourceDataLine out = line;
        if (out != null) {
            out.stop();
            out.flush();
        }
        Process p = player;
        if (p != null && p.isAlive()) p.destroy();
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
