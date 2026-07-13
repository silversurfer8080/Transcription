package org.example.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link AnalysisResult#parse} — the single entry point that turns a model's
 * free-form evaluation reply into {@code (prose, score, max, followUps)}, plus the
 * {@link AnalysisResult#stars()} presentation helper.
 *
 * <p>The follow-up header cases guard a real regression: different providers format the
 * {@code FOLLOW-UP QUESTIONS:} header differently (plain, bold markdown, …); the parser
 * must be lenient or the block silently leaks into the prose instead of becoming radios.
 * The rating and star-band cases were previously untested (a gap flagged in review).
 */
class AnalysisResultTest {

    // ── Full contract: prose + rating + follow-ups in one realistic reply ─────

    @Test
    void parse_realisticReply_splitsProseRatingAndFollowUps() {
        String reply = """
                The candidate explained thread pools clearly and gave a concrete example.
                Depth on failure handling was thin.

                FOLLOW-UP QUESTIONS:
                - How would you size the pool?
                - What happens on task rejection?
                RATING: 4/5""";
        AnalysisResult r = AnalysisResult.parse(reply, 5);
        assertEquals(4, r.score(), "score parsed from RATING line");
        assertEquals(5, r.max(),   "max parsed from RATING line");
        assertTrue(r.hasRating());
        assertEquals(List.of("How would you size the pool?", "What happens on task rejection?"),
                r.followUps(), "both follow-ups parsed, RATING line excluded");
        assertTrue(r.prose().startsWith("The candidate explained"),
                "prose keeps the analysis body");
        assertFalse(r.prose().contains("FOLLOW-UP"), "follow-up section stripped from prose");
        assertFalse(r.prose().contains("RATING:"),   "rating line stripped from prose");
    }

    // ── Rating parsing ────────────────────────────────────────────────────────

    @Test
    void parse_noRatingLine_scoreMinusOneAndFallbackMax() {
        AnalysisResult r = AnalysisResult.parse("Just prose, no rating.", 5);
        assertEquals(-1, r.score(), "absent RATING must yield score -1");
        assertEquals(5, r.max(), "max falls back to the requested scale");
        assertFalse(r.hasRating());
    }

    @Test
    void parse_scale10_rating() {
        AnalysisResult r = AnalysisResult.parse("Solid.\nRATING: 8/10", 5);
        assertEquals(8, r.score());
        assertEquals(10, r.max(), "max comes from the RATING line, not the fallback");
    }

    @Test
    void parse_ratingToleratesSpacesAroundSlash() {
        AnalysisResult r = AnalysisResult.parse("RATING: 3 / 5", 5);
        assertEquals(3, r.score());
        assertEquals(5, r.max());
    }

    @Test
    void parse_ratingIsCaseInsensitive() {
        AnalysisResult r = AnalysisResult.parse("rating: 2/5", 5);
        assertEquals(2, r.score(), "lowercase 'rating:' must still match (case-insensitive)");
    }

    // ── prose stripping ───────────────────────────────────────────────────────

    @Test
    void parse_prose_stripsRatingLineOnly() {
        AnalysisResult r = AnalysisResult.parse("Good structure, weak examples.\nRATING: 3/5", 5);
        assertEquals("Good structure, weak examples.", r.prose());
    }

    @Test
    void parse_prose_stripsFollowUpSectionAndRating() {
        String reply = """
                Concise and correct.

                FOLLOW-UP QUESTIONS:
                - One more?
                RATING: 5/5""";
        assertEquals("Concise and correct.", AnalysisResult.parse(reply, 5).prose());
    }

    // ── stars() rendering + level bands ───────────────────────────────────────

    @Test
    void stars_rendersFilledEmptyAndBand() {
        AnalysisResult r = new AnalysisResult("", 3, 5, List.of());
        assertEquals("★★★☆☆  3/5 — Satisfactory", r.stars());
    }

    @Test
    void stars_scale10_mapsBandProportionally() {
        AnalysisResult r = new AnalysisResult("", 8, 10, List.of());
        assertEquals("★★★★★★★★☆☆  8/10 — Very Good", r.stars());
    }

    @Test
    void stars_noRating_returnsEmpty() {
        assertEquals("", new AnalysisResult("body", -1, 5, List.of()).stars(),
                "no rating must render no stars");
    }

    @Test
    void stars_scoreAboveMax_isClamped() {
        // A model that returns "RATING: 7/5" must not overflow the star bar.
        AnalysisResult r = new AnalysisResult("", 7, 5, List.of());
        assertEquals("★★★★★  5/5 — Excellent", r.stars());
    }

    @Test
    void stars_bandBoundaries_onFivePointScale() {
        assertTrue(new AnalysisResult("", 1, 5, List.of()).stars().endsWith("Unsatisfactory"));
        assertTrue(new AnalysisResult("", 2, 5, List.of()).stars().endsWith("Needs Improvement"));
        assertTrue(new AnalysisResult("", 3, 5, List.of()).stars().endsWith("Satisfactory"));
        assertTrue(new AnalysisResult("", 4, 5, List.of()).stars().endsWith("Very Good"));
        assertTrue(new AnalysisResult("", 5, 5, List.of()).stars().endsWith("Excellent"));
    }

    // ── Follow-up header leniency (migrated from FollowUpParsingTest) ──────────

    @Test
    void followUps_plainHeader_parsesDashBullets() {
        String text = """
                Some analysis prose.

                FOLLOW-UP QUESTIONS:
                - First follow-up?
                - Second follow-up?
                RATING: 3/5""";
        assertEquals(List.of("First follow-up?", "Second follow-up?"),
                AnalysisResult.parse(text, 5).followUps());
    }

    @Test
    void followUps_markdownBoldHeader_isStillRecognized() {
        // Regression: GPT-OSS (Cerebras) emitted "**FOLLOW-UP QUESTIONS:**", which the
        // old ^\s*FOLLOW... anchor missed, dumping the block into the analysis text.
        String text = """
                Overall a marginal answer.

                **FOLLOW-UP QUESTIONS:**
                - Can you write the regex with named groups?
                - How would you handle other date formats?""";
        List<String> ups = AnalysisResult.parse(text, 5).followUps();
        assertEquals(2, ups.size(), "bold-markdown header must still be recognized");
        assertTrue(ups.get(0).startsWith("Can you write the regex"));
        assertFalse(ups.get(0).contains("*"), "surrounding markdown must be stripped");
    }

    @Test
    void followUps_boldedIndividualLines_stripMarkdown() {
        String text = """
                FOLLOW-UP QUESTIONS:
                - **What about thread safety?**
                - *And performance?*""";
        assertEquals(List.of("What about thread safety?", "And performance?"),
                AnalysisResult.parse(text, 5).followUps());
    }

    @Test
    void followUps_numberedBullets_areParsed() {
        String text = """
                FOLLOW-UP QUESTIONS:
                1. First?
                2. Second?""";
        assertEquals(List.of("First?", "Second?"), AnalysisResult.parse(text, 5).followUps());
    }

    @Test
    void followUps_stopsAtRatingLine() {
        String text = """
                FOLLOW-UP QUESTIONS:
                - Only follow-up?
                RATING: 4/5""";
        assertEquals(List.of("Only follow-up?"), AnalysisResult.parse(text, 5).followUps(),
                "the RATING line must not become a follow-up");
    }

    @Test
    void followUps_noHeader_returnsEmpty() {
        assertTrue(AnalysisResult.parse("Just prose, no follow-up section.", 5).followUps().isEmpty());
    }

    // ── Reasoning-model header deviations (Gemini / Cerebras preamble) ─────────

    @Test
    void followUps_preambleBeforeHeader_isParsed() {
        // Gemini / GPT-OSS often paraphrase and prefix the header with a lead-in
        // sentence — the strict start-of-line anchor missed this, leaking the whole
        // block into the prose and showing no radios.
        String text = """
                The candidate was solid but shallow on trade-offs.

                Here are three follow-up questions to probe deeper:
                - How would you size the connection pool?
                - What breaks under contention?
                - How do you detect a leak?
                RATING: 3/5""";
        AnalysisResult r = AnalysisResult.parse(text, 5);
        assertEquals(3, r.followUps().size(), "preamble lead-in must not hide the header");
        assertEquals("How would you size the connection pool?", r.followUps().get(0));
        assertFalse(r.prose().contains("follow-up questions to probe deeper"),
                "the header line and everything after must be stripped from the prose");
        assertFalse(r.prose().contains("How would you size"),
                "the follow-up questions must not leak into the analysis body");
    }

    @Test
    void followUps_shortPreambleHeader_isParsed() {
        String text = """
                Reasonable answer.

                Suggested follow-up questions:
                - One?
                - Two?""";
        assertEquals(List.of("One?", "Two?"), AnalysisResult.parse(text, 5).followUps());
    }

    @Test
    void followUps_strictHeaderStillPreferredOverProseMention() {
        // A prose sentence mentions "follow-up questions" (ending in a period), and the
        // real header appears later. The section must be cut at the REAL header, leaving
        // the prose mention intact.
        String text = """
                I would ask follow-up questions about caching before moving on.

                FOLLOW-UP QUESTIONS:
                - Real follow-up?""";
        AnalysisResult r = AnalysisResult.parse(text, 5);
        assertEquals(List.of("Real follow-up?"), r.followUps());
        assertTrue(r.prose().contains("ask follow-up questions about caching"),
                "a mid-prose mention ending in a period must survive in the analysis body");
    }

    @Test
    void followUps_proseMentionEndingInPeriod_notTreatedAsHeader() {
        // No real header at all: a prose-only sentence mentioning the phrase must NOT be
        // mistaken for a header (it ends in a period, not a colon), so nothing is parsed
        // and nothing is stripped.
        String text = "The candidate rambled; I would prepare follow-up questions later.";
        AnalysisResult r = AnalysisResult.parse(text, 5);
        assertTrue(r.followUps().isEmpty(), "a period-terminated mention is not a header");
        assertEquals(text, r.prose(), "prose must be left intact when there is no real header");
    }
}
