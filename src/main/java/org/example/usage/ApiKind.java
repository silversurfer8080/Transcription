package org.example.usage;

/**
 * The two kinds of paid API call the app makes, each with its own "secondary" metering
 * unit beyond a plain request count: transcription bills by <b>audio-seconds</b>, chat
 * analysis by <b>tokens</b>. Used to label usage rows and to pick the right rate-limit
 * header when parsing a provider response.
 */
public enum ApiKind {

    STT("Transcrição", "audio-seconds", "s"),
    LLM("Análise", "tokens", "tok");

    private final String label;
    private final String rateLimitUnit;   // the x-ratelimit-*-<unit> header segment
    private final String shortUnit;       // compact label shown next to the number

    ApiKind(String label, String rateLimitUnit, String shortUnit) {
        this.label = label;
        this.rateLimitUnit = rateLimitUnit;
        this.shortUnit = shortUnit;
    }

    public String label()         { return label; }
    /** The header segment for the secondary limit, e.g. {@code x-ratelimit-remaining-<unit>}. */
    public String rateLimitUnit() { return rateLimitUnit; }
    public String shortUnit()     { return shortUnit; }
}
