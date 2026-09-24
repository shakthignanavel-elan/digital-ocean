package com.ratelimiter.dataplane.degraded;

import com.ratelimiter.common.enums.RateLimitAlgorithm;
import com.ratelimiter.common.model.RateLimitConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapacityPartitionerTest {

    @Test
    void partitionsEvenlyWithCeil() {
        assertThat(CapacityPartitioner.partition(100, 4)).isEqualTo(25);
        assertThat(CapacityPartitioner.partition(10, 3)).isEqualTo(4);
        assertThat(CapacityPartitioner.partition(5, 10)).isEqualTo(1);
    }

    @Test
    void treatsInvalidNodeCountAsOne() {
        assertThat(CapacityPartitioner.partition(40, 0)).isEqualTo(40);
        assertThat(CapacityPartitioner.partition(40, -2)).isEqualTo(40);
    }

    @Test
    void zeroCapacityStaysZero() {
        assertThat(CapacityPartitioner.partition(0, 3)).isZero();
    }

    @Test
    void partitionsRateLimitConfig() {
        RateLimitConfig config = new RateLimitConfig(RateLimitAlgorithm.TOKEN_BUCKET, 90, 60);
        RateLimitConfig local = CapacityPartitioner.partition(config, 3);
        assertThat(local.getAvailableToken()).isEqualTo(30);
        assertThat(local.getRateLimitAlgorithm()).isEqualTo(RateLimitAlgorithm.TOKEN_BUCKET);
        assertThat(local.getTimeWindowInSeconds()).isEqualTo(60);
    }
}
