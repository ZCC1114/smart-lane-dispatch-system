package com.smartlane.dispatch.controller;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartlane.dispatch.dto.DispatchRuntimeRequest;
import com.smartlane.dispatch.dto.DispatchConfigView;
import com.smartlane.dispatch.dto.PageResult;
import com.smartlane.dispatch.dto.ScreenBoardView;
import com.smartlane.dispatch.dto.ScreenEventIdsRequest;
import com.smartlane.dispatch.dto.ScreenEventView;
import com.smartlane.dispatch.dto.ScreenLaneEntrySimulationRequest;
import com.smartlane.dispatch.dto.ScreenLaneExitSimulationRequest;
import com.smartlane.dispatch.dto.ScreenSimulationRequest;
import com.smartlane.dispatch.dto.YardEntryPayload;
import com.smartlane.dispatch.dto.RequestValidationPatterns;
import com.smartlane.dispatch.entity.DispatchTicket;
import com.smartlane.dispatch.entity.EntryLog;
import com.smartlane.dispatch.entity.Lane;
import com.smartlane.dispatch.service.OperationsService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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
				operationsService.getPendingScreenBoardEvents(10),
				operationsService.getScreenBoardEvents(10),
				operationsService.getLanes(),
				operationsService.getLastDailyResetAt());
	}

	@GetMapping("/events")
	public PageResult<ScreenEventView> getEvents(
			@RequestParam(required = false)
			@Size(max = 64, message = "查询条件不能超过 64 个字符")
			@Pattern(regexp = RequestValidationPatterns.SAFE_TEXT, message = "查询条件包含非法字符")
			String query,
			@RequestParam(required = false)
			@Pattern(regexp = "(?i)^(blacklist|not_whitelisted|wrong_lane|not_entered|other)$", message = "告警类型非法")
			String type,
			@RequestParam(required = false) Boolean handled,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime occurredAtFrom,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime occurredAtTo,
			@RequestParam(defaultValue = "false") boolean includeHandled,
			@RequestParam(defaultValue = "1") @Min(value = 1, message = "页码不能小于 1") int page,
			@RequestParam(defaultValue = "10")
			@Min(value = 1, message = "每页条数不能小于 1")
			@Max(value = 200, message = "每页条数不能超过 200")
			int pageSize) {
		return operationsService.getScreenEvents(type, query, occurredAtFrom, occurredAtTo, includeHandled, handled, page, pageSize);
	}

	@GetMapping("/events/export")
	public List<ScreenEventView> exportEvents(
			@RequestParam(required = false)
			@Size(max = 64, message = "查询条件不能超过 64 个字符")
			@Pattern(regexp = RequestValidationPatterns.SAFE_TEXT, message = "查询条件包含非法字符")
			String query,
			@RequestParam(required = false)
			@Pattern(regexp = "(?i)^(blacklist|not_whitelisted|wrong_lane|not_entered|other)$", message = "告警类型非法")
			String type,
			@RequestParam(required = false) Boolean handled,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime occurredAtFrom,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime occurredAtTo) {
		return operationsService.exportScreenEvents(type, query, occurredAtFrom, occurredAtTo, handled);
	}

	@PostMapping("/events/{eventId}/handle")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public void handleEvent(
			@PathVariable
			@Size(max = 80, message = "告警事件 ID 不能超过 80 个字符")
			@Pattern(regexp = RequestValidationPatterns.SCREEN_EVENT_ID, message = "告警事件 ID 格式非法")
			String eventId) {
		operationsService.handleScreenEvent(eventId);
	}

	@PostMapping("/events/handle")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public void handleEvents(@Valid @RequestBody ScreenEventIdsRequest request) {
		operationsService.handleScreenEvents(request.ids());
	}

	@PostMapping("/events/handle-unhandled")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public void handleUnhandledEvents(
			@RequestParam(required = false)
			@Size(max = 64, message = "查询条件不能超过 64 个字符")
			@Pattern(regexp = RequestValidationPatterns.SAFE_TEXT, message = "查询条件包含非法字符")
			String query,
			@RequestParam(required = false)
			@Pattern(regexp = "(?i)^(blacklist|not_whitelisted|wrong_lane|not_entered|other)$", message = "告警类型非法")
			String type,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime occurredAtFrom,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime occurredAtTo) {
		operationsService.handleUnhandledScreenEvents(type, query, occurredAtFrom, occurredAtTo);
	}

	@PostMapping("/events/{eventId}/acknowledge")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public void acknowledgeEvent(
			@PathVariable
			@Size(max = 80, message = "告警事件 ID 不能超过 80 个字符")
			@Pattern(regexp = RequestValidationPatterns.SCREEN_EVENT_ID, message = "告警事件 ID 格式非法")
			String eventId) {
		operationsService.acknowledgeScreenEvent(eventId);
	}

	@PostMapping("/events/acknowledge")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public void acknowledgeEvents(@Valid @RequestBody ScreenEventIdsRequest request) {
		operationsService.acknowledgeScreenEvents(request.ids());
	}

	@PostMapping("/daily-reset")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public DispatchConfigView dailyReset() {
		return operationsService.dailyReset();
	}

	@PostMapping("/simulate/yard-entry")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public DispatchTicket simulateYardEntry(@Valid @RequestBody ScreenSimulationRequest request) {
		operationsService.updateDispatchRuntime(new DispatchRuntimeRequest(true, true));
		return operationsService.registerYardEntry(new YardEntryPayload(request.plate(), "出租车", "SCREEN_SIMULATION", null));
	}

	@PostMapping("/simulate/lane-entry")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public EntryLog simulateLaneEntry(@Valid @RequestBody ScreenLaneEntrySimulationRequest request) {
		operationsService.updateDispatchRuntime(new DispatchRuntimeRequest(true, true));
		return operationsService.registerVehicleEntryFromDevice(request.laneId(), request.plate(), null, "出租车", "SCREEN_SIMULATION");
	}

	@PostMapping("/simulate/lane-exit")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public Lane simulateLaneExit(@Valid @RequestBody ScreenLaneExitSimulationRequest request) {
		operationsService.updateDispatchRuntime(new DispatchRuntimeRequest(true, true));
		return operationsService.simulateScreenLaneExit(request.laneId(), null);
	}

	@PostMapping("/lanes/{laneId}/clear-remaining")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public Lane clearLaneRemainingVehicles(
			@PathVariable
			@Size(max = 32, message = "车道编号不能超过 32 个字符")
			@Pattern(regexp = RequestValidationPatterns.IDENTIFIER, message = "车道编号格式非法")
			String laneId) {
		return operationsService.clearLaneRemainingVehicles(laneId, null, "现场确认车道剩余车辆已全部驶出");
	}

	@PostMapping("/simulate/global-exit")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public Lane simulateGlobalExit() {
		operationsService.updateDispatchRuntime(new DispatchRuntimeRequest(true, true));
		return operationsService.simulateScreenGlobalExit(null);
	}
}
