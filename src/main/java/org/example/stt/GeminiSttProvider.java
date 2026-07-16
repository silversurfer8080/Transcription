package org.example.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.usage.ApiKind;
import org.example.usage.RateLimit;
import org.example.usage.UsageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.function.Consumer;

/**
 * Free-tier speech-to-text via Google Gemini's multimodal
 * {@code generateContent} API.
 *
 * <p>Gemini is not a dedicated ASR — it transcribes an audio clip when prompted —
 * but that maps cleanly onto {@link BatchWindowSttProvider}'s batch-window model
 * and runs on Gemini's generous free tier (~1,500 requests/day), which is a key
 * budget <b>independent from Groq's</b> shared STT+LLM pool.
 *
 * <p>Because the free tier is limited by <i>requests per day</i>, this provider
 * uses a longer window than Groq (default 15 s) so fewer, larger clips stretch the
 * daily budget across more interviews.
 *
 * <p>Wire format (native API, not the OpenAI-compat layer, which is unreliable for
 * audio): a {@code contents[].parts[]} array carrying the instruction text plus an
 * {@code inline_data} part with the base64 WAV; the transcript comes back in
 * {@code candidates[0].content.parts[].text}.
 */
public class GeminiSttProvider extends BatchWindowSttProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiSttProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String DEFAULT_MODEL = "gemini-2.5-flash";
    private static final long DEFAULT_FLUSH_MS = 15_000;
    // Canonical capture format is 16 kHz mono 16-bit → 32000 bytes per audio-second.
    private static final int BYTES_PER_SECOND = 16_000 * 2;

    // Kept package-private so tests can assert the request carries the instruction.
    static final String PROMPT =
            "Transcribe the speech in this audio to plain text, verbatim. "
            + "Output only the transcript with no preamble, no quotes and no commentary. "
            + "If there is no intelligible speech, output nothing at all.";

    private final String apiKey;
    private final String model;
    private HttpClient httpClient;
    private volatile Consumer<UsageEvent> usageListener;   // optional; fired per response

    /**
     * Registers a listener notified with a {@link UsageEvent} for each response: requests +1
     * and the clip's audio-seconds. Gemini sends no rate-limit headers, so the event carries
     * an empty {@link RateLimit} (usage counters only). Fired on the flush thread.
     */
    public void setUsageListener(Consumer<UsageEvent> listener) {
        this.usageListener = listener;
    }

    public GeminiSttProvider(String apiKey, String channelId) {
        this(apiKey, channelId, DEFAULT_MODEL, DEFAULT_FLUSH_MS);
    }

    public GeminiSttProvider(String apiKey, String channelId, String model, long flushMillis) {
        super(channelId, flushMillis);
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override protected String providerName() { return "Gemini STT provider"; }
    @Override protected String threadName()   { return "gemini-stt-" + channelId; }
    @Override protected void onStart()        { this.httpClient = HttpClient.newHttpClient(); }
    @Override protected void onStop()         { if (httpClient != null) httpClient.close(); }

    @Override
    protected String transcribe(byte[] pcm) throws Exception {
        byte[] wav = toWav(pcm);
        String base64 = Base64.getEncoder().encodeToString(wav);
        String body = buildRequestJson(base64);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + model + ":generateContent"))
                .header("x-goog-api-key", apiKey)   // keeps the key out of the URL/logs
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        Consumer<UsageEvent> listener = usageListener;
        if (listener != null) {
            double audioSeconds = pcm.length / (double) BYTES_PER_SECOND;
            RateLimit rl = RateLimit.parse(response.headers(), ApiKind.STT);   // empty for Gemini
            listener.accept(new UsageEvent("Gemini", ApiKind.STT, 1, audioSeconds, rl));
        }

        if (response.statusCode() == 200) {
            return extractText(response.body());
        }
        String msg = friendlyError(response.statusCode(), response.body());
        log.warn("Gemini transcription failed (channel={}): {}", channelId, msg);
        reportError(msg);
        return null;
    }

    // ── Pure helpers (package-private for testing) ────────────────────────

    /** Builds the {@code generateContent} JSON body for a base64-encoded WAV clip. */
    static String buildRequestJson(String base64Wav) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode contents = root.putArray("contents");
        ObjectNode content = contents.addObject();
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", PROMPT);
        ObjectNode inline = parts.addObject().putObject("inline_data");
        inline.put("mime_type", "audio/wav");
        inline.put("data", base64Wav);
        // temperature 0 keeps the model from "improving" or paraphrasing the transcript.
        root.putObject("generationConfig").put("temperature", 0);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Gemini request body", e);
        }
    }

    /** Concatenates the text of every part in {@code candidates[0].content.parts}. */
    static String extractText(String json) {
        try {
            JsonNode parts = MAPPER.readTree(json)
                    .path("candidates").path(0)
                    .path("content").path("parts");
            StringBuilder sb = new StringBuilder();
            for (JsonNode p : parts) sb.append(p.path("text").asText(""));
            return sb.toString();
        } catch (Exception e) {
            log.error("Failed to parse Gemini transcription response", e);
            return "";
        }
    }

    static String friendlyError(int status, String body) {
        String detail = "";
        try {
            detail = MAPPER.readTree(body).path("error").path("message").asText("");
        } catch (Exception ignored) { /* body may not be JSON */ }
        String base = switch (status) {
            case 400      -> "Gemini: requisição inválida (400)";
            case 401, 403 -> "Chave Gemini inválida (" + status + ")";
            case 429      -> "Gemini: limite de uso atingido (429)";
            default       -> "Gemini HTTP " + status;
        };
        return detail.isEmpty() ? base : base + " — " + detail;
    }
}
