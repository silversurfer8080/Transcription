package org.example.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Thin HTTP wrapper for Ollama's native API — pin, unload, and poll the target model.
 *
 * <p>Constructed once from the LOCAL provider constants. All methods are blocking and
 * must be called from a virtual thread (never the FX thread). Mirrors {@code GroqClient}'s
 * {@link HttpClient} + Jackson usage; uses a single shared client with a 2 s connect
 * timeout so a down Ollama fails fast.
 *
 * <p>Not unit-tested (network boundary); pure parsing lives in {@link OllamaStatus}.
 */
public final class OllamaKeepAlive {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;   // e.g. http://localhost:11434  (no trailing slash)
    private final String model;     // e.g. qwen2.5:7b-instruct
    private final HttpClient http;  // reused; connectTimeout ~2s

    /**
     * @param openAiEndpoint {@link LlmProvider#LOCAL}{@code .endpoint()}; the native base
     *                       URL is derived by stripping {@code /v1/chat/completions}.
     * @param model          {@link LlmProvider#LOCAL}{@code .defaultModel()}
     */
    public OllamaKeepAlive(String openAiEndpoint, String model) {
        this.baseUrl = OllamaStatus.nativeBaseUrl(openAiEndpoint);
        this.model   = model;
        this.http    = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /**
     * {@code POST /api/generate {"model":model,"keep_alive":-1}} — load and pin the
     * model in VRAM. A cold load may take ~16 s; the request timeout is 60 s.
     *
     * @throws Exception on non-200 response, connection failure, or timeout
     */
    public void pin() throws Exception {
        postGenerate(-1, Duration.ofSeconds(60));
    }

    /**
     * {@code POST /api/generate {"model":model,"keep_alive":0}} — free VRAM immediately.
     * Harmless if the model is not currently loaded. Timeout 10 s.
     *
     * @throws Exception on non-200 response, connection failure, or timeout
     */
    public void unload() throws Exception {
        postGenerate(0, Duration.ofSeconds(10));
    }

    /**
     * {@code GET /api/ps} → parse response via {@link OllamaStatus#fromPsJson}. Timeout 5 s.
     *
     * @return the model's current state
     * @throws Exception on non-200 response, connection failure, or timeout
     */
    public OllamaStatus poll() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/ps"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + " from /api/ps");
        }
        return OllamaStatus.fromPsJson(response.body(), model, Instant.now());
    }

    // Shared POST helper for pin (keep_alive=-1) and unload (keep_alive=0).
    private void postGenerate(int keepAlive, Duration timeout) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("keep_alive", keepAlive);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + " from /api/generate");
        }
    }
}
