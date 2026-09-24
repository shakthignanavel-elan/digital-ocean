package com.ratelimiter.dataplane.service;

import com.ratelimiter.common.dto.ConfigurationDTO;
import com.ratelimiter.common.model.RateLimitConfig;
import com.ratelimiter.common.model.TokenAvailability;
import com.ratelimiter.dataplane.algorithm.AlgorithmEvaluator;
import com.ratelimiter.dataplane.algorithm.AlgorithmEvaluatorFactory;
import com.ratelimiter.dataplane.spi.ConfigurationStore;
import com.ratelimiter.dataplane.spi.DualBucketQuotaStore;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Store-agnostic orchestrator: load config → factory evaluators → dual-bucket tryConsume.
 */
@Service
public class RateLimitEvaluator {

    public record DualEvaluationResult(
            boolean allowed,
            String reason,
            TokenAvailability burstable,
            TokenAvailability sustained,
            ConfigurationDTO configuration,
            AlgorithmEvaluator burstableEvaluator,
            AlgorithmEvaluator sustainedEvaluator
    ) {
    }

    private final ConfigurationStore configurationStore;
    private final AlgorithmEvaluatorFactory evaluatorFactory;
    private final DualBucketQuotaStore dualBucketQuotaStore;

    public RateLimitEvaluator(ConfigurationStore configurationStore,
                              AlgorithmEvaluatorFactory evaluatorFactory,
                              DualBucketQuotaStore dualBucketQuotaStore) {
        this.configurationStore = configurationStore;
        this.evaluatorFactory = evaluatorFactory;
        this.dualBucketQuotaStore = dualBucketQuotaStore;
    }

    public Optional<ConfigurationDTO> findConfiguration(String namespace, UUID tenantId) {
        return configurationStore.find(namespace, tenantId);
    }

    public DualEvaluationResult evaluate(String namespace, UUID tenantId, long cost) {
        long nowSeconds = System.currentTimeMillis() / 1000;
        Optional<ConfigurationDTO> configuration = configurationStore.find(namespace, tenantId);
        if (configuration.isEmpty()) {
            TokenAvailability empty = new TokenAvailability(0, 0, 0, new Date(nowSeconds * 1000));
            return new DualEvaluationResult(false, "NO_CONFIG", empty, empty, null, null, null);
        }

        ConfigurationDTO config = configuration.get();
        RateLimitConfig burstableConfig = config.getBurstableRateLimitConfig();
        RateLimitConfig sustainedConfig = config.getFixedRateLimitConfig();

        AlgorithmEvaluator burstableEvaluator = evaluatorFactory.burstable(burstableConfig.getRateLimitAlgorithm());
        AlgorithmEvaluator sustainedEvaluator = evaluatorFactory.sustained(sustainedConfig.getRateLimitAlgorithm());

        var result = dualBucketQuotaStore.tryConsume(
                namespace, tenantId, burstableConfig, sustainedConfig, cost);

        return new DualEvaluationResult(
                result.allowed(),
                result.reason(),
                result.burstable(),
                result.sustained(),
                config,
                burstableEvaluator,
                sustainedEvaluator
        );
    }
}
