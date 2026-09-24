package com.ratelimiter.controlplane;

import com.ratelimiter.common.dto.ConfigurationDTO;
import com.ratelimiter.common.enums.RateLimitAlgorithm;
import com.ratelimiter.common.model.RateLimitConfig;
import com.ratelimiter.common.model.RedisQuotaKeys;
import com.ratelimiter.common.model.TokenAvailability;
import com.ratelimiter.common.model.TokenConfiguration;
import com.ratelimiter.common.redis.RateLimitScripts;
import com.ratelimiter.controlplane.redis.RedisQuotaStore;
import com.ratelimiter.controlplane.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static com.ratelimiter.controlplane.support.TestFixtures.NAMESPACE;
import static com.ratelimiter.controlplane.support.TestFixtures.TENANT_ID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Controlplane populates Redis config for namespace+tenant; quota reads use that config.
 * Skipped when Docker is unavailable.
 */
@SpringBootTest
@ActiveProfiles("local")
@Testcontainers(disabledWithoutDocker = true)
class RedisQuotaStoreIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private RedisQuotaStore redisQuotaStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void flush() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void syncWritesConfigDocumentReadableByNamespaceAndTenant() {
        ConfigurationDTO config = TestFixtures.sampleConfiguration();
        redisQuotaStore.syncConfiguration(config);

        assertThat(redisTemplate.hasKey(RedisQuotaKeys.configKey(NAMESPACE, TENANT_ID))).isTrue();
        assertThat(redisQuotaStore.findConfiguration(NAMESPACE, TENANT_ID)).isPresent();
        assertThat(redisQuotaStore.findConfiguration(NAMESPACE, TENANT_ID).orElseThrow()
                .getBurstableRateLimitConfig().getRateLimitAlgorithm())
                .isEqualTo(RateLimitAlgorithm.TOKEN_BUCKET);
        assertThat(redisQuotaStore.findConfiguration(NAMESPACE, TENANT_ID).orElseThrow()
                .getFixedRateLimitConfig().getRateLimitAlgorithm())
                .isEqualTo(RateLimitAlgorithm.SLIDING_WINDOW);

        TokenAvailability burstable = redisQuotaStore.readBurstableAvailability(NAMESPACE, TENANT_ID);
        TokenAvailability sustained = redisQuotaStore.readSustainedAvailability(NAMESPACE, TENANT_ID);
        assertThat(burstable.getAvailableToken()).isEqualTo(100);
        assertThat(burstable.getCurrentConsumedToken()).isZero();
        assertThat(sustained.getAvailableToken()).isEqualTo(1000);

        redisQuotaStore.deleteConfiguration(NAMESPACE, TENANT_ID);
        assertThat(redisQuotaStore.findConfiguration(NAMESPACE, TENANT_ID)).isEmpty();
        assertThat(redisTemplate.hasKey(RedisQuotaKeys.configKey(NAMESPACE, TENANT_ID))).isFalse();
    }

    @Test
    void quotaStateMatchesEvaluateConsumptionDrivenBySyncedConfig() {
        ConfigurationDTO config = new ConfigurationDTO();
        config.setNamespace(NAMESPACE);
        config.setTenantId(TENANT_ID);
        config.setBurstableRateLimitConfig(new RateLimitConfig(RateLimitAlgorithm.TOKEN_BUCKET, 5, 60));
        config.setFixedRateLimitConfig(new RateLimitConfig(RateLimitAlgorithm.SLIDING_WINDOW, 5, 3600));
        config.setTokenConfiguration(new TokenConfiguration(5));
        redisQuotaStore.syncConfiguration(config);

        DefaultRedisScript<String> evaluateScript = new DefaultRedisScript<>();
        evaluateScript.setResultType(String.class);
        evaluateScript.setScriptText(RateLimitScripts.EVALUATE_DUAL);

        String burstable = RedisQuotaKeys.burstableKey(NAMESPACE, TENANT_ID);
        String sustained = RedisQuotaKeys.sustainedKey(NAMESPACE, TENANT_ID);
        long now = System.currentTimeMillis() / 1000;

        for (int i = 0; i < 3; i++) {
            String json = redisTemplate.execute(
                    evaluateScript,
                    List.of(burstable, sustained),
                    String.valueOf(now),
                    "1",
                    "TOKEN_BUCKET", "5", "60",
                    "SLIDING_WINDOW", "5", "3600"
            );
            assertThat(json).contains("\"allowed\":true");
        }

        TokenAvailability afterBurst = redisQuotaStore.readBurstableAvailability(NAMESPACE, TENANT_ID);
        TokenAvailability afterSustained = redisQuotaStore.readSustainedAvailability(NAMESPACE, TENANT_ID);
        assertThat(afterBurst.getRemainingToken()).isEqualTo(2);
        assertThat(afterBurst.getCurrentConsumedToken()).isEqualTo(3);
        assertThat(afterSustained.getRemainingToken()).isEqualTo(2);
        assertThat(afterSustained.getCurrentConsumedToken()).isEqualTo(3);

        config.setBurstableRateLimitConfig(new RateLimitConfig(RateLimitAlgorithm.FIXED_WINDOW, 10, 60));
        config.setFixedRateLimitConfig(new RateLimitConfig(RateLimitAlgorithm.TOKEN_BUCKET, 10, 3600));
        redisQuotaStore.syncConfiguration(config);

        assertThat(redisQuotaStore.findConfiguration(NAMESPACE, TENANT_ID).orElseThrow()
                .getBurstableRateLimitConfig().getRateLimitAlgorithm())
                .isEqualTo(RateLimitAlgorithm.FIXED_WINDOW);

        TokenAvailability reloaded = redisQuotaStore.readBurstableAvailability(NAMESPACE, TENANT_ID);
        assertThat(reloaded.getAvailableToken()).isEqualTo(10);
        assertThat(reloaded.getRemainingToken()).isEqualTo(10);
        assertThat(reloaded.getCurrentConsumedToken()).isZero();
    }
}
