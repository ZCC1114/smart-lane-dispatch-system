package com.smartlane.dispatch.device.led;

public class LedDeviceException extends Exception {

	private static final long serialVersionUID = 1L;

	public LedDeviceException(String message) {
		super(message);
	}

	public LedDeviceException(String message, Throwable cause) {
		super(message, cause);
	}
}
