package com.smartlane.dispatch.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.smartlane.dispatch.dto.SignalOverrideRequest;
import com.smartlane.dispatch.entity.Lane;
import com.smartlane.dispatch.service.OperationsService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/signals")
public class SignalController {

	private final OperationsService operationsService;

	public SignalController(OperationsService operationsService) {
		this.operationsService = operationsService;
	}

	@PostMapping("/{laneId}")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public Lane overrideSignal(@PathVariable String laneId, @Valid @RequestBody SignalOverrideRequest request) {
		return operationsService.overrideSignal(laneId, request);
	}

	@PostMapping("/restore-auto")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public void restoreAuto() {
		operationsService.restoreAutoControl();
	}

	@PostMapping("/lockdown")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public void lockdown() {
		operationsService.globalLockdown();
	}
}
