package com.smartlane.dispatch.device.led;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;

@Service
public class LedGuideDynamicAreaWriter {

	private static final int DEFAULT_SCREEN_WIDTH = 192;
	private static final int DEFAULT_SCREEN_HEIGHT = 96;

	private final LedGuideDisplayProperties properties;
	private final LedGuideDynamicAreaClient client;
	private final Object monitor = new Object();

	private String lastFrameKey;
	private boolean legacyRowAreasCleared;
	private long lastConnectionVersion = -1;

	public LedGuideDynamicAreaWriter(
			LedGuideDisplayProperties properties,
			LedGuideDynamicAreaClient client) {
		this.properties = properties;
		this.client = client;
	}

	public LedGuideDisplayWriteResult write(LedGuideDisplayFrame frame, boolean forceRefresh) {
		if (!"6".equals(properties.getGeneration())) {
			return LedGuideDisplayWriteResult.failure("动态区写入仅支持六代控制卡");
		}

		synchronized (monitor) {
			long currentConnectionVersion = client.connectionVersion();
			if (currentConnectionVersion != lastConnectionVersion) {
				lastFrameKey = null;
				legacyRowAreasCleared = false;
				lastConnectionVersion = currentConnectionVersion;
			}

			try {
				List<LedGuideDynamicAreaRequest> requests = buildRequests(frame);
				LedGuideDynamicAreaRequest request = requests.getFirst();
				String cacheKey = request.cacheKey();
				int sentCount = 0;
				if (forceRefresh || !cacheKey.equals(lastFrameKey)) {
					client.write(request);
					lastFrameKey = cacheKey;
					sentCount++;
				}
				if (forceRefresh || !legacyRowAreasCleared) {
					deleteLegacyRowAreas(request);
					legacyRowAreasCleared = true;
				}
				lastConnectionVersion = client.connectionVersion();
				return LedGuideDisplayWriteResult.success("动态区发送成功，更新 " + sentCount + " 帧");
			} catch (Exception ex) {
				lastFrameKey = null;
				legacyRowAreasCleared = false;
				return LedGuideDisplayWriteResult.failure("动态区发送失败: " + ex.getMessage());
			}
		}
	}

	List<LedGuideDynamicAreaRequest> buildRequests(LedGuideDisplayFrame frame) {
		int screenWidth = positive(properties.getScreenWidth(), DEFAULT_SCREEN_WIDTH);
		int screenHeight = positive(properties.getScreenHeight(), DEFAULT_SCREEN_HEIGHT);
		int areaStartId = Math.max(0, properties.getDynamicAreaStartId());
		List<LedGuideDisplayFrame.Region> regions = frame.toRegions(screenWidth, screenHeight);
		return List.of(new LedGuideDynamicAreaRequest(
				properties.getIp(),
				properties.getPort(),
				properties.getModel(),
				screenWidth,
				screenHeight,
				areaStartId,
				0,
				0,
				screenWidth,
				screenHeight,
				"",
				8,
				properties.getColor(),
				renderFrame(regions, screenWidth, screenHeight),
				frame.payloadKey()));
	}

	private void deleteLegacyRowAreas(LedGuideDynamicAreaRequest request) throws Exception {
		int areaStartId = Math.max(0, properties.getDynamicAreaStartId());
		int[] legacyAreaIds = IntStream.range(1, LedGuideDisplayFrame.MAX_ROW_COUNT)
				.map(index -> areaStartId + index)
				.toArray();
		client.delete(request, legacyAreaIds);
	}

	private BufferedImage renderFrame(List<LedGuideDisplayFrame.Region> regions, int screenWidth, int screenHeight) {
		BufferedImage image = new BufferedImage(screenWidth, screenHeight, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setColor(Color.BLACK);
			graphics.fillRect(0, 0, screenWidth, screenHeight);
			graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			for (LedGuideDisplayFrame.Region region : regions) {
				drawCenteredText(graphics, region);
			}
		} finally {
			graphics.dispose();
		}
		return image;
	}

	private void drawCenteredText(Graphics2D graphics, LedGuideDisplayFrame.Region region) {
		String text = region.text();
		if (text == null || text.isBlank()) {
			return;
		}
		graphics.setColor(parseColor(region.color()));
		Font font = fitFont(graphics, text, region);
		graphics.setFont(font);
		FontMetrics metrics = graphics.getFontMetrics(font);
		int x = region.x() + Math.max(0, (region.width() - metrics.stringWidth(text)) / 2);
		int y = region.y() + Math.max(0, (region.height() - metrics.getHeight()) / 2) + metrics.getAscent();
		graphics.drawString(text, x, y);
	}

	private Font fitFont(Graphics2D graphics, String text, LedGuideDisplayFrame.Region region) {
		int size = Math.max(8, region.fontSize());
		while (size > 8) {
			Font font = LedFonts.textFont(Font.BOLD, size);
			FontMetrics metrics = graphics.getFontMetrics(font);
			if (metrics.stringWidth(text) <= region.width() && metrics.getHeight() <= region.height()) {
				return font;
			}
			size--;
		}
		return LedFonts.textFont(Font.BOLD, size);
	}

	private Color parseColor(String color) {
		if (color == null || color.isBlank()) {
			return Color.RED;
		}
		return switch (color.toUpperCase()) {
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

	private int positive(int value, int defaultValue) {
		return value > 0 ? value : defaultValue;
	}
}
