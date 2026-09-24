package com.ratelimiter.dataplane.algorithm;

import com.ratelimiter.common.enums.RateLimitAlgorithm;
import com.ratelimiter.common.model.RateLimitConfig;
import org.springframework.stereotype.Component;

@Component
public class TokenBucketEvaluator implements AlgorithmEvaluator {

    @Override
    public RateLimitAlgorithm algorithm() {
        return RateLimitAlgorithm.TOKEN_BUCKET;
    }

    @Override
    public BucketSnapshot refresh(BucketSnapshot state, RateLimitConfig config, long nowSeconds) {
        long capacity = config.getAvailableToken();
        long window = config.getTimeWindowInSeconds();
        long tokens = state.tokens();
        long lastRefill = state.lastRefill();
        long resetAt = state.resetAt();

        if (window > 0 && capacity > 0) {
            long elapsed = Math.max(0, nowSeconds - lastRefill);
            long refill = (long) Math.floor(elapsed * ((double) capacity / window));
            if (refill > 0) {
                tokens = Math.min(capacity, tokens + refill);
                lastRefill = nowSeconds;
            }
            tokens = Math.min(tokens, capacity);
            resetAt = nowSeconds + (long) Math.ceil(((capacity - tokens) * (double) window) / Math.max(capacity, 1));
        }
        return new BucketSnapshot(tokens, capacity, window, lastRefill, resetAt, state.eventTimestamps());
    }
}
