package com.smartlane.dispatch.controller;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartlane.dispatch.entity.EntryLog;
import com.smartlane.dispatch.service.OperationsService;

@RestController
@RequestMapping("/api/logs")
public class EntryLogController {

	private final OperationsService operationsService;

	public EntryLogController(OperationsService operationsService) {
		this.operationsService = operationsService;
	}

	@GetMapping
	public List<EntryLog> getLogs(
			@RequestParam(required = false) String query,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String laneId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime entryTimeFrom,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime entryTimeTo) {
		return operationsService.getLogs(query, status, laneId, entryTimeFrom, entryTimeTo);
	}
}
