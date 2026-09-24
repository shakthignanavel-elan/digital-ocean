package com.ratelimiter.dataplane.algorithm;

import com.ratelimiter.common.enums.RateLimitAlgorithm;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves burstable / sustained algorithm evaluators from configuration.
 */
@Component
public class AlgorithmEvaluatorFactory {

    private final Map<RateLimitAlgorithm, AlgorithmEvaluator> evaluators;

    public AlgorithmEvaluatorFactory(List<AlgorithmEvaluator> evaluatorList) {
        Map<RateLimitAlgorithm, AlgorithmEvaluator> map = new EnumMap<>(RateLimitAlgorithm.class);
        for (AlgorithmEvaluator evaluator : evaluatorList) {
            map.put(evaluator.algorithm(), evaluator);
        }
        this.evaluators = Map.copyOf(map);
    }

    public AlgorithmEvaluator get(RateLimitAlgorithm algorithm) {
        AlgorithmEvaluator evaluator = evaluators.get(algorithm);
        if (evaluator == null) {
            throw new IllegalArgumentException("No evaluator registered for algorithm=" + algorithm);
        }
        return evaluator;
    }

    public AlgorithmEvaluator burstable(RateLimitAlgorithm algorithm) {
        return get(algorithm);
    }

    public AlgorithmEvaluator sustained(RateLimitAlgorithm algorithm) {
        return get(algorithm);
    }
}
