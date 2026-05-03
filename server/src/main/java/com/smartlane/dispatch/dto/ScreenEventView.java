package com.smartlane.dispatch.dto;

import java.time.OffsetDateTime;

public record ScreenEventView(
		String id,
		String type,
		String plate,
		String message,
		OffsetDateTime occurredAt,
		String sourceId,
		String sourceName,
		boolean handled,
		OffsetDateTime handledAt) {
}
