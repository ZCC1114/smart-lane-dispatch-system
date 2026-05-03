package com.smartlane.dispatch.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record LaneCapacityRequest(
		@NotNull(message = "车道容量不能为空")
		@Min(value = 1, message = "车道容量必须大于 0")
		Integer capacity) {
}
