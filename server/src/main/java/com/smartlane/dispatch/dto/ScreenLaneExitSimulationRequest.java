package com.smartlane.dispatch.dto;

import jakarta.validation.constraints.NotBlank;

public record ScreenLaneExitSimulationRequest(
		@NotBlank(message = "车道不能为空") String laneId) {
}
