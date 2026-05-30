package com.smartlane.dispatch.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.smartlane.dispatch.entity.ScreenAcknowledgedEvent;

public interface ScreenAcknowledgedEventRepository extends JpaRepository<ScreenAcknowledgedEvent, String> {
}
