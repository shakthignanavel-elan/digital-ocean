package com.ratelimiter.dataplane.algorithm;

import com.ratelimiter.common.enums.RateLimitAlgorithm;

import java.util.ArrayList;
import java.util.List;

/**
 * Java mirror of the Redis Lua evaluate/refill logic used by dataplane and controlplane.
 * Keeps algorithm behavior testable without Redis.
 */
public final class RateLimitAlgorithmSimulator {

    public record BucketState(
            RateLimitAlgorithm algorithm,
            long tokens,
            long capacity,
            long windowSeconds,
            long lastRefill,
            long resetAt,
            List<Long> eventTimestamps
    ) {
        public BucketState {
            eventTimestamps = eventTimestamps == null ? List.of() : List.copyOf(eventTimestamps);
        }

        public static BucketState of(RateLimitAlgorithm algorithm, long tokens, long capacity,
                                     long windowSeconds, long lastRefill, long resetAt) {
            return new BucketState(algorithm, tokens, capacity, windowSeconds, lastRefill, resetAt, List.of());
        }

        public BucketState withTokens(long newTokens) {
            return new BucketState(algorithm, newTokens, capacity, windowSeconds, lastRefill, resetAt, eventTimestamps);
        }

        public BucketState withMeta(long tokens, long lastRefill, long resetAt, List<Long> events) {
            return new BucketState(algorithm, tokens, capacity, windowSeconds, lastRefill, resetAt, events);
        }

        public long currentConsumedToken() {
            return Math.max(0, capacity - tokens);
        }
    }

    public record DualResult(boolean allowed, BucketState burstable, BucketState sustained) {
    }

    private RateLimitAlgorithmSimulator() {
    }

    public static BucketState refresh(BucketState state, long nowSeconds) {
        long tokens = state.tokens();
        long capacity = state.capacity();
        long resetAt = state.resetAt();
        long lastRefill = state.lastRefill();
        long window = state.windowSeconds();
        List<Long> events = new ArrayList<>(state.eventTimestamps());

        if (state.algorithm() == RateLimitAlgorithm.TOKEN_BUCKET && window > 0 && capacity > 0) {
            long elapsed = Math.max(0, nowSeconds - lastRefill);
            long refill = (long) Math.floor(elapsed * ((double) capacity / window));
            if (refill > 0) {
                tokens = Math.min(capacity, tokens + refill);
                lastRefill = nowSeconds;
            }
            resetAt = nowSeconds + (long) Math.ceil(((capacity - tokens) * (double) window) / Math.max(capacity, 1));
        } else if (state.algorithm() == RateLimitAlgorithm.FIXED_WINDOW && window > 0) {
            if (nowSeconds >= resetAt) {
                tokens = capacity;
                resetAt = nowSeconds + window;
                lastRefill = nowSeconds;
            }
        } else if (state.algorithm() == RateLimitAlgorithm.SLIDING_WINDOW && window > 0) {
            long cutoff = nowSeconds - window;
            events.removeIf(ts -> ts <= cutoff);
            tokens = Math.max(0, capacity - events.size());
            if (!events.isEmpty()) {
                resetAt = events.getFirst() + window;
            } else {
                resetAt = nowSeconds + window;
            }
            lastRefill = nowSeconds;
        }
        return state.withMeta(tokens, lastRefill, resetAt, events);
    }

    public static DualResult evaluate(BucketState burstable, BucketState sustained, long nowSeconds, long cost) {
        BucketState b = refresh(burstable, nowSeconds);
        BucketState s = refresh(sustained, nowSeconds);
        boolean allowed = b.tokens() >= cost && s.tokens() >= cost;
        if (allowed) {
            b = consume(b, nowSeconds, cost);
            s = consume(s, nowSeconds, cost);
        }
        return new DualResult(allowed, b, s);
    }

    private static BucketState consume(BucketState state, long nowSeconds, long cost) {
        if (state.algorithm() == RateLimitAlgorithm.SLIDING_WINDOW) {
            List<Long> events = new ArrayList<>(state.eventTimestamps());
            for (int i = 0; i < cost; i++) {
                events.add(nowSeconds);
            }
            long tokens = state.tokens() - cost;
            long resetAt = events.isEmpty() ? nowSeconds + state.windowSeconds() : events.getFirst() + state.windowSeconds();
            return state.withMeta(tokens, nowSeconds, resetAt, events);
        }
        return state.withTokens(state.tokens() - cost);
    }
}
