package com.smartlane.dispatch.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.smartlane.dispatch.dto.DispatchBoardView;
import com.smartlane.dispatch.dto.DispatchConfigRequest;
import com.smartlane.dispatch.dto.DispatchConfigView;
import com.smartlane.dispatch.dto.DispatchRuntimeRequest;
import com.smartlane.dispatch.dto.ManualDispatchRequest;
import com.smartlane.dispatch.service.OperationsService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/dispatch")
public class DispatchController {

	private final OperationsService operationsService;

	public DispatchController(OperationsService operationsService) {
		this.operationsService = operationsService;
	}

	@PostMapping("/manual")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public void dispatchManual(@Valid @RequestBody ManualDispatchRequest request) {
		operationsService.dispatchManual(request);
	}

	@GetMapping("/config")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER','VIEWER')")
	public DispatchConfigView getConfig() {
		return operationsService.getDispatchConfig();
	}

	@GetMapping("/board")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER','VIEWER')")
	public DispatchBoardView getBoard() {
		return operationsService.getDispatchBoard();
	}

	@PutMapping("/config")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public DispatchConfigView updateConfig(@Valid @RequestBody DispatchConfigRequest request) {
		return operationsService.updateDispatchConfig(request);
	}

	@PutMapping("/runtime")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public DispatchConfigView updateRuntime(@Valid @RequestBody DispatchRuntimeRequest request) {
		return operationsService.updateDispatchRuntime(request);
	}

	@PostMapping("/daily-reset")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public DispatchConfigView dailyReset() {
		return operationsService.dailyReset();
	}
}
