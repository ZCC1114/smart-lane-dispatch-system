package com.smartlane.dispatch.controller;

import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.smartlane.dispatch.dto.PageResult;
import com.smartlane.dispatch.dto.WhitelistPayload;
import com.smartlane.dispatch.dto.WhitelistImportProgress;
import com.smartlane.dispatch.dto.WhitelistImportResult;
import com.smartlane.dispatch.dto.WhitelistSettingsRequest;
import com.smartlane.dispatch.dto.WhitelistSettingsView;
import com.smartlane.dispatch.entity.WhitelistRecord;
import com.smartlane.dispatch.security.AuthenticatedUser;
import com.smartlane.dispatch.service.OperationsService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/whitelist")
public class WhitelistController {

	private final OperationsService operationsService;

	public WhitelistController(OperationsService operationsService) {
		this.operationsService = operationsService;
	}

	@GetMapping
	@PreAuthorize("hasRole('ADMIN')")
	public PageResult<WhitelistRecord> getWhitelist(
			@RequestParam(required = false) String query,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "10") int pageSize) {
		return operationsService.getWhitelist(query, page, pageSize);
	}

	@PostMapping
	@PreAuthorize("hasRole('ADMIN')")
	public WhitelistRecord createWhitelist(
			@Valid @RequestBody WhitelistPayload payload,
			Authentication authentication) {
		return operationsService.createWhitelist(payload, resolveOperator(authentication));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public WhitelistRecord updateWhitelist(
			@PathVariable String id,
			@Valid @RequestBody WhitelistPayload payload,
			Authentication authentication) {
		return operationsService.updateWhitelist(id, payload, resolveOperator(authentication));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasRole('ADMIN')")
	public void deleteWhitelist(@PathVariable String id) {
		operationsService.deleteWhitelist(id);
	}

	@PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("hasRole('ADMIN')")
	public WhitelistImportResult importWhitelist(
			@RequestPart("file") MultipartFile file,
			@RequestParam(required = false) String jobId,
			Authentication authentication) {
		return operationsService.importWhitelist(file, resolveOperator(authentication), jobId);
	}

	@GetMapping("/import-progress/{jobId}")
	@PreAuthorize("hasRole('ADMIN')")
	public WhitelistImportProgress getImportProgress(@PathVariable String jobId) {
		return operationsService.getWhitelistImportProgress(jobId);
	}

	@GetMapping("/settings")
	@PreAuthorize("hasRole('ADMIN')")
	public WhitelistSettingsView getSettings() {
		return operationsService.getWhitelistSettings();
	}

	@PutMapping("/settings")
	@PreAuthorize("hasRole('ADMIN')")
	public WhitelistSettingsView updateSettings(@Valid @RequestBody WhitelistSettingsRequest request) {
		return operationsService.updateWhitelistSettings(request);
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
