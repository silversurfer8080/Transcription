package org.example.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GroqClientTest {

    // ── buildErrorMessage ─────────────────────────────────────────────────────

    @Test
    void buildErrorMessage_withErrorDetail_returnsFormattedMessage() {
        String body = "{\"error\":{\"message\":\"Invalid API key\"}}";
        assertEquals("HTTP_401 — Invalid API key", GroqClient.buildErrorMessage(401, body));
    }

    @Test
    void buildErrorMessage_rateLimitWithDetail_includesDetail() {
        String body = "{\"error\":{\"message\":\"Rate limit exceeded. Please retry after 10s.\"}}";
        assertEquals("HTTP_429 — Rate limit exceeded. Please retry after 10s.",
                GroqClient.buildErrorMessage(429, body));
    }

    @Test
    void buildErrorMessage_emptyErrorMessageField_returnsCodeOnly() {
        assertEquals("HTTP_500", GroqClient.buildErrorMessage(500, "{}"));
    }

    @Test
    void buildErrorMessage_malformedJson_returnsCodeOnly() {
        assertEquals("HTTP_503", GroqClient.buildErrorMessage(503, "not-valid-json"));
    }

    @Test
    void buildErrorMessage_emptyBody_returnsCodeOnly() {
        assertEquals("HTTP_500", GroqClient.buildErrorMessage(500, ""));
    }

    @Test
    void buildErrorMessage_nullMessageValue_returnsCodeOnly() {
        // error.message exists but is JSON null
        assertEquals("HTTP_400", GroqClient.buildErrorMessage(400, "{\"error\":{\"message\":null}}"));
    }

    // ── buildPronunciationPrompt ──────────────────────────────────────────────

    @Test
    void buildPronunciationPrompt_withFocusWord_includesFocusWordSection() {
        String prompt = GroqClient.buildPronunciationPrompt("He went to the store", "went");
        assertTrue(prompt.contains("went"),
                "Focus word must appear in the prompt");
        assertTrue(prompt.contains("Palavra/trecho específico para analisar"),
                "Focus word section header must be included when a focus word is provided");
    }

    @Test
    void buildPronunciationPrompt_blankFocusWord_omitsFocusWordSection() {
        String prompt = GroqClient.buildPronunciationPrompt("He went to the store", "   ");
        assertFalse(prompt.contains("Palavra/trecho específico para analisar"),
                "Blank focus word must suppress the focus word section");
    }

    @Test
    void buildPronunciationPrompt_nullFocusWord_omitsFocusWordSection() {
        String prompt = GroqClient.buildPronunciationPrompt("He went to the store", null);
        assertFalse(prompt.contains("Palavra/trecho específico para analisar"),
                "Null focus word must suppress the focus word section");
    }

    @Test
    void buildPronunciationPrompt_embedsTranscript() {
        String transcript = "The quick brown fox jumps over the lazy dog";
        String prompt = GroqClient.buildPronunciationPrompt(transcript, null);
        assertTrue(prompt.contains(transcript), "Transcript must appear verbatim in the prompt");
    }

    @Test
    void buildPronunciationPrompt_isInPortuguese() {
        String prompt = GroqClient.buildPronunciationPrompt("test", null);
        assertTrue(prompt.contains("Responda em português"),
                "Prompt must instruct the model to respond in Portuguese");
    }

    // ── buildEvaluationPrompt ─────────────────────────────────────────────────

    @Test
    void buildEvaluationPrompt_embedsAllThreeInputs() {
        String prompt = GroqClient.buildEvaluationPrompt(
                "What is a Java virtual thread?",
                "Lightweight thread managed by the JVM.",
                "It runs without blocking an OS thread.");
        assertTrue(prompt.contains("What is a Java virtual thread?"),    "Question must be embedded");
        assertTrue(prompt.contains("Lightweight thread managed by the JVM."), "Expected answer must be embedded");
        assertTrue(prompt.contains("without blocking an OS thread"),          "Candidate answer must be embedded");
    }

    @Test
    void buildEvaluationPrompt_containsVeredictoKeyword() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B");
        assertTrue(prompt.contains("Veredicto"), "Evaluation prompt must request a verdict");
    }
}
