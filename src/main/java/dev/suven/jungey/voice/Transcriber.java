package dev.suven.jungey.voice;

import com.fasterxml.jackson.databind.JsonNode;
import dev.suven.jungey.core.Config;
import dev.suven.jungey.net.Http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A second opinion on what was just said.
 *
 * <p>Vosk is a good listener for a wake word: it runs on a stream, costs nothing and never
 * leaves the machine. It is a mediocre listener for a sentence spoken at arm's length in a
 * room with a fan in it, which is where "unreliable" comes from. Whisper is markedly better
 * at exactly that, but it cannot run on a live stream here - it wants a finished clip.
 *
 * <p>So the two are used for what each is good at: Vosk decides when speech starts and
 * stops, Whisper decides what the words were. If Whisper is not configured, or the network
 * is down, or it comes back with nothing, the caller keeps what Vosk heard.
 */
final class Transcriber {

    /** Vosk-only means this class does nothing and the listener falls back to its own text. */
    enum Kind {NONE, HF, WHISPER_CPP}

    private static final int SAMPLE_RATE = 16_000;

    /**
     * What Whisper says when it is handed near-silence. Every one of these is a plausible
     * command in principle and never one in practice, so they are dropped rather than run.
     */
    private static final Set<String> HALLUCINATIONS = Set.of(
            "you", "thank you", "thanks", "thank you.", "bye", "bye.", "okay", "ok",
            "thanks for watching", "thanks for watching!", "please subscribe",
            "[blank_audio]", "(silence)", ".", "..", "...");

    private final Kind kind;

    Transcriber() {
        this.kind = detect();
    }

    private static Kind detect() {
        String choice = Config.get().str("voice.input.engine", "auto").toLowerCase(Locale.ENGLISH);

        switch (choice) {
            case "vosk", "none", "off" -> {
                return Kind.NONE;
            }
            case "hf", "huggingface", "whisper" -> {
                // Asked for by name: say so plainly if it cannot be had, rather than silently
                // dropping back to Vosk and leaving someone wondering why nothing improved.
                if (HuggingFace.configured()) return Kind.HF;
                System.err.println("[jungey] voice.input.engine=hf but no Hugging Face token found. "
                        + "Set hf.token, or export HF_TOKEN. Falling back to Vosk.");
                return Kind.NONE;
            }
            case "whispercpp", "whisper.cpp", "local" -> {
                if (whisperCppReady()) return Kind.WHISPER_CPP;
                System.err.println("[jungey] voice.input.engine=whispercpp but no whisper binary "
                        + "or model found. Falling back to Vosk.");
                return Kind.NONE;
            }
            default -> {
                // auto: prefer the local upgrade if it is installed, never phone home uninvited
                return whisperCppReady() ? Kind.WHISPER_CPP : Kind.NONE;
            }
        }
    }

    boolean enabled() {
        return kind != Kind.NONE;
    }

    /** For the console and the "voice" report. */
    String label() {
        return switch (kind) {
            case NONE -> "vosk";
            case HF -> "vosk + " + hfModel().substring(hfModel().indexOf('/') + 1) + " (hosted)";
            case WHISPER_CPP -> "vosk + whisper.cpp";
        };
    }

    /** True if the audio is loud enough to be worth sending anywhere. */
    static boolean hasSpeech(byte[] pcm) {
        if (pcm.length < SAMPLE_RATE / 2) return false;   // under half a second: a cough

        ByteBuffer buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        double sum = 0;
        int samples = pcm.length / 2;
        for (int i = 0; i < samples; i++) {
            double sample = buffer.getShort(i * 2) / 32768.0;
            sum += sample * sample;
        }
        return Math.sqrt(sum / samples) > 0.006;
    }

    /**
     * @param pcm 16 kHz signed 16-bit mono, exactly as it came off the microphone
     * @return what was said, or "" if this transcriber could not improve on Vosk
     */
    String transcribe(byte[] pcm) {
        if (kind == Kind.NONE || !hasSpeech(pcm)) return "";

        try {
            String text = switch (kind) {
                case HF -> viaHuggingFace(wav(pcm));
                case WHISPER_CPP -> viaWhisperCpp(wav(pcm));
                case NONE -> "";
            };
            return clean(text);
        } catch (Exception e) {
            System.err.println("[jungey] transcription failed, keeping what Vosk heard: "
                    + e.getMessage());
            return "";
        }
    }

    private static String viaHuggingFace(byte[] wav) throws Exception {
        Http.Reply reply = HuggingFace.call(hfModel(), wav, "audio/wav", Duration.ofSeconds(30));
        if (!reply.ok()) {
            System.err.println("[jungey] " + HuggingFace.explain(reply, hfModel()));
            return "";
        }

        JsonNode json = reply.json();
        // Whisper answers {"text": "..."}; some endpoints wrap it in a single-element array.
        if (json.isArray() && !json.isEmpty()) json = json.get(0);
        return json.path("text").asText("");
    }

    private static String hfModel() {
        return Config.get().str("voice.input.hf.model", "openai/whisper-large-v3");
    }

    private static String viaWhisperCpp(byte[] wav) throws IOException, InterruptedException {
        Path clip = Files.createTempFile("jungey-heard-", ".wav");
        try {
            Files.write(clip, wav);
            Process p = new ProcessBuilder(whisperBinary(),
                    "-m", whisperModel().toString(),
                    "-f", clip.toString(),
                    "-nt",          // no timestamps, just the words
                    "-np",          // no progress chatter on stdout
                    "-l", "en")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();

            String text = new String(AudioOut.drain(p.getInputStream()), StandardCharsets.UTF_8);
            p.waitFor();
            return text;
        } finally {
            Files.deleteIfExists(clip);
        }
    }

    private static boolean whisperCppReady() {
        return whisperBinary() != null && Files.isRegularFile(whisperModel());
    }

    private static String whisperBinary() {
        // The project renamed its CLI twice; all three names are still in the wild.
        for (String name : List.of("whisper-cli", "whisper-cpp", "whisper")) {
            String binary = Speaker.resolve(name);
            if (binary != null) return binary;
        }
        return null;
    }

    private static Path whisperModel() {
        String path = Config.get().str("voice.input.whisperModel",
                System.getProperty("user.home") + "/.local/share/whisper/ggml-base.en.bin");
        return Path.of(path.replaceFirst("^~", System.getProperty("user.home")));
    }

    /** Drop whisper's polite noises, and anything left that is not really a command. */
    private static String clean(String text) {
        if (text == null) return "";

        String s = text.replaceAll("\\[[^]]*]", " ")       // [BLANK_AUDIO], [MUSIC]
                .replaceAll("\\([^)]*\\)", " ")            // (wind blowing)
                .replaceAll("\\s+", " ")
                .trim();

        String bare = s.toLowerCase(Locale.ENGLISH).replaceAll("[.!?,]+$", "").trim();
        return HALLUCINATIONS.contains(bare) ? "" : s;
    }

    /** Wrap raw microphone PCM in the WAV header every speech service expects. */
    static byte[] wav(byte[] pcm) {
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        int byteRate = SAMPLE_RATE * 2;

        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt(36 + pcm.length);
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16);                 // PCM header length
        header.putShort((short) 1);        // uncompressed
        header.putShort((short) 1);        // mono
        header.putInt(SAMPLE_RATE);
        header.putInt(byteRate);
        header.putShort((short) 2);        // block align
        header.putShort((short) 16);       // bits per sample
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt(pcm.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcm.length);
        out.writeBytes(header.array());
        out.writeBytes(pcm);
        return out.toByteArray();
    }
}
