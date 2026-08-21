package com.smartlane.dispatch.controller;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.smartlane.dispatch.device.led.LedGuideDynamicAreaClient;
import com.smartlane.dispatch.device.led.LedGuideDynamicAreaRequest;
import com.smartlane.dispatch.device.led.LedDeviceException;
import com.smartlane.dispatch.device.led.LedGuideDisplayProperties;
import com.smartlane.dispatch.device.led.LedScreenService;
import com.smartlane.dispatch.dto.RequestValidationPatterns;
import com.smartlane.dispatch.dto.StrictJsonRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * LED 显示屏测试接口。
 *
 * <p>提供 REST API 用于向前端暴露 LED 发送能力，前端测试页位于 /led-test。</p>
 */
@RestController
@RequestMapping("/api/screen/led-test")
@PreAuthorize("hasAnyRole('ADMIN','DISPATCHER')")
public class LedTestController {
	private static final Logger log = LoggerFactory.getLogger(LedTestController.class);

	private final LedScreenService ledScreenService;
	private final LedGuideDynamicAreaClient dynamicAreaClient;
	private final LedGuideDisplayProperties displayProperties;

	public LedTestController(
			LedScreenService ledScreenService,
			LedGuideDynamicAreaClient dynamicAreaClient,
			LedGuideDisplayProperties displayProperties) {
		this.ledScreenService = ledScreenService;
		this.dynamicAreaClient = dynamicAreaClient;
		this.displayProperties = displayProperties;
	}

	@PostMapping("/send")
	public Map<String, String> send(@Valid @RequestBody LedTestRequest request) {
		AllowedLedTarget target = requireAllowedTarget(request);
		String result = ledScreenService.sendText(
			target.host(),
			target.port(),
			target.generation(),
			target.model(),
			request.segments(),
			request.screenWidth(),
			request.screenHeight(),
			request.columns(),
			request.rows()
		);
		return Map.of("result", result);
	}

	@PostMapping("/send-dynamic")
	public Map<String, String> sendDynamic(@Valid @RequestBody LedTestRequest request) {
		AllowedLedTarget target = requireAllowedTarget(request);
		if (!"6".equals(target.generation())) {
			return Map.of("result", "错误：动态区长连接仅支持六代控制卡");
		}
		if (request.segments() == null || request.segments().isEmpty()) {
			return Map.of("result", "错误：文本内容不能为空");
		}
		if (request.ip() == null || request.ip().trim().isEmpty()) {
			return Map.of("result", "错误：IP 地址不能为空");
		}

		int screenWidth = positive(request.screenWidth(), 192);
		int screenHeight = positive(request.screenHeight(), 96);
		int rowCount = Math.min(4, request.segments().size());
		int layoutRows = positive(request.rows(), rowCount);
		long startNanos = System.nanoTime();
		try {
			for (int index = 0; index < rowCount; index++) {
				LedTestRequest.Segment segment = request.segments().get(index);
				DynamicCell cell = dynamicCell(index, rowCount, layoutRows, screenHeight);
				dynamicAreaClient.write(new LedGuideDynamicAreaRequest(
						target.host(),
						target.port(),
						target.model(),
						screenWidth,
						screenHeight,
						index,
						0,
						cell.y(),
						screenWidth,
						cell.height(),
						segment.text(),
						segment.fontSize(),
						segment.color()));
			}
			long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
			return Map.of("result", "发送成功（六代动态区长连接）: 屏幕尺寸 "
					+ screenWidth + "x" + screenHeight + "，1列x" + rowCount
					+ "行，更新 " + rowCount + " 个动态区，耗时 " + elapsedMs + " ms");
		} catch (LedDeviceException ex) {
			log.warn("LED dynamic-area test failed", ex);
			return Map.of("result", "发送失败（六代动态区长连接），请查看服务日志");
		}
	}

