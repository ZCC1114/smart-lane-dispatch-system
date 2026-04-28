package com.smartlane.dispatch.dto;

public record TcpDidoRelayResponse(
		String host,
		int port,
		String relay,
		boolean on,
		String protocol,
		String commandHex,
		String responseHex,
		String responseText,
		boolean responseReceived,
		String message) {
}
