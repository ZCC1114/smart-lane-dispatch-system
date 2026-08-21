package com.smartlane.dispatch.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class SimpleMqttClientTests {

	@Test
	void decodesValidMultiByteRemainingLength() throws Exception {
		int length = SimpleMqttClient.readRemainingLength(
				new ByteArrayInputStream(new byte[] {(byte) 0x80, 0x01}),
				1024);

		assertThat(length).isEqualTo(128);
	}

	@Test
	void rejectsRemainingLengthThatUsesMoreThanFourBytes() {
		ByteArrayInputStream input = new ByteArrayInputStream(new byte[] {
				(byte) 0x80,
				(byte) 0x80,
				(byte) 0x80,
				(byte) 0x80,
				0x00
		});

		assertThatIOException()
				.isThrownBy(() -> SimpleMqttClient.readRemainingLength(
						input,
						SimpleMqttClient.HARD_MAX_PACKET_BYTES))
				.withMessageContaining("exceeds four bytes");
	}

	@Test
	void rejectsPacketLengthAboveConfiguredMaximumBeforeReadingBody() {
		ByteArrayInputStream input = new ByteArrayInputStream(new byte[] {(byte) 0x81, 0x01});

		assertThatIOException()
				.isThrownBy(() -> SimpleMqttClient.readRemainingLength(input, 128))
				.withMessageContaining("configured maximum");
		assertThatIOException()
				.isThrownBy(() -> SimpleMqttClient.readFully(new ByteArrayInputStream(new byte[0]), 129, 128))
				.withMessageContaining("body length");
	}

	@Test
	void rejectsPublishWithTopicLengthOutsidePacketBody() {
		SimpleMqttClient client = client((topic, payload) -> {});

		assertThatIOException()
				.isThrownBy(() -> client.handlePacket(0x30, new byte[] {0x00, 0x04, 'a'}))
				.withMessageContaining("invalid topic length");
	}

	@Test
	void rejectsPacketBodyAboveClientMaximum() {
		SimpleMqttClient client = client((topic, payload) -> {});

		assertThatIOException()
				.isThrownBy(() -> client.handlePacket(0x30, new byte[1025]))
				.withMessageContaining("configured maximum");
	}

	@Test
	void rejectsPublishWithMalformedUtf8Topic() {
		SimpleMqttClient client = client((topic, payload) -> {});

		assertThatIOException()
				.isThrownBy(() -> client.handlePacket(0x30, new byte[] {0x00, 0x01, (byte) 0xC3}))
				.withMessageContaining("Malformed MQTT UTF-8 topic");
	}

	@Test
	void deliversQosZeroPayloadAndRejectsUnsupportedQos() throws Exception {
		AtomicReference<String> receivedTopic = new AtomicReference<>();
		AtomicReference<byte[]> receivedPayload = new AtomicReference<>();
		SimpleMqttClient client = client((topic, payload) -> {
			receivedTopic.set(topic);
			receivedPayload.set(payload);
		});

		client.handlePacket(0x30, new byte[] {0x00, 0x01, 't', 'o', 'k'});

		assertThat(receivedTopic.get()).isEqualTo("t");
		assertThat(receivedPayload.get()).containsExactly('o', 'k');
		assertThatIOException()
				.isThrownBy(() -> client.handlePacket(0x32, new byte[] {0x00, 0x01, 't', 0x00, 0x01}))
				.withMessageContaining("Unsupported MQTT PUBLISH QoS");
	}

	@Test
	void isolatesListenerRuntimeFailureAtMessageBoundary() throws Exception {
		AtomicInteger deliveries = new AtomicInteger();
		SimpleMqttClient client = client((topic, payload) -> {
			if (deliveries.incrementAndGet() == 1) {
				throw new IllegalArgumentException("invalid device message");
			}
		});

		client.handlePacket(0x30, new byte[] {0x00, 0x01, 't', 'o', 'k'});
		client.handlePacket(0x30, new byte[] {0x00, 0x01, 't', 'o', 'k'});

		assertThat(deliveries).hasValue(2);
	}

	@Test
	void rejectsInvalidConfiguredPacketMaximum() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new SimpleMqttClient(
						"localhost",
						1883,
						"test-client",
						null,
						null,
						30,
						(topic, payload) -> {},
						Runnable::run,
						SimpleMqttClient.HARD_MAX_PACKET_BYTES + 1));
	}

	private SimpleMqttClient client(SimpleMqttClient.MessageListener listener) {
		return new SimpleMqttClient(
				"localhost",
				1883,
				"test-client",
				null,
				null,
				30,
				listener,
				Runnable::run,
				1024);
	}
}
