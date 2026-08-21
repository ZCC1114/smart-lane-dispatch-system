package com.smartlane.dispatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DispatchConfigRequest(
	        @NotBlank(message = "入口开放顺序不能为空")
	        @Size(max = 512, message = "入口开放顺序不能超过 512 个字符")
	        @Pattern(regexp = "^[A-Za-z0-9._:, -]+$", message = "入口开放顺序格式非法")
	        String entryLaneOrder,
	        Boolean entryDispatchEnabled,
	        Boolean exitDispatchEnabled,
	        @Min(value = 1, message = "未进车道超时时间必须至少 1 分钟")
	        @Max(value = 60, message = "未进车道超时时间不能超过 60 分钟")
	        Integer assignmentReserveMinutes) implements StrictJsonRequest {
}
