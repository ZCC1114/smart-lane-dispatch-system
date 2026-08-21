package com.smartlane.dispatch.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.smartlane.dispatch.dto.LaneActivePlateView;
import com.smartlane.dispatch.dto.LaneCapacityRequest;
import com.smartlane.dispatch.dto.LaneDispatchEnabledRequest;
import com.smartlane.dispatch.dto.LanePlateCorrectionRequest;
import com.smartlane.dispatch.entity.Lane;
import com.smartlane.dispatch.service.OperationsService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/lanes")
public class LaneController {

	private final OperationsService operationsService;

	public LaneController(OperationsService operationsService) {
		this.operationsService = operationsService;
	}

	@GetMapping
	public List<Lane> getLanes() {
		return operationsService.getLanes();
	}

	@GetMapping("/{laneId}/active-plates")
	public List<LaneActivePlateView> getActivePlates(@PathVariable("laneId") String laneId) {
		return operationsService.getActiveLanePlates(laneId);
	}

	@PutMapping("/{laneId}/active-plates/{entryLogId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public void correctActivePlate(
			@PathVariable("laneId") String laneId,
			@PathVariable("entryLogId") String entryLogId,
			@Valid @RequestBody LanePlateCorrectionRequest request) {
		operationsService.correctActiveLanePlate(laneId, entryLogId, request.plate());
	}

	@PutMapping("/{laneId}/capacity")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public Lane updateCapacity(@PathVariable("laneId") String laneId, @Valid @RequestBody LaneCapacityRequest request) {
		return operationsService.updateLaneCapacity(laneId, request.capacity());
	}

	@PutMapping("/{laneId}/dispatch-enabled")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public Lane updateDispatchEnabled(
			@PathVariable("laneId") String laneId,
			@Valid @RequestBody LaneDispatchEnabledRequest request) {
		return operationsService.updateLaneDispatchEnabled(laneId, request.dispatchEnabled());
	}
}
