package com.ratelimiter.dataplane.support;

import com.ratelimiter.dataplane.algorithm.AlgorithmEvaluatorFactory;
import com.ratelimiter.dataplane.store.memory.InMemoryConfigurationStore;
import com.ratelimiter.dataplane.store.memory.InMemoryDualBucketQuotaStore;

/**
 * Process-wide store instances so multiple dataplane Spring contexts share
 * the same config + quota state (stands in for Redis in dual-plane REST ITs).
 */
public final class SharedMemoryStores {

    private static volatile InMemoryConfigurationStore configuration;
    private static volatile InMemoryDualBucketQuotaStore quota;

    private SharedMemoryStores() {
    }

    public static synchronized InMemoryConfigurationStore configuration() {
        if (configuration == null) {
            configuration = new InMemoryConfigurationStore();
        }
        return configuration;
    }

    public static synchronized InMemoryDualBucketQuotaStore quota(AlgorithmEvaluatorFactory factory) {
        if (quota == null) {
            quota = new InMemoryDualBucketQuotaStore(factory);
        }
        return quota;
    }

    public static synchronized void reset() {
        if (configuration != null) {
            configuration.clear();
        }
        if (quota != null) {
            quota.clear();
        }
    }

    public static synchronized void dispose() {
        reset();
        configuration = null;
        quota = null;
    }
}
