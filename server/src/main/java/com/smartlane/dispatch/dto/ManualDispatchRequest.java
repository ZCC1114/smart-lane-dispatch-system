package com.smartlane.dispatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ManualDispatchRequest(
		@NotBlank(message = "车道编号不能为空")
		@Size(max = 32, message = "车道编号不能超过 32 个字符")
		@Pattern(regexp = RequestValidationPatterns.IDENTIFIER, message = "车道编号格式非法")
		String laneId,
		@NotBlank(message = "指令类型不能为空")
		@Pattern(
				regexp = "^(FORCE_OPEN_GATE|MANUAL_ENTRY|PLATE_CORRECTION|TEMP_ALLOW|CORRECT_COUNT|ADD_PLACEHOLDER_PLATES|ADD_REAL_PLATE|SET_PRIORITY)$",
				message = "指令类型非法")
		String commandType,
		@Size(max = 20, message = "车牌号码不能超过 20 个字符")
		@Pattern(regexp = RequestValidationPatterns.LICENSE_PLATE, message = "车牌号码格式非法")
		String plate,
		@NotBlank(message = "调度原因不能为空")
		@Size(max = 256, message = "调度原因不能超过 256 个字符")
		@Pattern(regexp = RequestValidationPatterns.SAFE_TEXT, message = "调度原因包含非法字符")
		String reason,
		@Size(max = 32, message = "车辆类型不能超过 32 个字符")
		@Pattern(regexp = RequestValidationPatterns.SAFE_TEXT, message = "车辆类型包含非法字符")
		String vehicleType,
		@Min(value = 0, message = "校正车辆数不能小于 0")
		@Max(value = 10000, message = "校正车辆数不能超过 10000")
		Integer correctedVehicleCount,
		@Min(value = 1, message = "占位车辆数不能小于 1")
		@Max(value = 10000, message = "占位车辆数不能超过 10000")
		Integer placeholderCount,
		Boolean markPriority) implements StrictJsonRequest {
}
