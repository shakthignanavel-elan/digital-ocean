package com.ratelimiter.dataplane.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.common.redis.RateLimitScripts;
import com.ratelimiter.dataplane.spi.ConfigurationStore;
import com.ratelimiter.dataplane.spi.DualBucketQuotaStore;
import com.ratelimiter.dataplane.store.redis.RedisConfigurationStore;
import com.ratelimiter.dataplane.store.redis.RedisDualBucketQuotaStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
@ConditionalOnProperty(name = "rate-limiter.store", havingValue = "redis", matchIfMissing = true)
public class RedisStoreConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public DefaultRedisScript<String> evaluateDualScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setResultType(String.class);
        script.setScriptText(RateLimitScripts.EVALUATE_DUAL);
        return script;
    }

    @Bean
    public ConfigurationStore configurationStore(StringRedisTemplate redisTemplate,
                                                 ObjectMapper objectMapper,
                                                 @Value("${rate-limiter.config-cache-ttl-ms:1000}") long ttlMillis) {
        return new RedisConfigurationStore(redisTemplate, objectMapper, ttlMillis);
    }

    @Bean
    public DualBucketQuotaStore dualBucketQuotaStore(StringRedisTemplate redisTemplate,
                                                     ObjectMapper objectMapper,
                                                     DefaultRedisScript<String> evaluateDualScript) {
        return new RedisDualBucketQuotaStore(redisTemplate, objectMapper, evaluateDualScript);
    }
}
