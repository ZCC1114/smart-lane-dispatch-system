package com.smartlane.dispatch.device.led;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.smartlane.dispatch.device.led.LedGuideDisplayProperties.WriteMode;

@Service
public class LedGuideDisplayWriter {

	private static final Logger log = LoggerFactory.getLogger(LedGuideDisplayWriter.class);

	private final LedGuideDisplayProperties properties;
	private final LedGuideDynamicAreaWriter dynamicAreaWriter;
	private final LedGuideProgramDisplayWriter programDisplayWriter;

	public LedGuideDisplayWriter(
			LedGuideDisplayProperties properties,
			LedGuideDynamicAreaWriter dynamicAreaWriter,
			LedGuideProgramDisplayWriter programDisplayWriter) {
		this.properties = properties;
		this.dynamicAreaWriter = dynamicAreaWriter;
		this.programDisplayWriter = programDisplayWriter;
	}

	public LedGuideDisplayWriteResult write(LedGuideDisplayFrame frame, boolean forceRefresh) {
		WriteMode writeMode = properties.getWriteMode();
		return switch (writeMode) {
			case PROGRAM -> programDisplayWriter.write(frame);
			case DYNAMIC -> dynamicAreaWriter.write(frame, forceRefresh);
			case DYNAMIC_WITH_PROGRAM_FALLBACK -> writeDynamicWithFallback(frame, forceRefresh);
		};
	}

	private LedGuideDisplayWriteResult writeDynamicWithFallback(LedGuideDisplayFrame frame, boolean forceRefresh) {
		LedGuideDisplayWriteResult dynamicResult = dynamicAreaWriter.write(frame, forceRefresh);
		if (dynamicResult.success()) {
			return dynamicResult;
		}

		log.warn("LED guide dynamic area write failed, fallback to full program: {}", dynamicResult.message());
		LedGuideDisplayWriteResult fallbackResult = programDisplayWriter.write(frame);
		if (fallbackResult.success()) {
			return LedGuideDisplayWriteResult.success(dynamicResult.message()
					+ "；已回退完整节目: " + fallbackResult.message());
		}
		return LedGuideDisplayWriteResult.failure(dynamicResult.message()
				+ "；回退完整节目也失败: " + fallbackResult.message());
	}
}
