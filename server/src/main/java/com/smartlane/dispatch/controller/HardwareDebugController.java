package com.smartlane.dispatch.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartlane.dispatch.device.TcpDidoCommandService;
import com.smartlane.dispatch.dto.TcpDidoRelayRequest;
import com.smartlane.dispatch.dto.TcpDidoRelayResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/hardware-debug")
public class HardwareDebugController {

	private final TcpDidoCommandService tcpDidoCommandService;

	public HardwareDebugController(TcpDidoCommandService tcpDidoCommandService) {
		this.tcpDidoCommandService = tcpDidoCommandService;
	}

	@PostMapping("/tcp-dido/relay")
	@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
	public TcpDidoRelayResponse controlTcpDidoRelay(@Valid @RequestBody TcpDidoRelayRequest request) {
		return tcpDidoCommandService.controlRelay(
				request.host(),
				request.port(),
				request.relay(),
				request.on(),
				request.protocol());
	}
}
