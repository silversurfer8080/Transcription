package org.example.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless JUnit 5 tests for {@link QuestionHeader#format} — the pure static helper
 * that composes the {@code TitledPane} title string for a collapsed {@code QuestionPanel}.
 *
 * <p>No JavaFX toolkit is required; the helper is intentionally JavaFX-free per NFR3.
 * The test class lives in {@code package org.example.ui} to reach the package-private
 * helper (same pattern as {@code AppStylesheetTest}).
 *
 * <p>Test naming mirrors the spec section references:
 * E1–E8 = edge cases (§10), FR = functional requirements (§2), AC = acceptance criteria (§15).
 */
class QuestionHeaderTest {

    // ── MAX_PREVIEW constant ──────────────────────────────────────────────────

    /** Spec §5 / AC6: the constant must be 55 so the truncation boundary is predictable. */
    @Test
    void maxPreview_constant_is55() {
        assertEquals(55, QuestionHeader.MAX_PREVIEW,
                "MAX_PREVIEW must equal 55 (current truncation length per spec §5)");
    }

    // ── E1 / FR2 / AC2: no question, no stars → bare "Question N" ─────────────

    @Test
    void emptyQuestion_emptyStars_returnsBareQuestionNumber() {
        // spec §8 example: format(1, "", "") → "Question 1"
        assertEquals("Question 1", QuestionHeader.format(1, "", ""),
                "empty question and empty stars must produce just 'Question N'");
    }

    @Test
    void questionIsBlankString_emptyStars_returnsBareQuestionNumber() {
        // whitespace-only question → trimmed to empty → same as no question (E5)
        assertEquals("Question 2", QuestionHeader.format(2, "   ", ""),
                "whitespace-only questionText must be treated as empty");
    }

    // ── E4: null inputs treated as empty strings ──────────────────────────────

    @Test
    void nullQuestion_nullStars_returnsBareQuestionNumber() {
        // spec §8 example: format(1, null, null) → "Question 1"
        assertEquals("Question 1", QuestionHeader.format(1, null, null),
                "null inputs must be treated as empty strings");
    }

    @Test
    void nullQuestion_emptyStars_returnsBareQuestionNumber() {
        assertEquals("Question 3", QuestionHeader.format(3, null, ""));
    }

    @Test
    void emptyQuestion_nullStars_returnsBareQuestionNumber() {
        assertEquals("Question 5", QuestionHeader.format(5, "", null));
    }

    // ── E3 / FR2 / AC2: question only, no stars — backward-compatible format ──

    @Test
    void questionOnly_short_byteIdenticalToPreChangeFormat() {
        // spec §8 example: format(2, "How do you handle concurrency?", "")
        // → "Question 2: \"How do you handle concurrency?\""
        // AC2: must be byte-identical to the pre-change "Question N: \"preview\"" format.
        String q = "How do you handle concurrency?";
        String expected = "Question 2: \"" + q + "\"";
        assertEquals(expected, QuestionHeader.format(2, q, ""),
                "question-only header must be byte-identical to the legacy format");
    }

    @Test
    void nullStars_questionPresent_sameAsEmptyStars() {
        // E4: null starsText → same result as ""
        String q = "Describe your testing strategy.";
        assertEquals(QuestionHeader.format(7, q, ""),
                QuestionHeader.format(7, q, null));
    }

    // ── E2: rating present, no question text → stars only, no trailing colon ──

    @Test
    void starsOnly_noQuestion_noTrailingColon() {
        // spec §8 example: format(4, "", "★★★★★  5/5 — Excellent")
        // → "Question 4  ★★★★★  5/5 — Excellent"
        String stars = "★★★★★  5/5 — Excellent";
        assertEquals("Question 4  " + stars, QuestionHeader.format(4, "", stars),
                "stars-only header must not append a trailing ': \"\"'");
    }

    @Test
    void starsOnly_nullQuestion_noTrailingColon() {
        // E4 + E2: null question behaves same as empty
        String stars = "★★★★★  5/5 — Excellent";
        assertEquals("Question 6  " + stars, QuestionHeader.format(6, null, stars));
    }

    // ── FR1: both stars and question → stars before preview (FR5: stars first, safe) ──

    @Test
    void starsAndQuestion_starsAppearBeforePreview() {
        // spec §8 example:
        // format(3, "How do you handle concurrency?", "★★★☆☆  3/5 — Satisfactory")
        // → "Question 3  ★★★☆☆  3/5 — Satisfactory: \"How do you handle concurrency?\""
        String stars = "★★★☆☆  3/5 — Satisfactory";
        String q     = "How do you handle concurrency?";
        String expected = "Question 3  " + stars + ": \"" + q + "\"";
        assertEquals(expected, QuestionHeader.format(3, q, stars),
                "stars must be placed between 'Question N' and the question preview");
    }

    @Test
    void starsAndQuestion_starsSeparatedByTwoSpaces() {
        // The spec mandates two spaces between "Question N" and the stars segment.
        String result = QuestionHeader.format(1, "Some question?", "★★  1/5 — Unsatisfactory");
        assertTrue(result.startsWith("Question 1  ★"),
                "two spaces must separate 'Question N' from the stars");
    }

    // ── E7: truncation at MAX_PREVIEW (55 chars) ──────────────────────────────

    @Test
    void questionExactly55chars_notTruncated_noEllipsis() {
        // E7: exactly MAX_PREVIEW chars → no truncation, no ellipsis
        String q55 = "12345678901234567890123456789012345678901234567890ABCDE"; // 50+5 = 55
        assertEquals(55, q55.length(), "test string must be exactly 55 chars");
        String result = QuestionHeader.format(1, q55, "");
        assertTrue(result.contains(q55),
                "question of exactly 55 chars must appear verbatim, without ellipsis");
        assertFalse(result.contains("…"),
                "no ellipsis must appear when question length == MAX_PREVIEW");
    }

    @Test
    void questionOf56chars_truncatedToFirst55charsWithEllipsis() {
        // E7: 56-char question → first 55 chars + "…"
        String q56 = "12345678901234567890123456789012345678901234567890ABCDEF"; // 50+6 = 56
        assertEquals(56, q56.length(), "test string must be exactly 56 chars");
        String first55 = q56.substring(0, 55);
        String result = QuestionHeader.format(2, q56, "");
        assertTrue(result.contains(first55 + "…"),
                "56-char question must be truncated to 55 chars followed by U+2026");
        assertFalse(result.contains("ABCDEF"),
                "the 56th character ('F') must not appear after truncation");
    }

    @Test
    void questionMuchLongerThan55chars_truncated() {
        // General long-question regression: a 100-char question must be clipped
        String long100 = "A".repeat(55) + "B".repeat(45); // 100 chars total
        String result = QuestionHeader.format(5, long100, "");
        assertTrue(result.contains("A".repeat(55) + "…"),
                "first 55 chars of long question must appear, followed by ellipsis");
        assertFalse(result.contains("B"),
                "characters beyond MAX_PREVIEW must be dropped");
    }

    @Test
    void truncation_withStars_previewIsStillCappedAt55() {
        // FR5: stars first, so even with stars present the question preview is still capped
        String long60 = "C".repeat(60);
        String stars  = "★★★★☆  4/5 — Very Good";
        String result = QuestionHeader.format(9, long60, stars);
        // Header must include stars; question must be capped at 55
        assertTrue(result.contains(stars), "stars must appear even when question is truncated");
        assertTrue(result.contains("C".repeat(55) + "…"),
                "question must still be truncated to 55 chars + ellipsis when stars are present");
        assertFalse(result.contains("C".repeat(56)),
                "the 56th 'C' must not appear after truncation");
    }

    // ── E5: leading/trailing whitespace in question text is trimmed ───────────

    @Test
    void questionWithLeadingTrailingWhitespace_trimmedBeforePreview() {
        String raw      = "   What is polymorphism?   ";
        String trimmed  = "What is polymorphism?";
        String result   = QuestionHeader.format(1, raw, "");
        assertTrue(result.contains("\"" + trimmed + "\""),
                "leading/trailing whitespace in questionText must be trimmed");
        assertFalse(result.contains("\"   "),
                "trimmed question must not start with spaces inside the quotes");
    }

    // ── E6: internal whitespace collapsed to single spaces ────────────────────

    @Test
    void questionWithEmbeddedNewlines_collapsedToSingleSpaces() {
        // E6: multi-line question must not inject raw newlines into a single-line header
        String multiline = "Line one\nline two\nline three";
        String result    = QuestionHeader.format(1, multiline, "");
        assertFalse(result.contains("\n"),
                "embedded newlines must be collapsed; header must not contain raw newlines");
        assertTrue(result.contains("Line one line two line three"),
                "newlines must be replaced with single spaces");
    }

    @Test
    void questionWithEmbeddedTabs_collapsedToSingleSpaces() {
        // E6: tabs (e.g. from a pasted text block) must also collapse
        String tabbed = "Part one\tpart two\tpart three";
        String result = QuestionHeader.format(1, tabbed, "");
        assertFalse(result.contains("\t"), "tabs must be collapsed");
        assertTrue(result.contains("Part one part two part three"),
                "tabs must be replaced with single spaces");
    }

    @Test
    void questionWithMultipleConsecutiveSpaces_collapsedToSingleSpace() {
        // E6: multiple spaces count as a whitespace run
        String padded = "What   is    polymorphism?";
        String result = QuestionHeader.format(1, padded, "");
        assertTrue(result.contains("What is polymorphism?"),
                "consecutive internal spaces must be collapsed to a single space");
    }

    @Test
    void questionWithMixedWhitespaceRuns_collapsedCorrectly() {
        // E6: mixed — tabs, newlines, multiple spaces all in one string
        String mixed = "Question\t\twith  multiple\n\nwhitespace runs";
        String result = QuestionHeader.format(1, mixed, "");
        assertTrue(result.contains("Question with multiple whitespace runs"),
                "all internal whitespace types must collapse to single spaces");
    }

    // ── E8: starsText whitespace handling ─────────────────────────────────────

    @Test
    void starsWithSurroundingWhitespace_trimmedAndUsed() {
        // E8: "  ★★★☆☆  3/5 — Satisfactory  " → trimmed to "★★★☆☆  3/5 — Satisfactory"
        String paddedStars = "  ★★★☆☆  3/5 — Satisfactory  ";
        String trimmedStars = "★★★☆☆  3/5 — Satisfactory";
        String result = QuestionHeader.format(1, "", paddedStars);
        assertEquals("Question 1  " + trimmedStars, result,
                "stars with surrounding whitespace must be trimmed before use");
    }

    @Test
    void whitespaceOnlyStars_treatedAsNoRating() {
        // E8: blank-after-trim starsText → treated as "no rating"
        assertEquals("Question 3", QuestionHeader.format(3, "", "   "),
                "whitespace-only starsText must be treated as empty (no rating)");
    }

    @Test
    void whitespaceOnlyStars_withQuestion_noStarsInOutput() {
        // E8 + E3: blank stars with a real question → question-only format (no stars segment)
        String q = "Describe your testing approach.";
        String result = QuestionHeader.format(2, q, "\t  \t");
        assertEquals("Question 2: \"" + q + "\"", result,
                "whitespace-only starsText must not inject a stars segment into the header");
    }

    // ── Multi-digit question numbers ──────────────────────────────────────────

    @Test
    void multiDigitQuestionNumber_rendersCorrectly() {
        assertEquals("Question 10", QuestionHeader.format(10, "", ""));
        assertEquals("Question 42", QuestionHeader.format(42, "", ""));
    }

    // ── Never throws ──────────────────────────────────────────────────────────

    @Test
    void format_neverThrows_forVariousEdgeInputs() {
        // Spec §8 / FR error behavior: format must never throw for any input.
        assertDoesNotThrow(() -> QuestionHeader.format(0, null, null));
        assertDoesNotThrow(() -> QuestionHeader.format(Integer.MAX_VALUE, null, null));
        assertDoesNotThrow(() -> QuestionHeader.format(1, "", ""));
        assertDoesNotThrow(() -> QuestionHeader.format(1, "x".repeat(1000), "y".repeat(1000)));
        assertDoesNotThrow(() -> QuestionHeader.format(1, "\n\t\r", "\n\t\r"));
    }

    // ── Integration-style: realistic rated header (AC1 / spec §8) ─────────────

    @Test
    void realisticRatedHeader_matchesSpecExample() {
        // AC1: star string verbatim from AnalysisResult.stars() between "Question N"
        // and the question preview (spec §8 full example).
        String stars    = "★★★☆☆  3/5 — Satisfactory";
        String question = "How do you handle concurrency?";
        String result   = QuestionHeader.format(3, question, stars);
        assertEquals(
                "Question 3  ★★★☆☆  3/5 — Satisfactory: \"How do you handle concurrency?\"",
                result,
                "full rated header must match the exact spec §8 example");
    }

    @Test
    void realisticUnratedHeader_matchesLegacyFormat() {
        // AC2: before any analysis, header is byte-identical to the legacy "Question N: \"...\"" form
        String question = "How do you handle concurrency?";
        String result   = QuestionHeader.format(2, question, "");
        assertEquals("Question 2: \"How do you handle concurrency?\"", result,
                "unrated header must be byte-identical to the pre-change format (AC2)");
    }
}
