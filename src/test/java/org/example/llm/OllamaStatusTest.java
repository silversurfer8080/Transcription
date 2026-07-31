package org.example.llm;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the pure {@link OllamaStatus} helper.
 * Headless, no network, no JavaFX — style of {@link AnalysisResultTest}.
 */
class OllamaStatusTest {

    // ── T1. nativeBaseUrl strips the completions suffix ───────────────────────

    @Test
    void nativeBaseUrl_stripsCompletionsSuffixFromLocalEndpoint() {
        String result = OllamaStatus.nativeBaseUrl(LlmProvider.LOCAL.endpoint());
        assertEquals("http://localhost:11434", result,
                "stripping /v1/chat/completions from LOCAL endpoint must yield the loopback root");
    }

    // ── T2. nativeBaseUrl returns input unchanged when suffix absent ──────────

    @Test
    void nativeBaseUrl_returnsInputUnchangedWhenSuffixAbsent() {
        String url = "http://other-server:8080";
        assertEquals(url, OllamaStatus.nativeBaseUrl(url),
                "a URL without the completions suffix must be returned as-is");
    }

    // ── T3. fromPsJson — target model, far-future expires_at → PINNED ─────────

    @Test
    void fromPsJson_farFutureExpiry_isPinned() {
        String json = """
                {"models":[
                  {"name":"qwen2.5:7b-instruct","model":"qwen2.5:7b-instruct",
                   "size_vram":4808888320,
                   "expires_at":"2318-07-30T12:34:56.000000Z"}
                ]}""";
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        OllamaStatus st = OllamaStatus.fromPsJson(json, "qwen2.5:7b-instruct", now);
        assertEquals(OllamaStatus.State.PINNED, st.state(), "year-2318 expiry must be treated as pinned");
        assertEquals(4808888320L, st.sizeVramBytes(), "size_vram must be parsed as long");
    }

    // ── T4. fromPsJson — finite expires_at = now + 120s → EXPIRING, remaining == 120 ──

    @Test
    void fromPsJson_finiteExpiry_isExpiringWithCorrectCountdown() {
        String json = """
                {"models":[
                  {"name":"qwen2.5:7b-instruct","model":"qwen2.5:7b-instruct",
                   "size_vram":100,
                   "expires_at":"2026-01-01T00:02:00Z"}
                ]}""";
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        OllamaStatus st = OllamaStatus.fromPsJson(json, "qwen2.5:7b-instruct", now);
        assertEquals(OllamaStatus.State.EXPIRING, st.state());
        assertEquals(120, st.remainingSeconds(), "remaining seconds must equal the gap to expires_at");
    }

    // ── T5. fromPsJson — model absent from non-empty models[] → UNLOADED ─────

    @Test
    void fromPsJson_modelAbsentFromNonEmptyList_isUnloaded() {
        String json = """
                {"models":[
                  {"name":"other-model","model":"other-model","size_vram":0,
                   "expires_at":"2318-07-30T12:34:56.000000Z"}
                ]}""";
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        OllamaStatus st = OllamaStatus.fromPsJson(json, "qwen2.5:7b-instruct", now);
        assertEquals(OllamaStatus.State.UNLOADED, st.state());
    }

    // ── T6. fromPsJson — empty models[] → UNLOADED ───────────────────────────

