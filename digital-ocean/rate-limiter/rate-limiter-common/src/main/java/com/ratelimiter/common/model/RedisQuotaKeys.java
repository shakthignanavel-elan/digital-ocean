package com.ratelimiter.common.model;

import java.util.UUID;

/**
 * Shared Redis key conventions so dataplane and controlplane stay consistent.
 */
public final class RedisQuotaKeys {

    public static final String CONFIG_PREFIX = "rl:config:";
    public static final String BURSTABLE_PREFIX = "rl:quota:burstable:";
    public static final String SUSTAINED_PREFIX = "rl:quota:sustained:";

    private RedisQuotaKeys() {
    }

    public static String configKey(String namespace, UUID tenantId) {
        return CONFIG_PREFIX + namespace + ":" + tenantId;
    }

    public static String burstableKey(String namespace, UUID tenantId) {
        return BURSTABLE_PREFIX + namespace + ":" + tenantId;
    }

    public static String sustainedKey(String namespace, UUID tenantId) {
        return SUSTAINED_PREFIX + namespace + ":" + tenantId;
    }

    public static String eventsKey(String bucketKey) {
        return bucketKey + ":events";
    }
}
