package com.ratelimiter.dataplane.store.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.common.dto.ConfigurationDTO;
import com.ratelimiter.common.model.RedisQuotaKeys;
import com.ratelimiter.dataplane.spi.ConfigurationStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RedisConfigurationStore implements ConfigurationStore {

    private record CacheEntry(ConfigurationDTO config, long expiresAtMillis) {
    }

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long ttlMillis;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

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

        String raw = redisTemplate.opsForValue().get(RedisQuotaKeys.configKey(namespace, tenantId));
        if (raw == null || raw.isBlank()) {
            cache.remove(cacheKey);
            return Optional.empty();
        }
        try {
            ConfigurationDTO config = objectMapper.readValue(raw, ConfigurationDTO.class);
            if (ttlMillis > 0) {
                cache.put(cacheKey, new CacheEntry(config, now + ttlMillis));
            }
            return Optional.of(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse configuration from Redis", e);
        }
    }

    @Override
    public void save(ConfigurationDTO configuration) {
        try {
            redisTemplate.opsForValue().set(
                    RedisQuotaKeys.configKey(configuration.getNamespace(), configuration.getTenantId()),
                    objectMapper.writeValueAsString(configuration)
            );
            cache.remove(configuration.getNamespace() + ":" + configuration.getTenantId());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize configuration", e);
        }
    }

    @Override
    public void delete(String namespace, UUID tenantId) {
        redisTemplate.delete(RedisQuotaKeys.configKey(namespace, tenantId));
        cache.remove(namespace + ":" + tenantId);
    }
}
