package org.example.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The structured result of an LLM answer evaluation, parsed from the free-form text
 * that {@link GroqClient#evaluateExchange} returns.
 *
 * <p>The evaluation prompts (see {@code GroqClient}) instruct the model to end its
 * reply with a {@code FOLLOW-UP QUESTIONS:} section and a final {@code RATING: n/max}
 * line. This record makes that otherwise "stringly-typed" contract explicit: a single
 * {@link #parse} call turns the raw reply into {@code (prose, score, max, followUps)},
 * replacing the scattered strip/parse helpers the UI used to chain by hand.
 *
 * <p><b>Change the tail format of either prompt and you must change the patterns here</b>,
 * or the star rating / follow-up radios silently break.
 *
 * @param prose     the analysis body, with the follow-up section and rating line removed
 * @param score     the parsed rating, or {@code -1} when no {@code RATING:} line was present
 * @param max       the rating scale (parsed from the {@code RATING:} line, else the fallback)
 * @param followUps the proposed follow-up questions (empty when none were found)
 */
public record AnalysisResult(String prose, int score, int max, List<String> followUps) {

    private static final Pattern RATING_PATTERN =
            Pattern.compile("(?im)^\\s*RATING:\\s*(\\d+)\\s*/\\s*(\\d+)\\s*$");

    // Leading [\s>#*_-]* tolerates markdown the model may wrap the header in
    // (e.g. GPT-OSS emits "**FOLLOW-UP QUESTIONS:**"); the trailing \b.*$ makes the
    // colon optional and swallows any trailing "**". Without this the whole follow-up
    // block leaks into the analysis body and no radios appear.
    private static final Pattern FOLLOWUP_HEADER_PATTERN =
            Pattern.compile("(?im)^[\\s>#*_-]*FOLLOW-?\\s?UP\\s+QUESTIONS\\b.*$");

    /**
     * Parses a raw model reply into an {@link AnalysisResult}.
     *
     * @param rawText     the model's free-form reply
     * @param fallbackMax the rating scale to assume when no {@code RATING:} line is found
     */
    public static AnalysisResult parse(String rawText, int fallbackMax) {
        int[] rating           = parseRating(rawText, fallbackMax);
        List<String> followUps = parseFollowUps(rawText);
        String prose           = stripRatingLine(stripFollowUpSection(rawText));
        return new AnalysisResult(prose, rating[0], rating[1], followUps);
    }

    /** Whether a {@code RATING:} line was present (i.e. {@link #score} is meaningful). */
    public boolean hasRating() { return score >= 0; }

    /**
     * Renders the score as a star bar with its named band, e.g.
     * {@code "★★★☆☆  3/5 — Satisfactory"}. Returns {@code ""} when there is no rating.
     */
    public String stars() {
        if (score < 0 || max <= 0) return "";
        int s = Math.max(0, Math.min(score, max));
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= max; i++) sb.append(i <= s ? '★' : '☆');
        return sb.append("  ").append(s).append('/').append(max)
                 .append(" — ").append(levelLabel(s, max)).toString();
    }

    // ── Parsing helpers (pure; package-private static so tests can reach them) ──

    // Returns {score, max}; score is -1 when no RATING line is present.
    static int[] parseRating(String text, int fallbackMax) {
        Matcher m = RATING_PATTERN.matcher(text);
        if (m.find()) return new int[]{ Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) };
        return new int[]{ -1, fallbackMax };
    }

    static String stripRatingLine(String text) {
        return text.replaceAll("(?im)^\\s*RATING:\\s*\\d+\\s*/\\s*\\d+\\s*$", "").trim();
    }

    static List<String> parseFollowUps(String text) {
        Matcher m = FOLLOWUP_HEADER_PATTERN.matcher(text);
        if (!m.find()) return List.of();
        String after = text.substring(m.end());
        List<String> result = new ArrayList<>();
        for (String line : after.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.toLowerCase().startsWith("rating:")) break;
            String stripped = trimmed
                    .replaceFirst("^\\s*(?:[-*•]\\s+|\\d+[.)]\\s+)", "")  // bullet / number prefix
                    .replaceAll("^\\*+|\\*+$", "")                          // surrounding **markdown**
                    .trim();
            if (!stripped.isEmpty()) result.add(stripped);
        }
        return result;
    }

    static String stripFollowUpSection(String text) {
        Matcher m = FOLLOWUP_HEADER_PATTERN.matcher(text);
        if (m.find()) return text.substring(0, m.start());
        return text;
    }

    // Maps a score onto the 5 named bands, proportionally for any scale (5 or 10).
    private static String levelLabel(int score, int max) {
        int level = Math.max(1, Math.min(5, (int) Math.ceil(score * 5.0 / max)));
        return switch (level) {
            case 1 -> "Unsatisfactory";
            case 2 -> "Needs Improvement";
            case 3 -> "Satisfactory";
            case 4 -> "Very Good";
            default -> "Excellent";
        };
    }
}
