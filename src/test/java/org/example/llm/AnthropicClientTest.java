package org.example.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AnthropicClient} helper logic.
 *
 * Note: Anthropic error bodies contain both "type" and "message" inside the
 * error object, producing a "type: message" formatted string. This differs from
 * Groq/OpenAI, which only surface a "message" field.
 */
class AnthropicClientTest {

    // ── buildErrorMessage ─────────────────────────────────────────────────────

    @Test
    void buildErrorMessage_typeAndMessage_formatsTypePlusMessage() {
        String body = "{\"error\":{\"type\":\"authentication_error\",\"message\":\"Invalid API key.\"}}";
        assertEquals("HTTP_401 — authentication_error: Invalid API key.",
                AnthropicClient.buildErrorMessage(401, body));
    }

    @Test
    void buildErrorMessage_typeOnly_returnsTypeAlone() {
        String body = "{\"error\":{\"type\":\"permission_error\"}}";
        assertEquals("HTTP_403 — permission_error",
                AnthropicClient.buildErrorMessage(403, body));
    }

    @Test
    void buildErrorMessage_messageOnly_returnsMessageAlone() {
        String body = "{\"error\":{\"message\":\"Overloaded.\"}}";
        assertEquals("HTTP_529 — Overloaded.",
                AnthropicClient.buildErrorMessage(529, body));
    }

    @Test
    void buildErrorMessage_neitherTypeNorMessage_returnsCodeOnly() {
        assertEquals("HTTP_500", AnthropicClient.buildErrorMessage(500, "{}"));
    }

    @Test
    void buildErrorMessage_malformedJson_returnsCodeOnly() {
        assertEquals("HTTP_500", AnthropicClient.buildErrorMessage(500, "bad json"));
    }

    @Test
    void buildErrorMessage_emptyBody_returnsCodeOnly() {
        assertEquals("HTTP_503", AnthropicClient.buildErrorMessage(503, ""));
    }

    // ── buildPronunciationPrompt ──────────────────────────────────────────────

    @Test
    void buildPronunciationPrompt_withFocusWord_includesFocusWordSection() {
        String prompt = AnthropicClient.buildPronunciationPrompt("He went there", "went");
        assertTrue(prompt.contains("went"),
                "Focus word must appear in the prompt");
        assertTrue(prompt.contains("Palavra/trecho específico para analisar"),
                "Focus word section must be present when focus word is provided");
    }

    @Test
    void buildPronunciationPrompt_nullFocusWord_omitsFocusWordSection() {
        String prompt = AnthropicClient.buildPronunciationPrompt("He went there", null);
        assertFalse(prompt.contains("Palavra/trecho específico para analisar"));
    }

    @Test
    void buildPronunciationPrompt_blankFocusWord_omitsFocusWordSection() {
        String prompt = AnthropicClient.buildPronunciationPrompt("He went there", "");
        assertFalse(prompt.contains("Palavra/trecho específico para analisar"));
    }

    @Test
    void buildPronunciationPrompt_embedsTranscriptVerbatim() {
        String transcript = "The algorithm runs in O(n log n)";
        String prompt = AnthropicClient.buildPronunciationPrompt(transcript, null);
        assertTrue(prompt.contains(transcript));
    }

    @Test
    void buildPronunciationPrompt_instructsPortugueseResponse() {
        String prompt = AnthropicClient.buildPronunciationPrompt("test", null);
        assertTrue(prompt.contains("Responda em português"));
    }

    // ── buildEvaluationPrompt ─────────────────────────────────────────────────

    @Test
    void buildEvaluationPrompt_embedsAllThreeInputs() {
        String prompt = AnthropicClient.buildEvaluationPrompt(
                "Explain garbage collection",
                "Automatic memory management via mark-and-sweep",
                "The JVM cleans up unused objects");
        assertTrue(prompt.contains("Explain garbage collection"),    "Question must be embedded");
        assertTrue(prompt.contains("mark-and-sweep"),                "Expected answer must be embedded");
        assertTrue(prompt.contains("unused objects"),                "Candidate answer must be embedded");
    }

    @Test
    void buildEvaluationPrompt_containsVeredictoKeyword() {
        String prompt = AnthropicClient.buildEvaluationPrompt("Q", "A", "B");
        assertTrue(prompt.contains("Veredicto"), "Evaluation prompt must request a verdict");
    }
}
