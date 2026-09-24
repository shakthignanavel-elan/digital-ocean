package com.ratelimiter.controlplane.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.common.dto.ConfigurationDTO;
import com.ratelimiter.common.enums.RateLimitAlgorithm;
import com.ratelimiter.common.model.RedisQuotaKeys;
import com.ratelimiter.controlplane.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.ratelimiter.controlplane.support.TestFixtures.NAMESPACE;
import static com.ratelimiter.controlplane.support.TestFixtures.TENANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisQuotaStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private DefaultRedisScript<String> quotaReadScript;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private RedisQuotaStore redisQuotaStore;

    @BeforeEach
    void setUp() {
        redisQuotaStore = new RedisQuotaStore(redisTemplate, objectMapper, quotaReadScript);
    }

    @Test
    void syncConfigurationWritesNamespaceTenantConfigJsonAndRuntimeState() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.delete(anyList())).thenReturn(0L);

        ConfigurationDTO config = TestFixtures.sampleConfiguration();
        redisQuotaStore.syncConfiguration(config);

        ArgumentCaptor<String> configJson = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(RedisQuotaKeys.configKey(NAMESPACE, TENANT_ID)), configJson.capture());

        ConfigurationDTO stored = objectMapper.readValue(configJson.getValue(), ConfigurationDTO.class);
        assertThat(stored.getNamespace()).isEqualTo(NAMESPACE);
        assertThat(stored.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(stored.getBurstableRateLimitConfig().getRateLimitAlgorithm())
                .isEqualTo(RateLimitAlgorithm.TOKEN_BUCKET);
        assertThat(stored.getFixedRateLimitConfig().getRateLimitAlgorithm())
                .isEqualTo(RateLimitAlgorithm.SLIDING_WINDOW);
        assertThat(stored.getBurstableRateLimitConfig().getAvailableToken()).isEqualTo(100);
        assertThat(stored.getFixedRateLimitConfig().getAvailableToken()).isEqualTo(1000);

        ArgumentCaptor<Map<String, String>> burstableCaptor = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(eq(RedisQuotaKeys.burstableKey(NAMESPACE, TENANT_ID)), burstableCaptor.capture());
        assertThat(burstableCaptor.getValue())
                .containsEntry("tokens", "100")
                .containsEntry("algorithm", "TOKEN_BUCKET");

        ArgumentCaptor<Map<String, String>> sustainedCaptor = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(eq(RedisQuotaKeys.sustainedKey(NAMESPACE, TENANT_ID)), sustainedCaptor.capture());
        assertThat(sustainedCaptor.getValue())
                .containsEntry("tokens", "1000")
                .containsEntry("algorithm", "SLIDING_WINDOW");
    }

    @Test
    void deleteConfigurationRemovesConfigAndStateKeys() {
        redisQuotaStore.deleteConfiguration(NAMESPACE, TENANT_ID);

        verify(redisTemplate).delete(List.of(
                RedisQuotaKeys.configKey(NAMESPACE, TENANT_ID),
                RedisQuotaKeys.burstableKey(NAMESPACE, TENANT_ID),
                RedisQuotaKeys.eventsKey(RedisQuotaKeys.burstableKey(NAMESPACE, TENANT_ID)),
                RedisQuotaKeys.eventsKey(RedisQuotaKeys.burstableKey(NAMESPACE, TENANT_ID)) + ":seq",
                RedisQuotaKeys.sustainedKey(NAMESPACE, TENANT_ID),
                RedisQuotaKeys.eventsKey(RedisQuotaKeys.sustainedKey(NAMESPACE, TENANT_ID)),
                RedisQuotaKeys.eventsKey(RedisQuotaKeys.sustainedKey(NAMESPACE, TENANT_ID)) + ":seq"
        ));
    }

    @Test
    void findConfigurationReadsNamespaceTenantDocument() throws Exception {
        ConfigurationDTO config = TestFixtures.sampleConfiguration();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisQuotaKeys.configKey(NAMESPACE, TENANT_ID)))
                .thenReturn(objectMapper.writeValueAsString(config));

        Optional<ConfigurationDTO> found = redisQuotaStore.findConfiguration(NAMESPACE, TENANT_ID);

        assertThat(found).isPresent();
        assertThat(found.get().getBurstableRateLimitConfig().getRateLimitAlgorithm())
                .isEqualTo(RateLimitAlgorithm.TOKEN_BUCKET);
        assertThat(found.get().getFixedRateLimitConfig().getRateLimitAlgorithm())
                .isEqualTo(RateLimitAlgorithm.SLIDING_WINDOW);
    }

    @Test
    void readBurstableAvailabilityUsesConfigKeyAndStateKey() {
        when(redisTemplate.execute(eq(quotaReadScript), anyList(), any(), eq("burstable")))
                .thenReturn("{\"exists\":true,\"tokens\":75,\"capacity\":100,\"resetAt\":1700000060,\"consumed\":25,\"algorithm\":\"TOKEN_BUCKET\"}");

        var availability = redisQuotaStore.readBurstableAvailability(NAMESPACE, TENANT_ID);

        assertThat(availability.getAvailableToken()).isEqualTo(100);
        assertThat(availability.getRemainingToken()).isEqualTo(75);
        assertThat(availability.getCurrentConsumedToken()).isEqualTo(25);
        verify(redisTemplate).execute(
                eq(quotaReadScript),
                eq(List.of(
                        RedisQuotaKeys.configKey(NAMESPACE, TENANT_ID),
                        RedisQuotaKeys.burstableKey(NAMESPACE, TENANT_ID)
                )),
                anyString(),
                eq("burstable")
        );
    }
}
