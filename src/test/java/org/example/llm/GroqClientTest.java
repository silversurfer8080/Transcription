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

    // ── buildEvaluationPrompt ─────────────────────────────────────────────────

    @Test
    void buildEvaluationPrompt_embedsAllThreeInputs() {
        String prompt = GroqClient.buildEvaluationPrompt(
                "What is a Java virtual thread?",
                "Lightweight thread managed by the JVM.",
                "It runs without blocking an OS thread.",
                "Maria", "Female", 5, "");
        assertTrue(prompt.contains("What is a Java virtual thread?"),    "Question must be embedded");
        assertTrue(prompt.contains("Lightweight thread managed by the JVM."), "Expected answer must be embedded");
        assertTrue(prompt.contains("without blocking an OS thread"),          "Candidate answer must be embedded");
    }

    @Test
    void buildEvaluationPrompt_instructsEnglishResponse() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "", null, 5, "");
        assertTrue(prompt.contains("Respond in English"),
                "Evaluation must be returned in English");
    }

    @Test
    void buildEvaluationPrompt_warnsThatAnswerIsSpeechToText() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "", null, 5, "").toLowerCase();
        assertTrue(prompt.contains("speech-to-text") || prompt.contains("speech recognition"),
                "Prompt must tell the model the answer is an automatic STT transcription");
    }

    @Test
    void buildEvaluationPrompt_asksForProseNotBullets() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "", null, 5, "");
        assertTrue(prompt.contains("paragraphs"),   "Evaluation must be written as paragraphs");
        assertTrue(prompt.contains("no bullet points"), "Evaluation must not use bullet points");
    }

    @Test
    void buildEvaluationPrompt_includesCandidateNameWhenProvided() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "Carlos", "Male", 5, "");
        assertTrue(prompt.contains("Carlos"), "Candidate name must appear in the prompt");
    }

    @Test
    void buildEvaluationPrompt_maleGender_usesHeHimPronouns() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "Carlos", "Male", 5, "");
        assertTrue(prompt.contains("he/him"), "Male candidate must use he/him pronouns");
    }

    @Test
    void buildEvaluationPrompt_femaleGender_usesSheHerPronouns() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "Maria", "Female", 5, "");
        assertTrue(prompt.contains("she/her"), "Female candidate must use she/her pronouns");
    }

    @Test
    void buildEvaluationPrompt_nullGender_fallsBackToNeutralPronouns() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "", null, 5, "");
        assertTrue(prompt.contains("they/them"), "Unspecified gender must use neutral pronouns");
    }

    @Test
    void buildEvaluationPrompt_withExpectedAnswer_includesModelAnswerLabel() {
        String prompt = GroqClient.buildEvaluationPrompt(
                "Q", "Use a lock-free queue", "B", "", null, 5, "");
        assertTrue(prompt.contains("Expected answer"), "Provided gabarito must be labelled");
        assertTrue(prompt.contains("Use a lock-free queue"), "Provided gabarito must be embedded");
    }

    @Test
    void buildEvaluationPrompt_emptyExpected_tellsModelToUseOwnKnowledge() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "", "B", "", null, 5, "");
        assertTrue(prompt.contains("No model answer was provided"),
                "Empty gabarito must fall back to the model's own expertise");
        assertFalse(prompt.contains("Expected answer (model answer"),
                "No expected-answer label when the gabarito is empty");
    }

    @Test
    void buildEvaluationPrompt_nullExpected_tellsModelToUseOwnKnowledge() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", null, "B", "", null, 5, "");
        assertTrue(prompt.contains("No model answer was provided"),
                "Null gabarito must fall back to the model's own expertise");
    }

    // ── Star-rating scale ─────────────────────────────────────────────────────

    @Test
    void buildEvaluationPrompt_fiveStarScale_listsFiveLevelsAndRatingLine() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "", null, 5, "");
        assertTrue(prompt.contains("1 = Unsatisfactory"), "5-star scale must define level 1");
        assertTrue(prompt.contains("5 = Excellent"),      "5-star scale must define level 5");
        assertTrue(prompt.contains("RATING: n/5"),        "Prompt must ask for a /5 rating line");
    }

    @Test
    void buildEvaluationPrompt_tenStarScale_usesBandsAndRatingLine() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "", null, 10, "");
        assertTrue(prompt.contains("9-10 = Excellent"), "10-star scale must use proportional bands");
        assertTrue(prompt.contains("RATING: n/10"),     "Prompt must ask for a /10 rating line");
    }

    @Test
    void buildEvaluationPrompt_invalidScale_fallsBackToFive() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "", null, 7, "");
        assertTrue(prompt.contains("RATING: n/5"), "Unsupported scales must fall back to 5 stars");
    }

    // ── Job description (Feature 2) ───────────────────────────────────────────

    @Test
    void buildEvaluationPrompt_withJobDescription_embedsItAndLabel() {
        String jobDesc = "Senior Java engineer building low-latency trading systems";
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "", null, 5, jobDesc);
        assertTrue(prompt.contains(jobDesc),
                "Prompt must embed the supplied job description verbatim");
        assertTrue(prompt.contains("Job description for the role"),
                "Prompt must include the labelling preamble for job description");
    }

    @Test
    void buildEvaluationPrompt_emptyJobDescription_tellsModelNoneProvided() {
        String promptEmpty = GroqClient.buildEvaluationPrompt("Q", "A", "B", "", null, 5, "");
        assertTrue(promptEmpty.contains("No job description was provided for the role"),
                "Empty job description must trigger the no-job-description fallback message");

        String promptNull = GroqClient.buildEvaluationPrompt("Q", "A", "B", "", null, 5, null);
        assertTrue(promptNull.contains("No job description was provided for the role"),
                "Null job description must trigger the no-job-description fallback message");
    }

    @Test
    void buildEvaluationPrompt_blankJobDescription_tellsModelNoneProvided() {
        String promptBlank = GroqClient.buildEvaluationPrompt("Q", "A", "B", "", null, 5, "   ");
        assertTrue(promptBlank.contains("No job description was provided for the role"),
                "Whitespace-only job description must trigger the no-job-description fallback message");
    }

    // ── Follow-up questions (Feature 1) ───────────────────────────────────────

    @Test
    void buildEvaluationPrompt_requestsFollowUpQuestions() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "", null, 5, "");
        assertTrue(prompt.contains("FOLLOW-UP QUESTIONS:"),
                "Prompt must include the literal FOLLOW-UP QUESTIONS: marker token");
        assertTrue(prompt.toLowerCase().contains("follow-up questions"),
                "Prompt must ask the model for follow-up questions (case-insensitive)");
    }

    @Test
    void buildEvaluationPrompt_followUpInstructionMentionsTwoToThreeQuestions() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "", null, 5, "");
        // Spec §5.2 requires the prompt to ask for 2 to 3 follow-up questions
        assertTrue(prompt.contains("2 to 3") || prompt.contains("2-3"),
                "Prompt must instruct the model to produce 2 to 3 follow-up questions");
    }

    @Test
    void buildEvaluationPrompt_ratingLineRemainsLastInstruction() {
        String prompt = GroqClient.buildEvaluationPrompt("Q", "A", "B", "", null, 5, "");
        // The RATING instruction must appear after the FOLLOW-UP QUESTIONS: block instruction
        int followUpPos = prompt.indexOf("FOLLOW-UP QUESTIONS:");
        int ratingPos   = prompt.lastIndexOf("RATING: n/");
        assertTrue(followUpPos >= 0, "FOLLOW-UP QUESTIONS: marker must appear in prompt");
        assertTrue(ratingPos   >= 0, "RATING: n/ instruction must appear in prompt");
        assertTrue(ratingPos > followUpPos,
                "RATING instruction must come after the follow-up block instruction");
    }
}
