package org.example.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.usage.ApiKind;
import org.example.usage.RateLimit;
import org.example.usage.UsageEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.function.Consumer;

public class GroqClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Optional, app-wide: notified with a UsageEvent for every LLM call (tokens used +
    // remaining quota from the rate-limit headers). Set once by the UI. Static because the
    // evaluation entry points are static and there is a single window.
    private static volatile Consumer<UsageEvent> usageListener;

    /** One confirmed follow-up turn: the follow-up question and the candidate's spoken answer. */
    public record FollowUpTurn(String question, String answer) {}

    private GroqClient() {}

    /** Registers a listener notified with a {@link UsageEvent} after every LLM call. */
    public static void setUsageListener(Consumer<UsageEvent> listener) {
        usageListener = listener;
    }

    public static String evaluateAnswer(LlmProvider provider, String apiKey, String question,
                                        String expectedAnswer, String candidateAnswer,
                                        String candidateName, String gender, int starScale,
                                        String jobDescription) throws Exception {
        return call(provider, apiKey, buildEvaluationPrompt(
                question, expectedAnswer, candidateAnswer, candidateName, gender, starScale,
                jobDescription));
    }

    /**
     * Evaluates the full exchange (initial Q&amp;A plus any follow-up rounds) by sending
     * the exchange as explicitly labeled turns to the model.
     *
     * @param provider  which OpenAI-compatible backend to call (Groq / Gemini / Cerebras)
     * @param followUps list of confirmed follow-up rounds; may be null or empty (base case)
     */
    public static String evaluateExchange(LlmProvider provider, String apiKey,
            String question, String expectedAnswer, String candidateAnswer,
            List<FollowUpTurn> followUps, String candidateName,
            String gender, int starScale, String jobDescription) throws Exception {
        return call(provider, apiKey, buildExchangePrompt(
                question, expectedAnswer, candidateAnswer, followUps,
                candidateName, gender, starScale, jobDescription));
    }

    /**
     * Generates a short, single-paragraph "reference points" guide for a follow-up
     * question — what a strong answer should cover — for the interviewer to judge
     * against. Called only when a follow-up is actually selected, so the two unchosen
     * options never cost a request.
     */
    public static String generateFollowUpExpected(LlmProvider provider, String apiKey,
            String jobDescription, String initialQuestion, String candidateInitialAnswer,
            String followUpQuestion) throws Exception {
        return call(provider, apiKey, buildFollowUpExpectedPrompt(
                jobDescription, initialQuestion, candidateInitialAnswer, followUpQuestion));
    }

    private static String call(LlmProvider provider, String apiKey, String userPrompt) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", provider.defaultModel());
        body.put("max_tokens", provider.maxTokens());
        // Disable/limit provider "thinking" so reasoning tokens don't consume the whole
        // budget and truncate the trailing RATING/follow-up section the parsers need.
        if (provider.reasoningEffort() != null) {
            body.put("reasoning_effort", provider.reasoningEffort());
        }

        ArrayNode messages = body.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", userPrompt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(provider.endpoint()))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            reportUsage(provider, response);
            if (response.statusCode() != 200) {
                throw new RuntimeException(buildErrorMessage(response.statusCode(), response.body()));
            }
            JsonNode root = MAPPER.readTree(response.body());
            return root.path("choices").get(0).path("message").path("content").asText();
        }
    }

    // Reports one LLM call's usage: +1 request, total_tokens (all providers return `usage`),
    // and the remaining quota from the rate-limit headers (Groq/Cerebras send them; Gemini's
    // compat layer does not, yielding an empty RateLimit). Defensive — never throws.
    private static void reportUsage(LlmProvider provider, HttpResponse<String> response) {
        Consumer<UsageEvent> listener = usageListener;
        if (listener == null) return;
        double tokens = 0;
        try {
            tokens = MAPPER.readTree(response.body()).path("usage").path("total_tokens").asDouble(0);
        } catch (Exception ignored) { /* error body may not be JSON */ }
        RateLimit rl = RateLimit.parse(response.headers(), ApiKind.LLM);
        listener.accept(new UsageEvent(provider.shortName(), ApiKind.LLM, 1, tokens, rl));
    }

    static String buildErrorMessage(int status, String body) {
        String detail = "";
        try {
            detail = MAPPER.readTree(body).path("error").path("message").asText("");
        } catch (Exception ignored) {}
        return "HTTP_" + status + (detail.isEmpty() ? "" : " — " + detail);
    }

    // ── Shared preamble helpers (package-private for testability) ──────────────

    /** Returns 10 when starScale is 10, 5 for any other value. */
    static int maxStars(int starScale) {
        return (starScale == 10) ? 10 : 5;
    }

    /** Builds the "who is the candidate / which pronouns to use" sentence. */
    static String whoLine(String candidateName, String gender) {
        String pronouns = "they/them";
        if (gender != null) {
            if (gender.equalsIgnoreCase("Male"))        pronouns = "he/him";
            else if (gender.equalsIgnoreCase("Female")) pronouns = "she/her";
        }
        String name = (candidateName == null) ? "" : candidateName.trim();
        return name.isEmpty()
                ? "Refer to the candidate using the pronouns " + pronouns + "."
                : "The candidate's name is " + name + "; refer to them as " + name
                  + " and use " + pronouns + " pronouns.";
    }

    /** Builds the job-description section, or the "none provided" fallback. */
    static String jobSection(String jobDescription) {
        return (jobDescription == null || jobDescription.trim().isEmpty())
                ? "No job description was provided for the role; judge the answer using general"
                  + " expectations for this kind of question and role."
                : "Job description for the role the candidate is interviewing for"
                  + " (weigh the answer against what this role actually requires):\n"
                  + jobDescription.trim();
    }

    /** Builds the expected-answer section, or the "no model answer" fallback. */
    static String expectedSection(String expectedAnswer) {
        String expected = (expectedAnswer == null) ? "" : expectedAnswer.trim();
        return expected.isEmpty()
                ? "No model answer was provided. Judge the technical correctness and "
                  + "completeness of the answer using your own expert knowledge of the question."
                : "Expected answer (model answer / key points):\n" + expected;
    }

    /** Builds the star-scale guide text (5-level or 10-band). */
    static String scaleGuide(int max) {
        return (max == 10)
                ? "Rate the answer from 1 to 10 stars using these bands: "
                  + "1-2 = Unsatisfactory, 3-4 = Needs Improvement, 5-6 = Satisfactory, "
                  + "7-8 = Very Good, 9-10 = Excellent."
                : "Rate the answer from 1 to 5 stars using this scale: "
                  + "1 = Unsatisfactory, 2 = Needs Improvement, 3 = Satisfactory, "
                  + "4 = Very Good, 5 = Excellent.";
    }

    // ── Prompt builders ────────────────────────────────────────────────────────

    /**
     * Builds the single-answer evaluation prompt (initial Q&amp;A only).
     * Output is kept byte-identical to the original; do not modify.
     */
    static String buildEvaluationPrompt(String question, String expectedAnswer,
                                        String candidateAnswer, String candidateName,
                                        String gender, int starScale,
                                        String jobDescription) {
        int max = maxStars(starScale);
        return """
                You are an experienced technical interviewer assessing a candidate's answer.

                %s

                %s

                Question asked:
                %s

                %s

                Candidate's answer (automatic speech-to-text transcription):
                %s

                The candidate's answer was captured by automatic speech recognition, so it may \
                contain transcription errors, missing or odd punctuation, filler words and the \
                natural hesitations of spoken language. Judge the meaning and intent behind the \
                words and never penalize wording, grammar or artifacts that are clearly \
                transcription noise rather than a real conceptual mistake.

                Write your assessment as flowing prose in two to three, at most four paragraphs, \
                with no bullet points and no section headings. Be objective and professional: \
                clearly call out when the candidate missed important points or stayed too \
                superficial, and do not inflate a weak answer, but the candidate's answer could be \
                slightly different from the expected answer and even so be correct, so don't be too \
                harsh on what is expected, especially when it comes to the coding question. Assess \
                the technical accuracy and completeness of the answer, and acknowledge genuine \
                strengths only when they exist.

                %s

                Keep the tone professional and direct — not harsh. Be kind and bear in mind this \
                is an interview, but do not be overly kind. Respond in English. After the prose \
                assessment, provide 2 to 3 follow-up questions the interviewer could ask to probe \
                the candidate more deeply, targeting gaps, vague spots, or claims worth pressing \
                on, kept relevant to the role. Output them in a dedicated section starting with a \
                line containing exactly FOLLOW-UP QUESTIONS: followed by each follow-up question \
                on its own line prefixed with "- ". After the follow-up questions block, output \
                the score on its own very last line in exactly this format, with no extra words: \
                RATING: n/%d
                """.formatted(whoLine(candidateName, gender), jobSection(jobDescription),
                        question, expectedSection(expectedAnswer), candidateAnswer,
                        scaleGuide(max), max);
    }

    /**
     * Builds the holistic exchange-evaluation prompt (initial Q&amp;A plus follow-up rounds).
     * Instructs the model to assess the whole exchange and produce exactly 3 new follow-ups.
     */
    static String buildExchangePrompt(String question, String expectedAnswer,
            String candidateAnswer, List<FollowUpTurn> followUps,
            String candidateName, String gender, int starScale,
            String jobDescription) {
        int max = maxStars(starScale);
        List<FollowUpTurn> fus = (followUps == null) ? List.of() : followUps;
        String conversation = buildConversationBlock(question, candidateAnswer, fus);
        return """
                You are an experienced technical interviewer assessing a candidate's \
                performance across a multi-turn exchange.

                %s

                %s

                Initial question asked:
                %s

                %s

                Out of scope: No expected answer for follow-ups — judge follow-up answers \
                using your own expert knowledge of the topic.

                The exchange below was captured by automatic speech recognition, so answers \
                may contain transcription errors, missing or odd punctuation, filler words and \
                the natural hesitations of spoken language. Judge the meaning and intent behind \
                the words and never penalize wording, grammar or artifacts that are clearly \
                speech-to-text noise rather than a real conceptual mistake.

                Exchange:
                %s

                Write your assessment as flowing prose in two to three, at most four paragraphs, \
                with no bullet points and no section headings. Assess the candidate across the \
                whole exchange, explicitly weighing the follow-up answers alongside the initial \
                answer. Be objective and professional: clearly call out when the candidate missed \
                important points or stayed too superficial, and do not inflate a weak answer, \
                but the candidate's answer could be slightly different from the expected answer \
                and even so be correct, so don't be too harsh. Acknowledge genuine strengths \
                only when they exist.

                %s

                Keep the tone professional and direct — not harsh. Be kind and bear in mind \
                this is an interview, but do not be overly kind. Respond in English. After the \
                prose assessment, provide exactly 3 follow-up questions the interviewer could \
                ask to probe the candidate more deeply in the next round, targeting the latest \
                gaps, vague spots, or claims worth pressing on, kept relevant to the role. \
                Output them in a dedicated section starting with a line containing exactly \
                FOLLOW-UP QUESTIONS: followed by each follow-up question on its own line \
                prefixed with "- ". That header line must be exactly FOLLOW-UP QUESTIONS: on \
                its own line — no lead-in sentence such as "Here are", no numbering and no \
                markdown before it. After the follow-up questions block, output the score for \
                the whole exchange on its own very last line in exactly this format, with no \
                extra words: RATING: n/%d
                """.formatted(whoLine(candidateName, gender), jobSection(jobDescription),
                        question, expectedSection(expectedAnswer), conversation,
                        scaleGuide(max), max);
    }

    /**
     * Builds the prompt for the one-paragraph follow-up "expected answer" guide.
     * Output is a single prose paragraph of reference points (no bullets/markdown),
     * grounded in the role and the exchange so far. Package-private for testing.
     */
    static String buildFollowUpExpectedPrompt(String jobDescription, String initialQuestion,
            String candidateInitialAnswer, String followUpQuestion) {
        String ia = (candidateInitialAnswer == null) ? "" : candidateInitialAnswer.trim();
        return """
                You are assisting a technical interviewer. Based on the role and the exchange \
                so far, write a SHORT single paragraph (3 to 5 sentences) describing the key \
                reference points a strong answer to the follow-up question below should cover — \
                that is, what the interviewer should listen for. This is a private guide for the \
                interviewer and is never shown to the candidate.

                Write ONLY the paragraph: no bullet points, no headings, no markdown, and no \
                preamble such as "Here is". Be concrete and concise. Respond in English.

                %s

                Initial question asked:
                %s

                Candidate's initial answer (automatic speech-to-text, may contain errors):
                %s

                Follow-up question to write the guide for:
                %s
                """.formatted(jobSection(jobDescription), initialQuestion,
                        ia.isEmpty() ? "(not captured)" : ia, followUpQuestion);
    }

    /**
     * Builds the labeled-turns block embedded in the exchange prompt.
     *
     * <p>Always includes the initial question/answer; then for each follow-up (1-based):
     * {@code Follow-up N (asked by the interviewer): …} and
     * {@code Candidate's answer to follow-up N: …}. A null/blank follow-up answer is
     * substituted with {@code (no answer was captured)}.
     */
    static String buildConversationBlock(String question, String candidateAnswer,
            List<FollowUpTurn> followUps) {
        List<FollowUpTurn> fus = (followUps == null) ? List.of() : followUps;
        StringBuilder sb = new StringBuilder();
        sb.append("Initial question (asked by the interviewer): ").append(question).append('\n');
        sb.append("Candidate's initial answer: ").append(candidateAnswer).append('\n');
        for (int i = 0; i < fus.size(); i++) {
            FollowUpTurn turn = fus.get(i);
            String ans = (turn.answer() == null || turn.answer().isBlank())
                    ? "(no answer was captured)"
                    : turn.answer();
            sb.append("Follow-up ").append(i + 1).append(" (asked by the interviewer): ")
              .append(turn.question()).append('\n');
            sb.append("Candidate's answer to follow-up ").append(i + 1).append(": ")
              .append(ans).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
