package com.smartlane.dispatch.controller;

import java.time.OffsetDateTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.smartlane.dispatch.dto.EntryLogView;
import com.smartlane.dispatch.dto.PageResult;
import com.smartlane.dispatch.dto.PlateCorrectionRequest;
import com.smartlane.dispatch.service.OperationsService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/logs")
public class EntryLogController {

	private final OperationsService operationsService;

	public EntryLogController(OperationsService operationsService) {
		this.operationsService = operationsService;
	}

	@GetMapping
	public PageResult<EntryLogView> getLogs(
			@RequestParam(required = false) String query,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String laneId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime entryTimeFrom,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime entryTimeTo,
			@RequestParam(required = false) String alarmType,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "10") int pageSize) {
		return operationsService.getLogs(query, status, laneId, entryTimeFrom, entryTimeTo, alarmType, page, pageSize);
	}

	@GetMapping("/export")
	public java.util.List<EntryLogView> exportLogs(
			@RequestParam(required = false) String query,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String laneId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime entryTimeFrom,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime entryTimeTo,
			@RequestParam(required = false) String alarmType) {
		return operationsService.exportLogs(query, status, laneId, entryTimeFrom, entryTimeTo, alarmType);
	}

	@PutMapping("/{id}/plate")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public void correctPlate(
			@PathVariable String id,
			@Valid @RequestBody PlateCorrectionRequest request) {
		operationsService.correctEntryLogPlate(id, request.plate(), Boolean.TRUE.equals(request.closeExistingActiveRecord()));
	}
}
