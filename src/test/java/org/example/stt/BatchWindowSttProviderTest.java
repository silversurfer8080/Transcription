package org.example.stt;

import org.example.stt.BatchWindowSttProvider.WindowSplit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link BatchWindowSttProvider#splitWindow} — the pure logic that caps each
 * transcription window and, as a safety valve, drops the oldest audio when a slow backend
 * lets the backlog run away. Uncapped providers (Groq/Gemini pass {@code maxWindowBytes == 0})
 * must keep the original whole-buffer behavior.
 */
class BatchWindowSttProviderTest {

    private static final int CAP = 5 * 16_000 * 2;   // 5 s @ 16 kHz/16-bit/mono = 160000 bytes
    private static final int BACKLOG = CAP * 12;      // 60 s

    private static byte[] bytes(int n) { return new byte[n]; }

    @Test
    void uncapped_returnsWholeBufferAsWindow_noRemainder_noDrop() {
        WindowSplit s = BatchWindowSttProvider.splitWindow(bytes(500_000), 0, 0);
        assertEquals(500_000, s.window().length, "maxWindowBytes=0 keeps the whole-buffer drain");
        assertEquals(0, s.remainder().length);
        assertEquals(0, s.droppedBytes());
    }

    @Test
    void underCap_wholeIsWindow_noRemainder() {
        WindowSplit s = BatchWindowSttProvider.splitWindow(bytes(120_000), CAP, BACKLOG);
        assertEquals(120_000, s.window().length);
        assertEquals(0, s.remainder().length);
        assertEquals(0, s.droppedBytes());
    }

    @Test
    void exactlyAtCap_wholeIsWindow_noRemainder() {
        WindowSplit s = BatchWindowSttProvider.splitWindow(bytes(CAP), CAP, BACKLOG);
        assertEquals(CAP, s.window().length);
        assertEquals(0, s.remainder().length, "a window exactly at the cap must not spill a remainder");
        assertEquals(0, s.droppedBytes());
    }

    @Test
    void overCap_capsWindowAndKeepsRemainder() {
        // 8 s buffered with a 5 s cap -> transcribe 5 s now, keep 3 s for next flush.
        WindowSplit s = BatchWindowSttProvider.splitWindow(bytes(CAP + 96_000), CAP, BACKLOG);
        assertEquals(CAP, s.window().length, "window capped at 5 s");
        assertEquals(96_000, s.remainder().length, "the extra 3 s stays buffered");
        assertEquals(0, s.droppedBytes(), "nothing dropped below the backlog cap");
    }

    @Test
    void overBacklog_dropsOldestThenCapsWindow() {
        // 65 s buffered, 60 s backlog cap -> drop the oldest 5 s, then still cap the window at 5 s.
        WindowSplit s = BatchWindowSttProvider.splitWindow(bytes(BACKLOG + CAP), CAP, BACKLOG);
        assertEquals(CAP, s.droppedBytes(), "the oldest 5 s over the 60 s backlog is dropped");
        assertEquals(CAP, s.window().length, "window still capped at 5 s");
        assertEquals(BACKLOG - CAP, s.remainder().length, "the remaining 55 s stays buffered");
    }

    @Test
    void windowIsAlwaysTheOldestAudio_remainderTheNewest() {
        // Tag the bytes so we can prove ordering: window = oldest slice, remainder = newest slice.
        byte[] all = new byte[CAP + 10];
        for (int i = 0; i < all.length; i++) all[i] = (byte) i;
        WindowSplit s = BatchWindowSttProvider.splitWindow(all, CAP, BACKLOG);
        assertEquals(0, s.window()[0], "window starts at the oldest byte");
        assertEquals((byte) (CAP - 1), s.window()[CAP - 1], "window ends just before the remainder");
        assertEquals((byte) CAP, s.remainder()[0], "remainder continues from where the window ended");
    }
}
