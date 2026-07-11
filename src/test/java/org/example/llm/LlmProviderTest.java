package org.example.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LlmProviderTest {

    @Test
    void everyProvider_hasHttpsEndpointAndModel() {
        for (LlmProvider p : LlmProvider.values()) {
            assertTrue(p.endpoint().startsWith("https://"),
                    p + " must use an HTTPS endpoint");
            assertFalse(p.defaultModel().isBlank(),
                    p + " must declare a default model");
            assertFalse(p.label().isBlank(), p + " must have a display label");
        }
    }

    @Test
    void allProviders_useOpenAiChatCompletionsPath() {
        // The shared GroqClient.call() only works because each backend exposes the
        // OpenAI-compatible /chat/completions route.
        for (LlmProvider p : LlmProvider.values()) {
            assertTrue(p.endpoint().endsWith("/chat/completions"),
                    p + " must expose the OpenAI-compatible chat-completions route");
        }
    }

    @Test
    void toString_showsHumanLabel() {
        assertEquals(LlmProvider.GEMINI.label(), LlmProvider.GEMINI.toString());
    }

    @Test
    void endpoints_pointAtExpectedHosts() {
        assertTrue(LlmProvider.GROQ.endpoint().contains("api.groq.com"));
        assertTrue(LlmProvider.GEMINI.endpoint().contains("generativelanguage.googleapis.com"));
        assertTrue(LlmProvider.CEREBRAS.endpoint().contains("api.cerebras.ai"));
    }
}
