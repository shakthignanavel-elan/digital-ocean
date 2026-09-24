package com.ratelimiter.dataplane.controller;

import com.ratelimiter.common.dto.EvaluationRequestDTO;
import com.ratelimiter.common.dto.EvaluationResponseDTO;
import com.ratelimiter.dataplane.service.EvaluateService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EvaluateController {

    private final EvaluateService evaluateService;

    public EvaluateController(EvaluateService evaluateService) {
        this.evaluateService = evaluateService;
    }

    @PostMapping("/evaluate")
    public EvaluationResponseDTO evaluate(@Valid @RequestBody EvaluationRequestDTO request) {
        return evaluateService.evaluate(request);
    }
}
