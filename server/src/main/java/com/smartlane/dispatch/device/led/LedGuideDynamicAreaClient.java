package com.smartlane.dispatch.device.led;

public interface LedGuideDynamicAreaClient {

	long connectionVersion();

	void write(LedGuideDynamicAreaRequest request) throws LedDeviceException;

	default void delete(LedGuideDynamicAreaRequest request, int... areaIds) throws LedDeviceException {
	}

	void disconnect();
}
