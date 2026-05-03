package com.smartlane.dispatch.dto;

import jakarta.validation.constraints.NotNull;

public record DispatchRuntimeRequest(
		@NotNull(message = "入口自动调度开关不能为空") Boolean entryDispatchEnabled,
		@NotNull(message = "出口自动调度开关不能为空") Boolean exitDispatchEnabled) {
}
