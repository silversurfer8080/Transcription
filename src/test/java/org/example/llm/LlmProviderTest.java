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

    @Test
    void everyProvider_declaresPositiveTokenBudget() {
        for (LlmProvider p : LlmProvider.values()) {
            assertTrue(p.maxTokens() > 0, p + " must declare a positive max-tokens budget");
        }
    }

    @Test
    void thinkingModels_disableOrLimitReasoning_soTheRatingTailSurvives() {
        // Gemini 2.5 Flash and GPT-OSS are reasoning models: without capping reasoning
        // the trailing RATING/follow-up section gets truncated (regression from live test).
        assertEquals("none", LlmProvider.GEMINI.reasoningEffort(),
                "Gemini must fully disable thinking via reasoning_effort=none");
        assertNotNull(LlmProvider.CEREBRAS.reasoningEffort(),
                "Cerebras (GPT-OSS) must limit reasoning so output isn't truncated");
        assertTrue(LlmProvider.CEREBRAS.maxTokens() >= LlmProvider.GROQ.maxTokens(),
                "Reasoning models need at least as much headroom as the non-thinking baseline");
    }

    @Test
    void nonThinkingModel_omitsReasoningEffort() {
        assertNull(LlmProvider.GROQ.reasoningEffort(),
                "Llama 3.3 is not a thinking model — reasoning_effort must be omitted");
    }
}
