package com.ratelimiter.dataplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.common.dto.EvaluationRequestDTO;
import com.ratelimiter.common.dto.EvaluationResponseDTO;
import com.ratelimiter.common.model.TokenAvailability;
import com.ratelimiter.dataplane.service.EvaluateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Date;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EvaluateController.class)
class EvaluateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EvaluateService evaluateService;

    @Test
    void postEvaluateReturnsDecisionAndQuotaSnapshot() throws Exception {
        UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Date reset = new Date();

        EvaluationResponseDTO response = new EvaluationResponseDTO();
        response.setAllowed(true);
        response.setNamespace("payments");
        response.setTenantId(tenantId.toString());
        response.setBurstableTokenAvailability(new TokenAvailability(100, 99, reset));
        response.setSustainedTokenAvailability(new TokenAvailability(1000, 999, reset));

        when(evaluateService.evaluate(any(EvaluationRequestDTO.class))).thenReturn(response);

        EvaluationRequestDTO request = new EvaluationRequestDTO("payments", tenantId);

        mockMvc.perform(post("/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.namespace").value("payments"))
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.burstableTokenAvailability.remainingToken").value(99))
                .andExpect(jsonPath("$.sustainedTokenAvailability.remainingToken").value(999));
    }

    @Test
    void postEvaluateRejectsMissingNamespace() throws Exception {
        String body = """
                {"tenantId":"11111111-1111-1111-1111-111111111111"}
                """;

        mockMvc.perform(post("/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
