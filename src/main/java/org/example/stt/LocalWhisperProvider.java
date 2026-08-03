package org.example.stt;

import org.example.usage.ApiKind;
import org.example.usage.RateLimit;
import org.example.usage.UsageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

import static org.example.stt.GroqWhisperProvider.buildMultipartBody;
import static org.example.stt.GroqWhisperProvider.extractText;

/**
 * Local, unlimited, offline speech-to-text via a self-hosted faster-whisper server that
 * exposes the OpenAI-compatible transcription route ({@code POST /v1/audio/transcriptions}) —
 * e.g. <b>faster-whisper-server</b> or <b>speaches</b>.
 *
 * <p>Same <b>batch</b> multipart contract as {@link GroqWhisperProvider}, so it extends
 * {@link BatchWindowSttProvider} (buffering, ~5&nbsp;s windowing, silence gating, flush
 * threading) and reuses that provider's {@link GroqWhisperProvider#buildMultipartBody} and
 * {@link GroqWhisperProvider#extractText} helpers. The only differences are a configurable
 * localhost base URL and <b>no {@code Authorization} header</b> (a local server needs no key).
 *
 * <p>Chosen because every cloud free tier (Deepgram/Groq/Gemini) exhausts at the user's
 * interview volume, whereas a local Whisper is both unlimited <i>and</i> accent-robust (real
 * Whisper medium/large-v3) — which Vosk is not.
 *
 * <p>Pure helpers ({@link #endpointUrl}, {@link #friendlyError}) are package-private so unit
 * tests can exercise them without a network call.
 */
public class LocalWhisperProvider extends BatchWindowSttProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalWhisperProvider.class);

    /** faster-whisper-server / speaches default port. */
    public static final String DEFAULT_BASE_URL = "http://localhost:8000";
    /** faster-whisper CTranslate2 model id for the recommended medium model. */
    public static final String DEFAULT_MODEL = "Systran/faster-whisper-medium";

    private static final long DEFAULT_FLUSH_MS = 5_000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    // Generous: a ~5 s clip transcribes in a couple of seconds on CPU medium, but the very
    // first request can load (or download) the model. Still bounds an indefinite hang.
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final int BYTES_PER_SECOND = 16_000 * 2;   // 16 kHz mono 16-bit

    private final String endpoint;   // full /v1/audio/transcriptions URL
    private final String model;
    private final String language;   // ISO code (e.g. "en"); null/blank -> Whisper auto-detects
    private HttpClient httpClient;
    private volatile Consumer<UsageEvent> usageListener;

    public LocalWhisperProvider(String baseUrl, String model, String channelId) {
        this(baseUrl, model, channelId, "en", DEFAULT_FLUSH_MS);
    }

    public LocalWhisperProvider(String baseUrl, String model, String channelId,
                                String language, long flushMillis) {
        super(channelId, flushMillis);
        this.endpoint = endpointUrl(baseUrl);
        this.model = model;
        this.language = language;
    }

    /**
     * Registers a listener notified with a {@link UsageEvent} per response (audio-seconds only;
     * a local server has no rate-limit quota, so {@link RateLimit#empty()}). Fired on the flush
     * thread — marshal to the UI thread yourself.
     */
    public void setUsageListener(Consumer<UsageEvent> listener) { this.usageListener = listener; }

    @Override protected String providerName() { return "Whisper Local provider"; }
    @Override protected String threadName()   { return "whisper-local-" + channelId; }
    @Override protected void onStart() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }
    @Override protected void onStop() { if (httpClient != null) httpClient.close(); }

    @Override
    protected String transcribe(byte[] pcm) throws Exception {
        byte[] wav = toWav(pcm);
        String boundary = "----LocalWhisper" + UUID.randomUUID();
        byte[] body = buildMultipartBody(boundary, "audio.wav", wav, model, language);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // Connection refused / reset / request timeout: the local server is almost certainly
            // not running (or still loading the model). Surface a friendly, actionable message.
            String msg = "Whisper local indisponível em " + endpoint + " — confirme que o servidor "
                    + "faster-whisper está rodando. (" + e.getClass().getSimpleName() + ")";
            log.warn("Local Whisper request failed (channel={}): {}", channelId, e.getMessage());
            reportError(msg);
            return null;
        }

        Consumer<UsageEvent> listener = usageListener;
        if (listener != null) {
            double audioSeconds = pcm.length / (double) BYTES_PER_SECOND;
            listener.accept(new UsageEvent("Whisper Local", ApiKind.STT, 1, audioSeconds, RateLimit.empty()));
        }

        if (response.statusCode() == 200) {
            return extractText(response.body());
        }
        String msg = friendlyError(response.statusCode(), response.body());
        log.warn("Local Whisper transcription failed (channel={}): {}", channelId, msg);
        reportError(msg);
        return null;
    }

    // ── Pure helpers (package-private for testing) ────────────────────────

    /**
     * Normalizes a base URL to the full OpenAI-compatible transcription endpoint, idempotently:
     * trims, drops trailing slashes, and appends {@code /v1/audio/transcriptions} unless the
     * base already ends with it. A blank base falls back to {@link #DEFAULT_BASE_URL}.
     */
    static String endpointUrl(String base) {
        String b = (base == null) ? "" : base.trim();
        if (b.isEmpty()) b = DEFAULT_BASE_URL;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b.endsWith("/v1/audio/transcriptions") ? b : b + "/v1/audio/transcriptions";
    }

    static String friendlyError(int status, String body) {
        return switch (status) {
            case 404 -> "Whisper local (404): modelo não encontrado no servidor — verifique o nome do modelo.";
            case 422 -> "Whisper local (422): requisição inválida (modelo ou formato de áudio).";
            case 500 -> "Whisper local (500): erro interno do servidor faster-whisper.";
            default  -> "Whisper local HTTP " + status;
        };
    }
}
