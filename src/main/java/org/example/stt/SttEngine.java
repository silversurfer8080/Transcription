package org.example.stt;

/**
 * Selectable speech-to-text backends shown in the UI's "Transcrição" dropdown.
 *
 * <p>Each value maps to a concrete {@link SpeechToTextProvider}; the app builds the
 * provider from the selected engine plus the relevant credential (an API key for
 * {@link #GROQ_WHISPER}/{@link #GEMINI}, or a local model directory for {@link #VOSK}).
 */
public enum SttEngine {

    /** Offline, unlimited, no key — the only backend that never rate-limits. */
    VOSK("Vosk — offline, ilimitado"),

    /** Groq Whisper (batch). Great quality but shares Groq's daily budget with the LLM. */
    GROQ_WHISPER("Groq Whisper"),

    /** Google Gemini multimodal transcription (batch). Independent free tier from Groq. */
    GEMINI("Google Gemini");

    private final String label;

    SttEngine(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
