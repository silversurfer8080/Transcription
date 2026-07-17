package org.example.usage;

/**
 * One API call's contribution to usage: the provider hit, the kind of call, how many
 * requests (normally 1) and how much of the secondary unit it consumed (audio-seconds for
 * STT, tokens for LLM), plus the {@link RateLimit} snapshot parsed from that response (never
 * null — {@link RateLimit#empty()} when the provider sends no rate-limit headers).
 */
public record UsageEvent(String provider, ApiKind kind, long requests, double secondary,
                         RateLimit rateLimit) {

    public UsageEvent {
        if (rateLimit == null) rateLimit = RateLimit.empty();
    }
}
