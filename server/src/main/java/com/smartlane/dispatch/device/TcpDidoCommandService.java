package com.smartlane.dispatch.device;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.smartlane.dispatch.dto.TcpDidoRelayResponse;

@Service
public class TcpDidoCommandService {

	private static final Pattern FIRST_NUMBER = Pattern.compile("\\d+");
	private static final HexFormat HEX = HexFormat.of().withUpperCase();

	private final DeviceGatewayProperties properties;

	public TcpDidoCommandService(DeviceGatewayProperties properties) {
		this.properties = properties;
	}

	public TcpDidoRelayResponse controlRelay(String host, int port, String relay, boolean on, String protocol) {
		String resolvedProtocol = normalizeProtocol(protocol);
		int relayIndex = parseRelayIndex(relay);
		byte[] command = buildRelayCommand(relayIndex, on, resolvedProtocol);
		byte[] response = send(host, port, command);
		return new TcpDidoRelayResponse(
				host,
				port,
				"A" + String.format(Locale.ROOT, "%02d", relayIndex),
				on,
				resolvedProtocol,
				HEX.formatHex(command),
				HEX.formatHex(response),
				printableAscii(response),
				response.length > 0,
				response.length > 0 ? "TCP 指令已发送并收到设备响应" : "TCP 指令已发送，设备未在超时时间内返回数据");
	}

	public byte[] buildRelayCommand(int relayIndex, boolean on, String protocol) {
		if (relayIndex < 1 || relayIndex > 16) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前 TCP DIDO 控制仅支持 A01-A16");
		}
		String normalizedProtocol = normalizeProtocol(protocol);
		return switch (normalizedProtocol) {
			case "A3" -> buildA3SceneCommand(relayIndex, on);
			default -> buildA1BasicCommand(relayIndex, on);
		};
	}

	private byte[] send(String host, int port, byte[] command) {
		int timeoutMs = Math.max(500, properties.getDidoTcp().getTimeoutMs());
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), timeoutMs);
			socket.setSoTimeout(timeoutMs);
			socket.getOutputStream().write(command);
			socket.getOutputStream().flush();
			return readAvailableResponse(socket);
		} catch (IOException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "TCP DIDO 指令发送失败: " + ex.getMessage(), ex);
		}
	}

	private byte[] readAvailableResponse(Socket socket) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] chunk = new byte[128];
		try {
			int read = socket.getInputStream().read(chunk);
			if (read > 0) {
				buffer.write(chunk, 0, read);
			}
		} catch (SocketTimeoutException ignored) {
			// Some device commands only act without a response. Sending succeeded.
		}
		return buffer.toByteArray();
	}

	private byte[] buildA1BasicCommand(int relayIndex, boolean on) {
		int mask = 1 << (relayIndex - 1);
		int state = on ? mask : 0;
		return new byte[] {
				(byte) 0xCC,
				(byte) 0xDD,
				(byte) 0xA1,
				0x01,
				(byte) ((state >> 8) & 0xFF),
				(byte) (state & 0xFF),
				(byte) ((mask >> 8) & 0xFF),
				(byte) (mask & 0xFF),
				(byte) 0xA4,
				0x48
		};
	}

	private byte[] buildA3SceneCommand(int relayIndex, boolean on) {
		int group = (relayIndex - 1) / 8;
		int bit = 1 << ((relayIndex - 1) % 8);
		byte[] command = new byte[20];
		command[0] = (byte) 0xCC;
		command[1] = (byte) 0xDD;
		command[2] = (byte) 0xA3;
		command[3] = 0x01;
		int stateOffset = 4 + (5 - group);
		int enableOffset = 10 + (5 - group);
		command[stateOffset] = (byte) (on ? bit : 0);
		command[enableOffset] = (byte) bit;
		command[18] = (byte) 0xDD;
		command[19] = (byte) 0xCC;
		return command;
	}

	private int parseRelayIndex(String relay) {
		if (relay == null || relay.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "继电器编号不能为空");
		}
		Matcher matcher = FIRST_NUMBER.matcher(relay);
		if (!matcher.find()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "继电器编号格式应为 A01 或 DO1");
		}
		return Integer.parseInt(matcher.group());
	}

	private String normalizeProtocol(String protocol) {
		if (protocol == null || protocol.isBlank()) {
			protocol = properties.getDidoTcp().getProtocol();
		}
		String value = protocol.trim().toUpperCase(Locale.ROOT);
		if ("A3".equals(value)) {
			return "A3";
		}
		return "A1";
	}

	private String printableAscii(byte[] bytes) {
		if (bytes.length == 0) {
			return "";
		}
		String text = new String(bytes, StandardCharsets.US_ASCII).trim();
		return text.chars().allMatch(ch -> ch >= 32 && ch <= 126) ? text : "";
	}
}
