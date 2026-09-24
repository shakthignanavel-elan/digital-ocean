package com.ratelimiter.dataplane.algorithm;

import com.ratelimiter.common.enums.RateLimitAlgorithm;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitAlgorithmSimulatorTest {

    @Test
    void tokenBucketAllowsUntilCapacityExhausted() {
        var burstable = RateLimitAlgorithmSimulator.BucketState.of(
                RateLimitAlgorithm.TOKEN_BUCKET, 2, 2, 60, 0, 60);
        var sustained = RateLimitAlgorithmSimulator.BucketState.of(
                RateLimitAlgorithm.FIXED_WINDOW, 10, 10, 3600, 0, 3600);

        var first = RateLimitAlgorithmSimulator.evaluate(burstable, sustained, 10, 1);
        assertThat(first.allowed()).isTrue();
        assertThat(first.burstable().tokens()).isEqualTo(1);

        var second = RateLimitAlgorithmSimulator.evaluate(first.burstable(), first.sustained(), 10, 1);
        assertThat(second.allowed()).isTrue();
        assertThat(second.burstable().tokens()).isEqualTo(0);

        var third = RateLimitAlgorithmSimulator.evaluate(second.burstable(), second.sustained(), 10, 1);
        assertThat(third.allowed()).isFalse();
        assertThat(third.burstable().tokens()).isEqualTo(0);
    }

    @Test
    void tokenBucketRefillsOverTimeWithoutRestart() {
        var state = RateLimitAlgorithmSimulator.BucketState.of(
                RateLimitAlgorithm.TOKEN_BUCKET, 0, 10, 10, 0, 10);

        var refreshed = RateLimitAlgorithmSimulator.refresh(state, 5);
        assertThat(refreshed.tokens()).isEqualTo(5);

        var afterConsume = RateLimitAlgorithmSimulator.evaluate(refreshed, sustainedFull(), 5, 1);
        assertThat(afterConsume.allowed()).isTrue();
        assertThat(afterConsume.burstable().tokens()).isEqualTo(4);
    }

    @Test
    void fixedWindowResetsWhenWindowElapses() {
        var state = RateLimitAlgorithmSimulator.BucketState.of(
                RateLimitAlgorithm.FIXED_WINDOW, 0, 5, 60, 0, 60);

        var beforeReset = RateLimitAlgorithmSimulator.refresh(state, 59);
        assertThat(beforeReset.tokens()).isEqualTo(0);

        var afterReset = RateLimitAlgorithmSimulator.refresh(state, 60);
        assertThat(afterReset.tokens()).isEqualTo(5);
        assertThat(afterReset.resetAt()).isEqualTo(120);
    }

    @Test
    void slidingWindowCountsOnlyEventsInsideRollingWindow() {
        var state = RateLimitAlgorithmSimulator.BucketState.of(
                RateLimitAlgorithm.SLIDING_WINDOW, 3, 3, 10, 0, 10);

        var t1 = RateLimitAlgorithmSimulator.evaluate(state, sustainedFull(), 100, 1);
        assertThat(t1.allowed()).isTrue();
        assertThat(t1.burstable().tokens()).isEqualTo(2);
        assertThat(t1.burstable().currentConsumedToken()).isEqualTo(1);

        var t2 = RateLimitAlgorithmSimulator.evaluate(t1.burstable(), sustainedFull(), 101, 1);
        var t3 = RateLimitAlgorithmSimulator.evaluate(t2.burstable(), sustainedFull(), 102, 1);
        assertThat(t3.burstable().tokens()).isEqualTo(0);

        var denied = RateLimitAlgorithmSimulator.evaluate(t3.burstable(), sustainedFull(), 103, 1);
        assertThat(denied.allowed()).isFalse();

        // Oldest event at t=100 rolls out at t=110 (window=10)
        var afterSlide = RateLimitAlgorithmSimulator.evaluate(denied.burstable(), sustainedFull(), 110, 1);
        assertThat(afterSlide.allowed()).isTrue();
        assertThat(afterSlide.burstable().tokens()).isEqualTo(0);
    }

    @Test
    void dualBucketRequiresBothBurstableAndSustainedCapacity() {
        var burstable = RateLimitAlgorithmSimulator.BucketState.of(
                RateLimitAlgorithm.TOKEN_BUCKET, 5, 5, 60, 0, 60);
        var sustainedEmpty = RateLimitAlgorithmSimulator.BucketState.of(
                RateLimitAlgorithm.SLIDING_WINDOW, 0, 5, 3600, 0, 3600);

        // Pre-fill sliding window to capacity
        List<Long> full = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            full.add(1L);
        }
        sustainedEmpty = sustainedEmpty.withMeta(0, 1, 3601, full);

        var result = RateLimitAlgorithmSimulator.evaluate(burstable, sustainedEmpty, 1, 1);
        assertThat(result.allowed()).isFalse();
        assertThat(result.burstable().tokens()).isEqualTo(5);
        assertThat(result.sustained().tokens()).isEqualTo(0);
    }

    @Test
    void concurrentSharedStateNeverOversellsCapacity() throws Exception {
        int capacity = 50;
        int workers = 200;
        AtomicReference<RateLimitAlgorithmSimulator.BucketState> burstable =
                new AtomicReference<>(RateLimitAlgorithmSimulator.BucketState.of(
                        RateLimitAlgorithm.TOKEN_BUCKET, capacity, capacity, 3600, 0, 3600));
        AtomicReference<RateLimitAlgorithmSimulator.BucketState> sustained =
                new AtomicReference<>(RateLimitAlgorithmSimulator.BucketState.of(
                        RateLimitAlgorithm.SLIDING_WINDOW, capacity, capacity, 3600, 0, 3600));

        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    synchronized (RateLimitAlgorithmSimulatorTest.class) {
                        var result = RateLimitAlgorithmSimulator.evaluate(
                                burstable.get(), sustained.get(), 1_000, 1);
                        burstable.set(result.burstable());
                        sustained.set(result.sustained());
                        if (result.allowed()) {
                            allowed.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowed.get()).isEqualTo(capacity);
        assertThat(burstable.get().tokens()).isZero();
        assertThat(sustained.get().currentConsumedToken()).isEqualTo(capacity);
    }

    private RateLimitAlgorithmSimulator.BucketState sustainedFull() {
        return RateLimitAlgorithmSimulator.BucketState.of(
                RateLimitAlgorithm.FIXED_WINDOW, 100, 100, 3600, 0, 3600);
    }
}
