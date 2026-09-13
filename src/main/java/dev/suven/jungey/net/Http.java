package dev.suven.jungey.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Thin wrapper over the JDK HTTP client. Every online skill goes through here. */
public final class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Sent on every request; some APIs (Wikipedia) reject callers without one. */
    private static final String USER_AGENT = "Jungey/0.1 (personal assistant; +https://github.com/9bista-suven7/jungey-AI)";

    private Http() {
    }

    public static String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .GET()
                .build();

        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + res.statusCode() + " from " + URI.create(url).getHost());
        }
        return res.body();
    }

    public static JsonNode getJson(String url) throws Exception {
        return MAPPER.readTree(get(url));
    }

    public static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** True if we appear to have working internet, checked cheaply. */
    public static boolean online() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.open-meteo.com/"))
                    .timeout(Duration.ofSeconds(4))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            CLIENT.send(req, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
