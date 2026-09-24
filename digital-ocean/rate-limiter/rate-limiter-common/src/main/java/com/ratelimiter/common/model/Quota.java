package com.ratelimiter.common.model;

import java.util.Date;
import java.util.UUID;

public class Quota {

    private UUID tenantId;
    private String namespace;
    private long availableToken;
    private long currentConsumedToken;
    private Date resetTimestamp;

    public Quota() {
    }

    public Quota(UUID tenantId, String namespace, long availableToken, long currentConsumedToken, Date resetTimestamp) {
        this.tenantId = tenantId;
        this.namespace = namespace;
        this.availableToken = availableToken;
        this.currentConsumedToken = currentConsumedToken;
        this.resetTimestamp = resetTimestamp;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public long getAvailableToken() {
        return availableToken;
    }

    public void setAvailableToken(long availableToken) {
        this.availableToken = availableToken;
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
