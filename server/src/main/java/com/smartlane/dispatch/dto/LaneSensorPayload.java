package com.smartlane.dispatch.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record LaneSensorPayload(
		@NotBlank(message = "车道编号不能为空") String laneId,
		@NotNull(message = "车辆数不能为空") @Min(value = 0, message = "车辆数不能小于 0") Integer vehicleCount,
		@Min(value = 1, message = "车道容量不能小于 1") Integer capacity,
		@NotBlank(message = "传感状态不能为空") String sensorStatus,
		OffsetDateTime observedAt) {
}
