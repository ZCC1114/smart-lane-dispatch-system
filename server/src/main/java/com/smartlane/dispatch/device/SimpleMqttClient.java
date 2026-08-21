package com.smartlane.dispatch.device;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimpleMqttClient {

	private static final Logger log = LoggerFactory.getLogger(SimpleMqttClient.class);

	static final int DEFAULT_MAX_PACKET_BYTES = 1024 * 1024;
	static final int HARD_MAX_PACKET_BYTES = 8 * 1024 * 1024;
	static final int MQTT_MAX_REMAINING_LENGTH = 268_435_455;
	static final int MQTT_MAX_REMAINING_LENGTH_BYTES = 4;
	static final int MQTT_MAX_UTF8_BYTES = 65_535;

	@FunctionalInterface
	public interface MessageListener {
		void onMessage(String topic, byte[] payload);
	}

	private final String host;
	private final int port;
	private final String clientId;
	private final String username;
	private final String password;
	private final int keepAliveSeconds;
	private final MessageListener messageListener;
	private final Executor readerExecutor;
	private final int maxPacketBytes;
	private final AtomicInteger packetCounter = new AtomicInteger(1);
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicBoolean connected = new AtomicBoolean(false);

	private volatile long lastOutboundAt;
	private Socket socket;
	private BufferedInputStream input;
	private BufferedOutputStream output;

	public SimpleMqttClient(
			String host,
			int port,
			String clientId,
			String username,
			String password,
			int keepAliveSeconds,
			MessageListener messageListener,
			Executor readerExecutor,
			int maxPacketBytes) {
		validateMaxPacketBytes(maxPacketBytes);
		this.host = host;
		this.port = port;
		this.clientId = clientId;
		this.username = username;
		this.password = password;
		this.keepAliveSeconds = keepAliveSeconds;
		this.messageListener = Objects.requireNonNull(messageListener, "messageListener");
		this.readerExecutor = Objects.requireNonNull(readerExecutor, "readerExecutor");
		this.maxPacketBytes = maxPacketBytes;
	}

	public synchronized void connect() throws IOException {
		if (connected.get()) {
			return;
		}

		boolean readerStarted = false;
		try {
			socket = new Socket();
			socket.connect(new InetSocketAddress(host, port), 5000);
			socket.setSoTimeout(1000);
			input = new BufferedInputStream(socket.getInputStream());
			output = new BufferedOutputStream(socket.getOutputStream());

			sendConnect();
			readConnAck();
			running.set(true);
			connected.set(true);
			lastOutboundAt = System.currentTimeMillis();
			readerExecutor.execute(this::readLoop);
			readerStarted = true;
		} finally {
			if (!readerStarted) {
				running.set(false);
				connected.set(false);
				closeQuietly();
			}
		}
	}

	public synchronized void disconnect() {
		running.set(false);
		connected.set(false);
		try {
			sendFixedPacket(0xE0, new byte[0]);
		} catch (IOException ignored) {
			// ignore disconnect failures during shutdown
		}
		closeQuietly();
	}

	public boolean isConnected() {
		return connected.get();
	}

	public synchronized void subscribe(String topicFilter) throws IOException {
		ensureConnected();
		byte[] topicBytes = encodeMqttUtf8(topicFilter, "topic filter", true);
		ensurePacketLength(2L + 2 + topicBytes.length + 1);
		byte[] payload = new byte[2 + 2 + topicBytes.length + 1];
		int packetId = nextPacketId();
		payload[0] = (byte) ((packetId >> 8) & 0xFF);
		payload[1] = (byte) (packetId & 0xFF);
		payload[2] = (byte) ((topicBytes.length >> 8) & 0xFF);
		payload[3] = (byte) (topicBytes.length & 0xFF);
		System.arraycopy(topicBytes, 0, payload, 4, topicBytes.length);
		payload[payload.length - 1] = 0;
		sendFixedPacket(0x82, payload);
	}

	public synchronized void publish(String topic, String payload) throws IOException {
		publish(topic, payload.getBytes(StandardCharsets.UTF_8));
	}

	public synchronized void publish(String topic, byte[] payloadBytes) throws IOException {
		ensureConnected();
		Objects.requireNonNull(payloadBytes, "payloadBytes");
		byte[] topicBytes = encodeMqttUtf8(topic, "topic", true);
		ensurePacketLength(2L + topicBytes.length + payloadBytes.length);
		byte[] packet = new byte[2 + topicBytes.length + payloadBytes.length];
		packet[0] = (byte) ((topicBytes.length >> 8) & 0xFF);
		packet[1] = (byte) (topicBytes.length & 0xFF);
		System.arraycopy(topicBytes, 0, packet, 2, topicBytes.length);
		System.arraycopy(payloadBytes, 0, packet, 2 + topicBytes.length, payloadBytes.length);
		sendFixedPacket(0x30, packet);
	}

	private void readLoop() {
		try {
			while (running.get()) {
				try {
					int fixedHeader = input.read();
					if (fixedHeader < 0) {
						throw new EOFException("MQTT connection closed by remote peer");
					}
					int remainingLength = readRemainingLength(input, maxPacketBytes);
					byte[] body = readFully(input, remainingLength, maxPacketBytes);
					handlePacket(fixedHeader, body);
				} catch (SocketTimeoutException ignored) {
					maybePing();
				}
			}
		} catch (IOException ex) {
			if (running.get()) {
				log.warn("MQTT reader stopped for client {}: {}", clientId, ex.getMessage());
			}
		} finally {
			connected.set(false);
			running.set(false);
			closeQuietly();
		}
	}

	void handlePacket(int fixedHeader, byte[] body) throws IOException {
		Objects.requireNonNull(body, "body");
		if (body.length > maxPacketBytes) {
			throw new IOException("MQTT packet exceeds configured maximum of " + maxPacketBytes + " bytes");
		}
		int packetType = (fixedHeader >> 4) & 0x0F;
		if (packetType == 3) {
			if (body.length < 2) {
				throw new IOException("Malformed MQTT PUBLISH packet: missing topic length");
			}
			int topicLength = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
			if (topicLength == 0 || topicLength > MQTT_MAX_UTF8_BYTES || topicLength > body.length - 2) {
				throw new IOException("Malformed MQTT PUBLISH packet: invalid topic length " + topicLength);
			}
			String topic = decodeMqttUtf8(body, 2, topicLength, "topic", true);
			int qos = (fixedHeader >> 1) & 0x03;
			if (qos != 0) {
				throw new IOException("Unsupported MQTT PUBLISH QoS: " + qos);
			}
			int payloadOffset = 2 + topicLength;
			if (body.length - payloadOffset > maxPacketBytes) {
				throw new IOException("MQTT PUBLISH payload exceeds configured maximum");
			}
			byte[] payload = Arrays.copyOfRange(body, payloadOffset, body.length);
			deliverMessage(topic, payload);
		}
	}

	private void deliverMessage(String topic, byte[] payload) {
		try {
			messageListener.onMessage(topic, payload);
		} catch (RuntimeException ex) {
			// A malformed or unexpected device message must not terminate the shared MQTT connection.
			log.warn(
					"MQTT message listener rejected a message on topic {}; message discarded; failureType={}",
					topic,
					ex.getClass().getSimpleName());
		}
	}

	private void maybePing() throws IOException {
		if (!connected.get()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastOutboundAt >= Math.max(1, keepAliveSeconds / 2) * 1000L) {
			sendFixedPacket(0xC0, new byte[0]);
		}
	}

	private void sendConnect() throws IOException {
		byte connectFlags = 0x02;
		if (username != null && !username.isBlank()) {
			connectFlags |= (byte) 0x80;
		}
		if (password != null && !password.isBlank()) {
			connectFlags |= 0x40;
		}

		byte[] protocolName = encodeString("MQTT", "protocol name", false);
		byte[] clientIdBytes = encodeString(clientId, "client id", false);
		byte[] usernameBytes = username != null && !username.isBlank()
				? encodeString(username, "username", false)
				: new byte[0];
		byte[] passwordBytes = password != null && !password.isBlank()
				? encodeString(password, "password", false)
				: new byte[0];
		ensurePacketLength((long) protocolName.length + 4 + clientIdBytes.length + usernameBytes.length + passwordBytes.length);
		byte[] payload = new byte[protocolName.length + 4 + clientIdBytes.length + usernameBytes.length + passwordBytes.length];
		int cursor = 0;
		System.arraycopy(protocolName, 0, payload, cursor, protocolName.length);
		cursor += protocolName.length;
		payload[cursor++] = 0x04;
		payload[cursor++] = connectFlags;
		payload[cursor++] = (byte) ((keepAliveSeconds >> 8) & 0xFF);
		payload[cursor++] = (byte) (keepAliveSeconds & 0xFF);
		System.arraycopy(clientIdBytes, 0, payload, cursor, clientIdBytes.length);
		cursor += clientIdBytes.length;
		if (usernameBytes.length > 0) {
			System.arraycopy(usernameBytes, 0, payload, cursor, usernameBytes.length);
			cursor += usernameBytes.length;
		}
		if (passwordBytes.length > 0) {
			System.arraycopy(passwordBytes, 0, payload, cursor, passwordBytes.length);
		}
		sendFixedPacket(0x10, payload);
	}

	private void readConnAck() throws IOException {
		int fixedHeader = input.read();
		if (fixedHeader < 0) {
			throw new EOFException("No CONNACK received");
		}
		int remainingLength = readRemainingLength(input, maxPacketBytes);
		byte[] body = readFully(input, remainingLength, maxPacketBytes);
		if (((fixedHeader >> 4) & 0x0F) != 2 || body.length != 2 || body[1] != 0) {
			throw new IOException("MQTT CONNACK failed, return code=" + (body.length > 1 ? body[1] : -1));
		}
	}

	private synchronized void sendFixedPacket(int header, byte[] body) throws IOException {
		if (output == null) {
			throw new IOException("MQTT output stream unavailable");
		}
		ensurePacketLength(body.length);
		output.write(header);
		writeRemainingLength(output, body.length);
		output.write(body);
		output.flush();
		lastOutboundAt = System.currentTimeMillis();
	}

	private void ensureConnected() throws IOException {
		if (!connected.get()) {
			throw new IOException("MQTT client is not connected");
		}
	}

	private int nextPacketId() {
		return packetCounter.updateAndGet(current -> current >= 0xFFFF ? 1 : current + 1);
	}

	private byte[] encodeString(String value, String fieldName, boolean topicName) throws IOException {
		byte[] bytes = encodeMqttUtf8(value, fieldName, topicName);
		byte[] encoded = new byte[2 + bytes.length];
		encoded[0] = (byte) ((bytes.length >> 8) & 0xFF);
		encoded[1] = (byte) (bytes.length & 0xFF);
		System.arraycopy(bytes, 0, encoded, 2, bytes.length);
		return encoded;
	}

	static int readRemainingLength(InputStream input, int maxPacketBytes) throws IOException {
		validateMaxPacketBytes(maxPacketBytes);
		int multiplier = 1;
		int value = 0;
		for (int byteIndex = 0; byteIndex < MQTT_MAX_REMAINING_LENGTH_BYTES; byteIndex++) {
			int encodedByte = input.read();
			if (encodedByte < 0) {
				throw new EOFException("Unexpected EOF while reading MQTT remaining length");
			}
			value += (encodedByte & 127) * multiplier;
			if (value > maxPacketBytes) {
				throw new IOException("MQTT packet exceeds configured maximum of " + maxPacketBytes + " bytes");
			}
			if ((encodedByte & 128) == 0) {
				return value;
			}
			multiplier *= 128;
		}
		throw new IOException("Malformed MQTT remaining length: exceeds four bytes");
	}

	static byte[] readFully(InputStream input, int length, int maxPacketBytes) throws IOException {
		validateMaxPacketBytes(maxPacketBytes);
		if (length < 0 || length > maxPacketBytes) {
			throw new IOException("Invalid MQTT packet body length: " + length);
		}
		byte[] buffer = input.readNBytes(length);
		if (buffer.length != length) {
			throw new EOFException("Unexpected EOF while reading MQTT packet body");
		}
		return buffer;
	}

	private static void writeRemainingLength(BufferedOutputStream output, int length) throws IOException {
		if (length < 0 || length > MQTT_MAX_REMAINING_LENGTH) {
			throw new IOException("Invalid MQTT remaining length: " + length);
		}
		int value = length;
		do {
			int encodedByte = value % 128;
			value /= 128;
			if (value > 0) {
				encodedByte |= 0x80;
			}
			output.write(encodedByte);
		} while (value > 0);
	}

	private void ensurePacketLength(long length) throws IOException {
		if (length < 0 || length > maxPacketBytes || length > MQTT_MAX_REMAINING_LENGTH) {
			throw new IOException("MQTT packet exceeds configured maximum of " + maxPacketBytes + " bytes");
		}
	}

	private static void validateMaxPacketBytes(int maxPacketBytes) {
		if (maxPacketBytes <= 0 || maxPacketBytes > HARD_MAX_PACKET_BYTES) {
			throw new IllegalArgumentException(
					"MQTT maximum packet size must be between 1 and " + HARD_MAX_PACKET_BYTES);
		}
	}

	private static byte[] encodeMqttUtf8(String value, String fieldName, boolean topicName) throws IOException {
		if (value == null) {
			throw new IOException("MQTT " + fieldName + " must not be null");
		}
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > MQTT_MAX_UTF8_BYTES) {
			throw new IOException("MQTT " + fieldName + " exceeds 65535 UTF-8 bytes");
		}
		validateMqttString(value, fieldName, topicName);
		return bytes;
	}

	private static String decodeMqttUtf8(byte[] bytes, int offset, int length, String fieldName, boolean topicName)
			throws IOException {
		try {
			String value = StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes, offset, length))
					.toString();
			validateMqttString(value, fieldName, topicName);
			return value;
		} catch (CharacterCodingException ex) {
			throw new IOException("Malformed MQTT UTF-8 " + fieldName, ex);
		}
	}

	private static void validateMqttString(String value, String fieldName, boolean topicName) throws IOException {
		if (value.indexOf('\0') >= 0) {
			throw new IOException("MQTT " + fieldName + " contains a null character");
		}
		if (topicName && value.isEmpty()) {
			throw new IOException("MQTT topic must not be empty");
		}
	}

	private synchronized void closeQuietly() {
		try {
			if (input != null) {
				input.close();
			}
		} catch (IOException ignored) {
			// ignore
		}
		try {
			if (output != null) {
				output.close();
			}
		} catch (IOException ignored) {
			// ignore
		}
		try {
			if (socket != null) {
				socket.close();
			}
		} catch (IOException ignored) {
			// ignore
		}
		input = null;
		output = null;
		socket = null;
	}
}
