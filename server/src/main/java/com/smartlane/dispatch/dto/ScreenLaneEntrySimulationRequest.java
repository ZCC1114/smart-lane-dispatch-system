package com.smartlane.dispatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ScreenLaneEntrySimulationRequest(
		@NotBlank(message = "车道不能为空")
		@Size(max = 32, message = "车道编号不能超过 32 个字符")
		@Pattern(regexp = RequestValidationPatterns.IDENTIFIER, message = "车道编号格式非法")
		String laneId,
		@NotBlank(message = "车牌号码不能为空")
		@Size(min = 2, max = 20, message = "车牌号码长度必须在 2 到 20 个字符之间")
		@Pattern(regexp = RequestValidationPatterns.LICENSE_PLATE, message = "车牌号码格式非法")
		String plate) implements StrictJsonRequest {
}
