package com.smartlane.dispatch.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.smartlane.dispatch.entity.DispatchConfig;

public interface DispatchConfigRepository extends JpaRepository<DispatchConfig, String> {
}
