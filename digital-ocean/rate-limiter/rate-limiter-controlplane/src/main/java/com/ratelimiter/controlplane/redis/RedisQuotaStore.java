package com.ratelimiter.controlplane.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.common.dto.ConfigurationDTO;
import com.ratelimiter.common.enums.RateLimitAlgorithm;
import com.ratelimiter.common.model.RateLimitConfig;
import com.ratelimiter.common.model.RedisQuotaKeys;
import com.ratelimiter.common.model.TokenAvailability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Controlplane Redis sync: persists namespace/tenant configuration (algorithm choices)
 * and initializes runtime quota state so dataplane can evaluate without local config.
 */
@Component
public class RedisQuotaStore {

    private static final Logger log = LoggerFactory.getLogger(RedisQuotaStore.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DefaultRedisScript<String> quotaReadScript;

    public RedisQuotaStore(StringRedisTemplate redisTemplate,
                           ObjectMapper objectMapper,
                           DefaultRedisScript<String> quotaReadScript) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.quotaReadScript = quotaReadScript;
    }

    /**
     * Writes the full configuration document for namespace+tenant and resets runtime
     * quota state for burstable + sustained buckets. Called on create/update.
     */
    public void syncConfiguration(ConfigurationDTO config) {
        String namespace = config.getNamespace();
        UUID tenantId = config.getTenantId();

        try {
            redisTemplate.opsForValue().set(
                    RedisQuotaKeys.configKey(namespace, tenantId),
                    objectMapper.writeValueAsString(config)
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize configuration for Redis", e);
        }

        initRuntimeState(
                RedisQuotaKeys.burstableKey(namespace, tenantId),
                config.getBurstableRateLimitConfig()
        );
        initRuntimeState(
                RedisQuotaKeys.sustainedKey(namespace, tenantId),
                config.getFixedRateLimitConfig()
        );
        log.info("synced configuration to redis namespace={} tenantId={}", namespace, tenantId);
    }

    public void deleteConfiguration(String namespace, UUID tenantId) {
        String burstable = RedisQuotaKeys.burstableKey(namespace, tenantId);
        String sustained = RedisQuotaKeys.sustainedKey(namespace, tenantId);
        redisTemplate.delete(List.of(
                RedisQuotaKeys.configKey(namespace, tenantId),
                burstable,
                RedisQuotaKeys.eventsKey(burstable),
                RedisQuotaKeys.eventsKey(burstable) + ":seq",
                sustained,
                RedisQuotaKeys.eventsKey(sustained),
                RedisQuotaKeys.eventsKey(sustained) + ":seq"
        ));
        log.info("deleted configuration from redis namespace={} tenantId={}", namespace, tenantId);
    }

    public Optional<ConfigurationDTO> findConfiguration(String namespace, UUID tenantId) {
        String raw = redisTemplate.opsForValue().get(RedisQuotaKeys.configKey(namespace, tenantId));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, ConfigurationDTO.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse configuration from Redis", e);
        }
    }

    public TokenAvailability readBurstableAvailability(String namespace, UUID tenantId) {
        return readAvailability(namespace, tenantId, "burstable",
                RedisQuotaKeys.burstableKey(namespace, tenantId));
    }

    public TokenAvailability readSustainedAvailability(String namespace, UUID tenantId) {
        return readAvailability(namespace, tenantId, "sustained",
                RedisQuotaKeys.sustainedKey(namespace, tenantId));
    }

    private TokenAvailability readAvailability(String namespace,
                                               UUID tenantId,
                                               String bucketSelector,
                                               String stateKey) {
        long nowSeconds = System.currentTimeMillis() / 1000;
        String configKey = RedisQuotaKeys.configKey(namespace, tenantId);
        String json = redisTemplate.execute(
                quotaReadScript,
                List.of(configKey, stateKey),
                String.valueOf(nowSeconds),
                bucketSelector
        );
        if (json == null) {
            return new TokenAvailability(0, 0, 0, new Date(nowSeconds * 1000));
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.path("exists").asBoolean(false)) {
                return new TokenAvailability(0, 0, 0, new Date(nowSeconds * 1000));
            }
            long capacity = node.path("capacity").asLong();
            long tokens = node.path("tokens").asLong();
            long consumed = node.path("consumed").asLong(Math.max(0, capacity - tokens));
            long resetAt = node.path("resetAt").asLong(nowSeconds);
            return new TokenAvailability(capacity, tokens, consumed, new Date(resetAt * 1000));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse quota state from Redis", e);
        }
    }

    /**
     * Runtime state only — algorithm/capacity/window live on the configuration document.
     */
    private void initRuntimeState(String key, RateLimitConfig config) {
        long nowSeconds = System.currentTimeMillis() / 1000;
        long capacity = config.getAvailableToken();
        long window = config.getTimeWindowInSeconds();
        RateLimitAlgorithm algorithm = config.getRateLimitAlgorithm();

        redisTemplate.delete(List.of(
                RedisQuotaKeys.eventsKey(key),
                RedisQuotaKeys.eventsKey(key) + ":seq"
        ));

        redisTemplate.opsForHash().putAll(key, Map.of(
                "tokens", String.valueOf(capacity),
                "lastRefill", String.valueOf(nowSeconds),
                "resetAt", String.valueOf(nowSeconds + window),
                // Denormalized for operators/debugging; dataplane still reads algorithms from config JSON.
                "algorithm", algorithm.name(),
                "capacity", String.valueOf(capacity),
                "windowSeconds", String.valueOf(window)
        ));
    }
}
