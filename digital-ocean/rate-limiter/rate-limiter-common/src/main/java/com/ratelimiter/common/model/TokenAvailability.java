package com.ratelimiter.common.model;

import java.util.Date;

public class TokenAvailability {

    private long availableToken;
    private long remainingToken;
    /** Tokens already consumed in the current window/bucket state. */
    private long currentConsumedToken;
    private Date resetTimestamp;

    public TokenAvailability() {
    }

    public TokenAvailability(long availableToken, long remainingToken, Date resetTimestamp) {
        this(availableToken, remainingToken, Math.max(0, availableToken - remainingToken), resetTimestamp);
    }

    public TokenAvailability(long availableToken, long remainingToken, long currentConsumedToken, Date resetTimestamp) {
        this.availableToken = availableToken;
        this.remainingToken = remainingToken;
        this.currentConsumedToken = currentConsumedToken;
        this.resetTimestamp = resetTimestamp;
    }

    public long getAvailableToken() {
        return availableToken;
    }

    public void setAvailableToken(long availableToken) {
        this.availableToken = availableToken;
    }

    public long getRemainingToken() {
        return remainingToken;
    }

    public void setRemainingToken(long remainingToken) {
        this.remainingToken = remainingToken;
    }

    public long getCurrentConsumedToken() {
        return currentConsumedToken;
    }

    public void setCurrentConsumedToken(long currentConsumedToken) {
        this.currentConsumedToken = currentConsumedToken;
    }

    public Date getResetTimestamp() {
        return resetTimestamp;
    }

    public void setResetTimestamp(Date resetTimestamp) {
        this.resetTimestamp = resetTimestamp;
    }
}
