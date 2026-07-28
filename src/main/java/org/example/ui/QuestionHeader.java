package org.example.ui;

/**
 * Pure, JavaFX-free helper that composes the title string for a
 * {@code QuestionPanel}'s collapsible {@code TitledPane}.
 *
 * <p>The title format is:
 * <pre>
 *   "Question N"                               — no question text, no rating
 *   "Question N: \"&lt;preview&gt;\""          — question only
 *   "Question N  &lt;stars&gt;"               — rating only
 *   "Question N  &lt;stars&gt;: \"&lt;preview&gt;\""  — rating + question
 * </pre>
 *
 * <p>Stars are placed immediately after {@code "Question N"} and before the
 * question preview so they remain visible even when the panel is narrow and the
 * question tail ellipsizes.
 *
 * <p>This class is package-private so it can be tested headlessly from
 * {@code QuestionHeaderTest} without booting the JavaFX toolkit.
 */
final class QuestionHeader {

    private QuestionHeader() {}

    /** Maximum characters of question text shown in the header preview. */
    static final int MAX_PREVIEW = 55;

    /**
     * Composes the {@code TitledPane} header string for a question panel.
     *
     * <p>Rules:
     * <ul>
     *   <li>{@code null} inputs are treated as empty strings.</li>
     *   <li>{@code starsText} is trimmed; if it trims to empty it is treated as
     *       "no rating" (no stars segment in the output).</li>
     *   <li>{@code questionText} is trimmed and then has internal whitespace runs
     *       (including newlines and tabs) collapsed to single spaces, so the
     *       single-line header never contains raw line breaks.</li>
     *   <li>If the collapsed question exceeds {@link #MAX_PREVIEW} characters
     *       it is truncated to exactly {@link #MAX_PREVIEW} characters followed
     *       by a single U+2026 HORIZONTAL ELLIPSIS ({@code …}).</li>
     *   <li>A question exactly {@link #MAX_PREVIEW} characters long is not
     *       truncated.</li>
     * </ul>
     *
     * @param number       the 1-based question number
     * @param questionText the question body (null or empty → no preview appended)
     * @param starsText    the {@link org.example.llm.AnalysisResult#stars()} string
     *                     (null or blank → no stars segment)
     * @return the composed header string; never {@code null}
     */
    static String format(int number, String questionText, String starsText) {
        // Normalise inputs — null treated as empty
        String q     = (questionText == null) ? "" : questionText.trim();
        String stars = (starsText    == null) ? "" : starsText.trim();

        // Collapse internal whitespace in the question to a single space per run so
        // multi-line questions never inject raw newlines into the single-line header.
        if (!q.isEmpty()) {
            q = q.replaceAll("\\s+", " ").trim();
        }

        StringBuilder sb = new StringBuilder("Question ").append(number);

        if (!stars.isEmpty()) {
            sb.append("  ").append(stars);
        }

        if (!q.isEmpty()) {
            String preview = q.length() > MAX_PREVIEW
                    ? q.substring(0, MAX_PREVIEW) + "…"
                    : q;
            sb.append(": \"").append(preview).append('"');
        }

        return sb.toString();
    }
}
