package com.ratelimiter.dataplane.degraded;

import com.ratelimiter.common.model.RedisQuotaKeys;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registers this dataplane in Redis via TTL heartbeats and caches the live node count.
 * When Redis is unreachable, {@link #activeNodeCount()} returns the last successful count.
 */
public class RedisDataplaneClusterView implements DataplaneClusterView {

    private static final Logger log = LoggerFactory.getLogger(RedisDataplaneClusterView.class);

    private final StringRedisTemplate redisTemplate;
    private final String nodeId;
    private final Duration heartbeatTtl;
    private final AtomicInteger cachedNodeCount = new AtomicInteger(1);

    public RedisDataplaneClusterView(StringRedisTemplate redisTemplate,
                                     MeterRegistry meterRegistry,
                                     @Value("${rate-limiter.degraded.node-id:}") String configuredNodeId,
                                     @Value("${rate-limiter.degraded.heartbeat-ttl-ms:15000}") long heartbeatTtlMs) {
        this.redisTemplate = redisTemplate;
        this.nodeId = (configuredNodeId == null || configuredNodeId.isBlank())
                ? UUID.randomUUID().toString()
                : configuredNodeId;
        this.heartbeatTtl = Duration.ofMillis(Math.max(1000L, heartbeatTtlMs));
        meterRegistry.gauge("ratelimiter.dataplane.nodes", cachedNodeCount);
    }

    @PostConstruct
    void initialHeartbeat() {
        heartbeat();
    }

    @Override
    public int activeNodeCount() {
        return Math.max(1, cachedNodeCount.get());
    }

    @Override
    public String nodeId() {
        return nodeId;
    }

    @Scheduled(fixedDelayString = "${rate-limiter.degraded.heartbeat-interval-ms:5000}")
    public void heartbeat() {
        try {
            redisTemplate.opsForValue().set(
                    RedisQuotaKeys.dataplaneHeartbeatKey(nodeId),
                    "1",
                    heartbeatTtl
            );
            int count = countHeartbeats();
            cachedNodeCount.set(Math.max(1, count));
            log.debug("dataplane heartbeat ok nodeId={} activeNodes={}", nodeId, cachedNodeCount.get());
        } catch (RuntimeException ex) {
            log.warn("dataplane heartbeat failed nodeId={} usingCachedNodes={} cause={}",
                    nodeId, cachedNodeCount.get(), ex.toString());
        }
    }

    private int countHeartbeats() {
        ScanOptions options = ScanOptions.scanOptions()
                .match(RedisQuotaKeys.DATAPLANE_HEARTBEAT_PREFIX + "*")
                .count(100)
                .build();
        int count = 0;
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                cursor.next();
                count++;
            }
        }
        return Math.max(1, count);
    }
}
