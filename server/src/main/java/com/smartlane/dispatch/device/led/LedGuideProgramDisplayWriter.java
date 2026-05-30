package com.smartlane.dispatch.device.led;

import org.springframework.stereotype.Service;

@Service
public class LedGuideProgramDisplayWriter {

	private final LedGuideDisplayProperties properties;
	private final LedScreenService ledScreenService;

	public LedGuideProgramDisplayWriter(
			LedGuideDisplayProperties properties,
			LedScreenService ledScreenService) {
		this.properties = properties;
		this.ledScreenService = ledScreenService;
	}

	public LedGuideDisplayWriteResult write(LedGuideDisplayFrame frame) {
		int screenWidth = positive(properties.getScreenWidth(), 192);
		int screenHeight = positive(properties.getScreenHeight(), 96);
		String result = ledScreenService.sendTextRegions(
				properties.getIp(),
				properties.getPort(),
				properties.getGeneration(),
				properties.getModel(),
				frame.toRegions(screenWidth, screenHeight),
				screenWidth,
				screenHeight);
		if (result.startsWith("发送成功")) {
			return LedGuideDisplayWriteResult.success("完整节目发送成功: " + result);
		}
		return LedGuideDisplayWriteResult.failure("完整节目发送失败: " + result);
	}

	private int positive(int value, int defaultValue) {
		return value > 0 ? value : defaultValue;
	}
}
