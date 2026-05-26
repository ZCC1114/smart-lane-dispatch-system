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
		String result = ledScreenService.sendText(
				properties.getIp(),
				properties.getPort(),
				properties.getGeneration(),
				properties.getModel(),
				frame.toSegments(),
				properties.getScreenWidth(),
				properties.getScreenHeight(),
				1,
				LedGuideDisplayFrame.ROW_COUNT);
		if (result.startsWith("发送成功")) {
			return LedGuideDisplayWriteResult.success("完整节目发送成功: " + result);
		}
		return LedGuideDisplayWriteResult.failure("完整节目发送失败: " + result);
	}
}
