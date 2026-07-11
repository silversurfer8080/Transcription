package org.example.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    // ── Shared preamble helpers (maxStars, whoLine, jobSection, expectedSection, scaleGuide) ──

    @Test
    void maxStars_ten_returnsTen() {
        assertEquals(10, GroqClient.maxStars(10));
    }

    @Test
    void maxStars_five_returnsFive() {
        assertEquals(5, GroqClient.maxStars(5));
    }

    @Test
    void maxStars_other_returnsFive() {
        assertEquals(5, GroqClient.maxStars(7));
        assertEquals(5, GroqClient.maxStars(0));
        assertEquals(5, GroqClient.maxStars(-1));
    }

    @Test
    void whoLine_maleWithName_containsNameAndHeHim() {
        String line = GroqClient.whoLine("Carlos", "Male");
        assertTrue(line.contains("Carlos"), "Name must appear in who line");
        assertTrue(line.contains("he/him"), "Male must use he/him");
    }

    @Test
    void whoLine_femaleWithName_containsSheHer() {
        String line = GroqClient.whoLine("Ana", "Female");
        assertTrue(line.contains("she/her"), "Female must use she/her");
    }

    @Test
    void whoLine_nullGender_usesTheyThem() {
        String line = GroqClient.whoLine("Alex", null);
        assertTrue(line.contains("they/them"), "Null gender must use they/them");
    }

    @Test
    void whoLine_emptyName_omitsNameSentence() {
        String line = GroqClient.whoLine("", null);
        assertFalse(line.contains("name is"), "Empty name must not produce a name sentence");
        assertTrue(line.contains("they/them"), "Still must specify neutral pronouns");
    }

    @Test
    void jobSection_withContent_includesLabelAndContent() {
        String section = GroqClient.jobSection("Backend engineer at fintech");
        assertTrue(section.contains("Job description for the role"),
                "Job section must include the label preamble");
        assertTrue(section.contains("Backend engineer at fintech"),
                "Job section must embed the description verbatim");
    }

    @Test
    void jobSection_emptyNullBlank_returnsFallback() {
        assertTrue(GroqClient.jobSection("").contains("No job description was provided"),
                "Empty string must trigger fallback");
        assertTrue(GroqClient.jobSection(null).contains("No job description was provided"),
                "Null must trigger fallback");
        assertTrue(GroqClient.jobSection("   ").contains("No job description was provided"),
                "Blank string must trigger fallback");
    }

    @Test
    void expectedSection_withContent_includesLabelAndContent() {
        String section = GroqClient.expectedSection("Use a ConcurrentHashMap");
        assertTrue(section.contains("Expected answer"), "Expected section must include the label");
        assertTrue(section.contains("ConcurrentHashMap"), "Expected section must embed answer verbatim");
    }

    @Test
    void expectedSection_emptyOrNull_returnsFallback() {
        assertTrue(GroqClient.expectedSection("").contains("No model answer was provided"),
                "Empty expected answer must use model's own knowledge");
        assertTrue(GroqClient.expectedSection(null).contains("No model answer was provided"),
                "Null expected answer must use model's own knowledge");
    }

    @Test
    void scaleGuide_fiveMax_containsFiveLevels() {
        String guide = GroqClient.scaleGuide(5);
        assertTrue(guide.contains("1 = Unsatisfactory"), "5-star guide must define level 1");
        assertTrue(guide.contains("5 = Excellent"),      "5-star guide must define level 5");
    }

    @Test
    void scaleGuide_tenMax_containsBands() {
        String guide = GroqClient.scaleGuide(10);
        assertTrue(guide.contains("9-10 = Excellent"),     "10-star guide must define top band");
        assertTrue(guide.contains("1-2 = Unsatisfactory"), "10-star guide must define bottom band");
    }

    // ── buildConversationBlock ─────────────────────────────────────────────────

    @Test
    void buildConversationBlock_noFollowUps_containsOnlyInitialQA() {
        String block = GroqClient.buildConversationBlock(
                "What is a thread?", "A unit of execution.", List.of());
        assertTrue(block.contains("Initial question (asked by the interviewer): What is a thread?"),
                "Initial question label must appear");
        assertTrue(block.contains("Candidate's initial answer: A unit of execution."),
                "Initial answer label must appear");
        assertFalse(block.contains("Follow-up"), "No follow-up turns expected for empty list");
    }

    @Test
    void buildConversationBlock_nullFollowUps_treatedAsEmpty() {
        String block = GroqClient.buildConversationBlock("Q?", "A.", null);
        assertTrue(block.contains("Initial question (asked by the interviewer): Q?"));
        assertTrue(block.contains("Candidate's initial answer: A."));
        assertFalse(block.contains("Follow-up"),
                "Null follow-up list must produce no follow-up lines");
    }

    @Test
    void buildConversationBlock_blankFollowUpAnswer_substitutesPlaceholder() {
        List<GroqClient.FollowUpTurn> turns = List.of(
                new GroqClient.FollowUpTurn("Can you elaborate?", ""),
                new GroqClient.FollowUpTurn("What about locking?", null)
        );
        String block = GroqClient.buildConversationBlock("Q?", "A.", turns);
        long placeholderCount = block.lines()
                .filter(l -> l.contains("(no answer was captured)")).count();
        assertEquals(2, placeholderCount,
                "Each blank/null follow-up answer must be replaced by the placeholder");
    }

    @Test
    void buildConversationBlock_nonBlankFollowUpAnswer_doesNotSubstitutePlaceholder() {
        List<GroqClient.FollowUpTurn> turns = List.of(
                new GroqClient.FollowUpTurn("Elaborate?", "Real answer here")
        );
        String block = GroqClient.buildConversationBlock("Q", "A", turns);
        assertTrue(block.contains("Real answer here"),
                "Non-blank answer must appear verbatim");
        assertFalse(block.contains("(no answer was captured)"),
                "Placeholder must not appear when answer is present");
    }

    @Test
    void buildConversationBlock_followUps_labeledAndOrderedCorrectly() {
        List<GroqClient.FollowUpTurn> turns = List.of(
                new GroqClient.FollowUpTurn("Follow Q1", "Answer 1"),
                new GroqClient.FollowUpTurn("Follow Q2", "Answer 2")
        );
        String block = GroqClient.buildConversationBlock("Initial Q", "Initial A", turns);
        assertTrue(block.contains("Follow-up 1 (asked by the interviewer): Follow Q1"),
                "First follow-up must use label with index 1");
        assertTrue(block.contains("Candidate's answer to follow-up 1: Answer 1"),
                "First follow-up answer must use matching label");
        assertTrue(block.contains("Follow-up 2 (asked by the interviewer): Follow Q2"),
                "Second follow-up must use label with index 2");
        assertTrue(block.contains("Candidate's answer to follow-up 2: Answer 2"),
                "Second follow-up answer must use matching label");
        assertTrue(block.indexOf("Follow-up 1") < block.indexOf("Follow-up 2"),
                "Turns must appear in list order");
    }

    // ── buildExchangePrompt ────────────────────────────────────────────────────

    @Test
    void buildExchangePrompt_embedsInitialQuestionAndAnswer() {
        String prompt = GroqClient.buildExchangePrompt(
                "What is polymorphism?", "", "It allows different types to share an interface.",
                List.of(), "Ana", "Female", 5, "");
        assertTrue(prompt.contains("What is polymorphism?"),
                "Initial question must be embedded in the exchange prompt");
        assertTrue(prompt.contains("different types to share an interface"),
                "Candidate initial answer must be embedded");
    }

    @Test
    void buildExchangePrompt_withFollowUps_embedsLabeledTurns() {
        List<GroqClient.FollowUpTurn> turns = List.of(
                new GroqClient.FollowUpTurn("Can you give an example?", "Like a Shape class")
        );
        String prompt = GroqClient.buildExchangePrompt(
                "Q?", "", "Initial answer", turns, "", null, 5, "");
        assertTrue(prompt.contains("Follow-up 1 (asked by the interviewer): Can you give an example?"),
                "Follow-up question must appear as labeled turn in the exchange");
        assertTrue(prompt.contains("Candidate's answer to follow-up 1: Like a Shape class"),
                "Follow-up answer must appear as labeled turn in the exchange");
    }

    @Test
    void buildExchangePrompt_instructsHolisticAssessment() {
        String prompt = GroqClient.buildExchangePrompt("Q", "", "A", List.of(), "", null, 5, "");
        assertTrue(prompt.contains("whole exchange") || prompt.contains("full exchange")
                        || prompt.contains("weighing the follow-up"),
                "Exchange prompt must instruct holistic assessment across the whole exchange");
    }

    @Test
    void buildExchangePrompt_requestsExactlyThreeFollowUps() {
        String prompt = GroqClient.buildExchangePrompt("Q", "", "A", List.of(), "", null, 5, "");
        assertTrue(prompt.contains("exactly 3"),
                "Exchange prompt must request exactly 3 follow-up questions (matching the 3 radios)");
    }

    @Test
    void buildExchangePrompt_containsFollowUpQuestionsMarker() {
        String prompt = GroqClient.buildExchangePrompt("Q", "", "A", List.of(), "", null, 5, "");
        assertTrue(prompt.contains("FOLLOW-UP QUESTIONS:"),
                "Exchange prompt must include the literal FOLLOW-UP QUESTIONS: marker");
    }

    @Test
    void buildExchangePrompt_ratingLineComesAfterFollowUpInstruction() {
        String prompt = GroqClient.buildExchangePrompt("Q", "", "A", List.of(), "", null, 5, "");
        int fuPos     = prompt.indexOf("FOLLOW-UP QUESTIONS:");
        int ratingPos = prompt.lastIndexOf("RATING: n/");
        assertTrue(fuPos     >= 0, "FOLLOW-UP QUESTIONS: must appear");
        assertTrue(ratingPos >= 0, "RATING: n/ must appear");
        assertTrue(ratingPos > fuPos,
                "RATING instruction must come after the FOLLOW-UP QUESTIONS: instruction");
    }

    @Test
    void buildExchangePrompt_fiveStarScale_emitsRatingN5() {
        String prompt = GroqClient.buildExchangePrompt("Q", "", "A", List.of(), "", null, 5, "");
        assertTrue(prompt.contains("RATING: n/5"),
                "5-star exchange prompt must request RATING: n/5");
    }

    @Test
    void buildExchangePrompt_tenStarScale_emitsRatingN10() {
        String prompt = GroqClient.buildExchangePrompt("Q", "", "A", List.of(), "", null, 10, "");
        assertTrue(prompt.contains("RATING: n/10"),
                "10-star exchange prompt must request RATING: n/10");
    }

    @Test
    void buildExchangePrompt_invalidScale_fallsBackToFive() {
        String prompt = GroqClient.buildExchangePrompt("Q", "", "A", List.of(), "", null, 7, "");
        assertTrue(prompt.contains("RATING: n/5"),
                "Unsupported scale must fall back to 5 stars");
    }

    @Test
    void buildExchangePrompt_containsSpeechToTextGuidance() {
        String prompt = GroqClient.buildExchangePrompt(
                "Q", "", "A", List.of(), "", null, 5, "").toLowerCase();
        assertTrue(prompt.contains("speech-to-text") || prompt.contains("speech recognition"),
                "Exchange prompt must include STT tolerance guidance");
    }

    @Test
    void buildExchangePrompt_asksForProseNotBulletsAndEnglish() {
        String prompt = GroqClient.buildExchangePrompt("Q", "", "A", List.of(), "", null, 5, "");
        assertTrue(prompt.contains("no bullet points"),
                "Exchange prompt must ask for prose without bullet points");
        assertTrue(prompt.contains("Respond in English"),
                "Exchange prompt must require English response");
    }

    @Test
    void buildExchangePrompt_maleGender_usesHeHimPronouns() {
        String prompt = GroqClient.buildExchangePrompt(
                "Q", "", "A", List.of(), "João", "Male", 5, "");
        assertTrue(prompt.contains("he/him"),
                "Male candidate must use he/him pronouns in exchange prompt");
    }

    @Test
    void buildExchangePrompt_femaleGender_usesSheHerPronouns() {
        String prompt = GroqClient.buildExchangePrompt(
                "Q", "", "A", List.of(), "Ana", "Female", 5, "");
        assertTrue(prompt.contains("she/her"),
                "Female candidate must use she/her pronouns in exchange prompt");
    }

    @Test
    void buildExchangePrompt_nullGender_usesNeutralPronouns() {
        String prompt = GroqClient.buildExchangePrompt(
                "Q", "", "A", List.of(), "", null, 5, "");
        assertTrue(prompt.contains("they/them"),
                "Unspecified gender must use they/them pronouns in exchange prompt");
    }

    @Test
    void buildExchangePrompt_withJobDescription_embedsVerbatim() {
        String jobDesc = "Staff engineer at a fintech startup";
        String prompt = GroqClient.buildExchangePrompt(
                "Q", "", "A", List.of(), "", null, 5, jobDesc);
        assertTrue(prompt.contains(jobDesc),
                "Job description must be embedded verbatim in the exchange prompt");
    }

    @Test
    void buildExchangePrompt_emptyJobDescription_fallback() {
        String prompt = GroqClient.buildExchangePrompt(
                "Q", "", "A", List.of(), "", null, 5, "");
        assertTrue(prompt.contains("No job description was provided"),
                "Empty job description must trigger the fallback message");
    }

    @Test
    void buildExchangePrompt_withExpectedAnswer_embedsItAndLabel() {
        String prompt = GroqClient.buildExchangePrompt(
                "Q", "Use virtual threads", "A", List.of(), "", null, 5, "");
        assertTrue(prompt.contains("Use virtual threads"),
                "Expected answer must be embedded in the exchange prompt");
        assertTrue(prompt.contains("Expected answer"),
                "Expected answer label must appear when an expected answer is provided");
    }

    @Test
    void buildExchangePrompt_emptyExpectedAnswer_usesOwnKnowledge() {
        String prompt = GroqClient.buildExchangePrompt(
                "Q", "", "A", List.of(), "", null, 5, "");
        assertTrue(prompt.contains("No model answer was provided"),
                "Empty expected answer must tell model to judge on its own expertise");
    }

    @Test
    void buildExchangePrompt_nullFollowUps_treatedAsEmpty() {
        // Base case: null follow-up list must still produce a valid prompt
        String prompt = GroqClient.buildExchangePrompt(
                "Q", "E", "A", null, "Carlos", "Male", 5, "Role desc");
        assertTrue(prompt.contains("FOLLOW-UP QUESTIONS:"),
                "Null follow-up list must still produce a complete exchange prompt");
        assertTrue(prompt.contains("RATING: n/5"),
                "Null follow-up list must still produce the rating instruction");
    }
}
