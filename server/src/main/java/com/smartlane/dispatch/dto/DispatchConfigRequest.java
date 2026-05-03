package com.smartlane.dispatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record DispatchConfigRequest(
	        @NotBlank(message = "入口开放顺序不能为空") String entryLaneOrder,
	        Boolean entryDispatchEnabled,
	        Boolean exitDispatchEnabled,
	        @Min(value = 1, message = "未进车道超时时间必须至少 1 分钟")
	        @Max(value = 60, message = "未进车道超时时间不能超过 60 分钟")
	        Integer assignmentReserveMinutes) {
}
