package com.ratelimiter.dataplane.algorithm;

import com.ratelimiter.common.enums.RateLimitAlgorithm;
import com.ratelimiter.common.model.RateLimitConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlgorithmEvaluatorFactoryTest {

    private final AlgorithmEvaluatorFactory factory = new AlgorithmEvaluatorFactory(List.of(
            new TokenBucketEvaluator(),
            new FixedWindowEvaluator(),
            new SlidingWindowEvaluator()
    ));

    @Test
    void resolvesBurstableAndSustainedEvaluatorsIndependently() {
        AlgorithmEvaluator burstable = factory.burstable(RateLimitAlgorithm.TOKEN_BUCKET);
        AlgorithmEvaluator sustained = factory.sustained(RateLimitAlgorithm.SLIDING_WINDOW);

        assertThat(burstable.algorithm()).isEqualTo(RateLimitAlgorithm.TOKEN_BUCKET);
        assertThat(sustained.algorithm()).isEqualTo(RateLimitAlgorithm.SLIDING_WINDOW);
        assertThat(factory.get(RateLimitAlgorithm.FIXED_WINDOW).algorithm())
                .isEqualTo(RateLimitAlgorithm.FIXED_WINDOW);
    }

    @Test
    void rejectsUnknownAlgorithm() {
        assertThatThrownBy(() -> factory.get(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void tokenBucketRefills() {
        var evaluator = factory.get(RateLimitAlgorithm.TOKEN_BUCKET);
        var config = new RateLimitConfig(RateLimitAlgorithm.TOKEN_BUCKET, 10, 10);
        var state = BucketSnapshot.initial(10, 10, 0).withTokens(0);
        var refreshed = evaluator.refresh(state, config, 5);
        assertThat(refreshed.tokens()).isEqualTo(5);
    }

    @Test
    void fixedWindowResets() {
        var evaluator = factory.get(RateLimitAlgorithm.FIXED_WINDOW);
        var config = new RateLimitConfig(RateLimitAlgorithm.FIXED_WINDOW, 5, 60);
        var state = new BucketSnapshot(0, 5, 60, 0, 60, List.of());
        assertThat(evaluator.refresh(state, config, 59).tokens()).isZero();
        assertThat(evaluator.refresh(state, config, 60).tokens()).isEqualTo(5);
    }

    @Test
    void slidingWindowRolls() {
        var evaluator = factory.get(RateLimitAlgorithm.SLIDING_WINDOW);
        var config = new RateLimitConfig(RateLimitAlgorithm.SLIDING_WINDOW, 2, 10);
        var afterFirst = evaluator.consume(
                evaluator.refresh(BucketSnapshot.initial(2, 10, 100), config, 100),
                config, 100, 1);
        var afterSecond = evaluator.consume(
                evaluator.refresh(afterFirst, config, 101),
                config, 101, 1);
        assertThat(afterSecond.tokens()).isZero();
        var afterSlide = evaluator.refresh(afterSecond, config, 110);
        assertThat(afterSlide.tokens()).isEqualTo(1);
    }
}
