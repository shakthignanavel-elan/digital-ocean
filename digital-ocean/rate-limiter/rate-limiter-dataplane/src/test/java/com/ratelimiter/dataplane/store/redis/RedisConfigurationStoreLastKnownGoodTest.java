package com.ratelimiter.dataplane.store.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.common.dto.ConfigurationDTO;
import com.ratelimiter.common.enums.RateLimitAlgorithm;
import com.ratelimiter.common.model.RateLimitConfig;
import com.ratelimiter.common.model.RedisQuotaKeys;
import com.ratelimiter.common.model.TokenConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisConfigurationStoreLastKnownGoodTest {

    private static final String NAMESPACE = "payments";
    private static final UUID TENANT = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ObjectMapper objectMapper;
    private RedisConfigurationStore store;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        store = new RedisConfigurationStore(redisTemplate, objectMapper, 1);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void returnsLastKnownGoodWhenRedisFailsAfterSuccessfulRead() throws Exception {
        ConfigurationDTO config = sample();
        String json = objectMapper.writeValueAsString(config);
        when(valueOperations.get(RedisQuotaKeys.configKey(NAMESPACE, TENANT)))
                .thenReturn(json);

        assertThat(store.find(NAMESPACE, TENANT)).isPresent();
        assertThat(store.find(NAMESPACE, TENANT).orElseThrow()
                .getBurstableRateLimitConfig().getAvailableToken()).isEqualTo(100);
        // TTL is 1ms — wait so soft cache expires; LKG must still serve
        Thread.sleep(5);

        when(valueOperations.get(RedisQuotaKeys.configKey(NAMESPACE, TENANT)))
                .thenThrow(new IllegalStateException("Redis connection refused"));

        assertThat(store.find(NAMESPACE, TENANT)).isPresent();
        assertThat(store.find(NAMESPACE, TENANT).orElseThrow().getBurstableRateLimitConfig().getAvailableToken())
                .isEqualTo(100);
        assertThat(store.lastKnownGoodSize()).isEqualTo(1);
    }

    @Test
    void throwsWhenRedisFailsAndNoLastKnownGood() {
        when(valueOperations.get(anyString())).thenThrow(new IllegalStateException("Redis down"));

        assertThatThrownBy(() -> store.find(NAMESPACE, TENANT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis down");
    }

    private ConfigurationDTO sample() {
        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setNamespace(NAMESPACE);
        dto.setTenantId(TENANT);
        dto.setBurstableRateLimitConfig(new RateLimitConfig(RateLimitAlgorithm.TOKEN_BUCKET, 100, 60));
        dto.setFixedRateLimitConfig(new RateLimitConfig(RateLimitAlgorithm.SLIDING_WINDOW, 1000, 3600));
        dto.setTokenConfiguration(new TokenConfiguration(100));
        return dto;
    }
}
