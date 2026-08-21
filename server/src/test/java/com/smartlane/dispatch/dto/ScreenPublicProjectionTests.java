package com.smartlane.dispatch.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class ScreenPublicProjectionTests {

	@Test
	void publicScreenProjectionsExcludeInternalAuditAndDeviceFields() {
		assertThat(componentNames(ScreenDispatchTicketView.class))
				.doesNotContain("source", "operator", "notes", "vehicleType");
		assertThat(componentNames(ScreenEntryLogView.class))
				.doesNotContain("source", "operator", "alarmType");
		assertThat(componentNames(ScreenLaneView.class))
				.doesNotContain("sensorStatus", "lastSensorAt", "ledMessage", "ledStatus", "priority");
	}

	private Set<String> componentNames(Class<? extends Record> recordType) {
		return Arrays.stream(recordType.getRecordComponents())
				.map(component -> component.getName())
				.collect(Collectors.toSet());
	}
}
