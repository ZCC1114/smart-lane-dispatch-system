package com.smartlane.dispatch.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.smartlane.dispatch.entity.ScreenHandledEvent;

public interface ScreenHandledEventRepository extends JpaRepository<ScreenHandledEvent, String> {
}
