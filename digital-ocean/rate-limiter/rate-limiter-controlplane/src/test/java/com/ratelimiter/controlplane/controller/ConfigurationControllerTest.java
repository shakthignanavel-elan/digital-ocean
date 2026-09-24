package com.ratelimiter.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.common.dto.ConfigurationDTO;
import com.ratelimiter.common.dto.QuotaDTO;
import com.ratelimiter.common.model.TokenAvailability;
import com.ratelimiter.controlplane.service.ConfigurationService;
import com.ratelimiter.controlplane.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.Date;

import static com.ratelimiter.controlplane.support.TestFixtures.NAMESPACE;
import static com.ratelimiter.controlplane.support.TestFixtures.TENANT_ID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ConfigurationController.class)
class ConfigurationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ConfigurationService configurationService;

    @Test
    void postConfigurationCreatesRateLimit() throws Exception {
        ConfigurationDTO dto = TestFixtures.sampleConfiguration();
        when(configurationService.create(eq(NAMESPACE), eq(TENANT_ID), any())).thenReturn(dto);

        mockMvc.perform(post("/configuration/{namespace}/{tenantId}", NAMESPACE, TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.namespace").value(NAMESPACE))
                .andExpect(jsonPath("$.tenantId").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$.burstableRateLimitConfig.rateLimitAlgorithm").value("TOKEN_BUCKET"))
                .andExpect(jsonPath("$.fixedRateLimitConfig.rateLimitAlgorithm").value("SLIDING_WINDOW"))
                .andExpect(jsonPath("$.tokenConfiguration.tokenCount").value(100));
    }

    @Test
    void getConfigurationReturnsDto() throws Exception {
        when(configurationService.get(NAMESPACE, TENANT_ID)).thenReturn(TestFixtures.sampleConfiguration());

        mockMvc.perform(get("/configuration/{namespace}/{tenantId}", NAMESPACE, TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.burstableRateLimitConfig.availableToken").value(100));
    }

    @Test
    void putConfigurationUpdatesRules() throws Exception {
        ConfigurationDTO dto = TestFixtures.sampleConfiguration();
        when(configurationService.update(eq(NAMESPACE), eq(TENANT_ID), any())).thenReturn(dto);

        mockMvc.perform(put("/configuration/{namespace}/{tenantId}", NAMESPACE, TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.namespace").value(NAMESPACE));
    }

    @Test
    void deleteConfigurationReturnsNoContent() throws Exception {
        doNothing().when(configurationService).delete(NAMESPACE, TENANT_ID);

        mockMvc.perform(delete("/configuration/{namespace}/{tenantId}", NAMESPACE, TENANT_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    void getQuotaReturnsConsumptionRemainingAndReset() throws Exception {
        Date reset = new Date(1_700_000_000_000L);
        QuotaDTO quota = new QuotaDTO(
                new TokenAvailability(100, 40, 60, reset),
                new TokenAvailability(1000, 800, 200, reset),
                NAMESPACE,
                TENANT_ID
        );
        when(configurationService.getQuota(NAMESPACE, TENANT_ID)).thenReturn(quota);

        mockMvc.perform(get("/quota/{namespace}/{tenantId}", NAMESPACE, TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.namespace").value(NAMESPACE))
                .andExpect(jsonPath("$.tenantId").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$.burstableTokenAvailability.availableToken").value(100))
                .andExpect(jsonPath("$.burstableTokenAvailability.remainingToken").value(40))
                .andExpect(jsonPath("$.burstableTokenAvailability.currentConsumedToken").value(60))
                .andExpect(jsonPath("$.sustainedTokenAvailability.availableToken").value(1000))
                .andExpect(jsonPath("$.sustainedTokenAvailability.remainingToken").value(800))
                .andExpect(jsonPath("$.sustainedTokenAvailability.currentConsumedToken").value(200));
    }

    @Test
    void getConfigurationMapsNotFound() throws Exception {
        when(configurationService.get(NAMESPACE, TENANT_ID))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "missing"));

        mockMvc.perform(get("/configuration/{namespace}/{tenantId}", NAMESPACE, TENANT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("missing"));
    }

    @Test
    void deleteMapsNotFound() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "missing"))
                .when(configurationService).delete(NAMESPACE, TENANT_ID);

        mockMvc.perform(delete("/configuration/{namespace}/{tenantId}", NAMESPACE, TENANT_ID))
                .andExpect(status().isNotFound());
    }
}
