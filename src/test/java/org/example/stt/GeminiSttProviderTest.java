package org.example.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeminiSttProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── buildRequestJson ──────────────────────────────────────────────────

    @Test
    void buildRequestJson_carriesInstructionAndInlineAudio() throws Exception {
        String body = GeminiSttProvider.buildRequestJson("QUJD");   // "ABC" base64
        JsonNode root = MAPPER.readTree(body);

        JsonNode parts = root.path("contents").path(0).path("parts");
        assertTrue(parts.isArray(), "request must carry a parts array");
        assertEquals(GeminiSttProvider.PROMPT, parts.path(0).path("text").asText(),
                "first part must be the transcription instruction");

        JsonNode inline = parts.path(1).path("inline_data");
        assertEquals("audio/wav", inline.path("mime_type").asText(),
                "audio part must be declared as audio/wav");
        assertEquals("QUJD", inline.path("data").asText(),
                "audio part must embed the base64 WAV verbatim");
    }

    @Test
    void buildRequestJson_setsDeterministicTemperature() throws Exception {
        JsonNode root = MAPPER.readTree(GeminiSttProvider.buildRequestJson("QQ=="));
        assertEquals(0, root.path("generationConfig").path("temperature").asInt(-1),
                "temperature must be 0 so the model transcribes rather than paraphrases");
    }

    // ── extractText ───────────────────────────────────────────────────────

    @Test
    void extractText_returnsCandidatePartText() {
        String json = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello world\"}]}}]}";
        assertEquals("hello world", GeminiSttProvider.extractText(json));
    }

    @Test
    void extractText_concatenatesMultipleParts() {
        String json = "{\"candidates\":[{\"content\":{\"parts\":["
                + "{\"text\":\"foo \"},{\"text\":\"bar\"}]}}]}";
        assertEquals("foo bar", GeminiSttProvider.extractText(json));
    }

    @Test
    void extractText_missingCandidates_returnsEmpty() {
        assertEquals("", GeminiSttProvider.extractText("{\"foo\":\"bar\"}"));
    }

    @Test
    void extractText_invalidJson_returnsEmpty() {
        assertEquals("", GeminiSttProvider.extractText("not json at all"));
    }

    // ── friendlyError ─────────────────────────────────────────────────────

    @Test
    void friendlyError_knownStatuses() {
        assertTrue(GeminiSttProvider.friendlyError(400, "").contains("400"));
        assertTrue(GeminiSttProvider.friendlyError(401, "").contains("401"));
        assertTrue(GeminiSttProvider.friendlyError(403, "").contains("403"));
        assertTrue(GeminiSttProvider.friendlyError(429, "").contains("429"));
        assertEquals("Gemini HTTP 500", GeminiSttProvider.friendlyError(500, ""));
    }

    @Test
    void friendlyError_appendsApiDetail() {
        String msg = GeminiSttProvider.friendlyError(429,
                "{\"error\":{\"message\":\"Quota exceeded\"}}");
        assertTrue(msg.contains("429"));
        assertTrue(msg.contains("Quota exceeded"));
    }
}
