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
 *   <li><b>Gemini 2.5 Flash</b> — ~1,500 requests/day free, the most generous tier and
 *       an API key independent from Groq's shared STT+LLM budget.</li>
 *   <li><b>Cerebras</b> — GPT-OSS 120B, ~1M tokens/day free, extremely fast inference.
 *       (Cerebras retired its Qwen free-tier models in 2026; GPT-OSS 120B is Cerebras's
 *       own recommended migration target.)</li>
 *   <li><b>Groq</b> — the original backend; fast, but its free budget is shared with
 *       Groq Whisper STT, which is what tends to exhaust it.</li>
 * </ul>
 *
 * <h3>The "thinking" gotcha</h3>
 * <p>Gemini 2.5 Flash and GPT-OSS are hybrid <i>reasoning</i> models: by default they
 * spend part of the output-token budget on internal thinking, which can truncate the
 * visible answer before it reaches the trailing {@code RATING:} / follow-up section the
 * parsers depend on. Each provider therefore declares a {@link #reasoningEffort()}
 * (mapped to the OpenAI-compatible {@code reasoning_effort} field: {@code "none"} fully
 * disables Gemini's thinking; {@code "low"} minimizes GPT-OSS's) plus a per-provider
 * {@link #maxTokens()} headroom. Non-thinking models (Llama 3.3) leave it {@code null}.
 */
public enum LlmProvider {

    GROQ("Groq — Llama 3.3 70B",
            "https://api.groq.com/openai/v1/chat/completions",
            "llama-3.3-70b-versatile", null, 1280),

    GEMINI("Google Gemini — 2.5 Flash",
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            "gemini-2.5-flash", "none", 1280),

    CEREBRAS("Cerebras — GPT-OSS 120B",
            "https://api.cerebras.ai/v1/chat/completions",
            "gpt-oss-120b", "low", 2500);

    private final String label;
    private final String endpoint;
    private final String defaultModel;
    private final String reasoningEffort;   // OpenAI-compat reasoning_effort; null = omit
    private final int maxTokens;

    LlmProvider(String label, String endpoint, String defaultModel,
                String reasoningEffort, int maxTokens) {
        this.label = label;
        this.endpoint = endpoint;
        this.defaultModel = defaultModel;
        this.reasoningEffort = reasoningEffort;
        this.maxTokens = maxTokens;
    }

    public String label()           { return label; }
    public String endpoint()        { return endpoint; }
    public String defaultModel()    { return defaultModel; }
    /** {@code reasoning_effort} to send ("none"/"low"/…), or null to omit the field. */
    public String reasoningEffort() { return reasoningEffort; }
    /** Output-token budget; larger for reasoning models so the tail isn't truncated. */
    public int maxTokens()          { return maxTokens; }

    /** Combo boxes render enum values with toString(); show the human label. */
    @Override
    public String toString() {
        return label;
    }
}
