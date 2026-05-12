package org.example.stt;

import javax.sound.sampled.AudioFormat;
import java.util.function.Consumer;

/**
 * Pluggable speech-to-text backend.
 *
 * <p>Lifecycle: {@link #start} → repeated {@link #sendAudioChunk} → {@link #stop}.
 * Each audio channel gets its own instance running on its own virtual thread.
 *
 * <p>Thread-safety contract: {@code sendAudioChunk} is called from the audio
 * capture virtual thread; {@code onResult} callbacks arrive on an
 * implementation-defined thread and must not assume the FX thread.
 */
public interface SpeechToTextProvider extends AutoCloseable {

    /**
     * Opens the connection / initialises the engine.
     *
     * @param format   the PCM format of chunks that will be sent via {@link #sendAudioChunk}
     * @param onResult called for every interim and final {@link TranscriptEvent}
     */
    void start(AudioFormat format, Consumer<TranscriptEvent> onResult) throws Exception;

    /**
     * Forwards a raw PCM chunk to the provider.
     * Must not block for more than a few milliseconds.
     */
    void sendAudioChunk(byte[] pcmData);

    /** Flushes, closes the connection, and releases resources. Idempotent. */
    void stop();

    @Override
    default void close() {
        stop();
    }
}
