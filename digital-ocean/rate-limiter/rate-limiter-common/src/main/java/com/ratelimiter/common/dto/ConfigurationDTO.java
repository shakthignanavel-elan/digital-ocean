package com.ratelimiter.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ratelimiter.common.model.RateLimitConfig;
import com.ratelimiter.common.model.TokenConfiguration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public class ConfigurationDTO {

    @Valid
    @NotNull
    private RateLimitConfig burstableRateLimitConfig;

    @Valid
    @NotNull
    private RateLimitConfig fixedRateLimitConfig;

    @Valid
    @NotNull
    private TokenConfiguration tokenConfiguration;

    @NotNull
    private UUID tenantId;

    @NotBlank
    private String namespace;

    public ConfigurationDTO() {
    }

    public RateLimitConfig getBurstableRateLimitConfig() {
        return burstableRateLimitConfig;
    }

    public void setBurstableRateLimitConfig(RateLimitConfig burstableRateLimitConfig) {
        this.burstableRateLimitConfig = burstableRateLimitConfig;
    }

    public RateLimitConfig getFixedRateLimitConfig() {
        return fixedRateLimitConfig;
    }

    public void setFixedRateLimitConfig(RateLimitConfig fixedRateLimitConfig) {
        this.fixedRateLimitConfig = fixedRateLimitConfig;
    }

    /** Alias used by API contract: rateLimitConfig maps to burstable config. */
    @JsonIgnore
    public RateLimitConfig getRateLimitConfig() {
        return burstableRateLimitConfig;
    }

    @JsonIgnore
    public void setRateLimitConfig(RateLimitConfig rateLimitConfig) {
        this.burstableRateLimitConfig = rateLimitConfig;
    }

    public TokenConfiguration getTokenConfiguration() {
        return tokenConfiguration;
    }

    public void setTokenConfiguration(TokenConfiguration tokenConfiguration) {
        this.tokenConfiguration = tokenConfiguration;
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
}
