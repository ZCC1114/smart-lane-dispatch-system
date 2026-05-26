package com.smartlane.dispatch.device.led;

public record LedGuideDynamicAreaRequest(
		String ip,
		int port,
		String model,
		int screenWidth,
		int screenHeight,
		int areaId,
		int x,
		int y,
		int width,
		int height,
		String text,
		int fontSize,
		String color) {

	public String cacheKey() {
		return ip + ":" + port
				+ "|" + model
				+ "|" + screenWidth + "x" + screenHeight
				+ "|" + areaId
				+ "|" + x + "," + y + "," + width + "," + height
				+ "|" + text
				+ "|" + fontSize
				+ "|" + color;
	}
}
