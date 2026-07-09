package org.example.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.audio.WavFileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Free-tier speech-to-text via Groq's Whisper endpoint
 * ({@code POST /openai/v1/audio/transcriptions}).
 *
 * <h3>Streaming contract over a batch API</h3>
 * <p>A streaming STT backend (WebSocket) emits interim + final results as you
 * speak. Groq's Whisper is instead a <b>batch</b> file API: you POST a complete
 * audio clip and get one transcript back. To fit the streaming
 * {@link SpeechToTextProvider} contract we buffer incoming PCM and, every
 * {@link #flushMillis} milliseconds, wrap the accumulated audio in an in-memory
 * WAV and POST it. Each response is emitted as a single <b>final</b>
 * {@link TranscriptEvent} — there are no interim/partial events, so the UI's
 * partial label simply stays empty.
 *
 * <p>The trade-off is latency (text appears one window + one round-trip after
 * it was spoken) and occasional word clipping at window boundaries, in exchange
 * for zero cost on Groq's free tier.
 *
 * <h3>Silence gating</h3>
 * <p>Whisper is known to hallucinate phrases ("Thank you.", "Thanks for
 * watching.") when fed pure silence, and every POST spends part of the free
 * tier's daily request budget. So a window whose peak amplitude is below
 * {@link #SILENCE_PEAK} is dropped without a request.
 *
 * <h3>Threading model</h3>
 * <p>{@link #sendAudioChunk} only appends to an in-memory buffer under a short
 * lock (never blocks on I/O). A dedicated flush virtual thread wakes on the
 * interval, atomically swaps out the buffer, and performs the (blocking) HTTP
 * POST off the capture thread. Flushes are sequential, so transcripts are
 * emitted in the order they were spoken.
 */
public class GroqWhisperProvider implements SpeechToTextProvider {

    private static final Logger log = LoggerFactory.getLogger(GroqWhisperProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String API_URL = "https://api.groq.com/openai/v1/audio/transcriptions";
    private static final String DEFAULT_MODEL = "whisper-large-v3-turbo";
    private static final long DEFAULT_FLUSH_MS = 5_000;

    // 16-bit sample peak below which a whole window is treated as silence and
    // NOT sent — protects both Whisper accuracy (no silence hallucinations) and
    // the free-tier 2000-requests/day budget. ~500 sits above typical room noise
    // but well below speech.
    private static final int SILENCE_PEAK = 500;

    // Skip windows shorter than ~0.25 s of audio (8000 bytes @ 16 kHz/16-bit/mono):
    // nothing worth a request, and Whisper needs a little context to be useful.
    private static final int MIN_BYTES = 8_000;

    private final String apiKey;
    private final String channelId;
    private final String model;
    private final String language;   // ISO code (e.g. "en"); null/blank → Whisper auto-detects
    private final long flushMillis;

    private AudioFormat format;
    private Consumer<TranscriptEvent> onResult;
    private Consumer<String> onError;   // optional; friendly one-line messages for the UI
    private HttpClient httpClient;
    private Thread flushThread;

    private final Object bufLock = new Object();
    private ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public GroqWhisperProvider(String apiKey, String channelId) {
        this(apiKey, channelId, "en", DEFAULT_MODEL, DEFAULT_FLUSH_MS);
    }

    public GroqWhisperProvider(String apiKey, String channelId,
                               String language, String model, long flushMillis) {
        this.apiKey = apiKey;
        this.channelId = channelId;
        this.language = language;
        this.model = model;
        this.flushMillis = flushMillis;
    }

    /** Optional: receive friendly error strings (bad key, rate limit, network) for the UI. */
    public void setErrorListener(Consumer<String> onError) {
        this.onError = onError;
    }

    @Override
    public void start(AudioFormat format, Consumer<TranscriptEvent> onResult) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("GroqWhisperProvider already started");
        }
        this.format = format;
        this.onResult = onResult;
        this.httpClient = HttpClient.newHttpClient();
        this.flushThread = Thread.ofVirtual()
                .name("groq-whisper-" + channelId)
                .start(this::runFlushLoop);
        log.info("Groq Whisper provider started (channel={}, model={}, window={}ms)",
                channelId, model, flushMillis);
    }

    @Override
    public void sendAudioChunk(byte[] pcmData) {
        if (!running.get()) return;
        synchronized (bufLock) {
            buffer.write(pcmData, 0, pcmData.length);
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;

        if (flushThread != null) {
            flushThread.interrupt();
            try { flushThread.join(3_000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        // Transcribe whatever tail accumulated after the last interval fired.
        try { flushOnce(); }
        catch (Exception e) { log.warn("Final Groq flush failed (channel={}): {}", channelId, e.getMessage()); }

        if (httpClient != null) httpClient.close();
        log.info("Groq Whisper provider stopped (channel={})", channelId);
    }

    // ── Flush loop (virtual thread) ───────────────────────────────────────

    private void runFlushLoop() {
        while (running.get()) {
            try {
                Thread.sleep(flushMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;   // stop() performs the final flush
            }
            try {
                flushOnce();
            } catch (Exception e) {
                log.error("Groq flush error (channel={}): {}", channelId, e.getMessage());
            }
        }
    }

    /** Atomically drains the buffer and, if it holds speech, transcribes it. */
    private void flushOnce() throws Exception {
        byte[] pcm;
        synchronized (bufLock) {
            if (buffer.size() < MIN_BYTES) return;
            pcm = buffer.toByteArray();
            buffer.reset();
        }
        if (isSilent(pcm)) {
            log.debug("Skipping silent {}-byte window (channel={})", pcm.length, channelId);
            return;
        }
        String text = transcribe(pcm);
        if (text != null && !text.isBlank()) {
            onResult.accept(new TranscriptEvent(text.trim(), true, -1, channelId));
        }
    }

    private String transcribe(byte[] pcm) throws Exception {
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
        if (response.statusCode() == 200) {
            return extractText(response.body());
        }
        String msg = friendlyError(response.statusCode(), response.body());
        log.warn("Groq transcription failed (channel={}): {}", channelId, msg);
        if (onError != null) onError.accept(msg);
        return null;
    }

    private byte[] toWav(byte[] pcm) {
        byte[] header = WavFileWriter.wavHeader(format, pcm.length);
        byte[] out = new byte[header.length + pcm.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(pcm, 0, out, header.length, pcm.length);
        return out;
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

    /** True when the loudest 16-bit little-endian sample is below the silence floor. */
    static boolean isSilent(byte[] pcm16le) {
        for (int i = 0; i + 1 < pcm16le.length; i += 2) {
            int sample = (short) ((pcm16le[i] & 0xFF) | (pcm16le[i + 1] << 8));
            if (Math.abs(sample) >= SILENCE_PEAK) return false;
        }
        return true;
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
