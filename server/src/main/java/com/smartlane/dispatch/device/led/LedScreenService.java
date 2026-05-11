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
import onbon.bx06.Bx6GEnv;
import onbon.bx06.Bx6GScreenClient;
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
		if (segments == null || segments.isEmpty()) {
			return "错误：文本内容不能为空";
		}
		if (ip == null || ip.trim().isEmpty()) {
			return "错误：IP 地址不能为空";
		}

		try {
			if ("5".equals(generation)) {
				return sendTextG5(ip, port, segments);
			} else {
				return sendTextG6(ip, port, model, segments);
			}
		} catch (Exception e) {
			log.error("LED send failed", e);
			return "发送失败: " + e.getMessage();
		}
	}

	private String sendTextG5(String ip, int port, List<Segment> segments) throws Exception {
		if (!bx5Initialized) {
			return "错误：五代 SDK 未初始化";
		}

		Bx5GScreenClient screen = new Bx5GScreenClient("LedTest");
		try {
			if (!screen.connect(ip, port)) {
				return "错误：连接显示屏失败（五代）";
			}

			var profile = screen.getProfile();
			var program = new onbon.bx05.file.ProgramBxFile(0, profile);
			int count = segments.size();
			int areaHeight = profile.getHeight() / count;
			for (int i = 0; i < count; i++) {
				Segment segment = segments.get(i);
				var area = new onbon.bx05.area.TextCaptionBxArea(
						0, i * areaHeight, profile.getWidth(), areaHeight, profile);
				var page = new onbon.bx05.area.page.TextBxPage(segment.text());
				page.setForeground(parseColor(segment.color()));
				page.setFont(new Font("宋体", Font.PLAIN,
						Math.max(8, Math.min(64, segment.fontSize()))));
				area.addPage(page);
				program.addArea(area);
			}

			screen.writeProgram(program);
			return "发送成功（五代）: 屏幕尺寸 " + profile.getWidth()
					+ "x" + profile.getHeight() + "，共 " + count + " 段文本";
		} finally {
			screen.disconnect();
		}
	}

	private String sendTextG6(String ip, int port, String model, List<Segment> segments) throws Exception {
		if (!bx6Initialized) {
			return "错误：六代 SDK 未初始化";
		}

		Bx6GScreenClient screen = createG6Screen(model);
		try {
			if (!screen.connect(ip, port)) {
				return "错误：连接显示屏失败（六代）";
			}

			var profile = screen.getProfile();
			var program = new onbon.bx06.file.ProgramBxFile(0, profile);
			int count = segments.size();
			int areaHeight = profile.getHeight() / count;
			for (int i = 0; i < count; i++) {
				Segment segment = segments.get(i);
				var area = new onbon.bx06.area.TextCaptionBxArea(
						0, i * areaHeight, profile.getWidth(), areaHeight, profile);
				var page = new onbon.bx06.area.page.TextBxPage(segment.text());
				page.setForeground(parseColor(segment.color()));
				page.setFont(new Font("宋体", Font.PLAIN,
						Math.max(8, Math.min(64, segment.fontSize()))));
				area.addPage(page);
				program.addArea(area);
			}

			screen.writeProgram(program);
			return "发送成功（六代 " + model + "）: 屏幕尺寸 " + profile.getWidth()
					+ "x" + profile.getHeight() + "，共 " + count + " 段文本";
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

	private Bx6GScreenClient createG6Screen(String model) {
		return switch (model) {
			case "Bx6M" -> new Bx6GScreenClient("LedTest", new Bx6M());
			case "Bx6Q" -> new Bx6GScreenClient("LedTest", new Bx6Q());
			default -> new Bx6GScreenClient("LedTest", new Bx6E());
		};
	}
}
