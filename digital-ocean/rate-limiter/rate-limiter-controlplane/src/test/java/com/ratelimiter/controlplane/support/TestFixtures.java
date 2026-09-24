package com.ratelimiter.controlplane.support;

import com.ratelimiter.common.dto.ConfigurationDTO;
import com.ratelimiter.common.enums.RateLimitAlgorithm;
import com.ratelimiter.common.model.RateLimitConfig;
import com.ratelimiter.common.model.TokenConfiguration;

import java.util.UUID;

public final class TestFixtures {

    public static final String NAMESPACE = "payments";
    public static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private TestFixtures() {
    }

    public static ConfigurationDTO sampleConfiguration() {
        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setNamespace(NAMESPACE);
        dto.setTenantId(TENANT_ID);
        dto.setBurstableRateLimitConfig(new RateLimitConfig(RateLimitAlgorithm.TOKEN_BUCKET, 100, 60));
        dto.setFixedRateLimitConfig(new RateLimitConfig(RateLimitAlgorithm.SLIDING_WINDOW, 1000, 3600));
        dto.setTokenConfiguration(new TokenConfiguration(100));
        return dto;
    }
}
