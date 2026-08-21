package com.smartlane.dispatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LanePlateCorrectionRequest(
		@NotBlank(message = "车牌号码不能为空")
		@Size(min = 2, max = 20, message = "车牌号码长度必须在 2 到 20 个字符之间")
		@Pattern(regexp = RequestValidationPatterns.LICENSE_PLATE, message = "车牌号码格式非法")
		String plate) implements StrictJsonRequest {
}
