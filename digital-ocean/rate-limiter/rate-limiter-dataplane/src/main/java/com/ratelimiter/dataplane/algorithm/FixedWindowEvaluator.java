package com.ratelimiter.dataplane.algorithm;

import com.ratelimiter.common.enums.RateLimitAlgorithm;
import com.ratelimiter.common.model.RateLimitConfig;
import org.springframework.stereotype.Component;

@Component
public class FixedWindowEvaluator implements AlgorithmEvaluator {

    @Override
    public RateLimitAlgorithm algorithm() {
        return RateLimitAlgorithm.FIXED_WINDOW;
    }

    @Override
    public BucketSnapshot refresh(BucketSnapshot state, RateLimitConfig config, long nowSeconds) {
        long capacity = config.getAvailableToken();
        long window = config.getTimeWindowInSeconds();
        long tokens = state.tokens();
        long lastRefill = state.lastRefill();
        long resetAt = state.resetAt();

        if (window > 0 && nowSeconds >= resetAt) {
            tokens = capacity;
            resetAt = nowSeconds + window;
            lastRefill = nowSeconds;
        }
        tokens = Math.min(tokens, capacity);
        return new BucketSnapshot(tokens, capacity, window, lastRefill, resetAt, state.eventTimestamps());
    }
}
