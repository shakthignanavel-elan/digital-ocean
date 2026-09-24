package com.ratelimiter.common.model;

import java.util.UUID;

/**
 * Shared Redis key conventions so dataplane and controlplane stay consistent.
 */
public final class RedisQuotaKeys {

    public static final String CONFIG_PREFIX = "rl:config:";
    public static final String BURSTABLE_PREFIX = "rl:quota:burstable:";
    public static final String SUSTAINED_PREFIX = "rl:quota:sustained:";
    /** Per-dataplane heartbeat keys: {@code rl:dataplane:hb:{nodeId}} with TTL. */
    public static final String DATAPLANE_HEARTBEAT_PREFIX = "rl:dataplane:hb:";

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

    public static String dataplaneHeartbeatKey(String nodeId) {
        return DATAPLANE_HEARTBEAT_PREFIX + nodeId;
    }
}
