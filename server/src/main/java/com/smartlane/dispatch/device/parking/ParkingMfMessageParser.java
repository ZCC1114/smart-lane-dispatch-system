package com.smartlane.dispatch.device.parking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 停车场总入口车牌抓拍设备（MF 系列）MQTT 消息解析工具类。
 * <p>
 * 负责将原始 MQTT Topic + Payload 解析为 {@link ParkingMfMessage} 对象。
 * 不依赖 Spring，可在任何上下文中使用。
 */
public class ParkingMfMessageParser {

	private static final Pattern SN_FROM_TOPIC = Pattern.compile("^/([^/]+)/mf/up$");
	private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper();

	private final ObjectMapper objectMapper;

	public ParkingMfMessageParser() {
		this(DEFAULT_MAPPER);
	}

	public ParkingMfMessageParser(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * 解析 MQTT 消息。
	 *
	 * @param topic   MQTT Topic，例如 {@code /00E02721A3A7/mf/up}
	 * @param payload MQTT Payload 字节数组
	 * @return 解析后的消息对象，如果格式不合法则返回 {@code null}
	 */
	public ParkingMfMessage parse(String topic, byte[] payload) {
		return parse(topic, new String(payload, StandardCharsets.UTF_8));
	}

	/**
	 * 解析 MQTT 消息。
	 *
	 * @param topic   MQTT Topic，例如 {@code /00E02721A3A7/mf/up}
	 * @param payload MQTT Payload 字符串（UTF-8）
	 * @return 解析后的消息对象，如果格式不合法则返回 {@code null}
	 */
	public ParkingMfMessage parse(String topic, String payload) {
		if (topic == null || payload == null || payload.isBlank()) {
			return null;
		}

		try {
			JsonNode root = objectMapper.readTree(payload);
			String cmd = text(root.path("cmd"));
			if (cmd == null) {
				return null;
			}

			String sn = text(root.path("sn"));
			if (sn == null) {
				sn = extractSnFromTopic(topic);
			}

			String msgId = text(root.path("msgId"));
			Long timestamp = longValue(root.path("timestamp"));
			String timezone = text(root.path("timezone"));

			return switch (cmd) {
				case "heartbeat" -> {
					ParkingMfMessage.HeartbeatData data = objectMapper.treeToValue(root.path("data"), ParkingMfMessage.HeartbeatData.class);
					yield new ParkingMfMessage(cmd, sn, msgId, timestamp, timezone, data, null);
				}
				case "plateResult" -> {
					ParkingMfMessage.PlateResultData data = objectMapper.treeToValue(root.path("data"), ParkingMfMessage.PlateResultData.class);
					yield new ParkingMfMessage(cmd, sn, msgId, timestamp, timezone, null, data);
				}
				default -> null;
			};
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * 判断 Topic 是否属于 MF 设备上报通道（{@code /{sn}/mf/up}）。
	 */
	public static boolean isMfUpTopic(String topic) {
		return topic != null && SN_FROM_TOPIC.matcher(topic).matches();
	}

	/**
	 * 从 Topic 中提取设备序列号（SN）。
	 *
	 * @param topic 例如 {@code /00E02721A3A7/mf/up}
	 * @return SN，例如 {@code 00E02721A3A7}；如果格式不匹配则返回 {@code null}
	 */
	public static String extractSnFromTopic(String topic) {
		if (topic == null) {
			return null;
		}
		Matcher matcher = SN_FROM_TOPIC.matcher(topic);
		return matcher.matches() ? matcher.group(1) : null;
	}

	private static String text(JsonNode node) {
		return node.isMissingNode() || node.isNull() ? null : node.asText().trim();
	}

	private static Long longValue(JsonNode node) {
		return node.isMissingNode() || node.isNull() ? null : node.asLong();
	}
}
