package org.example.stt;

/**
 * Immutable result from a speech-to-text provider.
 *
 * @param text       transcribed text; may be empty for silence
 * @param isFinal    false = interim/partial (will be replaced); true = committed
 * @param confidence 0.0–1.0 as reported by the provider; -1 if unavailable
 * @param channelId  opaque label for the audio channel ("mic", "candidate", etc.)
 */
public record TranscriptEvent(
        String text,
        boolean isFinal,
        double confidence,
        String channelId) {}
