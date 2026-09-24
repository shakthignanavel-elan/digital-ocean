package com.ratelimiter.dataplane;

import com.ratelimiter.common.dto.ConfigurationDTO;
import com.ratelimiter.common.enums.RateLimitAlgorithm;
import com.ratelimiter.common.model.RateLimitConfig;
import com.ratelimiter.common.model.TokenConfiguration;
import com.ratelimiter.dataplane.service.RateLimitEvaluator;
import com.ratelimiter.dataplane.store.memory.InMemoryConfigurationStore;
import com.ratelimiter.dataplane.store.memory.InMemoryDualBucketQuotaStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests against the in-memory store (no Redis / Docker required).
 */
@SpringBootTest(properties = {
        "rate-limiter.store=memory",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
class EvaluateInMemoryIntegrationTest {

    private static final String NAMESPACE = "payments";
    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private RateLimitEvaluator evaluator;

    @Autowired
    private InMemoryConfigurationStore configurationStore;

    @Autowired
    private InMemoryDualBucketQuotaStore quotaStore;

    @BeforeEach
    void reset() {
        configurationStore.clear();
        quotaStore.clear();
        seedConfiguration(
                RateLimitAlgorithm.TOKEN_BUCKET, 2, 60,
                RateLimitAlgorithm.SLIDING_WINDOW, 10, 3600
        );
    }

    @Test
    void evaluateUsesFactorySelectedAlgorithmsFromConfig() {
        var result = evaluator.evaluate(NAMESPACE, TENANT_ID, 1);
        assertThat(result.allowed()).isTrue();
        assertThat(result.burstableEvaluator().algorithm()).isEqualTo(RateLimitAlgorithm.TOKEN_BUCKET);
        assertThat(result.sustainedEvaluator().algorithm()).isEqualTo(RateLimitAlgorithm.SLIDING_WINDOW);
    }

    @Test
    void evaluateAppliesConfiguredAlgorithmsUntilExhausted() {
        assertThat(evaluator.findConfiguration(NAMESPACE, TENANT_ID)).isPresent();

        assertThat(evaluator.evaluate(NAMESPACE, TENANT_ID, 1).allowed()).isTrue();
        assertThat(evaluator.evaluate(NAMESPACE, TENANT_ID, 1).allowed()).isTrue();
        assertThat(evaluator.evaluate(NAMESPACE, TENANT_ID, 1).allowed()).isFalse();
        assertThat(evaluator.evaluate(NAMESPACE, TENANT_ID, 1).reason()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void evaluateDeniesWhenNamespaceTenantConfigMissing() {
        configurationStore.delete(NAMESPACE, TENANT_ID);

        var result = evaluator.evaluate(NAMESPACE, TENANT_ID, 1);

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).isEqualTo("NO_CONFIG");
    }

    @Test
    void slidingWindowAllowsAgainAfterEventsRollOut() throws Exception {
        configurationStore.clear();
        quotaStore.clear();
        seedConfiguration(
                RateLimitAlgorithm.SLIDING_WINDOW, 1, 2,
                RateLimitAlgorithm.TOKEN_BUCKET, 100, 3600
        );

        assertThat(evaluator.evaluate(NAMESPACE, TENANT_ID, 1).allowed()).isTrue();
        assertThat(evaluator.evaluate(NAMESPACE, TENANT_ID, 1).allowed()).isFalse();

        Thread.sleep(2100);

        assertThat(evaluator.evaluate(NAMESPACE, TENANT_ID, 1).allowed()).isTrue();
    }

    @Test
    void evaluateDeniedWhenSustainedExhaustedDoesNotConsumeBurstable() {
        configurationStore.clear();
        quotaStore.clear();
        seedConfiguration(
                RateLimitAlgorithm.TOKEN_BUCKET, 5, 60,
                RateLimitAlgorithm.FIXED_WINDOW, 0, 3600
        );

        var result = evaluator.evaluate(NAMESPACE, TENANT_ID, 1);

        assertThat(result.allowed()).isFalse();
        assertThat(result.burstable().getRemainingToken()).isEqualTo(5);
        assertThat(result.sustained().getRemainingToken()).isEqualTo(0);
    }

    @Test
    void concurrentClientsNeverOversellSharedInMemoryState() throws Exception {
        configurationStore.clear();
        quotaStore.clear();
        int capacity = 40;
        seedConfiguration(
                RateLimitAlgorithm.TOKEN_BUCKET, capacity, 3600,
                RateLimitAlgorithm.SLIDING_WINDOW, capacity, 3600
        );

        int workers = 120;
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    if (evaluator.evaluate(NAMESPACE, TENANT_ID, 1).allowed()) {
                        allowed.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowed.get()).isEqualTo(capacity);
        var finalState = evaluator.evaluate(NAMESPACE, TENANT_ID, 1);
        assertThat(finalState.allowed()).isFalse();
        assertThat(finalState.burstable().getCurrentConsumedToken()).isEqualTo(capacity);
    }

    @Test
    void hotReloadUpdatesConfigAndAppliesNewLimits() {
        // BeforeEach seeded burst capacity=2
        assertThat(evaluator.evaluate(NAMESPACE, TENANT_ID, 1).allowed()).isTrue();
        assertThat(evaluator.evaluate(NAMESPACE, TENANT_ID, 1).allowed()).isTrue();
        assertThat(evaluator.evaluate(NAMESPACE, TENANT_ID, 1).allowed()).isFalse();

        quotaStore.resetTenant(NAMESPACE, TENANT_ID);
        seedConfiguration(
                RateLimitAlgorithm.TOKEN_BUCKET, 3, 60,
                RateLimitAlgorithm.FIXED_WINDOW, 10, 3600
        );

        assertThat(evaluator.findConfiguration(NAMESPACE, TENANT_ID).orElseThrow()
                .getFixedRateLimitConfig().getRateLimitAlgorithm())
                .isEqualTo(RateLimitAlgorithm.FIXED_WINDOW);

        assertThat(evaluator.evaluate(NAMESPACE, TENANT_ID, 1).allowed()).isTrue();
        assertThat(evaluator.evaluate(NAMESPACE, TENANT_ID, 1).allowed()).isTrue();
        assertThat(evaluator.evaluate(NAMESPACE, TENANT_ID, 1).allowed()).isTrue();
        assertThat(evaluator.evaluate(NAMESPACE, TENANT_ID, 1).allowed()).isFalse();
    }

    private void seedConfiguration(RateLimitAlgorithm burstableAlg, long burstableTokens, long burstableWindow,
                                   RateLimitAlgorithm sustainedAlg, long sustainedTokens, long sustainedWindow) {
        ConfigurationDTO config = new ConfigurationDTO();
        config.setNamespace(NAMESPACE);
        config.setTenantId(TENANT_ID);
        config.setBurstableRateLimitConfig(new RateLimitConfig(burstableAlg, burstableTokens, burstableWindow));
        config.setFixedRateLimitConfig(new RateLimitConfig(sustainedAlg, sustainedTokens, sustainedWindow));
        config.setTokenConfiguration(new TokenConfiguration(burstableTokens));
        configurationStore.save(config);
    }
}
