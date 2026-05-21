package com.smartlane.dispatch.controller;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartlane.dispatch.dto.DispatchRuntimeRequest;
import com.smartlane.dispatch.dto.ScreenBoardView;
import com.smartlane.dispatch.dto.ScreenEventView;
import com.smartlane.dispatch.dto.ScreenLaneEntrySimulationRequest;
import com.smartlane.dispatch.dto.ScreenLaneExitSimulationRequest;
import com.smartlane.dispatch.dto.ScreenSimulationRequest;
import com.smartlane.dispatch.dto.YardEntryPayload;
import com.smartlane.dispatch.entity.DispatchTicket;
import com.smartlane.dispatch.entity.EntryLog;
import com.smartlane.dispatch.entity.Lane;
import com.smartlane.dispatch.service.OperationsService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/screen")
public class ScreenController {

	private final OperationsService operationsService;

	public ScreenController(OperationsService operationsService) {
		this.operationsService = operationsService;
	}

	@GetMapping("/board")
	public ScreenBoardView getBoard() {
		return ScreenBoardView.from(
				operationsService.getDispatchBoard(),
				operationsService.getRecentEntryLogs(10),
				operationsService.getRecentYardEntries(10),
				operationsService.getRecentGuideAssignments(12),
				operationsService.getScreenLaneVehicles(),
				operationsService.getScreenBoardEvents(10),
				operationsService.getLanes());
	}

	@GetMapping("/events")
	public List<ScreenEventView> getEvents(
			@RequestParam(required = false) String type,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime occurredAtFrom,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime occurredAtTo,
			@RequestParam(defaultValue = "false") boolean includeHandled) {
		return operationsService.getScreenEvents(type, occurredAtFrom, occurredAtTo, includeHandled);
	}

	@PostMapping("/events/{eventId}/handle")
	public void handleEvent(@PathVariable String eventId) {
		operationsService.handleScreenEvent(eventId);
	}

	@PostMapping("/simulate/yard-entry")
	public DispatchTicket simulateYardEntry(@Valid @org.springframework.web.bind.annotation.RequestBody ScreenSimulationRequest request) {
		operationsService.updateDispatchRuntime(new DispatchRuntimeRequest(true, true));
		return operationsService.registerYardEntry(new YardEntryPayload(request.plate(), "出租车", "SCREEN_SIMULATION", null));
	}

	@PostMapping("/simulate/lane-entry")
	public EntryLog simulateLaneEntry(@Valid @org.springframework.web.bind.annotation.RequestBody ScreenLaneEntrySimulationRequest request) {
		operationsService.updateDispatchRuntime(new DispatchRuntimeRequest(true, true));
		return operationsService.registerVehicleEntryFromDevice(request.laneId(), request.plate(), null, "出租车", "SCREEN_SIMULATION");
	}

	@PostMapping("/simulate/lane-exit")
	public Lane simulateLaneExit(@Valid @org.springframework.web.bind.annotation.RequestBody ScreenLaneExitSimulationRequest request) {
		operationsService.updateDispatchRuntime(new DispatchRuntimeRequest(true, true));
		return operationsService.simulateScreenLaneExit(request.laneId(), null);
	}

	@PostMapping("/simulate/global-exit")
	public Lane simulateGlobalExit() {
		operationsService.updateDispatchRuntime(new DispatchRuntimeRequest(true, true));
		return operationsService.simulateScreenGlobalExit(null);
	}
}
