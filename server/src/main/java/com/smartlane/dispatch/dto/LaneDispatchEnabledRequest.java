package com.smartlane.dispatch.dto;

import jakarta.validation.constraints.NotNull;

public record LaneDispatchEnabledRequest(
		@NotNull(message = "入口参与状态不能为空")
		Boolean dispatchEnabled) {
}
