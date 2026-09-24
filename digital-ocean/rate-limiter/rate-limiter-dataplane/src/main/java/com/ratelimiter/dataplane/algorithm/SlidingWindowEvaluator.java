package com.ratelimiter.dataplane.algorithm;

import com.ratelimiter.common.enums.RateLimitAlgorithm;
import com.ratelimiter.common.model.RateLimitConfig;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SlidingWindowEvaluator implements AlgorithmEvaluator {

    @Override
    public RateLimitAlgorithm algorithm() {
        return RateLimitAlgorithm.SLIDING_WINDOW;
    }

    @Override
    public BucketSnapshot refresh(BucketSnapshot state, RateLimitConfig config, long nowSeconds) {
        long capacity = config.getAvailableToken();
        long window = config.getTimeWindowInSeconds();
        List<Long> events = new ArrayList<>(state.eventTimestamps());
        long cutoff = nowSeconds - window;
        events.removeIf(ts -> ts <= cutoff);
        long tokens = Math.max(0, capacity - events.size());
        long resetAt = events.isEmpty() ? nowSeconds + window : events.getFirst() + window;
        return new BucketSnapshot(tokens, capacity, window, nowSeconds, resetAt, events);
    }
}
