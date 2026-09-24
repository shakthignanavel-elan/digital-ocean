package com.ratelimiter.common.model;

import com.ratelimiter.common.enums.RateLimitAlgorithm;

public class RateLimitConfig {

    private RateLimitAlgorithm rateLimitAlgorithm;
    private long availableToken;
    private long timeWindowInSeconds;

    public RateLimitConfig() {
    }

    public RateLimitConfig(RateLimitAlgorithm rateLimitAlgorithm, long availableToken, long timeWindowInSeconds) {
        this.rateLimitAlgorithm = rateLimitAlgorithm;
        this.availableToken = availableToken;
        this.timeWindowInSeconds = timeWindowInSeconds;
    }

    public RateLimitAlgorithm getRateLimitAlgorithm() {
        return rateLimitAlgorithm;
    }

    public void setRateLimitAlgorithm(RateLimitAlgorithm rateLimitAlgorithm) {
        this.rateLimitAlgorithm = rateLimitAlgorithm;
    }

    public long getAvailableToken() {
        return availableToken;
    }

    public void setAvailableToken(long availableToken) {
        this.availableToken = availableToken;
    }

    public long getTimeWindowInSeconds() {
        return timeWindowInSeconds;
    }

    public void setTimeWindowInSeconds(long timeWindowInSeconds) {
        this.timeWindowInSeconds = timeWindowInSeconds;
    }
}
