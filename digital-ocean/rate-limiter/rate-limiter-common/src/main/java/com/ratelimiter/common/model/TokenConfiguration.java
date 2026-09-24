package com.ratelimiter.common.model;

public class TokenConfiguration {

    private long tokenCount;

    public TokenConfiguration() {
    }

    public TokenConfiguration(long tokenCount) {
        this.tokenCount = tokenCount;
    }

    public long getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(long tokenCount) {
        this.tokenCount = tokenCount;
    }
}
