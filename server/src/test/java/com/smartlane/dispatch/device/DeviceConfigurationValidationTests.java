package com.smartlane.dispatch.device;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.smartlane.dispatch.device.led.LedGuideDisplayProperties;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class DeviceConfigurationValidationTests {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void mockGatewayDoesNotRequireDeviceCredentials() {
		DeviceGatewayProperties properties = new DeviceGatewayProperties();

		assertThat(validator.validate(properties)).isEmpty();
	}

	@Test
	void mqttGatewayRequiresExplicitConnectionConfiguration() {
		DeviceGatewayProperties properties = new DeviceGatewayProperties();
		properties.setGateway("mqtt");
		properties.getMqtt().setEnabled(true);

		assertThat(validator.validate(properties))
				.extracting(violation -> violation.getPropertyPath().toString())
				.contains("mqttConnectionConfigured");

		properties.getMqtt().setHost("broker.internal");
		properties.getMqtt().setUsername("mqtt-user");
		properties.getMqtt().setPassword("injected-secret");

		assertThat(validator.validate(properties)).isEmpty();
	}

	@Test
	void tcpDidoGatewayRequiresAnExplicitDeviceAddress() {
		DeviceGatewayProperties properties = new DeviceGatewayProperties();
		properties.setGateway("tcp-dido");

		assertThat(validator.validate(properties))
				.extracting(violation -> violation.getPropertyPath().toString())
				.contains("didoTcpConnectionConfigured");

		properties.getDidoTcp().setHost("dido.internal");

		assertThat(validator.validate(properties)).isEmpty();
	}

	@Test
	void enabledLedGuideRequiresAnExplicitDeviceAddress() {
		LedGuideDisplayProperties properties = new LedGuideDisplayProperties();
		properties.setEnabled(true);

		assertThat(validator.validate(properties))
				.extracting(violation -> violation.getPropertyPath().toString())
				.contains("deviceAddressConfigured");

		properties.setIp("led.internal");

		assertThat(validator.validate(properties)).isEmpty();
	}
}
