package com.smartlane.dispatch.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartlane.dispatch.device.led.LedGuideDynamicAreaClient;
import com.smartlane.dispatch.device.led.LedGuideDynamicAreaRequest;
import com.smartlane.dispatch.device.led.LedScreenService;

/**
 * LED 显示屏测试接口。
 *
 * <p>提供 REST API 用于向前端暴露 LED 发送能力，前端测试页位于 /led-test。</p>
 */
@RestController
@RequestMapping("/api/screen/led-test")
public class LedTestController {

	private final LedScreenService ledScreenService;
	private final LedGuideDynamicAreaClient dynamicAreaClient;

	public LedTestController(LedScreenService ledScreenService, LedGuideDynamicAreaClient dynamicAreaClient) {
		this.ledScreenService = ledScreenService;
		this.dynamicAreaClient = dynamicAreaClient;
	}

	@PostMapping("/send")
	public Map<String, String> send(@RequestBody LedTestRequest request) {
		String result = ledScreenService.sendText(
			request.ip(),
			request.port(),
			request.generation(),
			request.model(),
			request.segments(),
			request.screenWidth(),
			request.screenHeight(),
			request.columns(),
			request.rows()
		);
		return Map.of("result", result);
	}

	@PostMapping("/send-dynamic")
	public Map<String, String> sendDynamic(@RequestBody LedTestRequest request) {
		if (!"6".equals(request.generation())) {
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
		int rowHeight = Math.max(1, screenHeight / rowCount);
		long startNanos = System.nanoTime();
		try {
			for (int index = 0; index < rowCount; index++) {
				LedTestRequest.Segment segment = request.segments().get(index);
				int y = index * rowHeight;
				int height = index == rowCount - 1 ? screenHeight - y : rowHeight;
				dynamicAreaClient.write(new LedGuideDynamicAreaRequest(
						request.ip().trim(),
						positive(request.port(), 5005),
						request.model(),
						screenWidth,
						screenHeight,
						index,
						0,
						y,
						screenWidth,
						Math.max(1, height),
						segment.text(),
						segment.fontSize(),
						segment.color()));
			}
			long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
			return Map.of("result", "发送成功（六代动态区长连接）: 屏幕尺寸 "
					+ screenWidth + "x" + screenHeight + "，1列x" + rowCount
					+ "行，更新 " + rowCount + " 个动态区，耗时 " + elapsedMs + " ms");
		} catch (Exception ex) {
			return Map.of("result", "发送失败（六代动态区长连接）: " + ex.getMessage());
		}
	}

	private int positive(int value, int defaultValue) {
		return value > 0 ? value : defaultValue;
	}

	public record LedTestRequest(
		String ip,
		int port,
		String generation,
		String model,
		List<Segment> segments,
		int screenWidth,
		int screenHeight,
		int columns,
		int rows
	) {
		public record Segment(String text, int fontSize, String color) {}
	}
}
