package com.ratelimiter.controlplane.repository;

import com.ratelimiter.controlplane.entity.ConfigurationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfigurationRepository extends JpaRepository<ConfigurationEntity, ConfigurationEntity.ConfigurationId> {
}
