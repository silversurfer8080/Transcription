package org.example.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and writes the plain-text session format used to persist interviews.
 *
 * <p>This is the codec for the {@code --- Pergunta N ---} block format described in
 * {@code CLAUDE.md} ("Session persistence"). It holds only pure functions over
 * {@link String}/{@link List} — no JavaFX, no I/O beyond {@link #parseSessionFile}
 * reading a file — so the whole read/write contract is unit-testable without the UI.
 *
 * <p><b>Format (line terminator = {@code System.lineSeparator()}):</b>
 * <pre>
 * --- Pergunta N ---
 * &lt;question&gt;
 *
 * &lt;answer&gt;
 *
 * FOLLOW-UP 1: &lt;follow-up question&gt;
 * EXPECTED: &lt;AI-generated guide&gt;   (omitted when blank)
 * &lt;follow-up answer&gt;
 * </pre>
 *
 * <p><b>Backward compatibility is a hard requirement:</b> a section with zero
 * follow-ups renders byte-identically to the old single-Q&amp;A format, and a
 * follow-up with a blank guide omits its {@code EXPECTED:} line, so files written
 * before either feature still round-trip and load correctly.
 */
public final class SessionCodec {

    private SessionCodec() {}

    // ── DTOs ──────────────────────────────────────────────────────────────

    /** A follow-up (question, AI-generated expected guide, candidate answer) read back from disk. */
    public record FollowUp(String question, String expected, String answer) {}

    /** A question section parsed back from a saved session TXT. */
    public record LoadedQuestion(String question, String answer, List<FollowUp> followUps) {}

    // ── Patterns ──────────────────────────────────────────────────────────

    // Regex identifying FOLLOW-UP n: lines written by renderQuestionBlock.
    // Deliberately strict (hyphen, digits, colon) so legacy "FOLLOW UP QUESTION ->"
    // text inside transcripts is never mis-parsed as a follow-up marker.
    private static final Pattern FOLLOWUP_LINE_PATTERN =
            Pattern.compile("^\\s*FOLLOW-UP\\s+(\\d+):\\s?(.*)$");

    // Optional first line of a follow-up block carrying its AI-generated guide.
    private static final Pattern FOLLOWUP_EXPECTED_PATTERN =
            Pattern.compile("^\\s*EXPECTED:\\s?(.*)$");

    // ── Writing ───────────────────────────────────────────────────────────

    /**
     * Renders one question block as text for the session TXT file.
     *
     * <p>With zero follow-ups the output is byte-identical to the previous format, and a
     * follow-up with a blank guide omits its {@code EXPECTED:} line — so files written
     * before the guide feature still round-trip and load correctly.
     */
    public static String renderQuestionBlock(int number, String question, String answer,
                                             List<FollowUp> followUps) {
        StringBuilder sb = new StringBuilder();
        String ls = System.lineSeparator();
        sb.append("--- Pergunta ").append(number).append(" ---").append(ls);
        sb.append(question == null ? "" : question.trim()).append(ls);
        sb.append(ls);
        sb.append(answer == null ? "" : answer.trim()).append(ls);
        if (followUps != null) {
            for (int i = 0; i < followUps.size(); i++) {
                FollowUp fu = followUps.get(i);
                sb.append(ls);
                sb.append("FOLLOW-UP ").append(i + 1).append(": ")
                  .append(fu.question() == null ? "" : fu.question().trim()).append(ls);
                if (fu.expected() != null && !fu.expected().isBlank()) {
                    sb.append("EXPECTED: ").append(fu.expected().trim()).append(ls);
                }
                sb.append(fu.answer() == null ? "" : fu.answer().trim()).append(ls);
            }
        }
        return sb.toString();
    }

    // ── Reading ───────────────────────────────────────────────────────────

    /** Reads a whole session file into its ordered list of question sections. */
    public static List<LoadedQuestion> parseSessionFile(Path path) throws IOException {
        List<LoadedQuestion> sections = new ArrayList<>();
        List<String> currentLines = null;

        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.matches("--- Pergunta \\d+ ---")) {
                if (currentLines != null) sections.add(parseSection(currentLines));
                currentLines = new ArrayList<>();
            } else if (currentLines != null) {
                currentLines.add(line);   // keep blanks: they separate question from answer
            }
        }
        if (currentLines != null) sections.add(parseSection(currentLines));
        return sections;
    }

    /**
     * Parses a question section (lines between two {@code --- Pergunta N ---} headers)
     * into a {@link LoadedQuestion} with optional follow-up rounds.
     *
     * <p>Backward compatible: sections with no {@code FOLLOW-UP n:} markers produce a
     * {@code LoadedQuestion} with an empty follow-up list, identical in question/answer
     * content to the old single-Q/A behavior.
     */
    public static LoadedQuestion parseSection(List<String> lines) {
        // Step 1: locate the first FOLLOW-UP n: line
        int fuStart = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            if (FOLLOWUP_LINE_PATTERN.matcher(lines.get(i)).matches()) {
                fuStart = i;
                break;
            }
        }

        // Step 2: parse head (initial question + answer) using existing logic
        LoadedQuestion qa = splitQuestionAnswer(lines.subList(0, fuStart));

        // Step 3: parse tail (follow-up rounds). Each block is:
        //   FOLLOW-UP n: <question>
        //   [EXPECTED: <guide>]     (optional; only the first content line)
        //   <answer lines>
        List<FollowUp> followUps = new ArrayList<>();
        List<String> tail = lines.subList(fuStart, lines.size());
        String currentFuQuestion = null;
        String currentFuExpected = "";
        List<String> currentFuAnswerLines = new ArrayList<>();
        for (String line : tail) {
            Matcher m = FOLLOWUP_LINE_PATTERN.matcher(line);
            if (m.matches()) {
                if (currentFuQuestion != null) {
                    followUps.add(new FollowUp(currentFuQuestion, currentFuExpected,
                            joinNonBlank(currentFuAnswerLines)));
                }
                currentFuQuestion = m.group(2).trim();
                currentFuExpected = "";
                currentFuAnswerLines = new ArrayList<>();
            } else if (currentFuQuestion != null) {
                Matcher em = FOLLOWUP_EXPECTED_PATTERN.matcher(line);
                // Only the first content line of the block may be the EXPECTED guide,
                // so an "EXPECTED:" that appears later inside the answer is left intact.
                if (em.matches() && currentFuExpected.isEmpty() && currentFuAnswerLines.isEmpty()) {
                    currentFuExpected = em.group(1).trim();
                } else {
                    currentFuAnswerLines.add(line);
                }
            }
        }
        if (currentFuQuestion != null) {
            followUps.add(new FollowUp(currentFuQuestion, currentFuExpected,
                    joinNonBlank(currentFuAnswerLines)));
        }

        return new LoadedQuestion(qa.question(), qa.answer(), followUps);
    }

    // New format: <question lines> <blank> <answer lines>. Old format (no blank
    // line) is treated as answer-only so legacy files still load correctly.
    // Used by parseSection for the head portion (before any FOLLOW-UP lines).
    private static LoadedQuestion splitQuestionAnswer(List<String> lines) {
        int firstBlank = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) { firstBlank = i; break; }
        }
        if (firstBlank < 0) {
            return new LoadedQuestion("", joinNonBlank(lines), List.of());
        }
        return new LoadedQuestion(
                joinNonBlank(lines.subList(0, firstBlank)),
                joinNonBlank(lines.subList(firstBlank + 1, lines.size())),
                List.of());
    }

    private static String joinNonBlank(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String s : lines) {
            if (s.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(s.trim());
        }
        return sb.toString();
    }
}
