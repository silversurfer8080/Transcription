package org.example.stt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.usage.ApiKind;
import org.example.usage.RateLimit;
import org.example.usage.UsageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Free-tier speech-to-text via Groq's Whisper endpoint
 * ({@code POST /openai/v1/audio/transcriptions}).
 *
 * <p>Groq's Whisper is a <b>batch</b> file API, so this provider extends
 * {@link BatchWindowSttProvider}, which handles the buffering, ~5 s windowing,
 * silence gating and flush threading. Here we implement only the provider-specific
 * multipart POST.
 *
 * <p>Known limitation you may hit on the free tier (2,000 requests/day, 7,200
 * audio-seconds/hour): heavy days exhaust the budget and return HTTP 429. Whisper
 * also occasionally hallucinates caption-like phrases ("Thank you.") on near-silent
 * audio — the {@link #SILENCE_PEAK} gate suppresses the pure-silence case, but a
 * fully offline engine (Vosk) avoids this class of artifact entirely.
 *
 * <p>Pure helpers ({@link #buildMultipartBody}, {@link #extractText},
 * {@link #friendlyError}, and the inherited {@code isSilent}) are package-private so
 * unit tests can exercise them without a network call.
 */
public class GroqWhisperProvider extends BatchWindowSttProvider {

    private static final Logger log = LoggerFactory.getLogger(GroqWhisperProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String API_URL = "https://api.groq.com/openai/v1/audio/transcriptions";
    private static final String DEFAULT_MODEL = "whisper-large-v3-turbo";
    private static final long DEFAULT_FLUSH_MS = 5_000;
    // Canonical capture format is 16 kHz mono 16-bit → 32000 bytes per audio-second.
    private static final int BYTES_PER_SECOND = 16_000 * 2;

    private final String apiKey;
    private final String model;
    private final String language;   // ISO code (e.g. "en"); null/blank → Whisper auto-detects
    private HttpClient httpClient;
    private volatile Consumer<UsageEvent> usageListener;   // optional; fired per response

    public GroqWhisperProvider(String apiKey, String channelId) {
        this(apiKey, channelId, "en", DEFAULT_MODEL, DEFAULT_FLUSH_MS);
    }

    /** The given Whisper model, English, and the default window. */
    public GroqWhisperProvider(String apiKey, String channelId, String model) {
        this(apiKey, channelId, "en", model, DEFAULT_FLUSH_MS);
    }

    public GroqWhisperProvider(String apiKey, String channelId,
                               String language, String model, long flushMillis) {
        super(channelId, flushMillis);
        this.apiKey = apiKey;
        this.language = language;
        this.model = model;
    }

    /**
     * Registers a listener notified with a {@link UsageEvent} for each Groq response (success
     * and 429): requests +1, the clip's audio-seconds, and the remaining quota from the
     * rate-limit headers. Fired on the flush thread — marshal to the UI thread yourself.
     */
    public void setUsageListener(Consumer<UsageEvent> listener) {
        this.usageListener = listener;
    }

    @Override protected String providerName() { return "Groq Whisper provider"; }
    @Override protected String threadName()   { return "groq-whisper-" + channelId; }
    @Override protected void onStart()        { this.httpClient = HttpClient.newHttpClient(); }
    @Override protected void onStop()         { if (httpClient != null) httpClient.close(); }

    @Override
    protected String transcribe(byte[] pcm) throws Exception {
        byte[] wav = toWav(pcm);
        String boundary = "----GroqWhisper" + UUID.randomUUID();
        byte[] body = buildMultipartBody(boundary, "audio.wav", wav, model, language);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Report usage: +1 request, the clip's audio-seconds, and remaining quota from the
        // rate-limit headers (present on 200 and 429).
        Consumer<UsageEvent> listener = usageListener;
        if (listener != null) {
            double audioSeconds = pcm.length / (double) BYTES_PER_SECOND;
            RateLimit rl = RateLimit.parse(response.headers(), ApiKind.STT);
            listener.accept(new UsageEvent("Groq", ApiKind.STT, 1, audioSeconds, rl));
        }

        if (response.statusCode() == 200) {
            return extractText(response.body());
        }
        String msg = friendlyError(response.statusCode(), response.body());
        log.warn("Groq transcription failed (channel={}): {}", channelId, msg);
        reportError(msg);
        return null;
    }

    // ── Pure helpers (package-private for testing) ────────────────────────

    /**
     * Builds a {@code multipart/form-data} body carrying the audio file plus the
     * {@code model}, {@code response_format} and (optional) {@code language}
     * fields expected by Groq's transcription endpoint.
     */
    static byte[] buildMultipartBody(String boundary, String filename, byte[] fileBytes,
                                     String model, String language) {
        StringBuilder pre = new StringBuilder();
        appendField(pre, boundary, "model", model);
        appendField(pre, boundary, "response_format", "json");
        if (language != null && !language.isBlank()) {
            appendField(pre, boundary, "language", language);
        }
        pre.append("--").append(boundary).append("\r\n")
           .append("Content-Disposition: form-data; name=\"file\"; filename=\"")
           .append(filename).append("\"\r\n")
           .append("Content-Type: audio/wav\r\n\r\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(pre.toString().getBytes(StandardCharsets.UTF_8));
            out.write(fileBytes);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);   // ByteArrayOutputStream never actually throws
        }
        return out.toByteArray();
    }

    private static void appendField(StringBuilder sb, String boundary, String name, String value) {
        sb.append("--").append(boundary).append("\r\n")
          .append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
          .append(value).append("\r\n");
    }

    /** Extracts the transcript from a Groq {@code response_format=json} body. */
    static String extractText(String json) {
        try {
            return MAPPER.readTree(json).path("text").asText("");
        } catch (Exception e) {
            log.error("Failed to parse Groq transcription response", e);
            return "";
        }
    }

    static String friendlyError(int status, String body) {
        String detail = "";
        try {
            detail = MAPPER.readTree(body).path("error").path("message").asText("");
        } catch (Exception ignored) { /* body may not be JSON */ }
        String base = switch (status) {
            case 401 -> "Chave Groq inválida (401)";
            case 402 -> "Groq: pagamento necessário (402)";
            case 429 -> "Groq: limite de uso atingido (429)";
            default  -> "Groq HTTP " + status;
        };
        return detail.isEmpty() ? base : base + " — " + detail;
    }
}
