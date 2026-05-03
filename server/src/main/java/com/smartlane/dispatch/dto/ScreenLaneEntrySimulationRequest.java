package com.smartlane.dispatch.dto;

import jakarta.validation.constraints.NotBlank;

public record ScreenLaneEntrySimulationRequest(
		@NotBlank(message = "车道不能为空") String laneId,
		@NotBlank(message = "车牌号码不能为空") String plate) {
}
