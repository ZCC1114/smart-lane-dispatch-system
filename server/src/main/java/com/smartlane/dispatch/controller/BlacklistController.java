package com.smartlane.dispatch.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
import org.springframework.web.server.ResponseStatusException;

import com.smartlane.dispatch.dto.BlacklistPayload;
import com.smartlane.dispatch.dto.PageResult;
import com.smartlane.dispatch.entity.BlacklistRecord;
import com.smartlane.dispatch.security.AuthenticatedUser;
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
	public PageResult<BlacklistRecord> getBlacklist(
			@RequestParam(required = false) String query,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "10") int pageSize) {
		return operationsService.getBlacklist(query, page, pageSize);
	}

	@PostMapping
	@PreAuthorize("hasRole('ADMIN')")
	public BlacklistRecord createBlacklist(
			@Valid @RequestBody BlacklistPayload payload,
			Authentication authentication) {
		return operationsService.createBlacklist(payload, resolveOperator(authentication));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public BlacklistRecord updateBlacklist(
			@PathVariable String id,
			@Valid @RequestBody BlacklistPayload payload,
			Authentication authentication) {
		return operationsService.updateBlacklist(id, payload, resolveOperator(authentication));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasRole('ADMIN')")
	public void deleteBlacklist(@PathVariable String id) {
		operationsService.deleteBlacklist(id);
	}

	private String resolveOperator(Authentication authentication) {
		if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
			if (user.displayName() != null && !user.displayName().isBlank()) {
				return user.displayName();
			}
			return user.username();
		}
		if (authentication != null && authentication.getName() != null && !authentication.getName().isBlank()) {
			return authentication.getName();
		}
		throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "无法确定当前操作人");
	}
}
