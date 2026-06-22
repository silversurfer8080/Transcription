package org.example.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class GroqClient {

    private static final String API_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String MODEL   = "llama-3.3-70b-versatile";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GroqClient() {}

    public static String analyzePronunciation(String apiKey, String transcript, String focusWord)
            throws Exception {
        return call(apiKey, buildPronunciationPrompt(transcript, focusWord));
    }

    public static String evaluateAnswer(String apiKey, String question,
                                        String expectedAnswer, String candidateAnswer,
                                        String candidateName, String gender, int starScale)
            throws Exception {
        return call(apiKey, buildEvaluationPrompt(
                question, expectedAnswer, candidateAnswer, candidateName, gender, starScale));
    }

    private static String call(String apiKey, String userPrompt) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", MODEL);
        body.put("max_tokens", 1024);

        ArrayNode messages = body.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", userPrompt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException(buildErrorMessage(response.statusCode(), response.body()));
            }
            JsonNode root = MAPPER.readTree(response.body());
            return root.path("choices").get(0).path("message").path("content").asText();
        }
    }

    static String buildErrorMessage(int status, String body) {
        String detail = "";
        try {
            detail = MAPPER.readTree(body).path("error").path("message").asText("");
        } catch (Exception ignored) {}
        return "HTTP_" + status + (detail.isEmpty() ? "" : " — " + detail);
    }

    static String buildPronunciationPrompt(String transcript, String focusWord) {
        StringBuilder sb = new StringBuilder();
        sb.append("Você é um coach de pronúncia em inglês para entrevistadores técnicos brasileiros.\n\n");
        sb.append("Transcrição do que foi dito:\n\"").append(transcript).append("\"\n\n");
        if (focusWord != null && !focusWord.isBlank()) {
            sb.append("Palavra/trecho específico para analisar: \"").append(focusWord).append("\"\n\n");
        }
        sb.append("""
                Analise os pontos de pronúncia mais relevantes considerando erros comuns de \
                falantes de português brasileiro. Para cada item:
                1. Mostre a pronúncia correta em IPA
                2. Descreva o erro típico de um brasileiro (ex: vogal extra, th/d, stress errado)
                3. Dê uma dica prática para corrigir

                Seja objetivo. Responda em português.""");
        return sb.toString();
    }

    static String buildEvaluationPrompt(String question, String expectedAnswer,
                                        String candidateAnswer, String candidateName,
                                        String gender, int starScale) {
        int max = (starScale == 10) ? 10 : 5;
        String pronouns = "they/them";
        if (gender != null) {
            if (gender.equalsIgnoreCase("Male"))        pronouns = "he/him";
            else if (gender.equalsIgnoreCase("Female")) pronouns = "she/her";
        }
        String name = (candidateName == null) ? "" : candidateName.trim();
        String whoLine = name.isEmpty()
                ? "Refer to the candidate using the pronouns " + pronouns + "."
                : "The candidate's name is " + name + "; refer to them as " + name
                  + " and use " + pronouns + " pronouns.";

        String scaleGuide = (max == 10)
                ? "Rate the answer from 1 to 10 stars using these bands: "
                  + "1-2 = Unsatisfactory, 3-4 = Needs Improvement, 5-6 = Satisfactory, "
                  + "7-8 = Very Good, 9-10 = Excellent."
                : "Rate the answer from 1 to 5 stars using this scale: "
                  + "1 = Unsatisfactory, 2 = Needs Improvement, 3 = Satisfactory, "
                  + "4 = Very Good, 5 = Excellent.";

        return """
                You are an experienced technical interviewer assessing a candidate's answer.

                %s

                Question asked:
                %s

                Expected answer (model answer / key points):
                %s

                Candidate's answer (automatic speech-to-text transcription):
                %s

                The candidate's answer was captured by automatic speech recognition, so it may \
                contain transcription errors, missing or odd punctuation, filler words and the \
                natural hesitations of spoken language. Judge the meaning and intent behind the \
                words and never penalize wording, grammar or artifacts that are clearly \
                transcription noise rather than a real conceptual mistake.

                Write your assessment as flowing prose in at most two paragraphs, with no bullet \
                points and no section headings. Be objective and professional: clearly call out \
                when the candidate missed important points from the expected answer or stayed too \
                superficial, and do not inflate a weak answer. Assess technical accuracy and \
                completeness against the expected key points, and acknowledge genuine strengths \
                only when they exist.

                %s

                Keep the tone professional and direct — neither harsh nor overly kind. Respond in \
                English. After the assessment, output the score on its own very last line in \
                exactly this format, with no extra words: RATING: n/%d
                """.formatted(whoLine, question, expectedAnswer, candidateAnswer, scaleGuide, max);
    }
}
