package com.ratelimiter.dataplane.algorithm;

import java.util.List;

/**
 * In-memory bucket snapshot for algorithm unit tests / local simulation.
 */
public record BucketSnapshot(
        long tokens,
        long capacity,
        long windowSeconds,
        long lastRefill,
        long resetAt,
        List<Long> eventTimestamps
) {
    public BucketSnapshot {
        eventTimestamps = eventTimestamps == null ? List.of() : List.copyOf(eventTimestamps);
    }

    public static BucketSnapshot initial(long capacity, long windowSeconds, long nowSeconds) {
        return new BucketSnapshot(capacity, capacity, windowSeconds, nowSeconds, nowSeconds + windowSeconds, List.of());
    }

    public BucketSnapshot withTokens(long newTokens) {
        return new BucketSnapshot(newTokens, capacity, windowSeconds, lastRefill, resetAt, eventTimestamps);
    }

    public BucketSnapshot withMeta(long tokens, long lastRefill, long resetAt, List<Long> events) {
        return new BucketSnapshot(tokens, capacity, windowSeconds, lastRefill, resetAt, events);
    }

    public long currentConsumedToken() {
        return Math.max(0, capacity - tokens);
    }
}
