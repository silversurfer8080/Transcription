package org.example.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Pure snapshot of the target Ollama model's VRAM state, plus static helpers for
 * parsing {@code /api/ps} responses and formatting countdowns.
 *
 * <p>No network, no JavaFX. Every method is deterministic given its inputs and is
 * safe to call from any thread.
 */
public record OllamaStatus(State state, long remainingSeconds, long sizeVramBytes) {

    public enum State { UNLOADED, PINNED, EXPIRING, UNAVAILABLE }

    /** Loaded entries expiring more than this far ahead are treated as pinned
     *  ("never expires"); Ollama reports keep_alive=-1 pins as the year ~2318. */
    static final Duration PIN_THRESHOLD = Duration.ofDays(365);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── pure static API (all deterministic, no I/O) ──────────────────────────

    /**
     * Native base URL from the OpenAI-compat endpoint: strips "/v1/chat/completions".
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "http://localhost:11434/v1/chat/completions"} → {@code "http://localhost:11434"}</li>
     *   <li>Any string without the suffix is returned unchanged (safe fallback).</li>
     * </ul>
     */
    public static String nativeBaseUrl(String openAiEndpoint) {
        int i = openAiEndpoint.indexOf("/v1/chat/completions");
        return i >= 0 ? openAiEndpoint.substring(0, i) : openAiEndpoint;
    }

    /**
     * Parses a {@code GET /api/ps} JSON body, finds the target model by name, and
     * derives a status snapshot relative to {@code now}.
     *
     * <p>Match is on the {@code name} <em>or</em> {@code model} field (Ollama returns
     * both; either suffices). A parse failure or missing field always yields
     * {@link #unloaded()} — never an exception and never {@link State#UNAVAILABLE}
     * (that state comes only from the impure layer on a failed HTTP call).
     */
    public static OllamaStatus fromPsJson(String psJson, String modelName, Instant now) {
        try {
            JsonNode root = MAPPER.readTree(psJson);
            JsonNode models = root.path("models");
            if (!models.isArray()) return unloaded();
            for (JsonNode entry : models) {
                String nameField  = entry.path("name").asText("");
                String modelField = entry.path("model").asText("");
                if (!modelName.equals(nameField) && !modelName.equals(modelField)) continue;

                // Found the target model
                long sizeVram = entry.path("size_vram").asLong(0);
                String expiresAtStr = entry.path("expires_at").asText("");
                if (expiresAtStr.isBlank()) {
                    // Model is loaded but expiry unparseable — treat as PINNED (never mislabel unloaded)
                    return new OllamaStatus(State.PINNED, 0, sizeVram);
                }
                try {
                    Instant expiresAt = OffsetDateTime.parse(expiresAtStr).toInstant();
                    if (expiresAt.isAfter(now.plus(PIN_THRESHOLD))) {
                        return new OllamaStatus(State.PINNED, 0, sizeVram);
                    } else {
                        long remaining = Math.max(0, Duration.between(now, expiresAt).getSeconds());
                        return new OllamaStatus(State.EXPIRING, remaining, sizeVram);
                    }
                } catch (Exception parseEx) {
                    // Unparseable expires_at but model is present — treat as PINNED
                    return new OllamaStatus(State.PINNED, 0, sizeVram);
                }
            }
            return unloaded();
        } catch (Exception e) {
            return unloaded();   // malformed JSON or unexpected structure
        }
    }

    /**
     * Formats remaining seconds as {@code "M:SS"}.
     * {@code secs <= 0} returns {@code "0:00"}; minutes are not zero-padded and may
     * exceed 59 (e.g. {@code "63:20"}).
     */
    public static String formatCountdown(long remainingSeconds) {
        if (remainingSeconds <= 0) return "0:00";
        return String.format("%d:%02d", remainingSeconds / 60, remainingSeconds % 60);
    }

    /** Sentinel: model not loaded. */
    public static OllamaStatus unloaded() {
        return new OllamaStatus(State.UNLOADED, 0, 0);
    }

    /** Sentinel: Ollama unreachable / status unknown. */
    public static OllamaStatus unavailable() {
        return new OllamaStatus(State.UNAVAILABLE, 0, 0);
    }
}
