package com.smartlane.dispatch.device.led;

import java.awt.image.BufferedImage;

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
		String color,
		BufferedImage image,
		String imageKey) {

	public LedGuideDynamicAreaRequest(
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
		this(ip, port, model, screenWidth, screenHeight, areaId, x, y, width, height, text, fontSize, color, null, "");
	}

	public boolean hasImage() {
		return image != null;
	}

	public String cacheKey() {
		return ip + ":" + port
				+ "|" + model
				+ "|" + screenWidth + "x" + screenHeight
				+ "|" + areaId
				+ "|" + x + "," + y + "," + width + "," + height
				+ "|" + text
				+ "|" + fontSize
				+ "|" + color
				+ "|" + imageKey;
	}
}
