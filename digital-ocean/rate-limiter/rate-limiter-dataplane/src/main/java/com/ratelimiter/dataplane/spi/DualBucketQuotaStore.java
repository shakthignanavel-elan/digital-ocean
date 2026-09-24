package com.ratelimiter.dataplane.spi;

import com.ratelimiter.common.model.RateLimitConfig;
import com.ratelimiter.common.model.TokenAvailability;

import java.util.UUID;

/**
 * Atomic burstable + sustained consume. Backend may be Redis Lua, in-memory locks, etc.
 */
public interface DualBucketQuotaStore {

    DualBucketResult tryConsume(String namespace,
                                UUID tenantId,
                                RateLimitConfig burstableConfig,
                                RateLimitConfig sustainedConfig,
                                long cost);

    record DualBucketResult(
            boolean allowed,
            String reason,
            TokenAvailability burstable,
            TokenAvailability sustained
    ) {
    }
}
