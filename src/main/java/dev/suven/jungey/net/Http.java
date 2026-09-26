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
import java.util.Map;

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

    /**
     * A POST whose reply may be audio as easily as JSON, so the body comes back as bytes.
     * Speech services want minutes of patience on a cold model, hence the explicit timeout.
     */
    public static Reply post(String url, byte[] body, String contentType,
                             Map<String, String> headers, Duration timeout) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));

        headers.forEach(req::header);

        HttpResponse<byte[]> res = CLIENT.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Reply(res.statusCode(), res.body(),
                res.headers().firstValue("content-type").orElse(""));
    }

    /** A raw HTTP reply: status kept alongside the body so callers can retry on their own terms. */
    public record Reply(int status, byte[] body, String contentType) {

        public boolean ok() {
            return status / 100 == 2;
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public JsonNode json() throws Exception {
            return MAPPER.readTree(body);
        }

        /** The first line of an error body - enough to say what went wrong, short enough to log. */
        public String error() {
            String message = text().replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
            if (message.length() > 140) message = message.substring(0, 140) + "…";
            return "HTTP " + status + (message.isBlank() ? "" : " - " + message);
        }
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
