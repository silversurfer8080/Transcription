package org.example.stt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.AudioFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fully offline speech-to-text via <a href="https://alphacephei.com/vosk/">Vosk</a>.
 *
 * <p>Unlike the HTTP providers, this runs a local model on the machine — no API key,
 * no per-day quota, no network — so it is the only backend that <b>never</b>
 * rate-limits. It is also the fix for the recurring "estourei o limite" problem and
 * for Whisper's "thank you" silence hallucinations (Vosk only emits what it hears).
 *
 * <p>Vosk is a true streaming recognizer: PCM is fed directly and it finalizes a
 * result at each end-of-utterance (a natural pause). To preserve the existing
 * {@link SpeechToTextProvider} contract — the rest of the app expects <b>final
 * events only</b> — this provider emits one final {@link TranscriptEvent} per
 * finalized utterance and does not surface interim partials.
 *
 * <p>The model must match the capture format (16 kHz mono, i.e. {@code Main.DEFAULT_FORMAT}).
 * Loading a model costs hundreds of MB and seconds of time, so models are cached
 * statically by directory and shared across sessions for the JVM's lifetime.
 */
public class VoskSttProvider implements SpeechToTextProvider {

    private static final Logger log = LoggerFactory.getLogger(VoskSttProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        // Vosk's native library is very chatty on stdout at INFO; quiet it.
        LibVosk.setLogLevel(LogLevel.WARNINGS);
    }

    // One Model per directory, reused across sessions (Model is thread-safe & heavy).
    private static final Map<String, Model> MODEL_CACHE = new HashMap<>();

    private final String modelPath;
    private final String channelId;

    private Recognizer recognizer;
    private Consumer<TranscriptEvent> onResult;
    private Consumer<String> onError;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public VoskSttProvider(String modelPath, String channelId) {
        this.modelPath = modelPath;
        this.channelId = channelId;
    }

    @Override
    public void setErrorListener(Consumer<String> onError) {
        this.onError = onError;
    }

    private static synchronized Model modelFor(String path) throws Exception {
        Model m = MODEL_CACHE.get(path);
        if (m == null) {
            m = new Model(path);   // throws if the directory isn't a valid Vosk model
            MODEL_CACHE.put(path, m);
        }
        return m;
    }

    @Override
    public void start(AudioFormat format, Consumer<TranscriptEvent> onResult) throws Exception {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("VoskSttProvider already started");
        }
        this.onResult = onResult;
        Model model = modelFor(modelPath);   // may block a few seconds on first load
        this.recognizer = new Recognizer(model, (float) format.getSampleRate());
        log.info("Vosk provider started (channel={}, model={}, rate={} Hz)",
                channelId, modelPath, (int) format.getSampleRate());
    }

    @Override
    public void sendAudioChunk(byte[] pcmData) {
        Recognizer r = recognizer;
        if (!running.get() || r == null) return;
        try {
            // acceptWaveForm returns true when an utterance just finalized.
            if (r.acceptWaveForm(pcmData, pcmData.length)) {
                emitFinal(r.getResult());
            }
            // Interim partials are intentionally not emitted (final-only contract).
        } catch (Exception e) {
            log.error("Vosk recognition error (channel={}): {}", channelId, e.getMessage());
            if (onError != null) onError.accept("Vosk: erro de reconhecimento — " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        Recognizer r = recognizer;
        recognizer = null;
        try {
            if (r != null) {
                emitFinal(r.getFinalResult());   // flush the last partial utterance
                r.close();
            }
        } catch (Exception e) {
            log.warn("Vosk final flush failed (channel={}): {}", channelId, e.getMessage());
        }
        // NB: the Model is cached & shared — do NOT close it here.
        log.info("Vosk provider stopped (channel={})", channelId);
    }

    /** Parses a Vosk result JSON ({@code {"text": "..."}}) and emits it as a final event. */
    private void emitFinal(String resultJson) {
        String text = field(resultJson, "text");
        if (!text.isBlank()) {
            onResult.accept(new TranscriptEvent(text.trim(), true, -1, channelId));
        }
    }

    private static String field(String json, String name) {
        try {
            return MAPPER.readTree(json).path(name).asText("");
        } catch (Exception e) {
            return "";
        }
    }
}
