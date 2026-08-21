package com.smartlane.dispatch.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ScreenEventIdsRequest(
		@NotEmpty(message = "告警事件不能为空")
		@Size(max = 100, message = "单次最多处理 100 个告警事件")
		List<
				@NotBlank(message = "告警事件 ID 不能为空")
				@Size(max = 80, message = "告警事件 ID 不能超过 80 个字符")
				@Pattern(regexp = RequestValidationPatterns.SCREEN_EVENT_ID, message = "告警事件 ID 格式非法")
				String> ids) implements StrictJsonRequest {
}
