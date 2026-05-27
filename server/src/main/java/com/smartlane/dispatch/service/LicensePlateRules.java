package com.smartlane.dispatch.service;

import java.util.Locale;
import java.util.Set;

public final class LicensePlateRules {

	private static final Set<String> BLUE_PLATE_COLORS = Set.of("BLUE", "BLUEPLATE", "蓝", "蓝牌", "蓝色");

	private LicensePlateRules() {
	}

	public static boolean shouldIgnoreYardEntry(String plate, String plateColor) {
		return isBluePlateColor(plateColor) && !isBlueTaxiPlate(plate);
	}

	public static boolean isBluePlateColor(String plateColor) {
		String normalized = normalizePlateColor(plateColor);
		return normalized != null && BLUE_PLATE_COLORS.contains(normalized);
	}

	public static boolean isBlueTaxiPlate(String plate) {
		String normalized = normalizePlate(plate);
		if (normalized == null) {
			return false;
		}
		int[] codePoints = normalized.codePoints().toArray();
		return codePoints.length == 7 && codePoints[3] == 'T';
	}

	private static String normalizePlate(String plate) {
		if (plate == null) {
			return null;
		}
		return plate.replace("·", "").replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
	}

	private static String normalizePlateColor(String plateColor) {
		if (plateColor == null) {
			return null;
		}
		String normalized = plateColor.replaceAll("[\\s_-]+", "").toUpperCase(Locale.ROOT);
		return normalized.isBlank() ? null : normalized;
	}
}
