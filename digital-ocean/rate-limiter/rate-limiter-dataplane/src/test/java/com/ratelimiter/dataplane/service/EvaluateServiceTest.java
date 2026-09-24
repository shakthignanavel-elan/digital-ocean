package com.ratelimiter.dataplane.service;

import com.ratelimiter.common.dto.ConfigurationDTO;
import com.ratelimiter.common.dto.EvaluationRequestDTO;
import com.ratelimiter.common.dto.EvaluationResponseDTO;
import com.ratelimiter.common.enums.RateLimitAlgorithm;
import com.ratelimiter.common.model.RateLimitConfig;
import com.ratelimiter.common.model.TokenAvailability;
import com.ratelimiter.common.model.TokenConfiguration;
import com.ratelimiter.dataplane.algorithm.SlidingWindowEvaluator;
import com.ratelimiter.dataplane.algorithm.TokenBucketEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvaluateServiceTest {

    private static final String NAMESPACE = "payments";
    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private RateLimitEvaluator rateLimitEvaluator;

    @InjectMocks
    private EvaluateService evaluateService;

    @Test
    void evaluateEnforcesQuotaByNamespaceAndTenantIdUsingConfigStore() {
        Date reset = new Date();
        ConfigurationDTO config = sampleConfig(NAMESPACE, TENANT_ID);
        when(rateLimitEvaluator.evaluate(eq(NAMESPACE), eq(TENANT_ID), eq(1L)))
                .thenReturn(new RateLimitEvaluator.DualEvaluationResult(
                        true,
                        "OK",
                        new TokenAvailability(100, 99, 1, reset),
                        new TokenAvailability(1000, 999, 1, reset),
                        config,
                        new TokenBucketEvaluator(),
                        new SlidingWindowEvaluator()
                ));

        EvaluationResponseDTO response = evaluateService.evaluate(new EvaluationRequestDTO(NAMESPACE, TENANT_ID));

        assertThat(response.isAllowed()).isTrue();
        verify(rateLimitEvaluator).evaluate(NAMESPACE, TENANT_ID, 1L);
    }

    @Test
    void evaluateEnforcesQuotaByApiKeyWhenTenantIdAbsent() {
        String apiKey = "sk_live_example";
        UUID derivedTenant = UUID.nameUUIDFromBytes(apiKey.getBytes());
        Date reset = new Date();

        when(rateLimitEvaluator.evaluate(eq(NAMESPACE), eq(derivedTenant), eq(1L)))
                .thenReturn(new RateLimitEvaluator.DualEvaluationResult(
                        false,
                        "RATE_LIMITED",
                        new TokenAvailability(10, 0, 10, reset),
                        new TokenAvailability(100, 100, 0, reset),
                        sampleConfig(NAMESPACE, derivedTenant),
                        new TokenBucketEvaluator(),
                        new SlidingWindowEvaluator()
                ));

        EvaluationRequestDTO request = new EvaluationRequestDTO();
        request.setNamespace(NAMESPACE);
        request.setApiKey(apiKey);

        EvaluationResponseDTO response = evaluateService.evaluate(request);

        assertThat(response.isAllowed()).isFalse();
        assertThat(response.getTenantId()).isEqualTo(derivedTenant.toString());
    }

    @Test
    void evaluateRequiresTenantIdOrApiKey() {
        EvaluationRequestDTO request = new EvaluationRequestDTO();
        request.setNamespace(NAMESPACE);

        assertThatThrownBy(() -> evaluateService.evaluate(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ConfigurationDTO sampleConfig(String namespace, UUID tenantId) {
        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setNamespace(namespace);
        dto.setTenantId(tenantId);
        dto.setBurstableRateLimitConfig(new RateLimitConfig(RateLimitAlgorithm.TOKEN_BUCKET, 100, 60));
        dto.setFixedRateLimitConfig(new RateLimitConfig(RateLimitAlgorithm.SLIDING_WINDOW, 1000, 3600));
        dto.setTokenConfiguration(new TokenConfiguration(100));
        return dto;
    }
}
