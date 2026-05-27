package com.smartlane.dispatch.device.led;

import java.awt.Color;
import java.awt.Font;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.smartlane.dispatch.controller.LedTestController.LedTestRequest.Segment;

import jakarta.annotation.PostConstruct;
import onbon.bx05.Bx5GEnv;
import onbon.bx05.Bx5GScreenClient;
import onbon.bx05.Bx5GScreenProfile;
import onbon.bx06.Bx6GEnv;
import onbon.bx06.Bx6GScreenClient;
import onbon.bx06.Bx6GScreenProfile;
import onbon.bx06.series.Bx6Card;
import onbon.bx06.series.Bx6E;
import onbon.bx06.series.Bx6M;
import onbon.bx06.series.Bx6Q;

/**
 * LED 显示屏控制服务，支持仰邦 BX 五代/六代控制卡。
 *
 * <p>通过 TCP 直连控制卡，发送多段文本节目。每段文本可独立设置颜色、字号，
 * 在屏幕上按垂直区域从上到下同时显示。</p>
 */
@Service
public class LedScreenService {

	private static final Logger log = LoggerFactory.getLogger(LedScreenService.class);
	private static final int MAX_FONT_SIZE = 260;

	private volatile boolean bx5Initialized = false;
	private volatile boolean bx6Initialized = false;

	@PostConstruct
	public void init() {
		try {
			Bx5GEnv.initial(30000);
			bx5Initialized = true;
			log.info("Bx5GEnv initialized");
		} catch (Exception e) {
			log.warn("Bx5GEnv initialization failed: {}", e.getMessage());
		}
		try {
			Bx6GEnv.initial(30000);
			bx6Initialized = true;
			log.info("Bx6GEnv initialized");
		} catch (Exception e) {
			log.warn("Bx6GEnv initialization failed: {}", e.getMessage());
		}
	}

	/**
	 * 发送多段文本到 LED 显示屏。
	 *
	 * @param ip         控制卡 IP
	 * @param port       控制卡端口（默认 5005）
	 * @param generation 代际：5 或 6
	 * @param model      六代型号：Bx6E / Bx6M / Bx6Q
	 * @param segments   文本段列表
	 * @return 操作结果描述
	 */
	public String sendText(String ip, int port, String generation, String model, List<Segment> segments) {
		return sendText(ip, port, generation, model, segments, 0, 0, 0, 0);
	}

	/**
	 * 发送网格布局文本到 LED 显示屏。
	 *
	 * <p>screenWidth/screenHeight 为空时使用控制卡 profile 上的尺寸；columns/rows
	 * 为空时保持旧版从上到下分段布局。</p>
	 */
	public String sendText(
			String ip,
			int port,
			String generation,
			String model,
			List<Segment> segments,
			int screenWidth,
			int screenHeight,
			int columns,
			int rows) {
		if (segments == null || segments.isEmpty()) {
			return "错误：文本内容不能为空";
		}
		if (ip == null || ip.trim().isEmpty()) {
			return "错误：IP 地址不能为空";
		}

		try {
			if ("5".equals(generation)) {
				return sendTextG5(ip, port, segments, screenWidth, screenHeight, columns, rows);
			} else {
				return sendTextG6(ip, port, model, segments, screenWidth, screenHeight, columns, rows);
			}
		} catch (Exception e) {
			log.error("LED send failed", e);
			return "发送失败: " + e.getMessage();
		}
	}

	private String sendTextG5(
			String ip,
			int port,
			List<Segment> segments,
			int screenWidth,
			int screenHeight,
			int columns,
			int rows) throws Exception {
		if (!bx5Initialized) {
			return "错误：五代 SDK 未初始化";
		}

		Bx5GScreenClient screen = new Bx5GScreenClient("LedTest");
		try {
			if (!screen.connect(ip, port)) {
				return "错误：连接显示屏失败（五代）";
			}

			var profile = resolveG5Profile(screen.getProfile(), screenWidth, screenHeight);
			var program = new onbon.bx05.file.ProgramBxFile(0, profile);
			LedLayout layout = resolveLayout(profile.getWidth(), profile.getHeight(), columns, rows, segments.size());
			int count = Math.min(segments.size(), layout.capacity());
			for (int i = 0; i < count; i++) {
				Segment segment = segments.get(i);
				Cell cell = layout.cell(i);
				var area = new onbon.bx05.area.TextCaptionBxArea(
						cell.x(), cell.y(), cell.width(), cell.height(), profile);
				var page = new onbon.bx05.area.page.TextBxPage(segment.text());
				page.setBackground(Color.BLACK);
				page.setForeground(parseColor(segment.color()));
				page.setFont(LedFonts.textFont(Font.BOLD,
						clamp(segment.fontSize(), 8, MAX_FONT_SIZE)));
				page.setHorizontalAlignment(onbon.bx05.utils.TextBinary.Alignment.CENTER);
				page.setVerticalAlignment(onbon.bx05.utils.TextBinary.Alignment.CENTER);
				page.setDisplayStyle(onbon.bx05.utils.DisplayStyleFactory.getStyle(2));
				area.addPage(page);
				program.addArea(area);
			}

			screen.writeProgram(program);
			return "发送成功（五代）: 屏幕尺寸 " + profile.getWidth()
					+ "x" + profile.getHeight() + "，布局 " + layout.columns()
					+ "列x" + layout.rows() + "行，共 " + count + " 段文本";
		} finally {
			screen.disconnect();
		}
	}

