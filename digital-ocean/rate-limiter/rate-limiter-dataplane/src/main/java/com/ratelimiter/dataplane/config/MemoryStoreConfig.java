package com.ratelimiter.dataplane.config;

import com.ratelimiter.dataplane.algorithm.AlgorithmEvaluatorFactory;
import com.ratelimiter.dataplane.spi.ConfigurationStore;
import com.ratelimiter.dataplane.spi.DualBucketQuotaStore;
import com.ratelimiter.dataplane.store.memory.InMemoryConfigurationStore;
import com.ratelimiter.dataplane.store.memory.InMemoryDualBucketQuotaStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "rate-limiter.store", havingValue = "memory")
public class MemoryStoreConfig {

    @Bean
    @ConditionalOnMissingBean(InMemoryConfigurationStore.class)
    public InMemoryConfigurationStore inMemoryConfigurationStore() {
        return new InMemoryConfigurationStore();
    }

    @Bean
    @ConditionalOnMissingBean(ConfigurationStore.class)
    public ConfigurationStore configurationStore(InMemoryConfigurationStore store) {
        return store;
    }

    @Bean
    @ConditionalOnMissingBean(InMemoryDualBucketQuotaStore.class)
    public InMemoryDualBucketQuotaStore inMemoryDualBucketQuotaStore(AlgorithmEvaluatorFactory factory) {
        return new InMemoryDualBucketQuotaStore(factory);
    }

    @Bean
    @ConditionalOnMissingBean(DualBucketQuotaStore.class)
    public DualBucketQuotaStore dualBucketQuotaStore(InMemoryDualBucketQuotaStore store) {
        return store;
    }
}
