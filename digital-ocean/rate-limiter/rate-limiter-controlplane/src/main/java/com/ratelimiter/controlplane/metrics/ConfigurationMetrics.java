package com.ratelimiter.controlplane.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Controlplane metrics for configuration CRUD and quota reads.
 * Tags stay low-cardinality (operation + namespace + status).
 */
@Component
public class ConfigurationMetrics {

    private final MeterRegistry registry;

    public ConfigurationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String operation, String namespace, String status, long durationNanos) {
        String safeOp = operation == null ? "unknown" : operation;
        String safeNamespace = namespace == null || namespace.isBlank() ? "unknown" : namespace;
        String safeStatus = status == null ? "unknown" : status;

        Timer.builder("ratelimiter.configuration")
                .description("Controlplane configuration/quota operation latency")
                .tag("operation", safeOp)
                .tag("namespace", safeNamespace)
                .tag("status", safeStatus)
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);

        registry.counter(
                "ratelimiter.configuration.requests",
                "operation", safeOp,
                "namespace", safeNamespace,
                "status", safeStatus
        ).increment();
    }
}
