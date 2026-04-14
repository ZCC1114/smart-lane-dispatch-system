package com.smartlane.dispatch.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.smartlane.dispatch.dto.BlacklistPayload;
import com.smartlane.dispatch.entity.BlacklistRecord;
import com.smartlane.dispatch.service.OperationsService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/blacklist")
public class BlacklistController {

	private final OperationsService operationsService;

	public BlacklistController(OperationsService operationsService) {
		this.operationsService = operationsService;
	}

	@GetMapping
	@PreAuthorize("hasRole('ADMIN')")
	public List<BlacklistRecord> getBlacklist(@RequestParam(required = false) String query) {
		return operationsService.getBlacklist(query);
	}

	@PostMapping
	@PreAuthorize("hasRole('ADMIN')")
	public BlacklistRecord createBlacklist(@Valid @RequestBody BlacklistPayload payload) {
		return operationsService.createBlacklist(payload);
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public BlacklistRecord updateBlacklist(@PathVariable String id, @Valid @RequestBody BlacklistPayload payload) {
		return operationsService.updateBlacklist(id, payload);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasRole('ADMIN')")
	public void deleteBlacklist(@PathVariable String id) {
		operationsService.deleteBlacklist(id);
	}
}
