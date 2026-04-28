package com.smartlane.dispatch.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record TcpDidoRelayRequest(
		@NotBlank(message = "设备 IP 不能为空") String host,
		@Min(value = 1, message = "端口必须大于 0") @Max(value = 65535, message = "端口不能超过 65535") int port,
		@NotBlank(message = "继电器编号不能为空") String relay,
		@NotNull(message = "开关状态不能为空") Boolean on,
		String protocol) {
}
