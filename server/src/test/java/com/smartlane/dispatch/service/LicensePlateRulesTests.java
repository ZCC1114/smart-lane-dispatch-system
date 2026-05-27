package com.smartlane.dispatch.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LicensePlateRulesTests {

	@Test
	void blueTaxiPlateRequiresTInFourthPosition() {
		assertThat(LicensePlateRules.isBlueTaxiPlate("苏B3T530")).isTrue();
		assertThat(LicensePlateRules.isBlueTaxiPlate("苏B9T113")).isTrue();
		assertThat(LicensePlateRules.isBlueTaxiPlate("苏B3R89T")).isFalse();
		assertThat(LicensePlateRules.isBlueTaxiPlate("苏BD12345")).isFalse();
	}

	@Test
	void onlyExplicitBluePlateColorTriggersYardEntryFilter() {
		assertThat(LicensePlateRules.shouldIgnoreYardEntry("苏B3R89T", "BLUE")).isTrue();
		assertThat(LicensePlateRules.shouldIgnoreYardEntry("苏B3R89T", "蓝牌")).isTrue();
		assertThat(LicensePlateRules.shouldIgnoreYardEntry("苏B3T530", "BLUE")).isFalse();
		assertThat(LicensePlateRules.shouldIgnoreYardEntry("苏BD12345", "GREEN")).isFalse();
		assertThat(LicensePlateRules.shouldIgnoreYardEntry("苏B3R89T", null)).isFalse();
	}
}