    @Test
    void fromPsJson_emptyModels_isUnloaded() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        OllamaStatus st = OllamaStatus.fromPsJson("{\"models\":[]}", "qwen2.5:7b-instruct", now);
        assertEquals(OllamaStatus.State.UNLOADED, st.state());
    }

    // ── T7. fromPsJson — malformed JSON → UNLOADED, no exception ─────────────

    @Test
    void fromPsJson_malformedJson_isUnloadedNoException() {
        assertDoesNotThrow(() -> {
            OllamaStatus st = OllamaStatus.fromPsJson("not-valid-json", "qwen2.5:7b-instruct", Instant.now());
            assertEquals(OllamaStatus.State.UNLOADED, st.state());
        });
    }

    // ── T8. fromPsJson — expires_at in the past → EXPIRING, remainingSeconds == 0 ──

    @Test
    void fromPsJson_pastExpiry_isExpiringWithZeroRemaining() {
        String json = """
                {"models":[
                  {"name":"qwen2.5:7b-instruct","model":"qwen2.5:7b-instruct",
                   "size_vram":100,
                   "expires_at":"2020-01-01T00:00:00Z"}
                ]}""";
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        OllamaStatus st = OllamaStatus.fromPsJson(json, "qwen2.5:7b-instruct", now);
        assertEquals(OllamaStatus.State.EXPIRING, st.state(),
                "a past expiry still means the model was listed — EXPIRING not UNLOADED");
        assertEquals(0, st.remainingSeconds(), "remaining seconds must be clamped to 0 for past expiry");
    }

    // ── T9. fromPsJson — matches on model field when name differs ─────────────

    @Test
    void fromPsJson_matchesOnModelFieldWhenNameDiffers() {
        String json = """
                {"models":[
                  {"name":"different-display-name","model":"qwen2.5:7b-instruct",
                   "size_vram":500,
                   "expires_at":"2318-07-30T12:34:56.000000Z"}
                ]}""";
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        OllamaStatus st = OllamaStatus.fromPsJson(json, "qwen2.5:7b-instruct", now);
        assertEquals(OllamaStatus.State.PINNED, st.state(),
                "matching on the model field (not name) must still yield PINNED");
    }

    // ── T10. formatCountdown — boundary and representative values ─────────────

    @Test
    void formatCountdown_variousValues() {
        assertEquals("0:00",  OllamaStatus.formatCountdown(0),    "zero seconds");
        assertEquals("0:00",  OllamaStatus.formatCountdown(-5),   "negative seconds clamped to 0:00");
        assertEquals("0:05",  OllamaStatus.formatCountdown(5),    "5 seconds");
        assertEquals("1:05",  OllamaStatus.formatCountdown(65),   "65 seconds = 1 min 5 s");
        assertEquals("10:00", OllamaStatus.formatCountdown(600),  "600 seconds = 10 min");
    }

    // ── T11 (optional). fromPsJson — blank expires_at → PINNED, remainingSeconds == 0 ──

    @Test
    void fromPsJson_blankExpiresAt_isPinnedWithZeroRemaining() {
        String json = """
                {"models":[
                  {"name":"qwen2.5:7b-instruct","model":"qwen2.5:7b-instruct",
                   "size_vram":200,
                   "expires_at":""}
                ]}""";
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        OllamaStatus st = OllamaStatus.fromPsJson(json, "qwen2.5:7b-instruct", now);
        assertEquals(OllamaStatus.State.PINNED, st.state(),
                "blank expires_at with model present must be treated as PINNED, not UNLOADED");
        assertEquals(0, st.remainingSeconds());
    }

    // ── T12. unavailable() sentinel — state UNAVAILABLE, fields zeroed ────────
    // The UNAVAILABLE state is produced only by the impure layer (OllamaKeepAlive) on
    // a failed HTTP call. fromPsJson never returns it. Verify the factory produces
    // the correct sentinel so callers can switch on state reliably.

    @Test
    void unavailable_sentinelHasCorrectStateAndZeroFields() {
        OllamaStatus st = OllamaStatus.unavailable();
        assertEquals(OllamaStatus.State.UNAVAILABLE, st.state(),
                "unavailable() must carry State.UNAVAILABLE");
        assertEquals(0, st.remainingSeconds(),
                "unavailable() remainingSeconds must be 0");
        assertEquals(0, st.sizeVramBytes(),
                "unavailable() sizeVramBytes must be 0");
    }

    // ── T13. fromPsJson — matches on name field when model field differs ──────
    // Symmetric complement of T9: the spec says match is on name OR model field
    // (Ollama returns both; either suffices). T9 covers model-field match; this
    // covers name-field match when the model field carries a different value.

    @Test
    void fromPsJson_matchesOnNameFieldWhenModelDiffers() {
        String json = """
                {"models":[
                  {"name":"qwen2.5:7b-instruct","model":"different-internal-id",
                   "size_vram":800,
                   "expires_at":"2318-07-30T12:34:56.000000Z"}
                ]}""";
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        OllamaStatus st = OllamaStatus.fromPsJson(json, "qwen2.5:7b-instruct", now);
        assertEquals(OllamaStatus.State.PINNED, st.state(),
                "matching on the name field (not model) must also yield PINNED");
        assertEquals(800, st.sizeVramBytes());
    }

    // ── T14. fromPsJson — non-blank unparseable expires_at → PINNED ──────────
    // Spec (§5.1 E4): "Parse failure or blank -> treat as PINNED, remainingSeconds=0".
    // T11 covers the blank case; this covers the non-blank, non-parseable case
    // (the inner catch block in fromPsJson at the OffsetDateTime.parse call site).

    @Test
    void fromPsJson_nonBlankUnparseableExpiresAt_isPinned() {
        String json = """
                {"models":[
                  {"name":"qwen2.5:7b-instruct","model":"qwen2.5:7b-instruct",
                   "size_vram":300,
                   "expires_at":"not-a-valid-timestamp"}
                ]}""";
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        OllamaStatus st = OllamaStatus.fromPsJson(json, "qwen2.5:7b-instruct", now);
        assertEquals(OllamaStatus.State.PINNED, st.state(),
                "non-blank but unparseable expires_at must be treated as PINNED, not UNLOADED");
        assertEquals(0, st.remainingSeconds());
    }

    // ── T15. fromPsJson — valid JSON without models key → UNLOADED ───────────
    // Distinct from malformed JSON (T7) and empty array (T6): valid JSON object
    // with no "models" key at all. The implementation guards: !models.isArray()
    // returns true for a MissingNode, yielding unloaded().

    @Test
    void fromPsJson_validJsonWithNoModelsKey_isUnloaded() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        OllamaStatus st = OllamaStatus.fromPsJson("{\"other\":\"value\"}", "qwen2.5:7b-instruct", now);
        assertEquals(OllamaStatus.State.UNLOADED, st.state(),
                "valid JSON without a models key must be treated as UNLOADED");
    }

    // ── T16. formatCountdown — minutes exceeding 59 ───────────────────────────
    // Spec (§5.1): "minutes not zero-padded and may exceed 59, e.g. 63:20".
    // The existing T10 covers up to 10:00; this verifies the format does not wrap
    // at 60 min (i.e., no modulo on minutes — raw secs/60 is used directly).

    @Test
    void formatCountdown_minutesExceedingSixtyNotWrapped() {
        // 3800 s = 63 min 20 s
        assertEquals("63:20", OllamaStatus.formatCountdown(3800),
                "formatCountdown must not wrap minutes at 60: 3800s must yield 63:20");
    }
}
