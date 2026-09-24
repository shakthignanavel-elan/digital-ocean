package com.ratelimiter.dataplane.store.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.common.model.RateLimitConfig;
import com.ratelimiter.common.model.RedisQuotaKeys;
import com.ratelimiter.common.model.TokenAvailability;
import com.ratelimiter.dataplane.spi.DualBucketQuotaStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Redis Lua implementation of atomic dual-bucket consume (single EVAL).
 */
public class RedisDualBucketQuotaStore implements DualBucketQuotaStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DefaultRedisScript<String> evaluateDualScript;

    public RedisDualBucketQuotaStore(StringRedisTemplate redisTemplate,
                                     ObjectMapper objectMapper,
                                     DefaultRedisScript<String> evaluateDualScript) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.evaluateDualScript = evaluateDualScript;
    }

    @Override
    public DualBucketResult tryConsume(String namespace,
                                       UUID tenantId,
                                       RateLimitConfig burstableConfig,
                                       RateLimitConfig sustainedConfig,
                                       long cost) {
        long nowSeconds = System.currentTimeMillis() / 1000;
        String json = redisTemplate.execute(
                evaluateDualScript,
                List.of(
                        RedisQuotaKeys.burstableKey(namespace, tenantId),
                        RedisQuotaKeys.sustainedKey(namespace, tenantId)
                ),
                String.valueOf(nowSeconds),
                String.valueOf(cost),
                burstableConfig.getRateLimitAlgorithm().name(),
                String.valueOf(burstableConfig.getAvailableToken()),
                String.valueOf(burstableConfig.getTimeWindowInSeconds()),
                sustainedConfig.getRateLimitAlgorithm().name(),
                String.valueOf(sustainedConfig.getAvailableToken()),
                String.valueOf(sustainedConfig.getTimeWindowInSeconds())
        );
        if (json == null) {
            TokenAvailability empty = new TokenAvailability(0, 0, 0, new Date(nowSeconds * 1000));
            return new DualBucketResult(false, "STORE_ERROR", empty, empty);
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            boolean allowed = node.path("allowed").asBoolean(false);
            return new DualBucketResult(
                    allowed,
                    node.path("reason").asText(allowed ? "OK" : "RATE_LIMITED"),
                    toAvailability(node.path("burstable"), nowSeconds),
                    toAvailability(node.path("sustained"), nowSeconds)
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse dual evaluate result", e);
        }
    }

    private TokenAvailability toAvailability(JsonNode node, long nowSeconds) {
        long capacity = node.path("capacity").asLong();
        long tokens = node.path("tokens").asLong();
        long consumed = node.path("consumed").asLong(Math.max(0, capacity - tokens));
        long resetAt = node.path("resetAt").asLong(nowSeconds);
        return new TokenAvailability(capacity, tokens, consumed, new Date(resetAt * 1000));
    }
}
