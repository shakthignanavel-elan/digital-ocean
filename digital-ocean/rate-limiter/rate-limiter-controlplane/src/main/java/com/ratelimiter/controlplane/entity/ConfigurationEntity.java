package com.ratelimiter.controlplane.entity;

import com.ratelimiter.common.enums.RateLimitAlgorithm;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "rate_limit_configuration")
public class ConfigurationEntity {

    @EmbeddedId
    private ConfigurationId id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RateLimitAlgorithm burstableAlgorithm;

    @Column(nullable = false)
    private long burstableAvailableToken;

    @Column(nullable = false)
    private long burstableTimeWindowInSeconds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RateLimitAlgorithm fixedAlgorithm;

    @Column(nullable = false)
    private long fixedAvailableToken;

    @Column(nullable = false)
    private long fixedTimeWindowInSeconds;

    @Column(nullable = false)
    private long tokenCount;

    public ConfigurationId getId() {
        return id;
    }

    public void setId(ConfigurationId id) {
        this.id = id;
    }

    public RateLimitAlgorithm getBurstableAlgorithm() {
        return burstableAlgorithm;
    }

    public void setBurstableAlgorithm(RateLimitAlgorithm burstableAlgorithm) {
        this.burstableAlgorithm = burstableAlgorithm;
    }

    public long getBurstableAvailableToken() {
        return burstableAvailableToken;
    }

    public void setBurstableAvailableToken(long burstableAvailableToken) {
        this.burstableAvailableToken = burstableAvailableToken;
    }

    public long getBurstableTimeWindowInSeconds() {
        return burstableTimeWindowInSeconds;
    }

    public void setBurstableTimeWindowInSeconds(long burstableTimeWindowInSeconds) {
        this.burstableTimeWindowInSeconds = burstableTimeWindowInSeconds;
    }

    public RateLimitAlgorithm getFixedAlgorithm() {
        return fixedAlgorithm;
    }

    public void setFixedAlgorithm(RateLimitAlgorithm fixedAlgorithm) {
        this.fixedAlgorithm = fixedAlgorithm;
    }

    public long getFixedAvailableToken() {
        return fixedAvailableToken;
    }

    public void setFixedAvailableToken(long fixedAvailableToken) {
        this.fixedAvailableToken = fixedAvailableToken;
    }

    public long getFixedTimeWindowInSeconds() {
        return fixedTimeWindowInSeconds;
    }

    public void setFixedTimeWindowInSeconds(long fixedTimeWindowInSeconds) {
        this.fixedTimeWindowInSeconds = fixedTimeWindowInSeconds;
    }

    public long getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(long tokenCount) {
        this.tokenCount = tokenCount;
    }

    @Embeddable
    public static class ConfigurationId implements Serializable {

        @Column(nullable = false)
        private UUID tenantId;

        @Column(nullable = false)
        private String namespace;

        public ConfigurationId() {
        }

        public ConfigurationId(UUID tenantId, String namespace) {
            this.tenantId = tenantId;
            this.namespace = namespace;
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

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ConfigurationId that)) {
                return false;
            }
            return Objects.equals(tenantId, that.tenantId) && Objects.equals(namespace, that.namespace);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, namespace);
        }
    }
}
