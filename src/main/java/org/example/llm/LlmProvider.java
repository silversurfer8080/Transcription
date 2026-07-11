package org.example.llm;

/**
 * OpenAI-compatible chat-completions backends the evaluation can run against.
 *
 * <p>Groq, Google Gemini (via its OpenAI-compatibility layer) and Cerebras all
 * speak the same wire format: a {@code {model, messages}} request whose answer is
 * read from {@code choices[0].message.content}. Because of that, {@link GroqClient#call}
 * works unchanged across all three — only the {@link #endpoint()}, {@link #defaultModel()}
 * and the API key differ. Adding another OpenAI-compatible provider is a one-line
 * addition here.
 *
 * <p>Why these three, given the free tiers (mid-2026):
 * <ul>
 *   <li><b>Gemini Flash</b> — ~1,500 requests/day free, the most generous tier and an
 *       API key independent from Groq's shared STT+LLM budget.</li>
 *   <li><b>Cerebras</b> — Qwen3 (a strong open Chinese model), ~1M tokens/day free,
 *       extremely fast inference.</li>
 *   <li><b>Groq</b> — the original backend; fast, but its free budget is shared with
 *       Groq Whisper STT, which is what tends to exhaust it.</li>
 * </ul>
 */
public enum LlmProvider {

    GROQ("Groq — Llama 3.3 70B",
            "https://api.groq.com/openai/v1/chat/completions",
            "llama-3.3-70b-versatile"),

    GEMINI("Google Gemini — Flash",
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            "gemini-2.5-flash"),

    CEREBRAS("Cerebras — Qwen3 32B",
            "https://api.cerebras.ai/v1/chat/completions",
            "qwen-3-32b");

    private final String label;
    private final String endpoint;
    private final String defaultModel;

    LlmProvider(String label, String endpoint, String defaultModel) {
        this.label = label;
        this.endpoint = endpoint;
        this.defaultModel = defaultModel;
    }

    public String label()        { return label; }
    public String endpoint()     { return endpoint; }
    public String defaultModel() { return defaultModel; }

    /** Combo boxes render enum values with toString(); show the human label. */
    @Override
    public String toString() {
        return label;
    }
}
