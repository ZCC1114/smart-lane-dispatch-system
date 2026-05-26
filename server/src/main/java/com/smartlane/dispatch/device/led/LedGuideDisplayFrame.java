package com.smartlane.dispatch.device.led;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.smartlane.dispatch.controller.LedTestController.LedTestRequest.Segment;

public record LedGuideDisplayFrame(Mode mode, List<Line> rows) {

	public static final int ROW_COUNT = 4;

	public LedGuideDisplayFrame {
		Objects.requireNonNull(mode, "mode must not be null");
		rows = List.copyOf(Objects.requireNonNull(rows, "rows must not be null"));
		if (rows.size() != ROW_COUNT) {
			throw new IllegalArgumentException("LED guide display frame must contain exactly " + ROW_COUNT + " rows");
		}
	}

	public List<Segment> toSegments() {
		return rows.stream()
				.map(row -> new Segment(row.text(), row.fontSize(), row.color()))
				.toList();
	}

	public String payloadKey() {
		return rows.stream()
				.map(row -> row.text() + "|" + row.fontSize() + "|" + row.color())
				.collect(Collectors.joining("\n", mode.name() + "\n", ""));
	}

	public enum Mode {
		LIST,
		HIGHLIGHT
	}

	public record Line(String text, int fontSize, String color) {

		public Line {
			text = text == null ? "" : text;
			color = color == null || color.isBlank() ? "RED" : color;
		}
	}
}
