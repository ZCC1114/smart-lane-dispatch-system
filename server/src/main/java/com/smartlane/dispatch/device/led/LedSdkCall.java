package com.smartlane.dispatch.device.led;

import java.util.concurrent.Callable;

final class LedSdkCall {

	private LedSdkCall() {
	}

	static <T> T invoke(String operation, Callable<T> call) throws LedDeviceException {
		try {
			return call.call();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new LedDeviceException(operation + "被中断", ex);
		} catch (Exception ex) {
			// The vendor BX5/BX6 SDK exposes an overly broad checked-exception contract.
			// Keep it contained at this single adapter boundary.
			throw new LedDeviceException(operation + "失败", ex);
		}
	}
}
