package com.smartlane.dispatch.device.led;

import onbon.bx05.Bx5GScreenProfile;
import onbon.bx05.series.Bx5G;
import onbon.bx06.Bx6GScreenProfile;
import onbon.bx06.series.Bx6Card;

final class LedScreenProfiles {

	private LedScreenProfiles() {
	}

	static Bx5GScreenProfile withSize(Bx5GScreenProfile source, int screenWidth, int screenHeight) {
		if (screenWidth <= 0 || screenHeight <= 0) {
			return source;
		}
		Bx5GScreenProfile profile = new Bx5GScreenProfile(screenWidth, screenHeight, new Bx5G(), source.getColorType());
		profile.setMatrixType(source.getMatrixType());
		profile.setGamma(source.getGamma());
		return profile;
	}

	static Bx6GScreenProfile withSize(Bx6GScreenProfile source, Bx6Card card, int screenWidth, int screenHeight) {
		if (screenWidth <= 0 || screenHeight <= 0) {
			return source;
		}
		Bx6GScreenProfile profile = new Bx6GScreenProfile(
				screenWidth,
				screenHeight,
				card,
				source.getColorType(),
				source.getMatrixType());
		profile.setGamma(source.getGamma());
		return profile;
	}
}
