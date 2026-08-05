package org.example.stt;

import org.example.audio.WavFileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
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

    // 16 kHz / 16-bit / mono canonical capture -> 32000 bytes per audio-second.
    private static final int BYTES_PER_SECOND = 16_000 * 2;
    // With a window cap set, once the backlog grows past this many windows the oldest audio is
    // dropped to bound memory and latency. A runaway only happens if the backend is slower than
    // real time; a warning is logged so it surfaces in the structured logs.
    private static final int BACKLOG_WINDOWS = 12;
    private static final byte[] EMPTY = new byte[0];

    protected final String channelId;
    protected final long flushMillis;
    // Cap on the audio-bytes handed to transcribe() per flush (0 = unlimited). A slow backend
    // otherwise gets ever-larger clips (whole-buffer drain), so each transcription takes longer
    // and the lag spirals. With a cap set, windows stay bounded and the loop catches up
    // back-to-back. Cloud providers leave this 0, keeping their exact original behavior.
    protected final int maxWindowBytes;
    private final int maxBacklogBytes;

    protected AudioFormat format;
    private Consumer<TranscriptEvent> onResult;
    private Consumer<String> onError;

    private Thread flushThread;
    private final Object bufLock = new Object();
    private ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    protected final AtomicBoolean running = new AtomicBoolean(false);

    protected BatchWindowSttProvider(String channelId, long flushMillis) {
        this(channelId, flushMillis, 0);
    }

    /**
     * @param maxWindowBytes cap on the PCM bytes transcribed per flush (0 = unlimited, the
     *                       whole-buffer drain used by the cloud providers). A local/slow
     *                       backend should cap this (e.g. 5 s) so a lagging backend never gets
     *                       ever-larger clips.
     */
    protected BatchWindowSttProvider(String channelId, long flushMillis, int maxWindowBytes) {
        this.channelId = channelId;
        this.flushMillis = flushMillis;
        this.maxWindowBytes = Math.max(0, maxWindowBytes);
        this.maxBacklogBytes = this.maxWindowBytes > 0 ? this.maxWindowBytes * BACKLOG_WINDOWS : 0;
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
            // Sleep the normal interval only when caught up. When a full window is already
            // backlogged (a slow backend), process windows back-to-back to catch up instead of
            // waiting another interval. Uncapped providers always sleep (unchanged cadence).
            if (!hasFullWindowBacklog()) {
                try {
                    Thread.sleep(flushMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;   // stop() performs the final flush
                }
            }
            try {
                flushOnce();
            } catch (Exception e) {
                log.error("{} flush error (channel={}): {}", providerName(), channelId, e.getMessage());
            }
        }
    }

    private boolean hasFullWindowBacklog() {
        if (maxWindowBytes <= 0) return false;   // uncapped providers keep the fixed cadence
        synchronized (bufLock) {
            return buffer.size() >= maxWindowBytes;
        }
    }

    /**
     * Atomically drains up to one window from the buffer and, if it holds speech, transcribes it.
     * With {@link #maxWindowBytes} set, the window is capped (the excess stays buffered for the
     * next flush) and a runaway backlog drops its oldest audio; with it 0 the whole buffer is
     * drained as before.
     */
    private void flushOnce() throws Exception {
        byte[] pcm;
        int droppedBytes;
        synchronized (bufLock) {
            if (buffer.size() < MIN_BYTES) return;
            byte[] all = buffer.toByteArray();
            buffer.reset();
            WindowSplit split = splitWindow(all, maxWindowBytes, maxBacklogBytes);
            pcm = split.window();
            droppedBytes = split.droppedBytes();
            if (split.remainder().length > 0) {
                buffer.write(split.remainder(), 0, split.remainder().length);   // re-buffer the excess
            }
        }
        if (droppedBytes > 0) {
            log.warn("{} fell behind real time; dropped {}s of oldest audio to catch up (channel={})",
                    providerName(), String.format("%.1f", droppedBytes / (double) BYTES_PER_SECOND), channelId);
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

    /** The split of a drained buffer into the window to transcribe now, the excess to re-buffer,
     *  and how many oldest bytes were dropped as a runaway-backlog safety valve. */
    record WindowSplit(byte[] window, byte[] remainder, int droppedBytes) {}

    /**
     * Splits {@code all} into (window, remainder, droppedBytes). With {@code maxWindowBytes <= 0}
     * the whole array is the window (unchanged behavior). Otherwise: if the backlog exceeds
     * {@code maxBacklogBytes} the oldest excess is dropped; then at most {@code maxWindowBytes}
     * (the oldest remaining) becomes the window and the rest is the remainder to re-buffer.
     */
    static WindowSplit splitWindow(byte[] all, int maxWindowBytes, int maxBacklogBytes) {
        if (maxWindowBytes <= 0) return new WindowSplit(all, EMPTY, 0);
        int start = 0;
        if (maxBacklogBytes > 0 && all.length > maxBacklogBytes) {
            start = all.length - maxBacklogBytes;   // keep only the most recent maxBacklogBytes
        }
        if (all.length - start > maxWindowBytes) {
            byte[] window    = Arrays.copyOfRange(all, start, start + maxWindowBytes);
            byte[] remainder = Arrays.copyOfRange(all, start + maxWindowBytes, all.length);
            return new WindowSplit(window, remainder, start);
        }
        byte[] window = (start == 0) ? all : Arrays.copyOfRange(all, start, all.length);
        return new WindowSplit(window, EMPTY, start);
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
