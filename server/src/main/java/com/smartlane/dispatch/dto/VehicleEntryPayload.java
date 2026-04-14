package com.smartlane.dispatch.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotBlank;

public record VehicleEntryPayload(
		@NotBlank(message = "车道编号不能为空") String laneId,
		@NotBlank(message = "车牌号码不能为空") String plate,
		String vehicleType,
		String source,
		OffsetDateTime entryTime) {
}
