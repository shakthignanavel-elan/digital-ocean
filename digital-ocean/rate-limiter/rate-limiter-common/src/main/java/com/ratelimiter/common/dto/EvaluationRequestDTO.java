package com.ratelimiter.common.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public class EvaluationRequestDTO {

    @NotBlank
    private String namespace;

    /** Tenant identity; either tenantId or apiKey must be provided. */
    private UUID tenantId;

    /** Optional API key alternative identity for quota enforcement. */
    private String apiKey;

    public EvaluationRequestDTO() {
    }

    public EvaluationRequestDTO(String namespace, UUID tenantId) {
        this.namespace = namespace;
        this.tenantId = tenantId;
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

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
