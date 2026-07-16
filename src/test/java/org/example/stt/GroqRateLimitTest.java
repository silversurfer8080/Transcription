package org.example.stt;

import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link GroqRateLimit#parse} — turning Groq's {@code x-ratelimit-*} response headers
 * into a usable record. Headers may be missing or non-numeric (Groq can change them), so the
 * parser must degrade to {@code null} per field rather than throw.
 */
class GroqRateLimitTest {

    private static HttpHeaders headers(Map<String, String> values) {
        Map<String, List<String>> multi = new java.util.HashMap<>();
        values.forEach((k, v) -> multi.put(k, List.of(v)));
        return HttpHeaders.of(multi, (k, v) -> true);
    }

    @Test
    void parse_allHeaders_populatesAndComputesFractions() {
        GroqRateLimit rl = GroqRateLimit.parse(headers(Map.of(
                "x-ratelimit-remaining-requests", "1000",
                "x-ratelimit-limit-requests", "2000",
                "x-ratelimit-remaining-audio-seconds", "3600",
                "x-ratelimit-limit-audio-seconds", "7200")));
        assertTrue(rl.hasRequests());
        assertTrue(rl.hasAudio());
        assertTrue(rl.hasData());
        assertEquals(0.5, rl.requestsFraction(), 1e-9, "1000/2000 = 0.5");
        assertEquals(0.5, rl.audioFraction(), 1e-9, "3600/7200 = 0.5");
    }

    @Test
    void parse_onlyRequestHeaders_audioAbsent() {
        GroqRateLimit rl = GroqRateLimit.parse(headers(Map.of(
                "x-ratelimit-remaining-requests", "1999",
                "x-ratelimit-limit-requests", "2000")));
        assertTrue(rl.hasRequests());
        assertFalse(rl.hasAudio(), "no audio headers → audio unavailable");
        assertTrue(rl.hasData());
        assertEquals(0.0, rl.audioFraction(), 1e-9, "absent audio fraction is 0, not NaN");
    }

    @Test
    void parse_noRateLimitHeaders_hasNoData() {
        GroqRateLimit rl = GroqRateLimit.parse(headers(Map.of("content-type", "application/json")));
        assertFalse(rl.hasData());
        assertFalse(rl.hasRequests());
        assertFalse(rl.hasAudio());
    }

    @Test
    void parse_nonNumericHeader_yieldsNullNotThrow() {
        GroqRateLimit rl = GroqRateLimit.parse(headers(Map.of(
                "x-ratelimit-remaining-requests", "n/a",
                "x-ratelimit-limit-requests", "2000")));
        assertFalse(rl.hasRequests(), "a non-numeric remaining must not count as usable");
        assertNull(rl.remainingRequests());
        assertEquals(2000.0, rl.limitRequests());
    }

    @Test
    void fractions_clampWhenRemainingExceedsOrBelowLimit() {
        GroqRateLimit over = GroqRateLimit.parse(headers(Map.of(
                "x-ratelimit-remaining-requests", "2500",
                "x-ratelimit-limit-requests", "2000")));
        assertEquals(1.0, over.requestsFraction(), 1e-9, "fraction clamps at 1.0");

        GroqRateLimit zero = GroqRateLimit.parse(headers(Map.of(
                "x-ratelimit-remaining-requests", "0",
                "x-ratelimit-limit-requests", "2000")));
        assertEquals(0.0, zero.requestsFraction(), 1e-9);
    }

    @Test
    void parse_fractionalAudioSeconds_parsed() {
        GroqRateLimit rl = GroqRateLimit.parse(headers(Map.of(
                "x-ratelimit-remaining-audio-seconds", "7195.5",
                "x-ratelimit-limit-audio-seconds", "7200")));
        assertTrue(rl.hasAudio());
        assertEquals(7195.5, rl.remainingAudioSeconds(), 1e-9);
    }
}
