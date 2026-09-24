package com.ratelimiter.dataplane.degraded;

import com.ratelimiter.common.enums.RateLimitAlgorithm;
import com.ratelimiter.common.model.RateLimitConfig;
import com.ratelimiter.common.model.TokenAvailability;
import com.ratelimiter.dataplane.algorithm.AlgorithmEvaluatorFactory;
import com.ratelimiter.dataplane.algorithm.FixedWindowEvaluator;
import com.ratelimiter.dataplane.algorithm.SlidingWindowEvaluator;
import com.ratelimiter.dataplane.algorithm.TokenBucketEvaluator;
import com.ratelimiter.dataplane.metrics.RateLimitMetrics;
import com.ratelimiter.dataplane.spi.DualBucketQuotaStore;
import com.ratelimiter.dataplane.store.memory.InMemoryDualBucketQuotaStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailoverDualBucketQuotaStoreTest {

    private static final String NAMESPACE = "payments";
    private static final UUID TENANT = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final AtomicInteger nodes = new AtomicInteger(4);
    private InMemoryDualBucketQuotaStore local;
    private RateLimitMetrics metrics;
    private DualBucketQuotaStore failingPrimary;

    @BeforeEach
    void setUp() {
        AlgorithmEvaluatorFactory factory = new AlgorithmEvaluatorFactory(List.of(
                new TokenBucketEvaluator(),
                new FixedWindowEvaluator(),
                new SlidingWindowEvaluator()
        ));
        local = new InMemoryDualBucketQuotaStore(factory);
        metrics = new RateLimitMetrics(new SimpleMeterRegistry());
        failingPrimary = (ns, tenantId, burst, sustained, cost) -> {
            throw new IllegalStateException("Redis down");
        };
    }

    @Test
    void fallsBackToLocalPartitionedCapacityWhenRedisFails() {
        FailoverDualBucketQuotaStore store = new FailoverDualBucketQuotaStore(
                failingPrimary,
                local,
                clusterView(),
                metrics,
                true
        );

        // Global capacity 40 / 4 nodes => 10 local
        RateLimitConfig burst = new RateLimitConfig(RateLimitAlgorithm.TOKEN_BUCKET, 40, 3600);
        RateLimitConfig sustained = new RateLimitConfig(RateLimitAlgorithm.FIXED_WINDOW, 40, 3600);

        int allowed = 0;
        for (int i = 0; i < 20; i++) {
            var result = store.tryConsume(NAMESPACE, TENANT, burst, sustained, 1);
            if (result.allowed()) {
                allowed++;
                assertThat(result.reason()).isEqualTo("DEGRADED_OK");
            } else {
                assertThat(result.reason()).isEqualTo("DEGRADED_RATE_LIMITED");
            }
        }
        assertThat(allowed).isEqualTo(10);
    }

    @Test
    void usesPrimaryWhenHealthy() {
        DualBucketQuotaStore primary = (ns, tenantId, burst, sustained, cost) ->
                new DualBucketQuotaStore.DualBucketResult(
                        true, "OK",
                        new TokenAvailability(100, 99, 1, new Date()),
                        new TokenAvailability(100, 99, 1, new Date())
                );

        FailoverDualBucketQuotaStore store = new FailoverDualBucketQuotaStore(
                primary, local, clusterView(), metrics, true);

        var result = store.tryConsume(
                NAMESPACE, TENANT,
                new RateLimitConfig(RateLimitAlgorithm.TOKEN_BUCKET, 100, 60),
                new RateLimitConfig(RateLimitAlgorithm.TOKEN_BUCKET, 100, 60),
                1
        );
        assertThat(result.allowed()).isTrue();
        assertThat(result.reason()).isEqualTo("OK");
    }

    @Test
    void rethrowsWhenDegradedDisabled() {
        FailoverDualBucketQuotaStore store = new FailoverDualBucketQuotaStore(
                failingPrimary, local, clusterView(), metrics, false);

        assertThatThrownBy(() -> store.tryConsume(
                NAMESPACE, TENANT,
                new RateLimitConfig(RateLimitAlgorithm.TOKEN_BUCKET, 10, 60),
                new RateLimitConfig(RateLimitAlgorithm.TOKEN_BUCKET, 10, 60),
                1
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis down");
    }

    private DataplaneClusterView clusterView() {
        return new DataplaneClusterView() {
            @Override
            public int activeNodeCount() {
                return nodes.get();
            }

            @Override
            public String nodeId() {
                return "test-node";
            }
        };
    }
}
