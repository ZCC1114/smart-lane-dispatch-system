package com.smartlane.dispatch.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LaneSensorPayload(
		@NotBlank(message = "车道编号不能为空")
		@Size(max = 32, message = "车道编号不能超过 32 个字符")
		@Pattern(regexp = RequestValidationPatterns.IDENTIFIER, message = "车道编号格式非法")
		String laneId,
		@NotNull(message = "车辆数不能为空")
		@Min(value = 0, message = "车辆数不能小于 0")
		@Max(value = 10000, message = "车辆数不能超过 10000")
		Integer vehicleCount,
		@Min(value = 1, message = "车道容量不能小于 1")
		@Max(value = 10000, message = "车道容量不能超过 10000")
		Integer capacity,
		@NotBlank(message = "传感状态不能为空")
		@Pattern(regexp = "^(ONLINE|DEGRADED|OFFLINE)$", message = "传感状态非法")
		String sensorStatus,
		OffsetDateTime observedAt) implements StrictJsonRequest {
}
