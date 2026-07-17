package org.example.stt;

import java.net.http.HttpHeaders;

/**
 * Remaining Groq free-tier quota, parsed from the {@code x-ratelimit-*} response headers
 * Groq returns on every transcription call.
 *
 * <p>Two independent limits apply to the Whisper endpoint: <b>requests/day</b> and
 * <b>audio-seconds/hour</b>. Any field may be {@code null} when the corresponding header is
 * absent or unparseable, so callers must null-check (via {@link #hasRequests()} /
 * {@link #hasAudio()}) before using a value. All fields are stored as {@code Double} so the
 * same parser handles the integer request counts and the (possibly fractional) audio seconds.
 */
public record GroqRateLimit(Double remainingRequests, Double limitRequests,
                            Double remainingAudioSeconds, Double limitAudioSeconds) {

    /** True when a usable requests/day limit + remaining pair is present. */
    public boolean hasRequests() {
        return limitRequests != null && limitRequests > 0 && remainingRequests != null;
    }

    /** True when a usable audio-seconds/hour limit + remaining pair is present. */
    public boolean hasAudio() {
        return limitAudioSeconds != null && limitAudioSeconds > 0 && remainingAudioSeconds != null;
    }

    /** True when at least one of the two limits was reported. */
    public boolean hasData() {
        return hasRequests() || hasAudio();
    }

    /** Remaining requests as a fraction of the limit, clamped to [0, 1]. */
    public double requestsFraction() {
        return hasRequests() ? clamp(remainingRequests / limitRequests) : 0.0;
    }

    /** Remaining audio-seconds as a fraction of the limit, clamped to [0, 1]. */
    public double audioFraction() {
        return hasAudio() ? clamp(remainingAudioSeconds / limitAudioSeconds) : 0.0;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /**
     * Parses the four rate-limit headers from a Groq response. Missing or non-numeric
     * headers become {@code null} (never throws), so a partial or changed header set still
     * yields a usable record.
     */
    public static GroqRateLimit parse(HttpHeaders headers) {
        return new GroqRateLimit(
                num(headers, "x-ratelimit-remaining-requests"),
                num(headers, "x-ratelimit-limit-requests"),
                num(headers, "x-ratelimit-remaining-audio-seconds"),
                num(headers, "x-ratelimit-limit-audio-seconds"));
    }

    private static Double num(HttpHeaders headers, String name) {
        return headers.firstValue(name)
                .map(String::trim)
                .map(GroqRateLimit::tryParse)
                .orElse(null);
    }

    private static Double tryParse(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
