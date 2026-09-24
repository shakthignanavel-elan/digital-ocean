package com.ratelimiter.dataplane.store.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.common.dto.ConfigurationDTO;
import com.ratelimiter.common.model.RedisQuotaKeys;
import com.ratelimiter.dataplane.spi.ConfigurationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis-backed config store with a short TTL cache plus a last-known-good copy
 * used when Redis is unreachable (degraded mode).
 */
public class RedisConfigurationStore implements ConfigurationStore {

    private static final Logger log = LoggerFactory.getLogger(RedisConfigurationStore.class);

    private record CacheEntry(ConfigurationDTO config, long expiresAtMillis) {
    }

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long ttlMillis;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConfigurationDTO> lastKnownGood = new ConcurrentHashMap<>();

    public RedisConfigurationStore(StringRedisTemplate redisTemplate,
                                   ObjectMapper objectMapper,
                                   @Value("${rate-limiter.config-cache-ttl-ms:1000}") long ttlMillis) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttlMillis = ttlMillis;
    }

    @Override
    public Optional<ConfigurationDTO> find(String namespace, UUID tenantId) {
        String cacheKey = namespace + ":" + tenantId;
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAtMillis() > now) {
            return Optional.of(cached.config());
        }

        try {
            String raw = redisTemplate.opsForValue().get(RedisQuotaKeys.configKey(namespace, tenantId));
            if (raw == null || raw.isBlank()) {
                cache.remove(cacheKey);
                lastKnownGood.remove(cacheKey);
                return Optional.empty();
            }
            ConfigurationDTO config = objectMapper.readValue(raw, ConfigurationDTO.class);
            remember(cacheKey, config, now);
            return Optional.of(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse configuration from Redis", e);
        } catch (RuntimeException ex) {
            ConfigurationDTO lkg = lastKnownGood.get(cacheKey);
            if (lkg != null) {
                log.warn("Redis config read failed — using last-known-good namespace={} tenantId={} cause={}",
                        namespace, tenantId, ex.toString());
                return Optional.of(lkg);
            }
            log.error("Redis config read failed and no last-known-good namespace={} tenantId={}",
                    namespace, tenantId, ex);
            throw ex;
        }
    }

    @Override
    public void save(ConfigurationDTO configuration) {
        try {
            redisTemplate.opsForValue().set(
                    RedisQuotaKeys.configKey(configuration.getNamespace(), configuration.getTenantId()),
                    objectMapper.writeValueAsString(configuration)
            );
            String cacheKey = configuration.getNamespace() + ":" + configuration.getTenantId();
            cache.remove(cacheKey);
            remember(cacheKey, configuration, System.currentTimeMillis());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize configuration", e);
        }
    }

    @Override
    public void delete(String namespace, UUID tenantId) {
        redisTemplate.delete(RedisQuotaKeys.configKey(namespace, tenantId));
        String cacheKey = namespace + ":" + tenantId;
        cache.remove(cacheKey);
        lastKnownGood.remove(cacheKey);
    }

    /** Visible for tests. */
    int lastKnownGoodSize() {
        return lastKnownGood.size();
    }

    private void remember(String cacheKey, ConfigurationDTO config, long nowMillis) {
        lastKnownGood.put(cacheKey, config);
        if (ttlMillis > 0) {
            cache.put(cacheKey, new CacheEntry(config, nowMillis + ttlMillis));
        }
    }
}
