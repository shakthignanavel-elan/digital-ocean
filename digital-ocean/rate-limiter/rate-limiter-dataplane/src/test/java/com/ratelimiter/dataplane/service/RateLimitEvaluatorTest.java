package com.ratelimiter.dataplane.service;

import com.ratelimiter.common.dto.ConfigurationDTO;
import com.ratelimiter.common.enums.RateLimitAlgorithm;
import com.ratelimiter.common.model.RateLimitConfig;
import com.ratelimiter.common.model.TokenAvailability;
import com.ratelimiter.common.model.TokenConfiguration;
import com.ratelimiter.dataplane.algorithm.AlgorithmEvaluatorFactory;
import com.ratelimiter.dataplane.algorithm.SlidingWindowEvaluator;
import com.ratelimiter.dataplane.algorithm.TokenBucketEvaluator;
import com.ratelimiter.dataplane.metrics.RateLimitMetrics;
import com.ratelimiter.dataplane.spi.ConfigurationStore;
import com.ratelimiter.dataplane.spi.DualBucketQuotaStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitEvaluatorTest {

    private static final String NAMESPACE = "payments";
    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private ConfigurationStore configurationStore;

    @Mock
    private AlgorithmEvaluatorFactory evaluatorFactory;

    @Mock
    private DualBucketQuotaStore dualBucketQuotaStore;

    @Mock
    private RateLimitMetrics metrics;

    @InjectMocks
    private RateLimitEvaluator evaluator;

    @Test
    void evaluateDeniesWhenConfigurationMissing() {
        when(configurationStore.find(NAMESPACE, TENANT_ID)).thenReturn(Optional.empty());

        var result = evaluator.evaluate(NAMESPACE, TENANT_ID, 1);

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).isEqualTo("NO_CONFIG");
        verifyNoInteractions(evaluatorFactory, dualBucketQuotaStore);
        verify(metrics).recordEvaluate(eq(NAMESPACE), eq(false), eq("NO_CONFIG"), anyLong());
    }

    @Test
    void evaluateResolvesFactoryEvaluatorsAndDelegatesToQuotaStore() {
        ConfigurationDTO config = sampleConfig();
        TokenBucketEvaluator burstable = new TokenBucketEvaluator();
        SlidingWindowEvaluator sustained = new SlidingWindowEvaluator();
        Date reset = new Date();

        when(configurationStore.find(NAMESPACE, TENANT_ID)).thenReturn(Optional.of(config));
        when(evaluatorFactory.burstable(RateLimitAlgorithm.TOKEN_BUCKET)).thenReturn(burstable);
        when(evaluatorFactory.sustained(RateLimitAlgorithm.SLIDING_WINDOW)).thenReturn(sustained);
        when(dualBucketQuotaStore.tryConsume(
                eq(NAMESPACE), eq(TENANT_ID),
                eq(config.getBurstableRateLimitConfig()),
                eq(config.getFixedRateLimitConfig()),
                eq(1L)
        )).thenReturn(new DualBucketQuotaStore.DualBucketResult(
                true, "OK",
                new TokenAvailability(100, 99, 1, reset),
                new TokenAvailability(1000, 999, 1, reset)
        ));

        var result = evaluator.evaluate(NAMESPACE, TENANT_ID, 1);

        assertThat(result.allowed()).isTrue();
        assertThat(result.burstableEvaluator().algorithm()).isEqualTo(RateLimitAlgorithm.TOKEN_BUCKET);
        assertThat(result.sustainedEvaluator().algorithm()).isEqualTo(RateLimitAlgorithm.SLIDING_WINDOW);
        verify(dualBucketQuotaStore).tryConsume(any(), any(), any(), any(), anyLong());
        verify(metrics).recordEvaluate(eq(NAMESPACE), eq(true), eq("OK"), anyLong());
    }

    private ConfigurationDTO sampleConfig() {
        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setNamespace(NAMESPACE);
        dto.setTenantId(TENANT_ID);
        dto.setBurstableRateLimitConfig(new RateLimitConfig(RateLimitAlgorithm.TOKEN_BUCKET, 100, 60));
        dto.setFixedRateLimitConfig(new RateLimitConfig(RateLimitAlgorithm.SLIDING_WINDOW, 1000, 3600));
        dto.setTokenConfiguration(new TokenConfiguration(100));
        return dto;
    }
}
