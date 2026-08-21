package com.smartlane.dispatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BlacklistPayload(
		@NotBlank(message = "车牌号码不能为空")
		@Size(min = 2, max = 20, message = "车牌号码长度必须在 2 到 20 个字符之间")
		@Pattern(regexp = RequestValidationPatterns.LICENSE_PLATE, message = "车牌号码格式非法")
		String plate,
		@NotBlank(message = "封禁原因不能为空")
		@Size(max = 256, message = "封禁原因不能超过 256 个字符")
		@Pattern(regexp = RequestValidationPatterns.SAFE_TEXT, message = "封禁原因包含非法字符")
		String reason,
		@NotBlank(message = "风险等级不能为空")
		@Pattern(regexp = "^(LOW|MEDIUM|HIGH|CRITICAL)$", message = "风险等级非法")
		String level,
		boolean active) implements StrictJsonRequest {
}
