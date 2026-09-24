package com.ratelimiter.controlplane;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.mockito.Mockito.mock;

/**
 * Stand-in connection factory so controlplane tests can run without a live Redis.
 */
@TestConfiguration
public class TestRedisBeans {

    @Bean
    @Primary
    RedisConnectionFactory redisConnectionFactory() {
        return mock(RedisConnectionFactory.class);
    }
}