	private String sendTextG6(
			String ip,
			int port,
			String model,
			List<Segment> segments,
			int screenWidth,
			int screenHeight,
			int columns,
			int rows) throws Exception {
		if (!bx6Initialized) {
			return "错误：六代 SDK 未初始化";
		}

		String resolvedModel = normalizeG6Model(model);
		Bx6Card card = createG6Card(resolvedModel);
		Bx6GScreenClient screen = new Bx6GScreenClient("LedTest", card);
		try {
			if (!screen.connect(ip, port)) {
				return "错误：连接显示屏失败（六代）";
			}

			var profile = resolveG6Profile(screen.getProfile(), card, screenWidth, screenHeight);
			var program = new onbon.bx06.file.ProgramBxFile(0, profile);
			LedLayout layout = resolveLayout(profile.getWidth(), profile.getHeight(), columns, rows, segments.size());
			int count = Math.min(segments.size(), layout.capacity());
			for (int i = 0; i < count; i++) {
				Segment segment = segments.get(i);
				Cell cell = layout.cell(i);
				var area = new onbon.bx06.area.TextCaptionBxArea(
						cell.x(), cell.y(), cell.width(), cell.height(), profile);
				var page = new onbon.bx06.area.page.TextBxPage(segment.text());
				page.setBackground(Color.BLACK);
				page.setForeground(parseColor(segment.color()));
				page.setFont(LedFonts.textFont(Font.BOLD,
						clamp(segment.fontSize(), 8, MAX_FONT_SIZE)));
				page.setHorizontalAlignment(onbon.bx06.utils.TextBinary.Alignment.CENTER);
				page.setVerticalAlignment(onbon.bx06.utils.TextBinary.Alignment.CENTER);
				page.setDisplayStyle(onbon.bx06.utils.DisplayStyleFactory.getStyle(2));
				area.addPage(page);
				program.addArea(area);
			}

			screen.writeProgram(program);
			return "发送成功（六代 " + resolvedModel + "）: 屏幕尺寸 " + profile.getWidth()
					+ "x" + profile.getHeight() + "，布局 " + layout.columns()
					+ "列x" + layout.rows() + "行，共 " + count + " 段文本";
		} finally {
			screen.disconnect();
		}
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

	private Bx5GScreenProfile resolveG5Profile(Bx5GScreenProfile cardProfile, int screenWidth, int screenHeight) {
		return LedScreenProfiles.withSize(cardProfile, screenWidth, screenHeight);
	}

	private Bx6GScreenProfile resolveG6Profile(Bx6GScreenProfile cardProfile, Bx6Card card, int screenWidth, int screenHeight) {
		return LedScreenProfiles.withSize(cardProfile, card, screenWidth, screenHeight);
	}

	private LedLayout resolveLayout(int screenWidth, int screenHeight, int columns, int rows, int segmentCount) {
		int resolvedColumns = columns > 0 ? columns : 1;
		int resolvedRows = rows > 0 ? rows : Math.max(1, (int) Math.ceil((double) segmentCount / resolvedColumns));
		resolvedColumns = clamp(resolvedColumns, 1, 12);
		resolvedRows = clamp(resolvedRows, 1, 12);
		return new LedLayout(screenWidth, screenHeight, resolvedColumns, resolvedRows);
	}

	private int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private Bx6Card createG6Card(String model) {
		return switch (model) {
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

	private record LedLayout(int width, int height, int columns, int rows) {
		private int capacity() {
			return columns * rows;
		}

		private Cell cell(int index) {
			int column = index % columns;
			int row = index / columns;
			int cellWidth = width / columns;
			int cellHeight = height / rows;
			int x = column * cellWidth;
			int y = row * cellHeight;
			int resolvedWidth = column == columns - 1 ? width - x : cellWidth;
			int resolvedHeight = row == rows - 1 ? height - y : cellHeight;
			return new Cell(x, y, resolvedWidth, resolvedHeight);
		}
	}

	private record Cell(int x, int y, int width, int height) {}
}
