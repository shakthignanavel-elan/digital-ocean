package com.ratelimiter.common.dto;

import com.ratelimiter.common.model.TokenAvailability;

import java.util.UUID;

public class QuotaDTO {

    private TokenAvailability burstableTokenAvailability;
    private TokenAvailability sustainedTokenAvailability;
    private String namespace;
    private UUID tenantId;

    public QuotaDTO() {
    }

    public QuotaDTO(TokenAvailability burstableTokenAvailability,
                    TokenAvailability sustainedTokenAvailability,
                    String namespace,
                    UUID tenantId) {
        this.burstableTokenAvailability = burstableTokenAvailability;
        this.sustainedTokenAvailability = sustainedTokenAvailability;
        this.namespace = namespace;
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

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }
}
