package com.smartlane.dispatch.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VehicleEntryPayload(
		@NotBlank(message = "车道编号不能为空")
		@Size(max = 32, message = "车道编号不能超过 32 个字符")
		@Pattern(regexp = RequestValidationPatterns.IDENTIFIER, message = "车道编号格式非法")
		String laneId,
		@NotBlank(message = "车牌号码不能为空")
		@Size(min = 2, max = 20, message = "车牌号码长度必须在 2 到 20 个字符之间")
		@Pattern(regexp = RequestValidationPatterns.LICENSE_PLATE, message = "车牌号码格式非法")
		String plate,
		@Size(max = 32, message = "车辆类型不能超过 32 个字符")
		@Pattern(regexp = RequestValidationPatterns.SAFE_TEXT, message = "车辆类型包含非法字符")
		String vehicleType,
		@Size(max = 64, message = "数据来源不能超过 64 个字符")
		@Pattern(regexp = RequestValidationPatterns.SAFE_TEXT, message = "数据来源包含非法字符")
		String source,
		OffsetDateTime entryTime) implements StrictJsonRequest {
}
