package org.example.usage;

import java.net.http.HttpHeaders;

/**
 * Remaining API quota parsed from the {@code x-ratelimit-*} response headers that
 * OpenAI-compatible providers (Groq, Cerebras) return on every call.
 *
 * <p>Two limits apply: a plain <b>requests</b> budget and a <b>secondary</b> budget whose
 * unit depends on the endpoint — audio-seconds for transcription, tokens for chat (see
 * {@link ApiKind#rateLimitUnit()}). Any field may be {@code null} when the header is absent
 * or unparseable — notably Gemini's compatibility layer does not send these headers, so its
 * {@code RateLimit} is empty and only session usage counters are available for it.
 */
public record RateLimit(Double remainingRequests, Double limitRequests,
                        Double remainingSecondary, Double limitSecondary) {

    public boolean hasRequests() {
        return limitRequests != null && limitRequests > 0 && remainingRequests != null;
    }

    public boolean hasSecondary() {
        return limitSecondary != null && limitSecondary > 0 && remainingSecondary != null;
    }

    public boolean hasData() {
        return hasRequests() || hasSecondary();
    }

    public double requestsFraction() {
        return hasRequests() ? clamp(remainingRequests / limitRequests) : 0.0;
    }

    public double secondaryFraction() {
        return hasSecondary() ? clamp(remainingSecondary / limitSecondary) : 0.0;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** An empty reading (no headers) — used for providers that don't expose rate limits. */
    public static RateLimit empty() {
        return new RateLimit(null, null, null, null);
    }

    /**
     * Parses the rate-limit headers for the given endpoint kind: always the
     * {@code *-requests} pair, plus the {@code *-<unit>} pair where the unit is
     * {@link ApiKind#rateLimitUnit()} ({@code audio-seconds} or {@code tokens}). Missing or
     * non-numeric headers become {@code null} (never throws).
     */
    public static RateLimit parse(HttpHeaders headers, ApiKind kind) {
        String unit = kind.rateLimitUnit();
        return new RateLimit(
                num(headers, "x-ratelimit-remaining-requests"),
                num(headers, "x-ratelimit-limit-requests"),
                num(headers, "x-ratelimit-remaining-" + unit),
                num(headers, "x-ratelimit-limit-" + unit));
    }

    private static Double num(HttpHeaders headers, String name) {
        return headers.firstValue(name).map(String::trim).map(RateLimit::tryParse).orElse(null);
    }

    private static Double tryParse(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
