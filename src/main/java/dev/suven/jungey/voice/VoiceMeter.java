package dev.suven.jungey.voice;

import javax.sound.sampled.AudioFormat;
import java.util.function.LongSupplier;

/**
 * How loud Jungey's voice is right now, and roughly what shape the mouth saying it takes.
 *
 * <p>This is what the face moves its lips by. Audio is measured as it is handed to the sound
 * device, in windows of ten milliseconds, but read back by where the device has got to in
 * playing it - what is written is up to a quarter of a second ahead of what is heard, and
 * lips that move to the written audio visibly run early.
 *
 * <p>Loudness alone opens a mouth wrongly: in Piper's voices an "oo" is the loudest sound of
 * all and is said through a small round mouth. How the energy divides between low and high
 * frequencies says more. Open vowels - "ah", "eh" - spread theirs up to a kilohertz and more;
 * close ones - "oo", "ee", and the hum of an "m" - keep nine tenths of it below 700 Hz; an
 * "s" or an "f" has nearly none down there and is said through closed teeth. Each window's
 * loudness is scaled by how open its sound is, which gives the jaw its movement.
 */
public final class VoiceMeter {

    /**
     * @param open   how far the jaw drops, 0..1
     * @param width  lip shape, -1 rounded .. 1 drawn wide over the teeth
     * @param energy loudness, 0..1 against the voice's own recent peaks, whatever the sound
     */
    public record Reading(float open, float width, float energy) {
        static final Reading SILENT = new Reading(0, 0, 0);
    }

    private static final int WINDOW_MS = 10;

    /** Enough windows for any sentence Jungey says; older ones are overwritten. */
    private static final int SLOTS = 8192;

    /**
     * Read ahead of the sound by the time a frame takes to reach the screen, and the lips to
     * ease into shape: measured on a PipeWire desktop, lips read without it arrived 110 ms
     * after the sound. Sound ahead of lips is noticed at about 45 ms, lips ahead of sound
     * only past 125, so it errs early.
     */
    private static final int LOOKAHEAD_MS = 120;

    private final float[] open = new float[SLOTS];
    private final float[] width = new float[SLOTS];
    private final float[] energy = new float[SLOTS];

    /** Windows measured in the current clip. Written by the audio thread, read by the UI. */
    private volatile long windows;
    private volatile boolean active;
    private volatile LongSupplier heard = () -> 0;
    private volatile int windowFrames = 220;
    private volatile float sampleRate = 22_050f;

    // Everything below belongs to the audio thread.
    private boolean readable;
    private int channels, frameSize;
    private boolean bigEndian;
    private final byte[] carry = new byte[16];
    private int carried;
    private int count;
    private double sum, low, high;
    private double lowPass, highSplit, lowCoeff, highCoeff;
    /** The loudest recent window, decaying slowly, so level is relative to how this voice speaks. */
    private double peak = 0.12;

    /**
     * A clip is about to play.
     *
     * @param format what the bytes that follow are
     * @param played frames of this clip the listener has heard so far
     */
    void begin(AudioFormat format, LongSupplier played) {
        readable = format.getEncoding() == AudioFormat.Encoding.PCM_SIGNED
                && format.getSampleSizeInBits() == 16;
        channels = Math.max(1, format.getChannels());
        frameSize = Math.max(2, format.getFrameSize());
        bigEndian = format.isBigEndian();
        carried = 0;
        count = 0;
        sum = low = high = 0;
        lowPass = highSplit = 0;

        float rate = format.getSampleRate();
        // One-pole filters: below 700 Hz is where close vowels keep their energy, above
        // 2.5 kHz is the hiss of an "s".
        lowCoeff = 1 - Math.exp(-2 * Math.PI * 700 / rate);
        highCoeff = 1 - Math.exp(-2 * Math.PI * 2500 / rate);

        sampleRate = rate;
        windowFrames = Math.max(1, Math.round(rate * WINDOW_MS / 1000));
        heard = played;
        windows = 0;
        active = readable;
    }

    /** Audio on its way to the device. */
    void feed(byte[] buffer, int length) {
        if (!active) return;

        int i = 0;
        // A frame split across two writes is finished from the bytes kept last time.
        if (carried > 0) {
            int need = frameSize - carried;
            if (length < need) {
                System.arraycopy(buffer, 0, carry, carried, length);
                carried += length;
                return;
            }
            System.arraycopy(buffer, 0, carry, carried, need);
            measure(carry, 0);
            i = need;
            carried = 0;
        }
        for (; i + frameSize <= length; i += frameSize) measure(buffer, i);
        if (i < length) {
            carried = length - i;
            System.arraycopy(buffer, i, carry, 0, carried);
        }
    }

    private void measure(byte[] b, int at) {
        int lo = b[at + (bigEndian ? 1 : 0)] & 0xff;
        int hi = b[at + (bigEndian ? 0 : 1)];
        double x = ((hi << 8) | lo) / 32768.0;

        lowPass += lowCoeff * (x - lowPass);
        highSplit += highCoeff * (x - highSplit);
        double above = x - highSplit;

        sum += x * x;
        low += lowPass * lowPass;
        high += above * above;

        if (++count == windowFrames) close();
    }

    /** One window measured: store it where the reader will look for it. */
    private void close() {
        double rms = Math.sqrt(sum / count);
        peak = Math.max(rms, peak * 0.9985);
        peak = Math.max(peak, 0.04);

        double loud = Math.min(1, rms / (peak * 0.6));
        double openness = 0, shape = 0;
        if (sum > 1e-7) {
            double lowShare = low / sum;
            double highShare = high / sum;
            // Measured on Piper's voices: "s" and "f" put under a fifth of their energy
            // below 700 Hz, "ah" about half, "oo" and "ee" nine tenths or more.
            openness = lowShare < 0.15 ? 0.18
                    : lowShare < 0.40 ? 0.18 + 0.82 * (lowShare - 0.15) / 0.25
                    : lowShare < 0.78 ? 1.0
                    : lowShare < 0.90 ? 1.0 - 0.5 * (lowShare - 0.78) / 0.12
                    : 0.5;
            double wide = clamp((highShare - 0.08) / 0.15, 0, 1);
            double round = 0.4 * clamp((lowShare - 0.84) / 0.08, 0, 1);
            shape = clamp(wide - round, -1, 1);
        }

        long n = windows;
        int slot = (int) (n % SLOTS);
        open[slot] = (float) (loud * openness);
        width[slot] = (float) shape;
        energy[slot] = (float) loud;
        windows = n + 1;   // volatile write publishes the slot

        count = 0;
        sum = low = high = 0;
    }

    /** The clip has finished, or been cut off. */
    void end() {
        active = false;
    }

    /** What the listener is hearing at this moment. Cheap; safe from any thread. */
    public Reading now() {
        if (!active) return Reading.SILENT;
        long frame;
        try {
            frame = heard.getAsLong();
        } catch (RuntimeException e) {
            return Reading.SILENT;
        }
        return at(frame + (long) (sampleRate * LOOKAHEAD_MS / 1000));
    }

    /** The reading for a given frame of the current clip. */
    Reading at(long frame) {
        long written = windows;
        if (frame < 0 || written == 0) return Reading.SILENT;
        long index = Math.min(frame / windowFrames, written - 1);
        if (written - index > SLOTS) return Reading.SILENT;
        int slot = (int) (index % SLOTS);
        return new Reading(open[slot], width[slot], energy[slot]);
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }
}
