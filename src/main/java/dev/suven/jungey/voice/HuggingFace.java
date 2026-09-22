package dev.suven.jungey.voice;

import dev.suven.jungey.core.Config;
import dev.suven.jungey.net.Http;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * The Hugging Face inference endpoint, shared by the hosted voice and the hosted ears.
 *
 * <p>Hosted models are the good ones - Whisper hears far better than anything that fits
 * in this repository - but they are someone else's computer: the audio leaves the machine,
 * the round trip costs a second or two, and the whole thing stops working on a train.
 * So nothing here is ever the default. It switches on only once a token exists, and every
 * caller is expected to have a local fallback ready for when it does not answer.
 */
final class HuggingFace {

    private HuggingFace() {
    }

    /** Where the huggingface-cli keeps a token once you have run {@code hf auth login}. */
    private static final Path CLI_TOKEN =
            Path.of(System.getProperty("user.home"), ".cache", "huggingface", "token");

    /**
     * Config first, then the environment, then the CLI's own token file - so a token that
     * already works for everything else on the machine works here without being copied
     * into a plain-text properties file.
     */
    static String token() {
        String fromConfig = Config.get().str("hf.token", "");
        if (!fromConfig.isBlank()) return fromConfig.trim();

        for (String name : new String[]{"HF_TOKEN", "HUGGING_FACE_HUB_TOKEN", "HUGGINGFACE_API_KEY"}) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) return value.trim();
        }

        try {
            if (Files.isReadable(CLI_TOKEN)) {
                return Files.readString(CLI_TOKEN, StandardCharsets.UTF_8).trim();
            }
        } catch (Exception e) {
            // An unreadable token file is the same as no token file.
        }
        return "";
    }

    static boolean configured() {
        return !token().isBlank();
    }

    static String base() {
        return Config.get().str("hf.url", "https://router.huggingface.co/hf-inference/models");
    }

    /**
     * One inference call.
     *
     * <p>A model that has gone cold answers 503 while it loads, which can take the best
     * part of a minute. {@code x-wait-for-model} asks the endpoint to hold the connection
     * open instead, and the single retry covers the case where it declines to.
     */
    static Http.Reply call(String model, byte[] body, String contentType, Duration timeout)
            throws Exception {
        String url = base() + "/" + model;
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + token(),
                "Accept", "*/*",
                "x-wait-for-model", "true");

        Http.Reply reply = Http.post(url, body, contentType, headers, timeout);
        if (reply.status() == 503) {
            reply = Http.post(url, body, contentType, headers, timeout);
        }
        return reply;
    }

    /** Why a call failed, in words worth putting in the console. */
    static String explain(Http.Reply reply, String model) {
        return switch (reply.status()) {
            case 401, 403 -> "Hugging Face rejected the token. Check hf.token, or run: hf auth login";
            case 402 -> "Hugging Face says the monthly inference credits are used up.";
            case 404 -> "Hugging Face has no inference endpoint for " + model
                    + ". Try another model, or set hf.url to a provider that serves it.";
            case 503 -> "Hugging Face is still loading " + model + ". It should work on the next try.";
            default -> "Hugging Face: " + reply.error();
        };
    }
}
