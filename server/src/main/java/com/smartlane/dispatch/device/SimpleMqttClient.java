package com.smartlane.dispatch.device;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class SimpleMqttClient {

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
	private final AtomicInteger packetCounter = new AtomicInteger(1);
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicBoolean connected = new AtomicBoolean(false);

	private volatile long lastOutboundAt;
	private Socket socket;
	private BufferedInputStream input;
	private BufferedOutputStream output;
	private Thread readerThread;

	public SimpleMqttClient(
			String host,
			int port,
			String clientId,
			String username,
			String password,
			int keepAliveSeconds,
			MessageListener messageListener) {
		this.host = host;
		this.port = port;
		this.clientId = clientId;
		this.username = username;
		this.password = password;
		this.keepAliveSeconds = keepAliveSeconds;
		this.messageListener = messageListener;
	}

	public synchronized void connect() throws IOException {
		if (connected.get()) {
			return;
		}

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
		readerThread = new Thread(this::readLoop, "mqtt-reader-" + clientId);
		readerThread.setDaemon(true);
		readerThread.start();
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
		byte[] topicBytes = topicFilter.getBytes(StandardCharsets.UTF_8);
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
		ensureConnected();
		byte[] topicBytes = topic.getBytes(StandardCharsets.UTF_8);
		byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
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
					int remainingLength = readRemainingLength(input);
					byte[] body = readFully(input, remainingLength);
					handlePacket(fixedHeader, body);
				} catch (SocketTimeoutException ignored) {
					maybePing();
				}
			}
		} catch (Exception ignored) {
			// handled by disconnect path
		} finally {
			connected.set(false);
			running.set(false);
			closeQuietly();
		}
	}

	private void handlePacket(int fixedHeader, byte[] body) {
		int packetType = (fixedHeader >> 4) & 0x0F;
		if (packetType == 3) {
			int topicLength = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
			String topic = new String(body, 2, topicLength, StandardCharsets.UTF_8);
			int payloadOffset = 2 + topicLength;
			byte[] payload = new byte[body.length - payloadOffset];
			System.arraycopy(body, payloadOffset, payload, 0, payload.length);
			messageListener.onMessage(topic, payload);
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

		byte[] protocolName = encodeString("MQTT");
		byte[] clientIdBytes = encodeString(clientId);
		byte[] usernameBytes = username != null && !username.isBlank() ? encodeString(username) : new byte[0];
		byte[] passwordBytes = password != null && !password.isBlank() ? encodeString(password) : new byte[0];
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
		int remainingLength = readRemainingLength(input);
		byte[] body = readFully(input, remainingLength);
		if (((fixedHeader >> 4) & 0x0F) != 2 || body.length < 2 || body[1] != 0) {
			throw new IOException("MQTT CONNACK failed, return code=" + (body.length > 1 ? body[1] : -1));
		}
	}

	private synchronized void sendFixedPacket(int header, byte[] body) throws IOException {
		if (output == null) {
			throw new IOException("MQTT output stream unavailable");
		}
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

	private byte[] encodeString(String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		byte[] encoded = new byte[2 + bytes.length];
		encoded[0] = (byte) ((bytes.length >> 8) & 0xFF);
		encoded[1] = (byte) (bytes.length & 0xFF);
		System.arraycopy(bytes, 0, encoded, 2, bytes.length);
		return encoded;
	}

	private static int readRemainingLength(BufferedInputStream input) throws IOException {
		int multiplier = 1;
		int value = 0;
		int encodedByte;
		do {
			encodedByte = input.read();
			if (encodedByte < 0) {
				throw new EOFException("Unexpected EOF while reading MQTT remaining length");
			}
			value += (encodedByte & 127) * multiplier;
			multiplier *= 128;
		} while ((encodedByte & 128) != 0);
		return value;
	}

	private static byte[] readFully(BufferedInputStream input, int length) throws IOException {
		byte[] buffer = new byte[length];
		int offset = 0;
		while (offset < length) {
			int read = input.read(buffer, offset, length - offset);
			if (read < 0) {
				throw new EOFException("Unexpected EOF while reading MQTT packet body");
			}
			offset += read;
		}
		return buffer;
	}

	private static void writeRemainingLength(BufferedOutputStream output, int length) throws IOException {
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

	private void closeQuietly() {
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