	private AllowedLedTarget requireAllowedTarget(LedTestRequest request) {
		String configuredHost = displayProperties.getIp();
		if (configuredHost == null || configuredHost.isBlank()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "LED 测试设备尚未配置");
		}
		if (!configuredHost.trim().equalsIgnoreCase(request.ip().trim())
				|| displayProperties.getPort() != request.port()
				|| !displayProperties.getGeneration().equals(request.generation())
				|| !displayProperties.getModel().equals(request.model())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "LED 测试目标不在允许范围内");
		}
		return new AllowedLedTarget(
				configuredHost.trim(),
				displayProperties.getPort(),
				displayProperties.getGeneration(),
				displayProperties.getModel());
	}

	private int positive(int value, int defaultValue) {
		return value > 0 ? value : defaultValue;
	}

	private DynamicCell dynamicCell(int index, int rowCount, int layoutRows, int screenHeight) {
		if (rowCount == 3 && layoutRows == 4) {
			int guideHeight = Math.max(1, (int) Math.round(screenHeight * 3.0 / 8.0));
			int promptY = guideHeight * 2;
			return switch (index) {
				case 0 -> new DynamicCell(0, guideHeight);
				case 1 -> new DynamicCell(guideHeight, guideHeight);
				default -> new DynamicCell(promptY, Math.max(1, screenHeight - promptY));
			};
		}
		int rowHeight = Math.max(1, screenHeight / rowCount);
		int y = index * rowHeight;
		int height = index == rowCount - 1 ? screenHeight - y : rowHeight;
		return new DynamicCell(y, Math.max(1, height));
	}

	public record LedTestRequest(
		@NotBlank(message = "IP 地址不能为空")
		@Size(max = 253, message = "IP 地址过长")
		@Pattern(regexp = "^[A-Za-z0-9.:-]+$", message = "IP 地址格式非法")
		String ip,
		@Min(value = 1, message = "端口必须大于 0")
		@Max(value = 65535, message = "端口不能超过 65535")
		int port,
		@NotBlank(message = "控制卡代数不能为空")
		@Pattern(regexp = "^[56]$", message = "控制卡代数仅支持 5 或 6")
		String generation,
		@NotBlank(message = "控制卡型号不能为空")
		@Size(max = 32, message = "控制卡型号不能超过 32 个字符")
		@Pattern(regexp = RequestValidationPatterns.IDENTIFIER, message = "控制卡型号格式非法")
		String model,
		@NotEmpty(message = "文本内容不能为空")
		@Size(max = 24, message = "单次最多发送 24 段文本")
		List<@Valid Segment> segments,
		@Min(value = 1, message = "屏幕宽度必须大于 0")
		@Max(value = 4096, message = "屏幕宽度不能超过 4096")
		int screenWidth,
		@Min(value = 1, message = "屏幕高度必须大于 0")
		@Max(value = 4096, message = "屏幕高度不能超过 4096")
		int screenHeight,
		@Min(value = 1, message = "列数必须大于 0")
		@Max(value = 64, message = "列数不能超过 64")
		int columns,
		@Min(value = 1, message = "行数必须大于 0")
		@Max(value = 64, message = "行数不能超过 64")
		int rows
	) implements StrictJsonRequest {
		public record Segment(
				@NotBlank(message = "LED 文本不能为空")
				@Size(max = 256, message = "LED 文本不能超过 256 个字符")
				@Pattern(regexp = RequestValidationPatterns.SAFE_TEXT, message = "LED 文本包含非法字符")
				String text,
				@Min(value = 6, message = "字号不能小于 6")
				@Max(value = 96, message = "字号不能超过 96")
				int fontSize,
				@NotBlank(message = "LED 颜色不能为空")
				@Pattern(
						regexp = "^(RED|GREEN|BLUE|YELLOW|CYAN|MAGENTA|WHITE|BLACK|ORANGE|PINK)$",
						message = "LED 颜色非法")
				String color) implements StrictJsonRequest {
		}
	}

	private record DynamicCell(int y, int height) {}

	private record AllowedLedTarget(String host, int port, String generation, String model) {}
}
