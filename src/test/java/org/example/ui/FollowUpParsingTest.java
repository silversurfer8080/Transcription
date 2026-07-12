package org.example.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link InterviewApp#parseFollowUps} — the parser that turns the model's
 * {@code FOLLOW-UP QUESTIONS:} section into radio options. Different LLM providers
 * format the header differently (plain, bold markdown, …); the parser must be
 * lenient or the follow-up block silently leaks into the analysis body instead of
 * becoming clickable options.
 */
class FollowUpParsingTest {

    @Test
    void plainHeader_parsesDashBullets() {
        String text = """
                Some analysis prose.

                FOLLOW-UP QUESTIONS:
                - First follow-up?
                - Second follow-up?
                RATING: 3/5""";
        List<String> ups = InterviewApp.parseFollowUps(text);
        assertEquals(List.of("First follow-up?", "Second follow-up?"), ups);
    }

    @Test
    void markdownBoldHeader_isStillRecognized() {
        // Regression: GPT-OSS (Cerebras) emitted "**FOLLOW-UP QUESTIONS:**", which the
        // old ^\s*FOLLOW... anchor missed, dumping the block into the analysis text.
        String text = """
                Overall a marginal answer.

                **FOLLOW-UP QUESTIONS:**
                - Can you write the regex with named groups?
                - How would you handle other date formats?""";
        List<String> ups = InterviewApp.parseFollowUps(text);
        assertEquals(2, ups.size(), "bold-markdown header must still be recognized");
        assertTrue(ups.get(0).startsWith("Can you write the regex"));
        assertFalse(ups.get(0).contains("*"), "surrounding markdown must be stripped");
    }

    @Test
    void boldedIndividualLines_stripMarkdown() {
        String text = """
                FOLLOW-UP QUESTIONS:
                - **What about thread safety?**
                - *And performance?*""";
        List<String> ups = InterviewApp.parseFollowUps(text);
        assertEquals(List.of("What about thread safety?", "And performance?"), ups);
    }

    @Test
    void numberedBullets_areParsed() {
        String text = """
                FOLLOW-UP QUESTIONS:
                1. First?
                2. Second?""";
        List<String> ups = InterviewApp.parseFollowUps(text);
        assertEquals(List.of("First?", "Second?"), ups);
    }

    @Test
    void stopsAtRatingLine() {
        String text = """
                FOLLOW-UP QUESTIONS:
                - Only follow-up?
                RATING: 4/5""";
        List<String> ups = InterviewApp.parseFollowUps(text);
        assertEquals(List.of("Only follow-up?"), ups, "the RATING line must not become a follow-up");
    }

    @Test
    void noHeader_returnsEmpty() {
        assertTrue(InterviewApp.parseFollowUps("Just prose, no follow-up section.").isEmpty());
    }
}
