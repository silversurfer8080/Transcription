package org.example.stt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the pure, network-free helpers of {@link LocalWhisperProvider}: the base-URL
 * normalization ({@link LocalWhisperProvider#endpointUrl}) and the status-to-message mapping
 * ({@link LocalWhisperProvider#friendlyError}). The multipart body and JSON parsing are reused
 * from {@link GroqWhisperProvider} and already covered by {@code GroqWhisperProviderTest}.
 */
class LocalWhisperProviderTest {

    private static final String SUFFIX = "/v1/audio/transcriptions";

    @Test
    void endpointUrl_appendsRouteToBareBase() {
        assertEquals("http://localhost:8000" + SUFFIX,
                LocalWhisperProvider.endpointUrl("http://localhost:8000"));
    }

    @Test
    void endpointUrl_stripsTrailingSlashesBeforeAppending() {
        assertEquals("http://localhost:8000" + SUFFIX,
                LocalWhisperProvider.endpointUrl("http://localhost:8000/"));
        assertEquals("http://localhost:8000" + SUFFIX,
                LocalWhisperProvider.endpointUrl("http://localhost:8000///"));
    }

    @Test
    void endpointUrl_isIdempotentWhenRouteAlreadyPresent() {
        String full = "http://localhost:8000" + SUFFIX;
        assertEquals(full, LocalWhisperProvider.endpointUrl(full),
                "an already-complete endpoint must not gain a second /v1/audio/transcriptions");
    }

    @Test
    void endpointUrl_trimsWhitespace() {
        assertEquals("http://localhost:8000" + SUFFIX,
                LocalWhisperProvider.endpointUrl("   http://localhost:8000  "));
    }

    @Test
    void endpointUrl_blankOrNull_fallsBackToDefaultBase() {
        String expected = LocalWhisperProvider.DEFAULT_BASE_URL + SUFFIX;
        assertEquals(expected, LocalWhisperProvider.endpointUrl(""));
        assertEquals(expected, LocalWhisperProvider.endpointUrl("   "));
        assertEquals(expected, LocalWhisperProvider.endpointUrl(null));
    }

    @Test
    void endpointUrl_honorsCustomHostAndPort() {
        assertEquals("http://192.168.0.10:9000" + SUFFIX,
                LocalWhisperProvider.endpointUrl("http://192.168.0.10:9000"));
    }

    @Test
    void friendlyError_404_mentionsMissingModel() {
        String msg = LocalWhisperProvider.friendlyError(404, "{}");
        assertTrue(msg.contains("404"));
        assertTrue(msg.toLowerCase().contains("modelo"), "404 must point at the model name");
    }

    @Test
    void friendlyError_422_and_500_haveDistinctMessages() {
        assertTrue(LocalWhisperProvider.friendlyError(422, "").contains("422"));
        assertTrue(LocalWhisperProvider.friendlyError(500, "").contains("500"));
        assertNotEquals(LocalWhisperProvider.friendlyError(422, ""),
                LocalWhisperProvider.friendlyError(500, ""));
    }

    @Test
    void friendlyError_unknownStatus_fallsBackToGenericHttpLine() {
        assertEquals("Whisper local HTTP 503", LocalWhisperProvider.friendlyError(503, ""));
    }
}
