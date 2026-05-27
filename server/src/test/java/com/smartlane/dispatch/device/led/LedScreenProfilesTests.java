package com.smartlane.dispatch.device.led;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import onbon.bx05.Bx5GScreenProfile;
import onbon.bx05.series.Bx5G;
import onbon.bx06.Bx6GScreenProfile;
import onbon.bx06.series.Bx6E;

class LedScreenProfilesTests {

	@Test
	void g6SizeOverridePreservesControllerColorMatrixAndGamma() {
		Bx6GScreenProfile source = new Bx6GScreenProfile(
				160,
				64,
				new Bx6E(),
				Bx6GScreenProfile.ScreenColorType.SINGLE,
				Bx6GScreenProfile.ScreenMatrixType.RG);
		source.setGamma(2.2);

		Bx6GScreenProfile profile = LedScreenProfiles.withSize(source, new Bx6E(), 192, 96);

		assertThat(profile.getWidth()).isEqualTo(192);
		assertThat(profile.getHeight()).isEqualTo(96);
		assertThat(profile.getColorType()).isEqualTo(source.getColorType());
		assertThat(profile.getMatrixType()).isEqualTo(source.getMatrixType());
		assertThat(profile.getGamma()).isEqualTo(source.getGamma());
	}

	@Test
	void g5SizeOverridePreservesControllerColorMatrixAndGamma() {
		Bx5GScreenProfile source = new Bx5GScreenProfile(
				160,
				64,
				new Bx5G(),
				Bx5GScreenProfile.ScreenColorType.SINGLE);
		source.setMatrixType(Bx5GScreenProfile.ScreenMatrixType.RGGR);
		source.setGamma(1.8);

		Bx5GScreenProfile profile = LedScreenProfiles.withSize(source, 192, 96);

		assertThat(profile.getWidth()).isEqualTo(192);
		assertThat(profile.getHeight()).isEqualTo(96);
		assertThat(profile.getColorType()).isEqualTo(source.getColorType());
		assertThat(profile.getMatrixType()).isEqualTo(source.getMatrixType());
		assertThat(profile.getGamma()).isEqualTo(source.getGamma());
	}
}
