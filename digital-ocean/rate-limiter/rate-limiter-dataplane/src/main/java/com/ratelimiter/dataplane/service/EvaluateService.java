package com.ratelimiter.dataplane.service;

import com.ratelimiter.common.dto.EvaluationRequestDTO;
import com.ratelimiter.common.dto.EvaluationResponseDTO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class EvaluateService {

    private final RateLimitEvaluator rateLimitEvaluator;

    public EvaluateService(RateLimitEvaluator rateLimitEvaluator) {
        this.rateLimitEvaluator = rateLimitEvaluator;
    }

    public EvaluationResponseDTO evaluate(EvaluationRequestDTO request) {
        String namespace = request.getNamespace();
        UUID tenantId = resolveTenantId(request);

        var result = rateLimitEvaluator.evaluate(namespace, tenantId, 1);

        EvaluationResponseDTO response = new EvaluationResponseDTO();
        response.setAllowed(result.allowed());
        response.setNamespace(namespace);
        response.setTenantId(tenantId.toString());
        response.setBurstableTokenAvailability(result.burstable());
        response.setSustainedTokenAvailability(result.sustained());
        return response;
    }

    private UUID resolveTenantId(EvaluationRequestDTO request) {
        if (request.getTenantId() != null) {
            return request.getTenantId();
        }
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            return UUID.nameUUIDFromBytes(request.getApiKey().getBytes());
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId or apiKey is required");
    }
}
