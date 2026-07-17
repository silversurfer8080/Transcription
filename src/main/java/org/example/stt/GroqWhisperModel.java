package org.example.stt;

/**
 * Selectable Groq Whisper model, shown in the UI's "Modelo Whisper" dropdown only when
 * Groq is the chosen STT engine.
 *
 * <p><b>Turbo</b> is the speed-optimized distillation (low latency, the default, good for
 * clean/native speech). The full <b>large-v3</b> is more accurate on hard cases such as
 * strong accents, at the cost of a bit more latency. The Groq free-tier quota is billed by
 * audio-seconds, not by model, so the two cost the same for the same audio.
 */
public enum GroqWhisperModel {

    /** Distilled, fast — the default; good for clean/native speech. */
    TURBO("Turbo (rápido)", "whisper-large-v3-turbo"),

    /** Full large-v3 — more accurate on accents/hard audio, a bit slower. */
    LARGE_V3("Large-v3 (preciso, sotaque)", "whisper-large-v3");

    private final String label;
    private final String modelId;

    GroqWhisperModel(String label, String modelId) {
        this.label = label;
        this.modelId = modelId;
    }

    public String label() { return label; }

    /** The Groq API model identifier sent in the transcription request. */
    public String modelId() { return modelId; }

    /** Combo boxes render enum values with toString(); show the human label. */
    @Override
    public String toString() {
        return label;
    }
}
