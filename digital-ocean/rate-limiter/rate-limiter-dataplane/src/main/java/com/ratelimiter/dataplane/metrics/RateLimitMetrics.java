package com.ratelimiter.dataplane.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Dataplane metrics for evaluate throughput, latency, and allow/deny outcomes.
 * Tags stay low-cardinality (namespace + outcome); tenant id is never a tag.
 */
@Component
public class RateLimitMetrics {

    private final MeterRegistry registry;

    public RateLimitMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordEvaluate(String namespace, boolean allowed, String reason, long durationNanos) {
        String outcome = allowed ? "allowed" : "denied";
        String safeReason = reason == null || reason.isBlank() ? "unknown" : reason;
        String safeNamespace = namespace == null || namespace.isBlank() ? "unknown" : namespace;

        Timer.builder("ratelimiter.evaluate")
                .description("Dataplane evaluate latency")
                .tag("namespace", safeNamespace)
                .tag("outcome", outcome)
                .tag("reason", safeReason)
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);

        registry.counter(
                "ratelimiter.evaluate.requests",
                "namespace", safeNamespace,
                "outcome", outcome,
                "reason", safeReason
        ).increment();
    }
}
