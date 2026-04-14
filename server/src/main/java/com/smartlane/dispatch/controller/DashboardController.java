package com.smartlane.dispatch.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartlane.dispatch.dto.DashboardPayload;
import com.smartlane.dispatch.service.OperationsService;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

	private final OperationsService operationsService;

	public DashboardController(OperationsService operationsService) {
		this.operationsService = operationsService;
	}

	@GetMapping
	public DashboardPayload getDashboard() {
		return operationsService.getDashboard();
	}
}
