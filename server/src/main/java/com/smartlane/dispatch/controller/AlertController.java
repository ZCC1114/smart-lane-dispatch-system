package com.smartlane.dispatch.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.smartlane.dispatch.entity.AlertEvent;
import com.smartlane.dispatch.service.OperationsService;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

	private final OperationsService operationsService;

	public AlertController(OperationsService operationsService) {
		this.operationsService = operationsService;
	}

	@GetMapping
	public List<AlertEvent> getAlerts() {
		return operationsService.getAlerts();
	}

	@PostMapping("/{alertId}/resolve")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public void resolveAlert(@PathVariable String alertId) {
		operationsService.resolveAlert(alertId);
	}
}
