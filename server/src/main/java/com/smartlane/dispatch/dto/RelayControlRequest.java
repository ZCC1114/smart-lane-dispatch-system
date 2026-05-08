package com.smartlane.dispatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RelayControlRequest(
		@NotBlank(message = "继电器目标不能为空") String target,
		@NotNull(message = "继电器动作不能为空") Boolean on,
		@NotBlank(message = "操作原因不能为空") String reason) {
}
