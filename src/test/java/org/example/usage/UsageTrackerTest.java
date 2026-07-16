package org.example.usage;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link UsageTracker} accumulation: events for the same (provider, kind) sum into one
 * row, different pairs form separate rows, the latest non-empty {@link RateLimit} is retained,
 * and the onChange callback fires per event.
 */
class UsageTrackerTest {

    private static RateLimit rl(double remReq, double limReq) {
        return new RateLimit(remReq, limReq, null, null);
    }

    @Test
    void samePair_accumulatesCountersAndKeepsLatestRateLimit() {
        UsageTracker t = new UsageTracker();
        t.record(new UsageEvent("Groq", ApiKind.STT, 1, 5.0, rl(1999, 2000)));
        t.record(new UsageEvent("Groq", ApiKind.STT, 1, 4.0, rl(1998, 2000)));

        ApiUsage u = t.get("Groq", ApiKind.STT);
        assertNotNull(u);
        assertEquals(2, u.totalRequests());
        assertEquals(9.0, u.totalSecondary(), 1e-9);
        assertEquals(1998.0, u.latest().remainingRequests(), 1e-9, "keeps the most recent reading");
        assertEquals(1, t.all().size(), "same provider+kind is one row");
    }

    @Test
    void differentPairs_areSeparateRows() {
        UsageTracker t = new UsageTracker();
        t.record(new UsageEvent("Groq", ApiKind.STT, 1, 5.0, RateLimit.empty()));
        t.record(new UsageEvent("Groq", ApiKind.LLM, 1, 800, RateLimit.empty()));
        t.record(new UsageEvent("Gemini", ApiKind.STT, 1, 15.0, RateLimit.empty()));
        assertEquals(3, t.all().size(), "provider+kind pairs are distinct rows");
        assertEquals(800.0, t.get("Groq", ApiKind.LLM).totalSecondary(), 1e-9);
    }

    @Test
    void emptyRateLimit_doesNotClobberPreviousReading() {
        UsageTracker t = new UsageTracker();
        t.record(new UsageEvent("Groq", ApiKind.LLM, 1, 500, rl(990, 1000)));
        t.record(new UsageEvent("Groq", ApiKind.LLM, 1, 500, RateLimit.empty()));
        ApiUsage u = t.get("Groq", ApiKind.LLM);
        assertTrue(u.latest().hasRequests(), "an empty later reading must not erase a good one");
        assertEquals(990.0, u.latest().remainingRequests(), 1e-9);
    }

    @Test
    void onChange_firesPerEvent() {
        UsageTracker t = new UsageTracker();
        AtomicInteger calls = new AtomicInteger();
        t.setOnChange(calls::incrementAndGet);
        t.record(new UsageEvent("Groq", ApiKind.STT, 1, 5.0, RateLimit.empty()));
        t.record(new UsageEvent("Groq", ApiKind.STT, 1, 5.0, RateLimit.empty()));
        assertEquals(2, calls.get());
    }

    @Test
    void get_unknownPair_returnsNull() {
        assertNull(new UsageTracker().get("Nope", ApiKind.LLM));
    }
}
