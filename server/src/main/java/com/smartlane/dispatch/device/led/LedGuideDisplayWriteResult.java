package com.smartlane.dispatch.device.led;

public record LedGuideDisplayWriteResult(boolean success, String message) {

	public static LedGuideDisplayWriteResult success(String message) {
		return new LedGuideDisplayWriteResult(true, message);
	}

	public static LedGuideDisplayWriteResult failure(String message) {
		return new LedGuideDisplayWriteResult(false, message);
	}
}
