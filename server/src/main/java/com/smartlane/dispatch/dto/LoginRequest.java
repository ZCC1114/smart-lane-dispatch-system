package com.smartlane.dispatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(
		@NotBlank(message = "用户名不能为空")
		@Size(max = 64, message = "用户名不能超过 64 个字符")
		@Pattern(regexp = RequestValidationPatterns.SAFE_TEXT, message = "用户名包含非法字符")
		String username,
		@NotBlank(message = "密码不能为空")
		@Size(max = 128, message = "密码不能超过 128 个字符")
		String password) implements StrictJsonRequest {
}
