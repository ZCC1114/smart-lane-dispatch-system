package com.smartlane.dispatch.device.led;

public interface LedGuideDynamicAreaClient {

	long connectionVersion();

	void write(LedGuideDynamicAreaRequest request) throws Exception;

	default void delete(LedGuideDynamicAreaRequest request, int... areaIds) throws Exception {
	}

	void disconnect();
}
