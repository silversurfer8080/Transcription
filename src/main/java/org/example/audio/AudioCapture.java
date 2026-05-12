package org.example.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Captures PCM audio from a selected mixer on a virtual thread and dispatches
 * fixed-size chunks to a {@link Consumer}.
 *
 * <h3>Format negotiation</h3>
 * <p>The {@code format} passed to the constructor is the <em>output</em> format —
 * what the consumer receives. If the device does not natively support that format
 * (common with virtual cables such as VB-Audio, which only expose 44.1/48 kHz stereo),
 * {@link #start()} negotiates the closest available PCM format and sets up an
 * {@link AudioResampler} to down-mix and re-sample the raw capture to the requested
 * output format transparently. The consumer always sees the requested format.
 *
 * <h3>Why a virtual thread?</h3>
 * <p>{@link TargetDataLine#read} is a blocking I/O call — exactly the workload
 * virtual threads are designed for. Each channel (mic, candidate monitor) gets its
 * own capture instance, so a dual-channel session runs two parked-on-I/O threads
 * before the STT WebSockets even enter the picture.
 *
 * <h3>Chunk size</h3>
 * <p>We default to ~100 ms of audio at the <em>output</em> format rate. Smaller chunks
 * mean more syscalls and JNI hops; larger chunks inflate end-to-end latency for live
 * transcription. 100 ms is the sweet spot most streaming STT vendors recommend.
 *
 * <h3>Lifecycle</h3>
 * <p>{@link #start()} opens the line and spawns the reader. {@link #stop()} (also called
 * by {@link #close()}) flips an {@link AtomicBoolean}, closes the line — which unblocks
 * the in-flight {@code read()} — and joins the reader thread. Idempotent.
 */
public class AudioCapture implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AudioCapture.class);

    /** Default chunk duration in milliseconds. See class javadoc for rationale. */
    public static final int DEFAULT_CHUNK_MILLIS = 100;

    private final Mixer.Info mixerInfo;
    private final AudioFormat format;         // desired output format (what consumer receives)
    private final Consumer<byte[]> chunkConsumer;
    private final int chunkSize;              // output-format chunk size in bytes (~100 ms)
    private final AtomicBoolean running = new AtomicBoolean(false);

    private TargetDataLine line;
    private Thread captureThread;
    private AudioFormat nativeFormat;         // actual device format; set during start()
    private AudioResampler resampler;         // non-null when nativeFormat != format

    public AudioCapture(Mixer.Info mixerInfo,
                        AudioFormat format,
                        Consumer<byte[]> chunkConsumer) {
        this(mixerInfo, format, chunkConsumer, DEFAULT_CHUNK_MILLIS);
    }

    public AudioCapture(Mixer.Info mixerInfo,
                        AudioFormat format,
                        Consumer<byte[]> chunkConsumer,
                        int chunkMillis) {
        this.mixerInfo = Objects.requireNonNull(mixerInfo, "mixerInfo");
        this.format = Objects.requireNonNull(format, "format");
        this.chunkConsumer = Objects.requireNonNull(chunkConsumer, "chunkConsumer");
        if (chunkMillis <= 0) throw new IllegalArgumentException("chunkMillis must be > 0");
        this.chunkSize = bytesFor(format, chunkMillis);
    }

    /**
     * Opens the audio line and starts the capture loop.
     *
     * <p>Negotiates the native device format if the preferred format is not directly
     * supported, and configures an internal resampler as needed.
     *
     * @throws IllegalStateException     if already started
     * @throws LineUnavailableException  if no usable PCM format can be opened on the mixer
     */
    public void start() throws LineUnavailableException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("AudioCapture already running");
        }
        try {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            // Opens the best available line, setting this.line and this.nativeFormat.
            openBestLine(mixer);

            if (!nativeFormat.equals(format)) {
                resampler = new AudioResampler(nativeFormat, format);
                log.info("Format mismatch on '{}': native={}, target={} — resampling enabled",
                        mixerInfo.getName(), nativeFormat, format);
            }
        } catch (LineUnavailableException e) {
            running.set(false);
            throw e;
        }

        captureThread = Thread.ofVirtual()
                .name("audio-capture-" + sanitize(mixerInfo.getName()))
                .start(this::readLoop);

        log.info("Capture started: device='{}', native={}, output={}, outputChunkBytes={}",
                mixerInfo.getName(), nativeFormat, format, chunkSize);
    }

    private void readLoop() {
        int nativeChunkBytes = bytesFor(nativeFormat, DEFAULT_CHUNK_MILLIS);
        byte[] buffer = new byte[nativeChunkBytes];
        try {
            while (running.get()) {
                int read = line.read(buffer, 0, buffer.length);
                if (read <= 0) continue;

                // Defensive copy so the consumer may queue it asynchronously.
                byte[] raw = (read == buffer.length)
                        ? buffer.clone()
                        : java.util.Arrays.copyOf(buffer, read);

                byte[] chunk = (resampler != null) ? resampler.convert(raw) : raw;
                if (chunk.length == 0) continue;  // resampler may return empty on tiny reads

                try {
                    chunkConsumer.accept(chunk);
                } catch (RuntimeException ex) {
                    log.error("Chunk consumer threw — dropping chunk and continuing", ex);
                }
            }
        } catch (Exception ex) {
            log.error("Capture loop terminated unexpectedly", ex);
        }
    }

    /** Stops capture and releases the underlying line. Idempotent. */
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        try {
            if (line != null) {
                line.stop();
                line.close(); // unblocks any in-flight read()
            }
            if (captureThread != null) {
                captureThread.join(2_000);
                if (captureThread.isAlive()) log.warn("Capture thread did not exit within 2s");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        log.info("Capture stopped: device='{}'", mixerInfo.getName());
    }

    @Override
    public void close() { stop(); }

    public boolean isRunning() { return running.get(); }

    /** Output-format chunk size in bytes (approximately {@link #DEFAULT_CHUNK_MILLIS} ms). */
    public int chunkSize() { return chunkSize; }

    /** The format actually opened on the device; available after {@link #start()}. */
    public AudioFormat nativeFormat() { return nativeFormat; }

    // ---- format negotiation ----------------------------------------------

    /**
     * Tries each candidate format in order, opening the first one the driver accepts.
     *
     * <p>Windows/DirectSound's {@link Mixer#isLineSupported} is unreliable — it can
     * claim support for a format (e.g. 16 kHz mono) that the underlying driver rejects
     * when {@link TargetDataLine#open} is actually called. We therefore probe by
     * attempting a real open rather than trusting the capability query.
     */
    private void openBestLine(Mixer mixer) throws LineUnavailableException {
        // Build candidate list: desired format first, then common Windows rates.
        java.util.List<AudioFormat> candidates = new java.util.ArrayList<>();
        candidates.add(format);
        for (float rate : new float[]{44100f, 48000f, 96000f, 22050f}) {
            for (int ch : new int[]{2, 1}) {
                candidates.add(new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED, rate, 16, ch, ch * 2, rate, false));
            }
        }
        // Append driver-reported formats (wildcard fields excluded).
        for (Line.Info li : mixer.getTargetLineInfo()) {
            if (li instanceof DataLine.Info dli) {
                for (AudioFormat fmt : dli.getFormats()) {
                    if (AudioFormat.Encoding.PCM_SIGNED.equals(fmt.getEncoding())
                            && fmt.getSampleSizeInBits() == 16
                            && !fmt.isBigEndian()
                            && fmt.getSampleRate() > 0
                            && fmt.getChannels() > 0) {
                        candidates.add(fmt);
                    }
                }
            }
        }

        for (AudioFormat candidate : candidates) {
            DataLine.Info dlInfo = new DataLine.Info(TargetDataLine.class, candidate);
            try {
                TargetDataLine l = (TargetDataLine) mixer.getLine(dlInfo);
                int nativeChunk = bytesFor(candidate, DEFAULT_CHUNK_MILLIS);
                l.open(candidate, nativeChunk * 4);
                l.start();
                this.line         = l;
                this.nativeFormat = candidate;
                return;
            } catch (LineUnavailableException | IllegalArgumentException e) {
                log.debug("Format {} rejected on '{}': {}", candidate, mixerInfo.getName(), e.getMessage());
            }
        }

        throw new LineUnavailableException(
                "No usable 16-bit PCM format found for '" + mixerInfo.getName()
                + "'. If the device is already in use, check that Mic and Candidate "
                + "are set to different audio devices.");
    }

    // ---- utilities -------------------------------------------------------

    private static int bytesFor(AudioFormat fmt, int millis) {
        int bytesPerSecond = (int) (fmt.getSampleRate() * fmt.getFrameSize());
        return Math.max(bytesPerSecond * millis / 1000, fmt.getFrameSize());
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
