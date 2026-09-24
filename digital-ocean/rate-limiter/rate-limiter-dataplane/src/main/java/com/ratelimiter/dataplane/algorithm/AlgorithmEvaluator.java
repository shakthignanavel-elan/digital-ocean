package com.ratelimiter.dataplane.algorithm;

import com.ratelimiter.common.enums.RateLimitAlgorithm;
import com.ratelimiter.common.model.RateLimitConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Strategy for one rate-limit algorithm. Selected by {@link AlgorithmEvaluatorFactory}
 * for burstable / sustained; stores apply algorithms via {@link com.ratelimiter.dataplane.spi.DualBucketQuotaStore}.
 */
public interface AlgorithmEvaluator {

    RateLimitAlgorithm algorithm();

    /**
     * Pure-Java apply (tests / local simulation). Mirrors Lua behaviour for this algorithm.
     */
    BucketSnapshot refresh(BucketSnapshot state, RateLimitConfig config, long nowSeconds);

    default BucketSnapshot consume(BucketSnapshot refreshed, RateLimitConfig config, long nowSeconds, long cost) {
        if (algorithm() == RateLimitAlgorithm.SLIDING_WINDOW) {
            List<Long> events = new ArrayList<>(refreshed.eventTimestamps());
            for (int i = 0; i < cost; i++) {
                events.add(nowSeconds);
            }
            long tokens = refreshed.tokens() - cost;
            long resetAt = events.isEmpty()
                    ? nowSeconds + config.getTimeWindowInSeconds()
                    : events.getFirst() + config.getTimeWindowInSeconds();
            return refreshed.withMeta(tokens, nowSeconds, resetAt, events);
        }
        return refreshed.withTokens(refreshed.tokens() - cost);
    }
}
