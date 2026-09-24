package com.ratelimiter.dataplane.service;

import com.ratelimiter.common.dto.ConfigurationDTO;
import com.ratelimiter.common.model.RateLimitConfig;
import com.ratelimiter.common.model.TokenAvailability;
import com.ratelimiter.dataplane.algorithm.AlgorithmEvaluator;
import com.ratelimiter.dataplane.algorithm.AlgorithmEvaluatorFactory;
import com.ratelimiter.dataplane.metrics.RateLimitMetrics;
import com.ratelimiter.dataplane.spi.ConfigurationStore;
import com.ratelimiter.dataplane.spi.DualBucketQuotaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Store-agnostic orchestrator: load config → factory evaluators → dual-bucket tryConsume.
 */
@Service
public class RateLimitEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RateLimitEvaluator.class);

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
    private final RateLimitMetrics metrics;

    public RateLimitEvaluator(ConfigurationStore configurationStore,
                              AlgorithmEvaluatorFactory evaluatorFactory,
                              DualBucketQuotaStore dualBucketQuotaStore,
                              RateLimitMetrics metrics) {
        this.configurationStore = configurationStore;
        this.evaluatorFactory = evaluatorFactory;
        this.dualBucketQuotaStore = dualBucketQuotaStore;
        this.metrics = metrics;
    }

    public Optional<ConfigurationDTO> findConfiguration(String namespace, UUID tenantId) {
        return configurationStore.find(namespace, tenantId);
    }

    public DualEvaluationResult evaluate(String namespace, UUID tenantId, long cost) {
        long started = System.nanoTime();
        DualEvaluationResult result;
        try {
            result = doEvaluate(namespace, tenantId, cost);
        } catch (RuntimeException ex) {
            metrics.recordEvaluate(namespace, false, "ERROR", System.nanoTime() - started);
            log.error("evaluate failed namespace={} tenantId={} cost={}", namespace, tenantId, cost, ex);
            throw ex;
        }
        metrics.recordEvaluate(namespace, result.allowed(), result.reason(), System.nanoTime() - started);
        if (log.isDebugEnabled()) {
            log.debug("evaluate namespace={} tenantId={} cost={} allowed={} reason={} burstRemaining={} sustainedRemaining={}",
                    namespace, tenantId, cost, result.allowed(), result.reason(),
                    result.burstable() != null ? result.burstable().getRemainingToken() : null,
                    result.sustained() != null ? result.sustained().getRemainingToken() : null);
        } else if (!result.allowed() && "NO_CONFIG".equals(result.reason())) {
            log.info("evaluate denied NO_CONFIG namespace={} tenantId={}", namespace, tenantId);
        }
        return result;
    }

    private DualEvaluationResult doEvaluate(String namespace, UUID tenantId, long cost) {
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
