package com.smartlane.dispatch.device.led;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.smartlane.dispatch.controller.LedTestController.LedTestRequest.Segment;

public record LedGuideDisplayFrame(Mode mode, List<Line> rows) {

	public static final int MAX_ROW_COUNT = 4;

	public LedGuideDisplayFrame {
		Objects.requireNonNull(mode, "mode must not be null");
		rows = List.copyOf(Objects.requireNonNull(rows, "rows must not be null"));
		if (rows.isEmpty() || rows.size() > MAX_ROW_COUNT) {
			throw new IllegalArgumentException("LED guide display frame must contain 1 to " + MAX_ROW_COUNT + " rows");
		}
	}

	public List<Segment> toSegments() {
		return rows.stream()
				.map(row -> new Segment(row.text(), row.fontSize(), row.color()))
				.toList();
	}

	public List<Region> toRegions(int screenWidth, int screenHeight) {
		int resolvedWidth = Math.max(1, screenWidth);
		int resolvedHeight = Math.max(1, screenHeight);
		int totalUnits = rows.stream()
				.mapToInt(Line::heightUnits)
				.sum();
		int y = 0;
		var regions = new java.util.ArrayList<Region>(rows.size());
		for (int index = 0; index < rows.size(); index++) {
			Line row = rows.get(index);
			int height = index == rows.size() - 1
					? resolvedHeight - y
					: Math.max(1, (int) Math.round((double) resolvedHeight * row.heightUnits() / totalUnits));
			regions.add(new Region(row.text(), row.fontSize(), row.color(), 0, y, resolvedWidth, Math.max(1, height)));
			y += height;
		}
		return regions;
	}

	public String payloadKey() {
		return rows.stream()
				.map(row -> row.text() + "|" + row.fontSize() + "|" + row.color() + "|" + row.heightUnits())
				.collect(Collectors.joining("\n", mode.name() + "\n", ""));
	}

	public enum Mode {
		LIST,
		HIGHLIGHT
	}

	public record Line(String text, int fontSize, String color, int heightUnits) {

		public Line(String text, int fontSize, String color) {
			this(text, fontSize, color, 1);
		}

		public Line {
			text = text == null ? "" : text;
			color = color == null || color.isBlank() ? "RED" : color;
			heightUnits = Math.max(1, heightUnits);
		}
	}

	public record Region(String text, int fontSize, String color, int x, int y, int width, int height) {}
}
