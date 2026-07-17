package org.example.usage;

import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link RateLimit#parse} — turning the {@code x-ratelimit-*} headers into a reading,
 * picking the secondary header by {@link ApiKind} (audio-seconds for STT, tokens for LLM).
 * Headers may be missing or non-numeric (Gemini sends none), so each field degrades to null.
 */
class RateLimitTest {

    private static HttpHeaders headers(Map<String, String> values) {
        Map<String, List<String>> multi = new java.util.HashMap<>();
        values.forEach((k, v) -> multi.put(k, List.of(v)));
        return HttpHeaders.of(multi, (k, v) -> true);
    }

    @Test
    void parse_stt_readsRequestsAndAudioSeconds() {
        RateLimit rl = RateLimit.parse(headers(Map.of(
                "x-ratelimit-remaining-requests", "1000",
                "x-ratelimit-limit-requests", "2000",
                "x-ratelimit-remaining-audio-seconds", "3600",
                "x-ratelimit-limit-audio-seconds", "7200")), ApiKind.STT);
        assertTrue(rl.hasRequests());
        assertTrue(rl.hasSecondary());
        assertEquals(0.5, rl.requestsFraction(), 1e-9);
        assertEquals(0.5, rl.secondaryFraction(), 1e-9);
    }

    @Test
    void parse_llm_readsTokensAsSecondary() {
        RateLimit rl = RateLimit.parse(headers(Map.of(
                "x-ratelimit-remaining-requests", "990",
                "x-ratelimit-limit-requests", "1000",
                "x-ratelimit-remaining-tokens", "50000",
                "x-ratelimit-limit-tokens", "100000")), ApiKind.LLM);
        assertTrue(rl.hasSecondary(), "LLM secondary comes from the tokens headers");
        assertEquals(0.5, rl.secondaryFraction(), 1e-9);
    }

    @Test
    void parse_llm_ignoresAudioHeadersForTokens() {
        // An STT-style audio header must NOT satisfy the LLM secondary (which is tokens).
        RateLimit rl = RateLimit.parse(headers(Map.of(
                "x-ratelimit-remaining-audio-seconds", "3600",
                "x-ratelimit-limit-audio-seconds", "7200")), ApiKind.LLM);
        assertFalse(rl.hasSecondary(), "LLM must not read audio-seconds as its secondary unit");
    }

    @Test
    void parse_onlyRequests_secondaryAbsent() {
        RateLimit rl = RateLimit.parse(headers(Map.of(
                "x-ratelimit-remaining-requests", "1999",
                "x-ratelimit-limit-requests", "2000")), ApiKind.STT);
        assertTrue(rl.hasRequests());
        assertFalse(rl.hasSecondary());
        assertEquals(0.0, rl.secondaryFraction(), 1e-9);
    }

    @Test
    void parse_noHeaders_hasNoData() {
        RateLimit rl = RateLimit.parse(headers(Map.of("content-type", "application/json")), ApiKind.LLM);
        assertFalse(rl.hasData());
    }

    @Test
    void parse_nonNumeric_yieldsNull() {
        RateLimit rl = RateLimit.parse(headers(Map.of(
                "x-ratelimit-remaining-requests", "n/a",
                "x-ratelimit-limit-requests", "2000")), ApiKind.STT);
        assertFalse(rl.hasRequests());
        assertNull(rl.remainingRequests());
    }

    @Test
    void fractions_clamp() {
        RateLimit over = RateLimit.parse(headers(Map.of(
                "x-ratelimit-remaining-requests", "2500",
                "x-ratelimit-limit-requests", "2000")), ApiKind.STT);
        assertEquals(1.0, over.requestsFraction(), 1e-9);
    }

    @Test
    void empty_hasNoData() {
        assertFalse(RateLimit.empty().hasData());
        assertFalse(RateLimit.empty().hasRequests());
        assertFalse(RateLimit.empty().hasSecondary());
    }
}
