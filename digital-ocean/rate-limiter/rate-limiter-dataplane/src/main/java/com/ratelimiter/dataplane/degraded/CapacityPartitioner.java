package com.ratelimiter.dataplane.degraded;

import com.ratelimiter.common.model.RateLimitConfig;

/**
 * Partitions a global capacity across {@code nodeCount} dataplanes for degraded mode.
 */
public final class CapacityPartitioner {

    private CapacityPartitioner() {
    }

    /**
     * Returns {@code ceil(capacity / nodes)} with a floor of 1 when capacity &gt; 0,
     * so tiny quotas remain usable under partition.
     */
    public static long partition(long capacity, int nodeCount) {
        int nodes = Math.max(1, nodeCount);
        if (capacity <= 0) {
            return 0;
        }
        return Math.max(1L, (long) Math.ceil(capacity / (double) nodes));
    }

    public static RateLimitConfig partition(RateLimitConfig config, int nodeCount) {
        return new RateLimitConfig(
                config.getRateLimitAlgorithm(),
                partition(config.getAvailableToken(), nodeCount),
                config.getTimeWindowInSeconds()
        );
    }
}
