package com.ratelimiter.common.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RedisQuotaKeysTest {

    @Test
    void buildsStableKeysForNamespaceAndTenant() {
        UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        assertThat(RedisQuotaKeys.configKey("payments", tenantId))
                .isEqualTo("rl:config:payments:11111111-1111-1111-1111-111111111111");
        assertThat(RedisQuotaKeys.burstableKey("payments", tenantId))
                .isEqualTo("rl:quota:burstable:payments:11111111-1111-1111-1111-111111111111");
        assertThat(RedisQuotaKeys.sustainedKey("payments", tenantId))
                .isEqualTo("rl:quota:sustained:payments:11111111-1111-1111-1111-111111111111");
    }
}
