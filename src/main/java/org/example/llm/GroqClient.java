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
                                        String expectedAnswer, String candidateAnswer)
            throws Exception {
        return call(apiKey, buildEvaluationPrompt(question, expectedAnswer, candidateAnswer));
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

    private static String buildErrorMessage(int status, String body) {
        String detail = "";
        try {
            detail = MAPPER.readTree(body).path("error").path("message").asText("");
        } catch (Exception ignored) {}
        return "HTTP_" + status + (detail.isEmpty() ? "" : " — " + detail);
    }

    private static String buildPronunciationPrompt(String transcript, String focusWord) {
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

    private static String buildEvaluationPrompt(String question, String expectedAnswer,
                                                 String candidateAnswer) {
        return """
                Você é um avaliador técnico experiente em entrevistas de tecnologia.

                **Pergunta feita ao candidato:**
                %s

                **Resposta esperada (gabarito / pontos-chave):**
                %s

                **Transcrição da resposta do candidato:**
                %s

                Avalie a resposta considerando:
                1. **Precisão técnica** — acertou os conceitos centrais?
                2. **Completude** — cobriu os pontos essenciais do gabarito?
                3. **Pontos fortes**
                4. **Lacunas ou erros** importantes
                5. **Veredicto:** Excelente / Bom / Parcial / Insuficiente — com justificativa em 1-2 frases

                Responda em português, de forma direta e estruturada.
                """.formatted(question, expectedAnswer, candidateAnswer);
    }
}
