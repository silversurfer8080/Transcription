package org.example.stt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class GroqWhisperProviderTest {

    // ── extractText ───────────────────────────────────────────────────────

    @Test
    void extractText_returnsTextField() {
        assertEquals(" hello world ",
                GroqWhisperProvider.extractText("{\"text\":\" hello world \"}"));
    }

    @Test
    void extractText_missingField_returnsEmpty() {
        assertEquals("", GroqWhisperProvider.extractText("{\"foo\":\"bar\"}"));
    }

    @Test
    void extractText_invalidJson_returnsEmpty() {
        assertEquals("", GroqWhisperProvider.extractText("not json at all"));
    }

    // ── isSilent ──────────────────────────────────────────────────────────

    @Test
    void isSilent_allZeros_isSilent() {
        assertTrue(GroqWhisperProvider.isSilent(new byte[3200]));
    }

    @Test
    void isSilent_belowFloor_isSilent() {
        assertTrue(GroqWhisperProvider.isSilent(sample(499)));
    }

    @Test
    void isSilent_atFloor_isNotSilent() {
        assertFalse(GroqWhisperProvider.isSilent(sample(500)));
    }

    @Test
    void isSilent_loudSpeech_isNotSilent() {
        assertFalse(GroqWhisperProvider.isSilent(sample(12000)));
    }

    @Test
    void isSilent_negativePeak_isNotSilent() {
        // A single loud negative sample must also break the silence gate.
        assertFalse(GroqWhisperProvider.isSilent(sample(-9000)));
    }

    // ── buildMultipartBody ────────────────────────────────────────────────

    @Test
    void multipartBody_includesFieldsFileAndClosingBoundary() {
        byte[] file = {1, 2, 3, 4};
        byte[] body = GroqWhisperProvider.buildMultipartBody("BOUND", "audio.wav", file, "whisper-x", "en");
        String s = new String(body, StandardCharsets.ISO_8859_1);

        assertTrue(s.contains("--BOUND\r\n"), "opens with boundary");
        assertTrue(s.contains("name=\"model\""), "carries model field");
        assertTrue(s.contains("whisper-x"), "carries model value");
        assertTrue(s.contains("name=\"response_format\""), "carries response_format field");
        assertTrue(s.contains("name=\"language\""), "carries language field when set");
        assertTrue(s.contains("filename=\"audio.wav\""), "declares the file part");
        assertTrue(s.contains("Content-Type: audio/wav"), "sets file content type");
        assertTrue(s.contains(new String(file, StandardCharsets.ISO_8859_1)), "embeds raw file bytes");
        assertTrue(s.endsWith("--BOUND--\r\n"), "terminates with closing boundary");
    }

    @Test
    void multipartBody_omitsLanguageWhenBlank() {
        byte[] body = GroqWhisperProvider.buildMultipartBody("B", "audio.wav", new byte[]{9}, "m", null);
        String s = new String(body, StandardCharsets.ISO_8859_1);
        assertFalse(s.contains("name=\"language\""), "language field omitted when null");
    }

    // ── friendlyError ─────────────────────────────────────────────────────

    @Test
    void friendlyError_knownStatuses() {
        assertTrue(GroqWhisperProvider.friendlyError(401, "").contains("401"));
        assertTrue(GroqWhisperProvider.friendlyError(402, "").contains("402"));
        assertTrue(GroqWhisperProvider.friendlyError(429, "").contains("429"));
        assertEquals("Groq HTTP 500", GroqWhisperProvider.friendlyError(500, ""));
    }

    @Test
    void friendlyError_appendsApiDetail() {
        String msg = GroqWhisperProvider.friendlyError(429,
                "{\"error\":{\"message\":\"Rate limit reached\"}}");
        assertTrue(msg.contains("429"));
        assertTrue(msg.contains("Rate limit reached"));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** One 16-bit little-endian PCM sample with the given signed value. */
    private static byte[] sample(int value) {
        return new byte[] { (byte) (value & 0xFF), (byte) ((value >> 8) & 0xFF) };
    }
}
