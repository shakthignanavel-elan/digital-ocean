package com.ratelimiter.dataplane.support;

import com.ratelimiter.common.dto.ConfigurationDTO;
import com.ratelimiter.dataplane.algorithm.AlgorithmEvaluatorFactory;
import com.ratelimiter.dataplane.spi.ConfigurationStore;
import com.ratelimiter.dataplane.spi.DualBucketQuotaStore;
import com.ratelimiter.dataplane.store.memory.InMemoryConfigurationStore;
import com.ratelimiter.dataplane.store.memory.InMemoryDualBucketQuotaStore;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Wires process-shared in-memory stores into a dataplane context and exposes a
 * controlplane-shaped configuration REST API so tests can seed limits over HTTP.
 *
 * <p>In production the controlplane writes the same document to Redis; both
 * dataplanes would read that shared Redis state instead of this JVM store.
 */
@Configuration
@ConditionalOnProperty(name = "rate-limiter.store", havingValue = "shared-memory")
public class SharedMemoryStoreTestConfig {

    @Bean
    public InMemoryConfigurationStore inMemoryConfigurationStore() {
        return SharedMemoryStores.configuration();
    }

    @Bean
    public ConfigurationStore configurationStore(InMemoryConfigurationStore store) {
        return store;
    }

    @Bean
    public InMemoryDualBucketQuotaStore inMemoryDualBucketQuotaStore(AlgorithmEvaluatorFactory factory) {
        return SharedMemoryStores.quota(factory);
    }

    @Bean
    public DualBucketQuotaStore dualBucketQuotaStore(InMemoryDualBucketQuotaStore store) {
        return store;
    }

    /**
     * Mirrors controlplane {@code POST/PUT /configuration/{namespace}/{tenantId}}
     * so the dual-plane IT can publish config without booting the controlplane module.
     */
    @RestController
    static class ConfigurationSeedController {

        private final ConfigurationStore configurationStore;
        private final InMemoryDualBucketQuotaStore quotaStore;

        ConfigurationSeedController(ConfigurationStore configurationStore,
                                    InMemoryDualBucketQuotaStore quotaStore) {
            this.configurationStore = configurationStore;
            this.quotaStore = quotaStore;
        }

        @PostMapping("/configuration/{namespace}/{tenantId}")
        @ResponseStatus(HttpStatus.CREATED)
        public ConfigurationDTO create(@PathVariable String namespace,
                                       @PathVariable UUID tenantId,
                                       @Valid @RequestBody ConfigurationDTO request) {
            return upsert(namespace, tenantId, request);
        }

        @PutMapping("/configuration/{namespace}/{tenantId}")
        public ConfigurationDTO update(@PathVariable String namespace,
                                       @PathVariable UUID tenantId,
                                       @Valid @RequestBody ConfigurationDTO request) {
            return upsert(namespace, tenantId, request);
        }

        private ConfigurationDTO upsert(String namespace, UUID tenantId, ConfigurationDTO request) {
            request.setNamespace(namespace);
            request.setTenantId(tenantId);
            configurationStore.save(request);
            // New limits apply to a fresh quota snapshot for this tenant (hot-reload style).
            quotaStore.resetTenant(namespace, tenantId);
            return request;
        }
    }
}
