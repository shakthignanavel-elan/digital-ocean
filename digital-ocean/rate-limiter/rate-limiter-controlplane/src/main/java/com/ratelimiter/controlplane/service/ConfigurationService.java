package com.ratelimiter.controlplane.service;

import com.ratelimiter.common.dto.ConfigurationDTO;
import com.ratelimiter.common.dto.QuotaDTO;
import com.ratelimiter.common.model.RateLimitConfig;
import com.ratelimiter.common.model.TokenAvailability;
import com.ratelimiter.common.model.TokenConfiguration;
import com.ratelimiter.controlplane.entity.ConfigurationEntity;
import com.ratelimiter.controlplane.redis.RedisQuotaStore;
import com.ratelimiter.controlplane.repository.ConfigurationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class ConfigurationService {

    private final ConfigurationRepository repository;
    private final RedisQuotaStore redisQuotaStore;

    public ConfigurationService(ConfigurationRepository repository, RedisQuotaStore redisQuotaStore) {
        this.repository = repository;
        this.redisQuotaStore = redisQuotaStore;
    }

    @Transactional(readOnly = true)
    public ConfigurationDTO get(String namespace, UUID tenantId) {
        return toDto(findOrThrow(namespace, tenantId));
    }

    @Transactional
    public ConfigurationDTO create(String namespace, UUID tenantId, ConfigurationDTO request) {
        ConfigurationEntity.ConfigurationId id = new ConfigurationEntity.ConfigurationId(tenantId, namespace);
        if (repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Configuration already exists for namespace=" + namespace + ", tenantId=" + tenantId);
        }
        normalizeRequest(namespace, tenantId, request);
        ConfigurationEntity saved = repository.save(toEntity(request));
        ConfigurationDTO dto = toDto(saved);
        redisQuotaStore.syncConfiguration(dto);
        return dto;
    }

    @Transactional
    public ConfigurationDTO update(String namespace, UUID tenantId, ConfigurationDTO request) {
        ConfigurationEntity existing = findOrThrow(namespace, tenantId);
        normalizeRequest(namespace, tenantId, request);
        apply(existing, request);
        ConfigurationEntity saved = repository.save(existing);
        ConfigurationDTO dto = toDto(saved);
        redisQuotaStore.syncConfiguration(dto);
        return dto;
    }

    @Transactional
    public void delete(String namespace, UUID tenantId) {
        ConfigurationEntity.ConfigurationId id = new ConfigurationEntity.ConfigurationId(tenantId, namespace);
        if (!repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Configuration not found for namespace=" + namespace + ", tenantId=" + tenantId);
        }
        repository.deleteById(id);
        redisQuotaStore.deleteConfiguration(namespace, tenantId);
    }

    @Transactional(readOnly = true)
    public QuotaDTO getQuota(String namespace, UUID tenantId) {
        findOrThrow(namespace, tenantId);
        TokenAvailability burstable = redisQuotaStore.readBurstableAvailability(namespace, tenantId);
        TokenAvailability sustained = redisQuotaStore.readSustainedAvailability(namespace, tenantId);
        return new QuotaDTO(burstable, sustained, namespace, tenantId);
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
