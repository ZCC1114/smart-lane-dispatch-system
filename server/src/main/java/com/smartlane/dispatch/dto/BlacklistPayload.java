package com.smartlane.dispatch.dto;

import jakarta.validation.constraints.NotBlank;

public record BlacklistPayload(
		@NotBlank(message = "车牌号码不能为空") String plate,
		@NotBlank(message = "封禁原因不能为空") String reason,
		@NotBlank(message = "风险等级不能为空") String level,
		@NotBlank(message = "操作员不能为空") String operator,
		boolean active) {
}
