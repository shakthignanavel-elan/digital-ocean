package com.ratelimiter.dataplane.spi;

import com.ratelimiter.common.dto.ConfigurationDTO;

import java.util.Optional;
import java.util.UUID;

/**
 * Durable/runtime configuration lookup keyed by namespace + tenant.
 * Implementations: Redis, in-memory, etc.
 */
public interface ConfigurationStore {

    Optional<ConfigurationDTO> find(String namespace, UUID tenantId);

    void save(ConfigurationDTO configuration);

    void delete(String namespace, UUID tenantId);
}
