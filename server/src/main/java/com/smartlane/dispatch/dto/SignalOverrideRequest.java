package com.smartlane.dispatch.dto;

import jakarta.validation.constraints.NotBlank;

public record SignalOverrideRequest(
		@NotBlank(message = "车道编号不能为空") String laneId,
		@NotBlank(message = "入口信号不能为空") String entrySignal,
		@NotBlank(message = "出口信号不能为空") String exitSignal,
		@NotBlank(message = "控制模式不能为空") String mode,
		@NotBlank(message = "操作原因不能为空") String reason) {
}
