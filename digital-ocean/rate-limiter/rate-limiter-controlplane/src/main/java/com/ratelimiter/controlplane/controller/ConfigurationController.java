package com.ratelimiter.controlplane.controller;

import com.ratelimiter.common.dto.ConfigurationDTO;
import com.ratelimiter.common.dto.QuotaDTO;
import com.ratelimiter.controlplane.service.ConfigurationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ConfigurationController {

    private final ConfigurationService configurationService;

    public ConfigurationController(ConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    @GetMapping("/configuration/{namespace}/{tenantId}")
    public ConfigurationDTO get(@PathVariable String namespace, @PathVariable UUID tenantId) {
        return configurationService.get(namespace, tenantId);
    }

    @PostMapping("/configuration/{namespace}/{tenantId}")
    @ResponseStatus(HttpStatus.CREATED)
    public ConfigurationDTO create(@PathVariable String namespace,
                                   @PathVariable UUID tenantId,
                                   @Valid @RequestBody ConfigurationDTO request) {
        return configurationService.create(namespace, tenantId, request);
    }

    @PutMapping("/configuration/{namespace}/{tenantId}")
    public ConfigurationDTO update(@PathVariable String namespace,
                                   @PathVariable UUID tenantId,
                                   @Valid @RequestBody ConfigurationDTO request) {
        return configurationService.update(namespace, tenantId, request);
    }

    @DeleteMapping("/configuration/{namespace}/{tenantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String namespace, @PathVariable UUID tenantId) {
        configurationService.delete(namespace, tenantId);
    }

    @GetMapping("/quota/{namespace}/{tenantId}")
    public QuotaDTO getQuota(@PathVariable String namespace, @PathVariable UUID tenantId) {
        return configurationService.getQuota(namespace, tenantId);
    }
}
