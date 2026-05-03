package com.smartlane.dispatch.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotBlank;

public record YardEntryPayload(
		@NotBlank(message = "车牌号码不能为空") String plate,
		String vehicleType,
		String source,
		OffsetDateTime capturedAt) {
}
