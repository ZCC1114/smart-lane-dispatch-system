package com.smartlane.dispatch.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;

public record LaneCapacityRequest(
		@NotNull(message = "车道容量不能为空")
		@Min(value = 1, message = "车道容量必须大于 0")
		@Max(value = 10000, message = "车道容量不能超过 10000")
		Integer capacity) implements StrictJsonRequest {
}
