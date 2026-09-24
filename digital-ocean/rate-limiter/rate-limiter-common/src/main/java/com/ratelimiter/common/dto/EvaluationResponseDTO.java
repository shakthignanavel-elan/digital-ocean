package com.ratelimiter.common.dto;

import com.ratelimiter.common.model.TokenAvailability;

public class EvaluationResponseDTO {

    private boolean allowed;
    private String namespace;
    private String tenantId;
    private TokenAvailability burstableTokenAvailability;
    private TokenAvailability sustainedTokenAvailability;

    public EvaluationResponseDTO() {
    }

    public boolean isAllowed() {
        return allowed;
    }

    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public TokenAvailability getBurstableTokenAvailability() {
        return burstableTokenAvailability;
    }

    public void setBurstableTokenAvailability(TokenAvailability burstableTokenAvailability) {
        this.burstableTokenAvailability = burstableTokenAvailability;
    }

    public TokenAvailability getSustainedTokenAvailability() {
        return sustainedTokenAvailability;
    }

    public void setSustainedTokenAvailability(TokenAvailability sustainedTokenAvailability) {
        this.sustainedTokenAvailability = sustainedTokenAvailability;
    }
}
