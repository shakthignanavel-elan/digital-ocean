package com.ratelimiter.dataplane.store.memory;

import com.ratelimiter.common.model.RateLimitConfig;
import com.ratelimiter.common.model.TokenAvailability;
import com.ratelimiter.dataplane.algorithm.AlgorithmEvaluator;
import com.ratelimiter.dataplane.algorithm.AlgorithmEvaluatorFactory;
import com.ratelimiter.dataplane.algorithm.BucketSnapshot;
import com.ratelimiter.dataplane.spi.DualBucketQuotaStore;

import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM-local dual-bucket store for tests / single-node demos.
 * Uses the same AlgorithmEvaluator strategies as production; synchronizes per tenant key.
 */
public class InMemoryDualBucketQuotaStore implements DualBucketQuotaStore {

    private final AlgorithmEvaluatorFactory evaluatorFactory;
    private final ConcurrentHashMap<String, BucketSnapshot> burstableState = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BucketSnapshot> sustainedState = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public InMemoryDualBucketQuotaStore(AlgorithmEvaluatorFactory evaluatorFactory) {
        this.evaluatorFactory = evaluatorFactory;
    }

    @Override
    public DualBucketResult tryConsume(String namespace,
                                       UUID tenantId,
                                       RateLimitConfig burstableConfig,
                                       RateLimitConfig sustainedConfig,
                                       long cost) {
        String id = namespace + ":" + tenantId;
        Object lock = locks.computeIfAbsent(id, ignored -> new Object());
        long now = System.currentTimeMillis() / 1000;

        synchronized (lock) {
            AlgorithmEvaluator burstableEval = evaluatorFactory.burstable(burstableConfig.getRateLimitAlgorithm());
            AlgorithmEvaluator sustainedEval = evaluatorFactory.sustained(sustainedConfig.getRateLimitAlgorithm());

            BucketSnapshot burstable = burstableState.getOrDefault(id,
                    BucketSnapshot.initial(burstableConfig.getAvailableToken(), burstableConfig.getTimeWindowInSeconds(), now));
            BucketSnapshot sustained = sustainedState.getOrDefault(id,
                    BucketSnapshot.initial(sustainedConfig.getAvailableToken(), sustainedConfig.getTimeWindowInSeconds(), now));

            // Re-bind capacity/window from latest config (hot reload).
            burstable = new BucketSnapshot(
                    Math.min(burstable.tokens(), burstableConfig.getAvailableToken()),
                    burstableConfig.getAvailableToken(),
                    burstableConfig.getTimeWindowInSeconds(),
                    burstable.lastRefill(),
                    burstable.resetAt(),
                    burstable.eventTimestamps()
            );
            sustained = new BucketSnapshot(
                    Math.min(sustained.tokens(), sustainedConfig.getAvailableToken()),
                    sustainedConfig.getAvailableToken(),
                    sustainedConfig.getTimeWindowInSeconds(),
                    sustained.lastRefill(),
                    sustained.resetAt(),
                    sustained.eventTimestamps()
            );

            burstable = burstableEval.refresh(burstable, burstableConfig, now);
            sustained = sustainedEval.refresh(sustained, sustainedConfig, now);

            boolean allowed = burstable.tokens() >= cost && sustained.tokens() >= cost;
            if (allowed) {
                burstable = burstableEval.consume(burstable, burstableConfig, now, cost);
                sustained = sustainedEval.consume(sustained, sustainedConfig, now, cost);
            }

            burstableState.put(id, burstable);
            sustainedState.put(id, sustained);

            return new DualBucketResult(
                    allowed,
                    allowed ? "OK" : "RATE_LIMITED",
                    toAvailability(burstable, now),
                    toAvailability(sustained, now)
            );
        }
    }

    public void clear() {
        burstableState.clear();
        sustainedState.clear();
        locks.clear();
    }

    public void resetTenant(String namespace, UUID tenantId) {
        String id = namespace + ":" + tenantId;
        burstableState.remove(id);
        sustainedState.remove(id);
    }

    private TokenAvailability toAvailability(BucketSnapshot snapshot, long nowSeconds) {
        return new TokenAvailability(
                snapshot.capacity(),
                snapshot.tokens(),
                snapshot.currentConsumedToken(),
                new Date(snapshot.resetAt() * 1000)
        );
    }
}
