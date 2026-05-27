package com.smartlane.dispatch.device.led;

import java.awt.Color;
import java.awt.Font;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import onbon.bx06.Bx6GEnv;
import onbon.bx06.Bx6GScreen.Result;
import onbon.bx06.Bx6GScreenClient;
import onbon.bx06.Bx6GScreenProfile;
import onbon.bx06.area.DynamicBxArea;
import onbon.bx06.area.page.TextBxPage;
import onbon.bx06.cmd.dyn.DynamicBxAreaRule;
import onbon.bx06.message.global.ACK;
import onbon.bx06.series.Bx6Card;
import onbon.bx06.series.Bx6E;
import onbon.bx06.series.Bx6M;
import onbon.bx06.series.Bx6Q;

@Component
public class Bx6LedGuideDynamicAreaClient implements LedGuideDynamicAreaClient {

	private static final Logger log = LoggerFactory.getLogger(Bx6LedGuideDynamicAreaClient.class);
	private static final int MAX_FONT_SIZE = 260;

	private final Object monitor = new Object();

	private Bx6GScreenClient screen;
	private Bx6Card card;
	private String connectionKey;
	private long connectionVersion = 0;
	private boolean initialized = false;

	public Bx6LedGuideDynamicAreaClient() {
		try {
			if (!Bx6GEnv.isLoaded()) {
				Bx6GEnv.initial(30000);
				log.info("Bx6GEnv initialized for LED guide dynamic area");
			}
			initialized = true;
		} catch (Exception e) {
			log.warn("Bx6GEnv initialization for LED guide dynamic area failed: {}", e.getMessage());
		}
	}

	@Override
	public long connectionVersion() {
		synchronized (monitor) {
			return connectionVersion;
		}
	}

	@Override
	public void write(LedGuideDynamicAreaRequest request) throws Exception {
		long startNanos = System.nanoTime();
		synchronized (monitor) {
			ensureConnected(request);
			try {
				writeOnce(request);
			} catch (Exception firstFailure) {
				log.warn("LED guide dynamic area write failed, reconnecting once: {}", firstFailure.getMessage());
				closeConnectedScreen();
				ensureConnected(request);
				writeOnce(request);
			}
		}
		long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
		log.info("LED guide dynamic area {} written in {} ms", request.areaId(), elapsedMs);
	}

	@Override
	@PreDestroy
	public void disconnect() {
		synchronized (monitor) {
			closeConnectedScreen();
		}
	}

	private void ensureConnected(LedGuideDynamicAreaRequest request) throws Exception {
		if (!initialized) {
			throw new IllegalStateException("六代 LED SDK 未初始化");
		}

		String nextConnectionKey = request.ip() + ":" + request.port() + ":" + normalizeG6Model(request.model());
		if (screen != null && nextConnectionKey.equals(connectionKey) && screen.isConnected()) {
			return;
		}

		closeConnectedScreen();
		card = createG6Card(request.model());
		screen = new Bx6GScreenClient("LedGuideDynamic", card);
		if (!screen.connect(request.ip(), request.port())) {
			closeConnectedScreen();
			throw new IOException("连接显示屏失败（六代动态区）");
		}
		connectionKey = nextConnectionKey;
		connectionVersion++;
		log.info("LED guide dynamic area connected to {}", nextConnectionKey);
	}

	private void writeOnce(LedGuideDynamicAreaRequest request) throws IOException {
		Bx6GScreenProfile profile = LedScreenProfiles.withSize(screen.getProfile(), card, request.screenWidth(), request.screenHeight());
		DynamicBxAreaRule rule = new DynamicBxAreaRule();
		rule.setId(request.areaId());
		rule.setImmediatePlay((byte) 1);
		rule.setRunMode((byte) 1);
		rule.setTimeout(0);

		DynamicBxArea area = new DynamicBxArea(request.x(), request.y(), request.width(), request.height(), profile);
		TextBxPage page = new TextBxPage(displayText(request.text()));
		page.setBackground(Color.BLACK);
		page.setForeground(parseColor(request.color()));
		page.setFont(LedFonts.textFont(Font.BOLD, clamp(request.fontSize(), 8, MAX_FONT_SIZE)));
		page.setHorizontalAlignment(onbon.bx06.utils.TextBinary.Alignment.CENTER);
		page.setVerticalAlignment(onbon.bx06.utils.TextBinary.Alignment.CENTER);
		page.setDisplayStyle(onbon.bx06.utils.DisplayStyleFactory.getStyle(2));
		area.addPage(page);

		Result<ACK> result = screen.writeDynamic(rule, area);
		if (result == null || !result.isOK()) {
			throw new IOException("动态区写入未返回成功: " + result);
		}
	}

	private void closeConnectedScreen() {
		if (screen != null) {
			try {
				screen.disconnect();
			} catch (Exception e) {
				log.debug("LED guide dynamic area disconnect ignored: {}", e.getMessage());
			}
		}
		screen = null;
		card = null;
		connectionKey = null;
	}

	private String displayText(String text) {
		return text == null || text.isBlank() ? " " : text;
	}

	private Color parseColor(String color) {
		if (color == null || color.isBlank()) {
			return Color.RED;
		}
		return switch (color.toUpperCase()) {
			case "RED" -> Color.RED;
			case "GREEN" -> Color.GREEN;
			case "BLUE" -> Color.BLUE;
			case "YELLOW" -> Color.YELLOW;
			case "CYAN" -> Color.CYAN;
			case "MAGENTA" -> Color.MAGENTA;
			case "WHITE" -> Color.WHITE;
			case "BLACK" -> Color.BLACK;
			case "ORANGE" -> Color.ORANGE;
			case "PINK" -> Color.PINK;
			default -> Color.RED;
		};
	}

	private int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private Bx6Card createG6Card(String model) {
		return switch (normalizeG6Model(model)) {
			case "Bx6M" -> new Bx6M();
			case "Bx6Q" -> new Bx6Q();
			default -> new Bx6E();
		};
	}

	private String normalizeG6Model(String model) {
		if ("Bx6M".equals(model) || "Bx6Q".equals(model)) {
			return model;
		}
		return "Bx6E";
	}
}
