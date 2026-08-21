package com.smartlane.dispatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignalOverrideRequest(
		@NotBlank(message = "入口信号不能为空")
		@Pattern(regexp = "^(RED|GREEN|OFFLINE)$", message = "入口信号非法")
		String entrySignal,
		@NotBlank(message = "出口信号不能为空")
		@Pattern(regexp = "^(RED|GREEN|OFFLINE)$", message = "出口信号非法")
		String exitSignal,
		@Pattern(regexp = "^(AUTO|MANUAL|OFFLINE)$", message = "车道模式非法")
		String mode,
		@NotBlank(message = "操作原因不能为空")
		@Size(max = 256, message = "操作原因不能超过 256 个字符")
		@Pattern(regexp = RequestValidationPatterns.SAFE_TEXT, message = "操作原因包含非法字符")
		String reason) implements StrictJsonRequest {
}
