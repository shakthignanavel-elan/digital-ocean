package com.ratelimiter.controlplane.service;

import com.ratelimiter.common.dto.ConfigurationDTO;
import com.ratelimiter.common.dto.QuotaDTO;
import com.ratelimiter.common.enums.RateLimitAlgorithm;
import com.ratelimiter.common.model.RateLimitConfig;
import com.ratelimiter.common.model.TokenAvailability;
import com.ratelimiter.common.model.TokenConfiguration;
import com.ratelimiter.controlplane.entity.ConfigurationEntity;
import com.ratelimiter.controlplane.metrics.ConfigurationMetrics;
import com.ratelimiter.controlplane.redis.RedisQuotaStore;
import com.ratelimiter.controlplane.repository.ConfigurationRepository;
import com.ratelimiter.controlplane.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Date;
import java.util.Optional;

import static com.ratelimiter.controlplane.support.TestFixtures.NAMESPACE;
import static com.ratelimiter.controlplane.support.TestFixtures.TENANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfigurationServiceTest {

    @Mock
    private ConfigurationRepository repository;

    @Mock
    private RedisQuotaStore redisQuotaStore;

    @Mock
    private ConfigurationMetrics metrics;

    @InjectMocks
    private ConfigurationService configurationService;

    private ConfigurationDTO request;

    @BeforeEach
    void setUp() {
        request = TestFixtures.sampleConfiguration();
    }

    @Test
    void createPersistsConfigurationAndSyncsRedisWithoutRestart() {
        when(repository.existsById(any())).thenReturn(false);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ConfigurationDTO created = configurationService.create(NAMESPACE, TENANT_ID, request);

        assertThat(created.getNamespace()).isEqualTo(NAMESPACE);
        assertThat(created.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(created.getBurstableRateLimitConfig().getRateLimitAlgorithm())
                .isEqualTo(RateLimitAlgorithm.TOKEN_BUCKET);
        assertThat(created.getFixedRateLimitConfig().getRateLimitAlgorithm())
                .isEqualTo(RateLimitAlgorithm.SLIDING_WINDOW);
        assertThat(created.getTokenConfiguration().getTokenCount()).isEqualTo(100);

        ArgumentCaptor<ConfigurationDTO> captor = ArgumentCaptor.forClass(ConfigurationDTO.class);
        verify(redisQuotaStore).syncConfiguration(captor.capture());
        assertThat(captor.getValue().getBurstableRateLimitConfig().getRateLimitAlgorithm())
                .isEqualTo(RateLimitAlgorithm.TOKEN_BUCKET);
        assertThat(captor.getValue().getFixedRateLimitConfig().getRateLimitAlgorithm())
                .isEqualTo(RateLimitAlgorithm.SLIDING_WINDOW);
    }

    @Test
    void createRejectsDuplicateConfiguration() {
        when(repository.existsById(any())).thenReturn(true);

        assertThatThrownBy(() -> configurationService.create(NAMESPACE, TENANT_ID, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(repository, never()).save(any());
        verify(redisQuotaStore, never()).syncConfiguration(any());
    }

    @Test
    void getReturnsConfigurationByNamespaceAndTenant() {
        when(repository.findById(any())).thenReturn(Optional.of(toEntity(request)));

        ConfigurationDTO found = configurationService.get(NAMESPACE, TENANT_ID);

        assertThat(found.getBurstableRateLimitConfig().getAvailableToken()).isEqualTo(100);
        assertThat(found.getFixedRateLimitConfig().getAvailableToken()).isEqualTo(1000);
    }

    @Test
    void getThrowsNotFoundWhenMissing() {
        when(repository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> configurationService.get(NAMESPACE, TENANT_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateChangesRulesAndResyncsRedisLive() {
        ConfigurationEntity existing = toEntity(request);
        when(repository.findById(any())).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ConfigurationDTO update = TestFixtures.sampleConfiguration();
        update.setBurstableRateLimitConfig(new RateLimitConfig(RateLimitAlgorithm.TOKEN_BUCKET, 50, 30));
        update.setTokenConfiguration(new TokenConfiguration(50));

        ConfigurationDTO updated = configurationService.update(NAMESPACE, TENANT_ID, update);

        assertThat(updated.getBurstableRateLimitConfig().getAvailableToken()).isEqualTo(50);
        assertThat(updated.getBurstableRateLimitConfig().getTimeWindowInSeconds()).isEqualTo(30);

        ArgumentCaptor<ConfigurationDTO> captor = ArgumentCaptor.forClass(ConfigurationDTO.class);
        verify(redisQuotaStore).syncConfiguration(captor.capture());
        assertThat(captor.getValue().getBurstableRateLimitConfig().getAvailableToken()).isEqualTo(50);
    }

    @Test
    void deleteRemovesConfigurationAndRedisState() {
        when(repository.existsById(any())).thenReturn(true);

        configurationService.delete(NAMESPACE, TENANT_ID);

        verify(repository).deleteById(any());
        verify(redisQuotaStore).deleteConfiguration(NAMESPACE, TENANT_ID);
    }

    @Test
    void deleteThrowsNotFoundWhenMissing() {
        when(repository.existsById(any())).thenReturn(false);

        assertThatThrownBy(() -> configurationService.delete(NAMESPACE, TENANT_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getQuotaReturnsBurstableAndSustainedAvailability() {
        when(repository.findById(any())).thenReturn(Optional.of(toEntity(request)));
        Date reset = new Date();
        when(redisQuotaStore.readBurstableAvailability(NAMESPACE, TENANT_ID))
                .thenReturn(new TokenAvailability(100, 75, 25, reset));
        when(redisQuotaStore.readSustainedAvailability(NAMESPACE, TENANT_ID))
                .thenReturn(new TokenAvailability(1000, 900, 100, reset));

        QuotaDTO quota = configurationService.getQuota(NAMESPACE, TENANT_ID);

        assertThat(quota.getNamespace()).isEqualTo(NAMESPACE);
        assertThat(quota.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(quota.getBurstableTokenAvailability().getAvailableToken()).isEqualTo(100);
        assertThat(quota.getBurstableTokenAvailability().getRemainingToken()).isEqualTo(75);
        assertThat(quota.getBurstableTokenAvailability().getCurrentConsumedToken()).isEqualTo(25);
        assertThat(quota.getSustainedTokenAvailability().getAvailableToken()).isEqualTo(1000);
        assertThat(quota.getSustainedTokenAvailability().getRemainingToken()).isEqualTo(900);
        assertThat(quota.getSustainedTokenAvailability().getCurrentConsumedToken()).isEqualTo(100);
        assertThat(quota.getBurstableTokenAvailability().getResetTimestamp()).isEqualTo(reset);
    }

    private ConfigurationEntity toEntity(ConfigurationDTO dto) {
        ConfigurationEntity entity = new ConfigurationEntity();
        entity.setId(new ConfigurationEntity.ConfigurationId(dto.getTenantId(), dto.getNamespace()));
        entity.setBurstableAlgorithm(dto.getBurstableRateLimitConfig().getRateLimitAlgorithm());
        entity.setBurstableAvailableToken(dto.getBurstableRateLimitConfig().getAvailableToken());
        entity.setBurstableTimeWindowInSeconds(dto.getBurstableRateLimitConfig().getTimeWindowInSeconds());
        entity.setFixedAlgorithm(dto.getFixedRateLimitConfig().getRateLimitAlgorithm());
        entity.setFixedAvailableToken(dto.getFixedRateLimitConfig().getAvailableToken());
        entity.setFixedTimeWindowInSeconds(dto.getFixedRateLimitConfig().getTimeWindowInSeconds());
        entity.setTokenCount(dto.getTokenConfiguration().getTokenCount());
        return entity;
    }
}
