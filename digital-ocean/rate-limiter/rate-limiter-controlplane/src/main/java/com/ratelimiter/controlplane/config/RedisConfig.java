package com.ratelimiter.controlplane.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.common.redis.RateLimitScripts;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    public DefaultRedisScript<String> quotaReadScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setResultType(String.class);
        script.setScriptText(RateLimitScripts.QUOTA_READ);
        return script;
    }
}
