package com.smartlane.dispatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RelayControlRequest(
		@NotBlank(message = "继电器目标不能为空")
		@Pattern(regexp = "^(ENTRY_RED|ENTRY_GREEN|EXIT_RED|EXIT_GREEN)$", message = "继电器目标非法")
		String target,
		@NotNull(message = "继电器动作不能为空") Boolean on,
		@NotBlank(message = "操作原因不能为空")
		@Size(max = 256, message = "操作原因不能超过 256 个字符")
		@Pattern(regexp = RequestValidationPatterns.SAFE_TEXT, message = "操作原因包含非法字符")
		String reason) implements StrictJsonRequest {
}
