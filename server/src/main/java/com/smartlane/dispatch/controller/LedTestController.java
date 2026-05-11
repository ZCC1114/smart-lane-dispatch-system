package com.smartlane.dispatch.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

	public LedTestController(LedScreenService ledScreenService) {
		this.ledScreenService = ledScreenService;
	}

	@PostMapping("/send")
	public Map<String, String> send(@RequestBody LedTestRequest request) {
		String result = ledScreenService.sendText(
			request.ip(),
			request.port(),
			request.generation(),
			request.model(),
			request.segments()
		);
		return Map.of("result", result);
	}

	public record LedTestRequest(
		String ip,
		int port,
		String generation,
		String model,
		List<Segment> segments
	) {
		public record Segment(String text, int fontSize, String color) {}
	}
}
