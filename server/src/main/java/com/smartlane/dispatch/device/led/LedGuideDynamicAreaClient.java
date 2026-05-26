package com.smartlane.dispatch.device.led;

public interface LedGuideDynamicAreaClient {

	long connectionVersion();

	void write(LedGuideDynamicAreaRequest request) throws Exception;

	void disconnect();
}
