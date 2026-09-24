package com.ratelimiter.controlplane.service;

import com.ratelimiter.common.dto.ConfigurationDTO;
import com.ratelimiter.common.dto.QuotaDTO;
import com.ratelimiter.common.model.RateLimitConfig;
import com.ratelimiter.common.model.TokenAvailability;
import com.ratelimiter.common.model.TokenConfiguration;
import com.ratelimiter.controlplane.entity.ConfigurationEntity;
import com.ratelimiter.controlplane.metrics.ConfigurationMetrics;
import com.ratelimiter.controlplane.redis.RedisQuotaStore;
import com.ratelimiter.controlplane.repository.ConfigurationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class ConfigurationService {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationService.class);

    private final ConfigurationRepository repository;
    private final RedisQuotaStore redisQuotaStore;
    private final ConfigurationMetrics metrics;

    public ConfigurationService(ConfigurationRepository repository,
                                RedisQuotaStore redisQuotaStore,
                                ConfigurationMetrics metrics) {
        this.repository = repository;
        this.redisQuotaStore = redisQuotaStore;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public ConfigurationDTO get(String namespace, UUID tenantId) {
        return timed("get", namespace, () -> toDto(findOrThrow(namespace, tenantId)));
    }

    @Transactional
    public ConfigurationDTO create(String namespace, UUID tenantId, ConfigurationDTO request) {
        return timed("create", namespace, () -> {
            ConfigurationEntity.ConfigurationId id = new ConfigurationEntity.ConfigurationId(tenantId, namespace);
            if (repository.existsById(id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Configuration already exists for namespace=" + namespace + ", tenantId=" + tenantId);
            }
            normalizeRequest(namespace, tenantId, request);
            ConfigurationEntity saved = repository.save(toEntity(request));
            ConfigurationDTO dto = toDto(saved);
            redisQuotaStore.syncConfiguration(dto);
            log.info("configuration created namespace={} tenantId={} burstable={} sustained={}",
                    namespace, tenantId,
                    dto.getBurstableRateLimitConfig().getAvailableToken(),
                    dto.getFixedRateLimitConfig().getAvailableToken());
            return dto;
        });
    }

    @Transactional
    public ConfigurationDTO update(String namespace, UUID tenantId, ConfigurationDTO request) {
        return timed("update", namespace, () -> {
            ConfigurationEntity existing = findOrThrow(namespace, tenantId);
            normalizeRequest(namespace, tenantId, request);
            apply(existing, request);
            ConfigurationEntity saved = repository.save(existing);
            ConfigurationDTO dto = toDto(saved);
            redisQuotaStore.syncConfiguration(dto);
            log.info("configuration updated namespace={} tenantId={} burstable={} sustained={}",
                    namespace, tenantId,
                    dto.getBurstableRateLimitConfig().getAvailableToken(),
                    dto.getFixedRateLimitConfig().getAvailableToken());
            return dto;
        });
    }

    @Transactional
    public void delete(String namespace, UUID tenantId) {
        timed("delete", namespace, () -> {
            ConfigurationEntity.ConfigurationId id = new ConfigurationEntity.ConfigurationId(tenantId, namespace);
            if (!repository.existsById(id)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Configuration not found for namespace=" + namespace + ", tenantId=" + tenantId);
            }
            repository.deleteById(id);
            redisQuotaStore.deleteConfiguration(namespace, tenantId);
            log.info("configuration deleted namespace={} tenantId={}", namespace, tenantId);
            return null;
        });
    }

    @Transactional(readOnly = true)
    public QuotaDTO getQuota(String namespace, UUID tenantId) {
        return timed("quota", namespace, () -> {
            findOrThrow(namespace, tenantId);
            TokenAvailability burstable = redisQuotaStore.readBurstableAvailability(namespace, tenantId);
            TokenAvailability sustained = redisQuotaStore.readSustainedAvailability(namespace, tenantId);
            log.debug("quota read namespace={} tenantId={} burstableRemaining={} sustainedRemaining={}",
                    namespace, tenantId, burstable.getRemainingToken(), sustained.getRemainingToken());
            return new QuotaDTO(burstable, sustained, namespace, tenantId);
        });
    }

    private <T> T timed(String operation, String namespace, java.util.concurrent.Callable<T> action) {
        long started = System.nanoTime();
        try {
            T result = action.call();
            metrics.record(operation, namespace, "success", System.nanoTime() - started);
            return result;
        } catch (ResponseStatusException ex) {
            metrics.record(operation, namespace, statusTag(ex.getStatusCode().value()), System.nanoTime() - started);
            throw ex;
        } catch (RuntimeException ex) {
            metrics.record(operation, namespace, "error", System.nanoTime() - started);
            log.error("configuration {} failed namespace={}", operation, namespace, ex);
            throw ex;
        } catch (Exception ex) {
            metrics.record(operation, namespace, "error", System.nanoTime() - started);
            log.error("configuration {} failed namespace={}", operation, namespace, ex);
            throw new IllegalStateException(ex);
        }
    }

    private static String statusTag(int status) {
        if (status == 404) {
            return "not_found";
        }
        if (status == 409) {
            return "conflict";
        }
        if (status >= 400 && status < 500) {
            return "client_error";
        }
        return "error";
    }

    private ConfigurationEntity findOrThrow(String namespace, UUID tenantId) {
        return repository.findById(new ConfigurationEntity.ConfigurationId(tenantId, namespace))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Configuration not found for namespace=" + namespace + ", tenantId=" + tenantId));
    }

    private void normalizeRequest(String namespace, UUID tenantId, ConfigurationDTO request) {
        request.setNamespace(namespace);
        request.setTenantId(tenantId);
        if (request.getBurstableRateLimitConfig() == null && request.getRateLimitConfig() != null) {
            request.setBurstableRateLimitConfig(request.getRateLimitConfig());
        }
        if (request.getTokenConfiguration() == null) {
            request.setTokenConfiguration(new TokenConfiguration(0));
        }
    }

    private ConfigurationEntity toEntity(ConfigurationDTO dto) {
        ConfigurationEntity entity = new ConfigurationEntity();
        entity.setId(new ConfigurationEntity.ConfigurationId(dto.getTenantId(), dto.getNamespace()));
        apply(entity, dto);
        return entity;
    }

    private void apply(ConfigurationEntity entity, ConfigurationDTO dto) {
        RateLimitConfig burstable = dto.getBurstableRateLimitConfig();
        RateLimitConfig fixed = dto.getFixedRateLimitConfig();
        entity.setBurstableAlgorithm(burstable.getRateLimitAlgorithm());
        entity.setBurstableAvailableToken(burstable.getAvailableToken());
        entity.setBurstableTimeWindowInSeconds(burstable.getTimeWindowInSeconds());
        entity.setFixedAlgorithm(fixed.getRateLimitAlgorithm());
        entity.setFixedAvailableToken(fixed.getAvailableToken());
        entity.setFixedTimeWindowInSeconds(fixed.getTimeWindowInSeconds());
        long tokenCount = dto.getTokenConfiguration() != null
                ? dto.getTokenConfiguration().getTokenCount()
                : burstable.getAvailableToken();
        entity.setTokenCount(tokenCount);
    }

    private ConfigurationDTO toDto(ConfigurationEntity entity) {
        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setNamespace(entity.getId().getNamespace());
        dto.setTenantId(entity.getId().getTenantId());
        dto.setBurstableRateLimitConfig(new RateLimitConfig(
                entity.getBurstableAlgorithm(),
                entity.getBurstableAvailableToken(),
                entity.getBurstableTimeWindowInSeconds()
        ));
        dto.setFixedRateLimitConfig(new RateLimitConfig(
                entity.getFixedAlgorithm(),
                entity.getFixedAvailableToken(),
                entity.getFixedTimeWindowInSeconds()
        ));
        dto.setTokenConfiguration(new TokenConfiguration(entity.getTokenCount()));
        return dto;
    }
}
