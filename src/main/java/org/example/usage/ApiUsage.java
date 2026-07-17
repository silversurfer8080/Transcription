package org.example.usage;

/**
 * Running usage for a single (provider, {@link ApiKind}) pair this session: cumulative
 * request and secondary-unit counters, plus the most recent non-empty {@link RateLimit}
 * reading. Mutated only on the JavaFX thread (via {@link UsageTracker}).
 */
public final class ApiUsage {

    private final String provider;
    private final ApiKind kind;
    private long totalRequests;
    private double totalSecondary;
    private RateLimit latest = RateLimit.empty();
    private boolean everUpdated;

    public ApiUsage(String provider, ApiKind kind) {
        this.provider = provider;
        this.kind = kind;
    }

    /** Accumulates one call's usage and remembers its rate-limit reading when non-empty. */
    void add(UsageEvent e) {
        totalRequests  += e.requests();
        totalSecondary += e.secondary();
        if (e.rateLimit().hasData()) latest = e.rateLimit();
        everUpdated = true;
    }

    public String   provider()       { return provider; }
    public ApiKind  kind()           { return kind; }
    public long     totalRequests()  { return totalRequests; }
    public double   totalSecondary() { return totalSecondary; }
    /** Latest remaining-quota reading; {@link RateLimit#empty()} when never reported. */
    public RateLimit latest()        { return latest; }
    public boolean  everUpdated()    { return everUpdated; }

    /** Stable identity used to key the tracker map. */
    public static String key(String provider, ApiKind kind) {
        return provider + "|" + kind.name();
    }
}
