package com.smartlane.dispatch.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartlane.dispatch.dto.LaneSensorPayload;
import com.smartlane.dispatch.dto.VehicleEntryPayload;
import com.smartlane.dispatch.dto.YardEntryPayload;
import com.smartlane.dispatch.entity.DispatchTicket;
import com.smartlane.dispatch.entity.EntryLog;
import com.smartlane.dispatch.entity.Lane;
import com.smartlane.dispatch.service.OperationsService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/integration")
public class IntegrationController {

	private final OperationsService operationsService;

	public IntegrationController(OperationsService operationsService) {
		this.operationsService = operationsService;
	}

	@PostMapping("/lane-sensors")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public Lane ingestLaneSensor(@Valid @RequestBody LaneSensorPayload payload) {
		return operationsService.ingestLaneSensor(payload);
	}

	@PostMapping("/yard-entries")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public DispatchTicket registerYardEntry(@Valid @RequestBody YardEntryPayload payload) {
		return operationsService.registerYardEntry(payload);
	}

	@PostMapping("/vehicle-entries")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public EntryLog registerVehicleEntry(@Valid @RequestBody VehicleEntryPayload payload) {
		return operationsService.registerVehicleEntryFromDevice(payload.laneId(), payload.plate(), payload.entryTime(), payload.vehicleType(), payload.source());
	}
}
