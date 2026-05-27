package com.smartlane.dispatch.device.led;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

final class LedFonts {

	private static final String[] PREFERRED_FAMILIES = {
			"Noto Sans CJK SC",
			"Noto Sans CJK",
			"Source Han Sans SC",
			"WenQuanYi Zen Hei",
			"Microsoft YaHei",
			"SimHei",
			"SimSun",
			"Dialog"
	};

	private static volatile String resolvedFamily;

	private LedFonts() {
	}

	static Font textFont(int style, int size) {
		return new Font(resolveFamily(), style, size);
	}

	private static String resolveFamily() {
		String family = resolvedFamily;
		if (family != null) {
			return family;
		}
		Set<String> available = Set.of(GraphicsEnvironment
				.getLocalGraphicsEnvironment()
				.getAvailableFontFamilyNames(Locale.SIMPLIFIED_CHINESE));
		family = Arrays.stream(PREFERRED_FAMILIES)
				.filter(available::contains)
				.findFirst()
				.orElse(Font.SANS_SERIF);
		resolvedFamily = family;
		return family;
	}
}
