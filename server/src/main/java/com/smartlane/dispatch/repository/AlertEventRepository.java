package com.smartlane.dispatch.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.smartlane.dispatch.entity.AlertEvent;

public interface AlertEventRepository extends JpaRepository<AlertEvent, String> {

    List<AlertEvent> findAllByOrderByCreatedAtDesc();
}
