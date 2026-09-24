package com.ratelimiter.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.common.dto.ConfigurationDTO;
import com.ratelimiter.common.model.TokenAvailability;
import com.ratelimiter.controlplane.redis.RedisQuotaStore;
import com.ratelimiter.controlplane.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Date;

import static com.ratelimiter.controlplane.support.TestFixtures.NAMESPACE;
import static com.ratelimiter.controlplane.support.TestFixtures.TENANT_ID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end controlplane API against H2. Redis is mocked so rule updates still
 * exercise live sync hooks without requiring Docker.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(TestRedisBeans.class)
class ConfigurationApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RedisQuotaStore redisQuotaStore;

    @Test
    void configurationLifecycleAndQuotaMatchSpec() throws Exception {
        doNothing().when(redisQuotaStore).syncConfiguration(any());
        doNothing().when(redisQuotaStore).deleteConfiguration(anyString(), any());
        Date reset = new Date();
        when(redisQuotaStore.readBurstableAvailability(NAMESPACE, TENANT_ID))
                .thenReturn(new TokenAvailability(100, 70, 30, reset));
        when(redisQuotaStore.readSustainedAvailability(NAMESPACE, TENANT_ID))
                .thenReturn(new TokenAvailability(1000, 900, 100, reset));

        ConfigurationDTO body = TestFixtures.sampleConfiguration();

        mockMvc.perform(post("/configuration/{namespace}/{tenantId}", NAMESPACE, TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.burstableRateLimitConfig.availableToken").value(100))
                .andExpect(jsonPath("$.fixedRateLimitConfig.availableToken").value(1000));

        mockMvc.perform(get("/configuration/{namespace}/{tenantId}", NAMESPACE, TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenConfiguration.tokenCount").value(100));

        body.getBurstableRateLimitConfig().setAvailableToken(80);
        mockMvc.perform(put("/configuration/{namespace}/{tenantId}", NAMESPACE, TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.burstableRateLimitConfig.availableToken").value(80));

        mockMvc.perform(get("/quota/{namespace}/{tenantId}", NAMESPACE, TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.burstableTokenAvailability.remainingToken").value(70))
                .andExpect(jsonPath("$.burstableTokenAvailability.currentConsumedToken").value(30))
                .andExpect(jsonPath("$.sustainedTokenAvailability.remainingToken").value(900))
                .andExpect(jsonPath("$.sustainedTokenAvailability.currentConsumedToken").value(100));

        mockMvc.perform(delete("/configuration/{namespace}/{tenantId}", NAMESPACE, TENANT_ID))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/configuration/{namespace}/{tenantId}", NAMESPACE, TENANT_ID))
                .andExpect(status().isNotFound());
    }
}
