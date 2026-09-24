package com.ratelimiter.dataplane.degraded;

import com.ratelimiter.common.model.RateLimitConfig;
import com.ratelimiter.dataplane.metrics.RateLimitMetrics;
import com.ratelimiter.dataplane.spi.DualBucketQuotaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Prefers Redis atomic evaluate; on Redis failure falls back to local in-memory
 * limiting with capacities partitioned by {@link DataplaneClusterView#activeNodeCount()}.
 */
public class FailoverDualBucketQuotaStore implements DualBucketQuotaStore {

    private static final Logger log = LoggerFactory.getLogger(FailoverDualBucketQuotaStore.class);

    private final DualBucketQuotaStore primary;
    private final DualBucketQuotaStore localFallback;
    private final DataplaneClusterView clusterView;
    private final RateLimitMetrics metrics;
    private final boolean degradedEnabled;

    public FailoverDualBucketQuotaStore(DualBucketQuotaStore primary,
                                        DualBucketQuotaStore localFallback,
                                        DataplaneClusterView clusterView,
                                        RateLimitMetrics metrics,
                                        boolean degradedEnabled) {
        this.primary = primary;
        this.localFallback = localFallback;
        this.clusterView = clusterView;
        this.metrics = metrics;
        this.degradedEnabled = degradedEnabled;
    }

    @Override
    public DualBucketResult tryConsume(String namespace,
                                       UUID tenantId,
                                       RateLimitConfig burstableConfig,
                                       RateLimitConfig sustainedConfig,
                                       long cost) {
        try {
            DualBucketResult primaryResult = primary.tryConsume(
                    namespace, tenantId, burstableConfig, sustainedConfig, cost);
            if (degradedEnabled && "STORE_ERROR".equals(primaryResult.reason())) {
                return degradedConsume(
                        namespace, tenantId, burstableConfig, sustainedConfig, cost,
                        new IllegalStateException("primary store returned STORE_ERROR"));
            }
            return primaryResult;
        } catch (RuntimeException ex) {
            if (!degradedEnabled) {
                throw ex;
            }
            return degradedConsume(namespace, tenantId, burstableConfig, sustainedConfig, cost, ex);
        }
    }

    private DualBucketResult degradedConsume(String namespace,
                                             UUID tenantId,
                                             RateLimitConfig burstableConfig,
                                             RateLimitConfig sustainedConfig,
                                             long cost,
                                             RuntimeException cause) {
        int nodes = clusterView.activeNodeCount();
        RateLimitConfig localBurst = CapacityPartitioner.partition(burstableConfig, nodes);
        RateLimitConfig localSustained = CapacityPartitioner.partition(sustainedConfig, nodes);

        log.warn(
                "Redis unavailable — degraded local evaluate namespace={} tenantId={} nodes={} "
                        + "burstCapacity={}/{} sustainedCapacity={}/{} cause={}",
                namespace, tenantId, nodes,
                localBurst.getAvailableToken(), burstableConfig.getAvailableToken(),
                localSustained.getAvailableToken(), sustainedConfig.getAvailableToken(),
                cause.toString()
        );

        DualBucketResult local = localFallback.tryConsume(
                namespace, tenantId, localBurst, localSustained, cost);

        String reason = local.allowed() ? "DEGRADED_OK" : "DEGRADED_RATE_LIMITED";
        metrics.recordDegraded(namespace, local.allowed(), nodes);

        return new DualBucketResult(local.allowed(), reason, local.burstable(), local.sustained());
    }
}
