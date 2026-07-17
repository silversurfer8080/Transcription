package org.example.usage;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Session-scoped registry of {@link ApiUsage} per (provider, {@link ApiKind}), fed by
 * {@link UsageEvent}s from the STT providers and the LLM client. Holds the live remaining
 * quota and the cumulative session totals that the "Métricas" tab and the live indicators
 * render.
 *
 * <p>Not thread-safe by design: it lives on the JavaFX Application Thread, and providers —
 * which run on virtual threads — must marshal their events through {@code Platform.runLater}
 * before calling {@link #record}. Insertion order is preserved so the metrics table is stable.
 */
public final class UsageTracker {

    private final Map<String, ApiUsage> byKey = new LinkedHashMap<>();
    private Runnable onChange;

    /** Sets a callback invoked after every recorded event (e.g. to refresh the UI). */
    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    /** Records one call's usage, creating the row on first sighting, then fires onChange. */
    public void record(UsageEvent e) {
        byKey.computeIfAbsent(ApiUsage.key(e.provider(), e.kind()),
                k -> new ApiUsage(e.provider(), e.kind())).add(e);
        if (onChange != null) onChange.run();
    }

    /** The current usage row for a provider/kind, or null if none recorded yet. */
    public ApiUsage get(String provider, ApiKind kind) {
        return byKey.get(ApiUsage.key(provider, kind));
    }

    /** All usage rows, in first-seen order. */
    public Collection<ApiUsage> all() {
        return byKey.values();
    }
}
