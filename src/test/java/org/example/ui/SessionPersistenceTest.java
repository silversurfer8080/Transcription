package org.example.ui;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the pure, package-private static helpers
 * {@link InterviewApp#renderQuestionBlock} and {@link InterviewApp#parseSection},
 * and the persistence DTOs {@link InterviewApp.FollowUp} / {@link InterviewApp.LoadedQuestion}.
 *
 * <p>No JavaFX toolkit, audio device, or network access is required.
 */
class SessionPersistenceTest {

    /**
     * Simulates how {@code parseSessionFile} feeds lines to {@code parseSection}:
     * reads the rendered block, strips the trailing newline, splits on any line-ending
     * convention, and drops the first line (the {@code --- Pergunta N ---} header
     * that {@code parseSessionFile} consumes before accumulating body lines).
     */
    private static List<String> bodyLines(String rendered) {
        String text = rendered;
        // Strip exactly one trailing line separator (mirrors Files.readAllLines behaviour)
        if (text.endsWith("\r\n")) {
            text = text.substring(0, text.length() - 2);
        } else if (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }
        // Split on any line-ending convention
        String[] parts = text.split("\\r\\n|\\n|\\r", -1);
        // Drop the header line (index 0)
        return Arrays.asList(parts).subList(1, parts.length);
    }

    // ── renderQuestionBlock — zero follow-ups (backward compat) ──────────────

    @Test
    void renderQuestionBlock_zeroFollowUps_containsHeaderQuestionAnswer() {
        String block = InterviewApp.renderQuestionBlock(
                1, "What is a thread?", "A unit of execution.", List.of());
        assertTrue(block.contains("--- Pergunta 1 ---"),
                "Header line must appear");
        assertTrue(block.contains("What is a thread?"),
                "Question text must appear");
        assertTrue(block.contains("A unit of execution."),
                "Answer text must appear");
        assertFalse(block.contains("FOLLOW-UP"),
                "No FOLLOW-UP lines expected when follow-up list is empty");
    }

    @Test
    void renderQuestionBlock_zeroFollowUps_hasBlankLineBetweenQuestionAndAnswer() {
        String block = InterviewApp.renderQuestionBlock(2, "Q", "A", List.of());
        // bodyLines drops the header; remaining structure: [question, blank, answer]
        List<String> lines = bodyLines(block);
        assertEquals("Q", lines.get(0), "First body line must be the question");
        assertEquals("",  lines.get(1), "Second body line must be the blank separator");
        assertEquals("A", lines.get(2), "Third body line must be the answer");
    }

    @Test
    void renderQuestionBlock_nullFollowUps_treatedAsZeroRounds() {
        String block = InterviewApp.renderQuestionBlock(1, "Q", "A", null);
        assertFalse(block.contains("FOLLOW-UP"),
                "Null follow-up list must produce no FOLLOW-UP lines");
    }

    @Test
    void renderQuestionBlock_withFollowUps_emitsNumberedMarkerLines() {
        List<InterviewApp.FollowUp> fus = List.of(
                new InterviewApp.FollowUp("Tell me more about X?", "It means Y"),
                new InterviewApp.FollowUp("And what about Z?", "Z is like W")
        );
        String block = InterviewApp.renderQuestionBlock(3, "Q", "A", fus);
        assertTrue(block.contains("FOLLOW-UP 1: Tell me more about X?"),
                "First follow-up marker must appear with index 1");
        assertTrue(block.contains("It means Y"),
                "First follow-up answer must appear");
        assertTrue(block.contains("FOLLOW-UP 2: And what about Z?"),
                "Second follow-up marker must appear with index 2");
        assertTrue(block.contains("Z is like W"),
                "Second follow-up answer must appear");
        // Ordering: marker 1 before marker 2
        assertTrue(block.indexOf("FOLLOW-UP 1") < block.indexOf("FOLLOW-UP 2"),
                "Follow-up markers must appear in ascending order");
    }

    @Test
    void renderQuestionBlock_emptyFollowUpAnswer_producesEmptyAnswerLine() {
        List<InterviewApp.FollowUp> fus = List.of(
                new InterviewApp.FollowUp("Unanswered?", "")
        );
        String block = InterviewApp.renderQuestionBlock(1, "Q", "A", fus);
        List<String> lines = bodyLines(block);
        // Find the FOLLOW-UP 1 marker line
        int markerIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("FOLLOW-UP 1:")) { markerIdx = i; break; }
        }
        assertTrue(markerIdx >= 0, "FOLLOW-UP 1 marker line must be present");
        assertTrue(markerIdx + 1 < lines.size(),
                "A line must follow the FOLLOW-UP 1 marker");
        assertEquals("", lines.get(markerIdx + 1),
                "Empty follow-up answer must produce an empty line immediately after the marker");
    }

    // ── parseSection — backward compatibility ─────────────────────────────────

    @Test
    void parseSection_legacyQuestionBlankAnswer_parsesCorrectly() {
        List<String> lines = List.of("What is a virtual thread?", "", "It runs on carrier threads.");
        InterviewApp.LoadedQuestion lq = InterviewApp.parseSection(lines);
        assertEquals("What is a virtual thread?", lq.question(), "Question must be extracted");
        assertEquals("It runs on carrier threads.", lq.answer(), "Answer must be extracted");
        assertTrue(lq.followUps().isEmpty(),
                "Legacy question/blank/answer format must produce zero follow-ups");
    }

    @Test
    void parseSection_legacyAnswerOnly_emptyQuestionNoFollowUps() {
        // No blank line present → whole body treated as answer (answer-only legacy form)
        List<String> lines = List.of("It runs on carrier threads without blocking.");
        InterviewApp.LoadedQuestion lq = InterviewApp.parseSection(lines);
        assertEquals("", lq.question(),
                "Answer-only legacy format must yield an empty question");
        assertEquals("It runs on carrier threads without blocking.", lq.answer(),
                "Whole body must become the answer");
        assertTrue(lq.followUps().isEmpty(),
                "Answer-only format must produce zero follow-ups");
    }

    @Test
    void parseSection_withFollowUps_parsesRoundsInOrder() {
        List<String> lines = List.of(
                "Explain concurrency.",
                "",
                "Concurrency is about managing multiple tasks.",
                "",
                "FOLLOW-UP 1: Can you give an example?",
                "Yes, using ExecutorService.",
                "",
                "FOLLOW-UP 2: What about virtual threads?",
                "They are lightweight."
        );
        InterviewApp.LoadedQuestion lq = InterviewApp.parseSection(lines);
        assertEquals("Explain concurrency.", lq.question());
        assertEquals("Concurrency is about managing multiple tasks.", lq.answer());
        assertEquals(2, lq.followUps().size(), "Two follow-ups must be parsed");
        assertEquals("Can you give an example?",           lq.followUps().get(0).question());
        assertEquals("Yes, using ExecutorService.",         lq.followUps().get(0).answer());
        assertEquals("What about virtual threads?",         lq.followUps().get(1).question());
        assertEquals("They are lightweight.",               lq.followUps().get(1).answer());
    }

    @Test
    void parseSection_emptyFollowUpAnswer_preservedAsEmpty() {
        List<String> lines = List.of(
                "Q",
                "",
                "A",
                "",
                "FOLLOW-UP 1: Unanswered follow-up?",
                ""
        );
        InterviewApp.LoadedQuestion lq = InterviewApp.parseSection(lines);
        assertEquals(1, lq.followUps().size(),
                "A follow-up with an empty answer must still be parsed as one round");
        assertEquals("Unanswered follow-up?", lq.followUps().get(0).question());
        assertEquals("", lq.followUps().get(0).answer(),
                "Empty follow-up answer must be preserved (not null or absent)");
    }

    @Test
    void parseSection_legacyInlineFollowUpText_notMisParsedAsMarker() {
        // Old sessions contain "FOLLOW UP QUESTION ->" (space not hyphen, no digits, no colon).
        // The strict regex FOLLOWUP_LINE_PATTERN must not match this legacy text.
        List<String> lines = List.of(
                "Describe your debugging process.",
                "",
                "I use breakpoints. FOLLOW UP QUESTION -> How do you handle async? " +
                "FOLLOW UP ANSWER -> With logs."
        );
        InterviewApp.LoadedQuestion lq = InterviewApp.parseSection(lines);
        assertEquals("Describe your debugging process.", lq.question());
        assertTrue(lq.answer().contains("FOLLOW UP QUESTION"),
                "Legacy FOLLOW UP QUESTION text must remain part of the answer");
        assertTrue(lq.followUps().isEmpty(),
                "Legacy 'FOLLOW UP QUESTION ->' text must NOT be treated as a structured follow-up");
    }

    @Test
    void parseSection_singleFollowUpAtEndOfSection_parsedCorrectly() {
        List<String> lines = List.of(
                "Define heap.",
                "",
                "Memory area for objects.",
                "",
                "FOLLOW-UP 1: What happens when it's full?",
                "OutOfMemoryError is thrown."
        );
        InterviewApp.LoadedQuestion lq = InterviewApp.parseSection(lines);
        assertEquals("Define heap.", lq.question());
        assertEquals("Memory area for objects.", lq.answer());
        assertEquals(1, lq.followUps().size());
        assertEquals("What happens when it's full?", lq.followUps().get(0).question());
        assertEquals("OutOfMemoryError is thrown.", lq.followUps().get(0).answer());
    }

    // ── Round-trip tests (renderQuestionBlock → bodyLines → parseSection) ─────

    @Test
    void roundTrip_zeroFollowUps_preservesQuestionAndAnswer() {
        String rendered = InterviewApp.renderQuestionBlock(
                1, "What is JVM?", "Java Virtual Machine.", List.of());
        InterviewApp.LoadedQuestion lq = InterviewApp.parseSection(bodyLines(rendered));
        assertEquals("What is JVM?",         lq.question(),       "Question survives round-trip");
        assertEquals("Java Virtual Machine.", lq.answer(),         "Answer survives round-trip");
        assertTrue(lq.followUps().isEmpty(),                       "Zero rounds survive round-trip");
    }

    @Test
    void roundTrip_withFollowUps_preservesAllRounds() {
        List<InterviewApp.FollowUp> fus = List.of(
                new InterviewApp.FollowUp("What is GC?",        "Automatic memory management."),
                new InterviewApp.FollowUp("When does GC run?",  "When heap is low.")
        );
        String rendered = InterviewApp.renderQuestionBlock(
                1, "Explain JVM.", "It executes bytecode.", fus);
        InterviewApp.LoadedQuestion lq = InterviewApp.parseSection(bodyLines(rendered));
        assertEquals("Explain JVM.",       lq.question());
        assertEquals("It executes bytecode.", lq.answer());
        assertEquals(2, lq.followUps().size(), "Follow-up count must survive round-trip");
        assertEquals("What is GC?",                  lq.followUps().get(0).question());
        assertEquals("Automatic memory management.", lq.followUps().get(0).answer());
        assertEquals("When does GC run?",            lq.followUps().get(1).question());
        assertEquals("When heap is low.",            lq.followUps().get(1).answer());
    }

    @Test
    void roundTrip_emptyFollowUpAnswer_preservesRoundCountAndEmptyAnswer() {
        List<InterviewApp.FollowUp> fus = List.of(
                new InterviewApp.FollowUp("Unanswered follow-up?", "")
        );
        String rendered = InterviewApp.renderQuestionBlock(1, "Q", "A", fus);
        InterviewApp.LoadedQuestion lq = InterviewApp.parseSection(bodyLines(rendered));
        assertEquals(1, lq.followUps().size(),
                "Round count must survive round-trip even with empty answer");
        assertEquals("Unanswered follow-up?", lq.followUps().get(0).question(),
                "Follow-up question must survive round-trip");
        assertEquals("", lq.followUps().get(0).answer(),
                "Empty follow-up answer must survive round-trip as empty string");
    }

    @Test
    void roundTrip_threeFollowUps_allPreservedInOrder() {
        List<InterviewApp.FollowUp> fus = List.of(
                new InterviewApp.FollowUp("Follow Q1", "Answer 1"),
                new InterviewApp.FollowUp("Follow Q2", "Answer 2"),
                new InterviewApp.FollowUp("Follow Q3", "Answer 3")
        );
        String rendered = InterviewApp.renderQuestionBlock(5, "Main Q", "Main A", fus);
        InterviewApp.LoadedQuestion lq = InterviewApp.parseSection(bodyLines(rendered));
        assertEquals(3, lq.followUps().size(), "All three rounds must be preserved");
        for (int i = 0; i < 3; i++) {
            assertEquals("Follow Q" + (i + 1), lq.followUps().get(i).question(),
                    "Follow-up question " + (i + 1) + " must be in order");
            assertEquals("Answer " + (i + 1),  lq.followUps().get(i).answer(),
                    "Follow-up answer " + (i + 1) + " must be in order");
        }
    }
}
