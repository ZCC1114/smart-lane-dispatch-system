package com.smartlane.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartlane.dispatch.device.DeviceGatewayProperties;
import com.smartlane.dispatch.device.MqttDeviceGateway;
import com.smartlane.dispatch.service.LaneRuntimeStateService;

class MqttDeviceGatewaySignalFeedbackTests {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final MqttDeviceGateway gateway = new MqttDeviceGateway(
			new DeviceGatewayProperties(),
			objectMapper,
			null,
			null,
			new LaneRuntimeStateService());

	@Test
	void redRelayOnlyTreatsEnergizedAsRedAndReleasedAsGreen() throws Exception {
		assertSignal("{\"A06\":110000}", "", "A06", "RED");
		assertSignal("{\"A06\":100000}", "", "A06", "GREEN");
		assertSignal("{\"A06\":0}", "", "A06", "GREEN");
	}

	@Test
	void greenRelayOnlyKeepsLegacyMeaning() throws Exception {
		assertSignal("{\"A06\":110000}", "A06", "", "GREEN");
		assertSignal("{\"A06\":100000}", "A06", "", "RED");
	}

	private void assertSignal(String payload, String greenRelayKey, String redRelayKey, String expected) throws Exception {
		JsonNode message = objectMapper.readTree(payload);
		String signal = ReflectionTestUtils.invokeMethod(
				gateway,
				"resolveSignalFromRelayFeedback",
				message,
				greenRelayKey,
				redRelayKey);
		assertThat(signal).isEqualTo(expected);
	}
}
