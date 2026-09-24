package com.ratelimiter.dataplane.store.memory;

import com.ratelimiter.common.dto.ConfigurationDTO;
import com.ratelimiter.dataplane.spi.ConfigurationStore;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryConfigurationStore implements ConfigurationStore {

    private final ConcurrentHashMap<String, ConfigurationDTO> configs = new ConcurrentHashMap<>();

    private static String key(String namespace, UUID tenantId) {
        return namespace + ":" + tenantId;
    }

    @Override
    public Optional<ConfigurationDTO> find(String namespace, UUID tenantId) {
        return Optional.ofNullable(configs.get(key(namespace, tenantId)));
    }

    @Override
    public void save(ConfigurationDTO configuration) {
        configs.put(key(configuration.getNamespace(), configuration.getTenantId()), configuration);
    }

    @Override
    public void delete(String namespace, UUID tenantId) {
        configs.remove(key(namespace, tenantId));
    }

    public void clear() {
        configs.clear();
    }
}
