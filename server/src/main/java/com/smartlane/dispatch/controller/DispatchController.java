package com.smartlane.dispatch.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

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
}
