package com.smartlane.dispatch.dto;

import jakarta.validation.constraints.NotBlank;

public record ManualDispatchRequest(
		@NotBlank(message = "车道编号不能为空") String laneId,
		@NotBlank(message = "指令类型不能为空") String commandType,
		String plate,
		@NotBlank(message = "调度原因不能为空") String reason,
		String vehicleType,
		Integer correctedVehicleCount,
		Boolean markPriority) {
}
