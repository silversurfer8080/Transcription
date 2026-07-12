package org.example.stt;

import org.example.audio.WavFileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Base for STT providers backed by a <b>batch</b> (file) transcription API rather
 * than a streaming socket — e.g. Groq Whisper and Google Gemini.
 *
 * <p>A batch API takes a complete audio clip and returns one transcript. To fit the
 * streaming {@link SpeechToTextProvider} contract, this base buffers incoming PCM
 * and, every {@link #flushMillis} milliseconds, hands the accumulated window to the
 * subclass's {@link #transcribe(byte[])} on a dedicated flush virtual thread. Each
 * non-blank result is emitted as a single <b>final</b> {@link TranscriptEvent} —
 * there are no interim/partial events.
 *
 * <p>Shared behavior (kept identical to the original single-provider design):
 * <ul>
 *   <li>{@link #sendAudioChunk} only appends under a short lock and never blocks on I/O;</li>
 *   <li>silence gating via {@link #isSilent} drops windows that are pure silence
 *       (both to save the free-tier request budget and to avoid Whisper's
 *       "thank you" silence hallucinations);</li>
 *   <li>a {@link #MIN_BYTES} floor skips windows too short to be worth a request;</li>
 *   <li>{@link #stop()} interrupts the loop, joins it, then performs one final flush.</li>
 * </ul>
 *
 * <p>Subclasses implement only the provider-specific HTTP call in {@link #transcribe}
 * and may allocate/free resources in {@link #onStart}/{@link #onStop}.
 */
public abstract class BatchWindowSttProvider implements SpeechToTextProvider {

    private static final Logger log = LoggerFactory.getLogger(BatchWindowSttProvider.class);

    // 16-bit sample peak below which a whole window is treated as silence and NOT
    // sent. ~500 sits above typical room noise but well below speech.
    protected static final int SILENCE_PEAK = 500;

    // Skip windows shorter than ~0.25 s of audio (8000 bytes @ 16 kHz/16-bit/mono).
    protected static final int MIN_BYTES = 8_000;

    protected final String channelId;
    protected final long flushMillis;

    protected AudioFormat format;
    private Consumer<TranscriptEvent> onResult;
    private Consumer<String> onError;

    private Thread flushThread;
    private final Object bufLock = new Object();
    private ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    protected final AtomicBoolean running = new AtomicBoolean(false);

    protected BatchWindowSttProvider(String channelId, long flushMillis) {
        this.channelId = channelId;
        this.flushMillis = flushMillis;
    }

    @Override
    public void setErrorListener(Consumer<String> onError) {
        this.onError = onError;
    }

    // ── Subclass hooks ────────────────────────────────────────────────────

    /** Human-readable provider name for logs. */
    protected abstract String providerName();

    /** Name of the flush virtual thread. */
    protected String threadName() { return "stt-batch-" + channelId; }

    /** Initialise provider resources (e.g. an HTTP client). Called once from start(). */
    protected void onStart() {}

    /** Release provider resources. Called once from stop(). */
    protected void onStop() {}

    /**
     * Transcribes one non-silent PCM window (canonical 16 kHz / 16-bit / mono).
     * Returns the transcript, or {@code null}/blank if nothing usable was recognized.
     * On a provider error, report a friendly message via {@link #reportError} and return null.
     */
    protected abstract String transcribe(byte[] pcm) throws Exception;

    // ── Lifecycle (shared) ────────────────────────────────────────────────

    @Override
    public void start(AudioFormat format, Consumer<TranscriptEvent> onResult) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException(providerName() + " already started");
        }
        this.format = format;
        this.onResult = onResult;
        onStart();
        this.flushThread = Thread.ofVirtual().name(threadName()).start(this::runFlushLoop);
        log.info("{} started (channel={}, window={}ms)", providerName(), channelId, flushMillis);
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
        catch (Exception e) { log.warn("Final {} flush failed (channel={}): {}", providerName(), channelId, e.getMessage()); }

        onStop();
        log.info("{} stopped (channel={})", providerName(), channelId);
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
                log.error("{} flush error (channel={}): {}", providerName(), channelId, e.getMessage());
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

    // ── Shared helpers ────────────────────────────────────────────────────

    /** Surfaces a friendly one-line error to the UI, if a listener is wired. */
    protected void reportError(String msg) {
        if (onError != null) onError.accept(msg);
    }

    /** Wraps a PCM window in an in-memory WAV using the capture {@link #format}. */
    protected byte[] toWav(byte[] pcm) {
        byte[] header = WavFileWriter.wavHeader(format, pcm.length);
        byte[] out = new byte[header.length + pcm.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(pcm, 0, out, header.length, pcm.length);
        return out;
    }

    /** True when the loudest 16-bit little-endian sample is below the silence floor. */
    static boolean isSilent(byte[] pcm16le) {
        for (int i = 0; i + 1 < pcm16le.length; i += 2) {
            int sample = (short) ((pcm16le[i] & 0xFF) | (pcm16le[i + 1] << 8));
            if (Math.abs(sample) >= SILENCE_PEAK) return false;
        }
        return true;
    }
}
