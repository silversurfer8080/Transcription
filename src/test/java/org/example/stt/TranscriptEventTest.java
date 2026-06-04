package org.example.stt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TranscriptEventTest {

    @Test
    void constructor_storesAllFields() {
        var event = new TranscriptEvent("hello world", true, 0.95, "mic");
        assertEquals("hello world", event.text());
        assertTrue(event.isFinal());
        assertEquals(0.95, event.confidence(), 1e-9);
        assertEquals("mic", event.channelId());
    }

    @Test
    void isFinal_false_representsPartialResult() {
        var partial = new TranscriptEvent("hello...", false, 0.7, "candidate");
        assertFalse(partial.isFinal());
    }

    @Test
    void channelId_distinguishesMicFromCandidate() {
        var mic       = new TranscriptEvent("hi", true, 1.0, "mic");
        var candidate = new TranscriptEvent("hi", true, 1.0, "candidate");
        assertNotEquals(mic.channelId(), candidate.channelId());
    }

    @Test
    void confidence_negativeOne_isValidSentinelForMissingConfidence() {
        // DeepgramStreamingProvider uses -1 when the JSON confidence field is absent
        var event = new TranscriptEvent("text", false, -1.0, "mic");
        assertEquals(-1.0, event.confidence(), 1e-9);
    }

    @Test
    void recordEquality_sameValues_equal() {
        var e1 = new TranscriptEvent("text", true, 0.9, "mic");
        var e2 = new TranscriptEvent("text", true, 0.9, "mic");
        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
    }

    @Test
    void recordEquality_differentText_notEqual() {
        var e1 = new TranscriptEvent("hello", true, 0.9, "mic");
        var e2 = new TranscriptEvent("world", true, 0.9, "mic");
        assertNotEquals(e1, e2);
    }

    @Test
    void recordEquality_differentChannel_notEqual() {
        var e1 = new TranscriptEvent("text", true, 0.9, "mic");
        var e2 = new TranscriptEvent("text", true, 0.9, "candidate");
        assertNotEquals(e1, e2);
    }

    @Test
    void toString_containsFieldValues() {
        var event = new TranscriptEvent("interview", true, 0.85, "mic");
        String s = event.toString();
        assertTrue(s.contains("interview"), "toString() of a record must include field values");
    }
}
