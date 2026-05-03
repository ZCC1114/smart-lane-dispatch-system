package com.smartlane.dispatch.dto;

import jakarta.validation.constraints.NotBlank;

public record ScreenSimulationRequest(
		@NotBlank(message = "车牌号码不能为空") String plate) {
}
