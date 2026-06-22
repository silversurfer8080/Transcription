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
                "It runs without blocking an OS thread.",
                "Maria", "Female", 5);
        assertTrue(prompt.contains("What is a Java virtual thread?"),    "Question must be embedded");
        assertTrue(prompt.contains("Lightweight thread managed by the JVM."), "Expected answer must be embedded");
        assertTrue(prompt.contains("without blocking an OS thread"),          "Candidate answer must be embedded");
    }

    @Test
    void buildEvaluationPrompt_instructsEnglishResponse() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "", null, 5);
        assertTrue(prompt.contains("Respond in English"),
                "Evaluation must be returned in English");
    }

    @Test
    void buildEvaluationPrompt_warnsThatAnswerIsSpeechToText() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "", null, 5).toLowerCase();
        assertTrue(prompt.contains("speech-to-text") || prompt.contains("speech recognition"),
                "Prompt must tell the model the answer is an automatic STT transcription");
    }

    @Test
    void buildEvaluationPrompt_asksForAtMostTwoParagraphs() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "", null, 5);
        assertTrue(prompt.contains("two paragraphs"),
                "Evaluation must be limited to at most two paragraphs");
    }

    @Test
    void buildEvaluationPrompt_includesCandidateNameWhenProvided() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "Carlos", "Male", 5);
        assertTrue(prompt.contains("Carlos"), "Candidate name must appear in the prompt");
    }

    @Test
    void buildEvaluationPrompt_maleGender_usesHeHimPronouns() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "Carlos", "Male", 5);
        assertTrue(prompt.contains("he/him"), "Male candidate must use he/him pronouns");
    }

    @Test
    void buildEvaluationPrompt_femaleGender_usesSheHerPronouns() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "Maria", "Female", 5);
        assertTrue(prompt.contains("she/her"), "Female candidate must use she/her pronouns");
    }

    @Test
    void buildEvaluationPrompt_nullGender_fallsBackToNeutralPronouns() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "", null, 5);
        assertTrue(prompt.contains("they/them"), "Unspecified gender must use neutral pronouns");
    }

    // ── Star-rating scale ─────────────────────────────────────────────────────

    @Test
    void buildEvaluationPrompt_fiveStarScale_listsFiveLevelsAndRatingLine() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "", null, 5);
        assertTrue(prompt.contains("1 = Unsatisfactory"), "5-star scale must define level 1");
        assertTrue(prompt.contains("5 = Excellent"),      "5-star scale must define level 5");
        assertTrue(prompt.contains("RATING: n/5"),        "Prompt must ask for a /5 rating line");
    }

    @Test
    void buildEvaluationPrompt_tenStarScale_usesBandsAndRatingLine() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "", null, 10);
        assertTrue(prompt.contains("9-10 = Excellent"), "10-star scale must use proportional bands");
        assertTrue(prompt.contains("RATING: n/10"),     "Prompt must ask for a /10 rating line");
    }

    @Test
    void buildEvaluationPrompt_invalidScale_fallsBackToFive() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "", null, 7);
        assertTrue(prompt.contains("RATING: n/5"), "Unsupported scales must fall back to 5 stars");
    }
}
