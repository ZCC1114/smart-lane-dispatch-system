package com.smartlane.dispatch.device.led;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "app.led.guide")
public class LedGuideDisplayProperties {

	private boolean enabled = false;
	private String ip = "172.17.2.70";
	private int port = 5005;
	private String generation = "6";
	private String model = "Bx6E";
	private int screenWidth = 192;
	private int screenHeight = 96;
	private int columns = 2;
	private int rows = 6;
	private int fontSize = 15;
	private String color = "RED";
	private String promptText = "请按照车道指示进行停车等待！";
	private long highlightDurationMs = 10000;
	private WriteMode writeMode = WriteMode.DYNAMIC_WITH_PROGRAM_FALLBACK;
	private int dynamicAreaStartId = 0;
	private long fullRefreshMs = 60000;

	public enum WriteMode {
		PROGRAM,
		DYNAMIC,
		DYNAMIC_WITH_PROGRAM_FALLBACK
	}
}
