package com.smartlane.dispatch.device;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartlane.dispatch.dto.YardEntryPayload;
import com.smartlane.dispatch.entity.DispatchTicket;
import com.smartlane.dispatch.entity.Lane;
import com.smartlane.dispatch.service.LaneRuntimeStateService;
import com.smartlane.dispatch.service.OperationsService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Service
@ConditionalOnProperty(value = "app.device.gateway", havingValue = "mqtt")
public class MqttDeviceGateway implements LaneDeviceGateway {

	private static final Logger log = LoggerFactory.getLogger(MqttDeviceGateway.class);
	private static final Logger flowLog = LoggerFactory.getLogger("vehicle-flow");
	private static final ZoneOffset DEVICE_ZONE = ZoneOffset.ofHours(8);
	private static final byte[] CX_ENABLE_REMOTE_CONFIG_COMMAND = hexBytes("4D9301010101A1000000");
	private static final byte[] CX_ENABLE_RELAY_UPLOAD_COMMAND = hexBytes("4D930101010AA1000000");
	private static final DateTimeFormatter DASH_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final DateTimeFormatter SLASH_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
	private static final Set<String> SMART_CAMERA_LANE_ENTRY_ALARM_TYPES = Set.of("1");
	private static final Set<String> SMART_CAMERA_LANE_STATUS_ALARM_TYPES = Set.of("49409", "49665");

	private final DeviceGatewayProperties properties;
	private final ObjectMapper objectMapper;
	private final TcpDidoCommandService tcpDidoCommandService;
	private final ObjectProvider<OperationsService> operationsServiceProvider;
	private final LaneRuntimeStateService laneRuntimeStateService;
	private final AtomicBoolean connecting = new AtomicBoolean(false);
	private final AtomicLong messageCounter = new AtomicLong(System.currentTimeMillis());
	private final Map<String, DeviceGatewayProperties.LaneBinding> bindingsByLaneId = new ConcurrentHashMap<>();
	private final Map<String, List<DeviceGatewayProperties.LaneBinding>> bindingsByMfSn = new ConcurrentHashMap<>();
	private final Map<String, List<DeviceGatewayProperties.LaneBinding>> bindingsByMfGroupId = new ConcurrentHashMap<>();
	private final Map<String, List<DeviceGatewayProperties.LaneBinding>> bindingsByMfDeviceNo = new ConcurrentHashMap<>();
	private final Map<String, DeviceGatewayProperties.LaneBinding> bindingsByCameraDevId = new ConcurrentHashMap<>();
	private final Map<String, List<DeviceGatewayProperties.LaneBinding>> bindingsByDidoDeviceId = new ConcurrentHashMap<>();
	private final Map<String, String> activeEntryCameraMotorStayLaneIds = new ConcurrentHashMap<>();
	private final Map<String, String> lastLaneSyncStates = new ConcurrentHashMap<>();
	private final Map<String, String> lastDidoDeviceSyncStates = new ConcurrentHashMap<>();
	private final Map<String, String> lastGateActions = new ConcurrentHashMap<>();
	private final Map<String, Boolean> lastDidoInputStates = new ConcurrentHashMap<>();

	private volatile SimpleMqttClient mqttClient;

	public MqttDeviceGateway(
			DeviceGatewayProperties properties,
			ObjectMapper objectMapper,
			TcpDidoCommandService tcpDidoCommandService,
			ObjectProvider<OperationsService> operationsServiceProvider,
			LaneRuntimeStateService laneRuntimeStateService) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.tcpDidoCommandService = tcpDidoCommandService;
		this.operationsServiceProvider = operationsServiceProvider;
		this.laneRuntimeStateService = laneRuntimeStateService;
	}

	@PostConstruct
	void indexLaneBindings() {
		for (DeviceGatewayProperties.LaneBinding binding : properties.getLanes()) {
			if (isBlank(binding.getLaneId())) {
				continue;
			}
			bindingsByLaneId.put(binding.getLaneId(), binding);
			addBinding(bindingsByMfSn, binding.getMfSn(), binding);
			addBinding(bindingsByMfGroupId, binding.getMfGroupId(), binding);
			addBinding(bindingsByMfDeviceNo, binding.getMfDeviceNo(), binding);
			addDidoBinding(binding.getEntryDidoDeviceId(), binding);
			addDidoBinding(binding.getExitDidoDeviceId(), binding);
			addDidoBinding(binding.getDidoDeviceId(), binding);
			addCameraBinding(binding.getCameraDevId(), binding);
		}
		log.info("MQTT device gateway indexed {} lane bindings", bindingsByLaneId.size());
	}

	@Override
	public void syncLane(Lane lane) {
		syncBatch(List.of(lane));
	}

	@Override
	public void syncBatch(List<Lane> lanes) {
		if (!properties.getMqtt().isEnabled()) {
			for (Lane lane : lanes) {
				laneRuntimeStateService.markCommandFailed(lane.getId(), "MQTT 网关未启用", now());
			}
			return;
		}
		SimpleMqttClient client = mqttClient;
		if (client == null || !client.isConnected()) {
			for (Lane lane : lanes) {
				laneRuntimeStateService.markCommandPending(lane.getId(), "MQTT 未连接，等待下发灯控指令", now());
			}
			return;
		}

		Map<String, List<Lane>> didoDeviceLanes = new HashMap<>();
		for (Lane lane : lanes) {
			DeviceGatewayProperties.LaneBinding binding = bindingsByLaneId.get(lane.getId());
			if (binding == null) {
				laneRuntimeStateService.markCommandPending(lane.getId(), "未配置车道设备绑定", now());
				continue;
			}

			String ledMessage = resolveLedMessage(lane);
			String nextSyncState = lane.getEntrySignal() + "|"
					+ lane.getExitSignal() + "|"
					+ lane.getVehicleCount() + "|"
					+ lane.getCurrentPlate() + "|"
					+ ledMessage;
			try {
				if (!nextSyncState.equals(lastLaneSyncStates.get(lane.getId()))) {
					syncParkingCamera(client, lane, binding, ledMessage);
					lastLaneSyncStates.put(lane.getId(), nextSyncState);
				}
			} catch (Exception ex) {
				laneRuntimeStateService.markCommandFailed(lane.getId(), "灯控指令下发失败", now());
				log.warn("Failed to sync parking camera for lane {}", lane.getId(), ex);
			}

			if (properties.getDido().isEnabled()) {
				addDidoLane(didoDeviceLanes, resolveEntryDidoDeviceId(binding), lane);
				addDidoLane(didoDeviceLanes, resolveExitDidoDeviceId(binding), lane);
			}
		}

		for (Map.Entry<String, List<Lane>> entry : didoDeviceLanes.entrySet()) {
			String didoDeviceId = entry.getKey();
			List<Lane> deviceLanes = entry.getValue();
			String nextDeviceSyncState = buildDidoDeviceSyncState(didoDeviceId, deviceLanes);
			if (nextDeviceSyncState.equals(lastDidoDeviceSyncStates.get(didoDeviceId))) {
				continue;
			}
			try {
				syncDidoTrafficLightsForDevice(client, didoDeviceId, deviceLanes);
				lastDidoDeviceSyncStates.put(didoDeviceId, nextDeviceSyncState);
				for (Lane lane : deviceLanes) {
					laneRuntimeStateService.markCommandPublished(lane.getId(), "灯控指令已下发，等待设备反馈", now());
				}
			} catch (Exception ex) {
				for (Lane lane : deviceLanes) {
					laneRuntimeStateService.markCommandFailed(lane.getId(), "灯控指令下发失败", now());
				}
				log.warn("Failed to sync DIDO device {} for {} lanes", didoDeviceId, deviceLanes.size(), ex);
			}
		}
	}

	@Override
	public void controlRelay(Lane lane, String relayTarget, boolean on, String reason) {
		DeviceGatewayProperties.LaneBinding binding = bindingsByLaneId.get(lane.getId());
		if (!properties.getMqtt().isEnabled()) {
			laneRuntimeStateService.markCommandFailed(lane.getId(), "MQTT 网关未启用", now());
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MQTT 网关未启用");
		}
		if (binding == null) {
			laneRuntimeStateService.markCommandPending(lane.getId(), "未配置车道设备绑定", now());
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前车道未配置 CX 设备绑定");
		}
		String didoDeviceId = resolveRelayDidoDeviceId(binding, relayTarget);
		if (!properties.getDido().isEnabled() || isBlank(didoDeviceId)) {
			laneRuntimeStateService.markCommandFailed(lane.getId(), "未配置 DIDO/CX 继电器设备", now());
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前车道未配置 DIDO/CX 继电器设备");
		}

		SimpleMqttClient client = mqttClient;
		if (client == null || !client.isConnected()) {
			laneRuntimeStateService.markCommandPending(lane.getId(), "MQTT 未连接，无法下发继电器指令", now());
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MQTT 未连接，无法下发 CX 继电器指令");
		}

		String relayKey = resolveRelayKey(binding, relayTarget);
		if (isBlank(relayKey)) {
			laneRuntimeStateService.markCommandFailed(lane.getId(), "继电器映射缺失", now());
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前车道未配置该业务继电器");
		}

		try {
			String topic = renderTopic(properties.getDido().getDownTopicTemplate(), didoDeviceId);
			if (didoPayloadMode().startsWith("hex-")) {
				byte[] command = publishDidoRelayHex(client, didoDeviceId, relayKey, on);
				flowLog.info(
						"节点=手动继电器下发 event=RELAY_COMMAND protocol=MQTT_HEX laneId={} laneName={} deviceId={} target={} relay={} on={} reason={} topic={} payloadHex={}",
						lane.getId(),
						lane.getName(),
						didoDeviceId,
						relayTarget,
						relayKey,
						on,
						nullToEmpty(reason),
						topic,
						bytesToHex(command));
			} else {
				ObjectNode payload = objectMapper.createObjectNode();
				payload.put(relayKey, didoRelayValue(on));
				payload.put("res", nextMessageId("dido-manual"));
				String payloadText = objectMapper.writeValueAsString(payload);
				client.publish(topic, payloadText);
				flowLog.info(
						"节点=手动继电器下发 event=RELAY_COMMAND protocol=MQTT_JSON laneId={} laneName={} deviceId={} target={} relay={} on={} reason={} topic={} payload={}",
						lane.getId(),
						lane.getName(),
						didoDeviceId,
						relayTarget,
						relayKey,
						on,
						nullToEmpty(reason),
						topic,
						payloadText);
			}
			String message = relayDisplayName(relayTarget) + (on ? "已吸合" : "已关闭");
			if (!isBlank(reason)) {
				message += " · " + reason;
			}
			laneRuntimeStateService.markCommandPublished(lane.getId(), "CX 继电器指令已下发", now());
			laneRuntimeStateService.recordDeviceMessage(lane.getId(), message, now());
		} catch (IOException ex) {
			laneRuntimeStateService.markCommandFailed(lane.getId(), "CX 继电器指令下发失败", now());
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "CX 继电器指令下发失败: " + ex.getMessage(), ex);
		}
	}

	@Override
	public void clearSyncState() {
		lastLaneSyncStates.clear();
		lastDidoDeviceSyncStates.clear();
		lastGateActions.clear();
		lastDidoInputStates.clear();
		activeEntryCameraMotorStayLaneIds.clear();
	}

	@Scheduled(initialDelay = 1000, fixedDelayString = "${app.device.mqtt.reconnect-delay-ms:5000}")
	public void maintainConnection() {
		if (!properties.getMqtt().isEnabled()) {
			return;
		}
		SimpleMqttClient client = mqttClient;
		if (client != null && client.isConnected()) {
			return;
		}
		if (!connecting.compareAndSet(false, true)) {
			return;
		}

		try {
			SimpleMqttClient nextClient = new SimpleMqttClient(
					properties.getMqtt().getHost(),
					properties.getMqtt().getPort(),
					properties.getMqtt().getClientId(),
					properties.getMqtt().getUsername(),
					properties.getMqtt().getPassword(),
					properties.getMqtt().getKeepAliveSeconds(),
					this::handleRawMqttMessage);
			nextClient.connect();
			mqttClient = nextClient;
			subscribeConfiguredTopics(nextClient);
			requestInitialDeviceState(nextClient);
			log.info("MQTT device gateway connected to {}:{}", properties.getMqtt().getHost(), properties.getMqtt().getPort());
		} catch (Exception ex) {
			closeClient();
			log.warn("MQTT device gateway connection failed: {}", ex.getMessage());
		} finally {
			connecting.set(false);
		}
	}

	@Scheduled(initialDelayString = "${app.device.smart-camera.have-car-poll-ms:30000}", fixedDelayString = "${app.device.smart-camera.have-car-poll-ms:30000}")
	public void pollHaveCarState() {
		if (!properties.getMqtt().isEnabled()
				|| !properties.getSmartCamera().isEnabled()
				|| !properties.getSmartCamera().isHaveCarPollEnabled()) {
			return;
		}
		SimpleMqttClient client = mqttClient;
		if (client == null || !client.isConnected()) {
			return;
		}

		for (DeviceGatewayProperties.LaneBinding binding : uniqueCameraBindings()) {
			if (isBlank(binding.getCameraDevId())) {
				continue;
			}
			try {
				publishSmartCameraCommand(client, binding, "getHaveCar", objectMapper.createObjectNode());
			} catch (Exception ex) {
				log.warn("Failed to poll have-car state for lane {}", binding.getLaneId(), ex);
			}
		}
	}

	@PreDestroy
	public void shutdown() {
		closeClient();
	}

	private void handleRawMqttMessage(String topic, byte[] payload) {
		String text = new String(payload, StandardCharsets.UTF_8).trim();
		if (text.isBlank()) {
			return;
		}

		try {
			JsonNode message = objectMapper.readTree(text);
			if (properties.getParkingMf().isEnabled()
					&& topicMatchesFilter(topic, properties.getParkingMf().getUpTopicFilter())) {
				handleParkingMfMessage(topic, message);
				return;
			}

			String cmd = text(message.path("cmd"));
			if (!isBlank(cmd)) {
				handleSmartCameraMessage(topic, message, cmd);
				return;
			}

			if (properties.getSmartCamera().isEnabled()
					&& topicMatchesFilter(topic, properties.getSmartCamera().getWillTopicFilter())) {
				handleSmartCameraWillMessage(topic, message);
				return;
			}

			if (properties.getDido().isEnabled()
					&& topicMatchesFilter(topic, properties.getDido().getUpTopicFilter())) {
				handleDidoStatusMessage(topic, message);
			}
		} catch (Exception ex) {
			log.warn("Failed to handle MQTT message on topic {}: {}", topic, text, ex);
		}
	}

	private void handleParkingMfMessage(String topic, JsonNode message) throws IOException {
		String cmd = text(message.path("cmd"));
		JsonNode data = message.path("data");
		String sn = firstNonBlank(text(message.path("sn")), extractMfSnFromTopic(topic));
		OffsetDateTime observedAt = parseDeviceTime(firstNonBlank(text(message.path("timestamp")), text(data.path("checkTime")), text(data.path("uploadTime"))));

		switch (cmd) {
			case "heartbeat" -> handleParkingHeartbeat(sn, data, observedAt);
			case "plateResult" -> handleParkingPlateResult(sn, message, data);
			case "ioOutputResp", "sfTriggerResp", "ledControlResp" ->
					log.debug("Parking MF response {} received from sn={}", cmd, sn);
			default -> log.debug("Ignored parking MF command {} from sn={}", cmd, sn);
		}
	}

	private void handleParkingHeartbeat(String sn, JsonNode data, OffsetDateTime observedAt) {
		if (data.path("deviceStatus").isArray()) {
			for (JsonNode deviceStatus : data.path("deviceStatus")) {
				String deviceNo = text(deviceStatus.path("deviceNo"));
				String groupId = text(deviceStatus.path("groupId"));
				if (isYardEntryParkingMf(sn, groupId, deviceNo)) {
					log.debug("Yard entry parking MF camera is online, sn={}, groupId={}, deviceNo={}", sn, groupId, deviceNo);
					continue;
				}
				String network = text(deviceStatus.path("network"));
				for (DeviceGatewayProperties.LaneBinding binding : findMfBindings(sn, null, deviceNo)) {
					updateLaneDeviceStatus(
							binding.getLaneId(),
							"online".equalsIgnoreCase(network) ? "ONLINE" : "OFFLINE",
							observedAt,
							"停车相机" + ("online".equalsIgnoreCase(network) ? "在线" : "离线"));
				}
			}
			return;
		}

		for (DeviceGatewayProperties.LaneBinding binding : bindingsByMfSn.getOrDefault(sn, List.of())) {
			updateLaneDeviceStatus(binding.getLaneId(), "ONLINE", observedAt, "停车相机在线");
		}
	}

	private void handleParkingPlateResult(String sn, JsonNode message, JsonNode data) throws IOException {
		String groupId = text(data.path("groupId"));
		String deviceNo = text(data.path("deviceNo"));
		String plate = text(data.path("plateNo"));
		String plateColor = extractPlateColor(data);
		OffsetDateTime capturedAt = parseDeviceTime(firstNonBlank(text(data.path("parkingTime")), text(data.path("uploadTime")), text(message.path("timestamp"))));
		boolean yardEntry = isYardEntryParkingMf(sn, groupId, deviceNo);
		log.info(
				"Parking MF plateResult received sn={} groupId={} deviceNo={} plate={} plateColor={} capturedAt={} yardEntryMatch={} configuredYardSn={} configuredYardGroupId={} configuredYardDeviceNo={}",
				sn,
				groupId,
				deviceNo,
				plate,
				plateColor,
				capturedAt,
				yardEntry,
				nullToEmpty(properties.getParkingMf().getYardEntrySn()),
				nullToEmpty(properties.getParkingMf().getYardEntryGroupId()),
				nullToEmpty(properties.getParkingMf().getYardEntryDeviceNo()));
		flowLog.info(
				"节点=总入口MF收到车牌 event=YARD_MF_PLATE_RECEIVED sn={} groupId={} deviceNo={} plate={} plateColor={} capturedAt={} yardEntryMatch={} msgId={}",
				sn,
				groupId,
				deviceNo,
				plate,
				plateColor,
				capturedAt,
				yardEntry,
				text(message.path("msgId")));
		if (yardEntry) {
			logParkingMfYardConfigMismatch(sn, groupId, deviceNo);
			if (!isBlank(plate)) {
				registerYardEntryFromDevice(plate, capturedAt, "ALPR_YARD", plateColor);
				publishParkingMfYardEntryLedControl(sn, groupId, plate);
			}
			return;
		}

		DeviceGatewayProperties.LaneBinding binding = firstBinding(findMfBindings(sn, groupId, deviceNo));
		if (binding == null) {
			log.warn(
					"Parking MF plateResult cannot be mapped to yard entry or lane, sn={} groupId={} deviceNo={} plate={} configuredYardSn={} configuredYardGroupId={} configuredYardDeviceNo={}",
					sn,
					groupId,
					deviceNo,
					plate,
					nullToEmpty(properties.getParkingMf().getYardEntrySn()),
					nullToEmpty(properties.getParkingMf().getYardEntryGroupId()),
					nullToEmpty(properties.getParkingMf().getYardEntryDeviceNo()));
			return;
		}

		if (!isBlank(plate)) {
			log.warn(
					"Parking MF plateResult routed as lane entry, not yard entry. plate={} laneId={} sn={} groupId={} deviceNo={} capturedAt={}",
					plate,
					binding.getLaneId(),
					sn,
					groupId,
					deviceNo,
					capturedAt);
			registerVehicleEntryFromDevice(
					binding.getLaneId(),
					plate,
					capturedAt,
					"出租车",
					"ALPR");
		}
		publishParkingMfResponse(binding, sn, "plateResultResp", text(message.path("msgId")), data);
	}

	private void handleSmartCameraMessage(String topic, JsonNode message, String cmd) {
		if (!properties.getSmartCamera().isEnabled()) {
			return;
		}
		String devId = firstNonBlank(text(message.path("devId")), extractDeviceIdFromTopic(topic));
		if (isYardEntryCamera(devId)) {
			handleYardEntryCameraMessage(topic, message, cmd);
			return;
		}

		DeviceGatewayProperties.LaneBinding binding = cameraBinding(devId);
		if (binding == null) {
			log.debug("Smart camera message cannot be mapped to lane, devId={}, cmd={}", devId, cmd);
			return;
		}
		DeviceGatewayProperties.LaneBinding entryBinding = resolveEntryBindingForSmartCamera(devId, binding, observedAtFromMessage(message));

		JsonNode content = message.path("content");
		OffsetDateTime observedAt = parseDeviceTime(firstNonBlank(text(content.path("alarmTime")), text(message.path("utcTs"))));
		entryBinding = resolveLaneStatusAlarmBinding(devId, entryBinding, content);
		switch (cmd) {
			case "heartbeat" -> updateLaneDeviceStatus(binding.getLaneId(), "ONLINE", observedAt, "智能相机在线");
			case "devVerInfo", "getVerInfoRsp" -> updateLaneDeviceStatus(binding.getLaneId(), "ONLINE", observedAt, "智能相机版本已上报");
			case "devOffline" -> updateLaneDeviceStatus(binding.getLaneId(), "OFFLINE", observedAt, "智能相机离线");
			case "devAlarm" -> handleSmartCameraAlarm(devId, entryBinding, content, observedAt);
			case "passCount" -> handleSmartCameraCountIgnored(entryBinding, observedAt);
			case "getHaveCarRsp" -> handleSmartCameraPresenceIgnored(entryBinding, observedAt);
			case "getVideoRsp", "clearCountRsp" -> log.debug("Smart camera response {} received from devId={}", cmd, devId);
			default -> {
				if (topicMatchesFilter(topic, properties.getSmartCamera().getWillTopicFilter())) {
					updateLaneDeviceStatus(binding.getLaneId(), "OFFLINE", observedAt, "智能相机离线");
				} else {
					log.debug("Ignored smart camera command {} from devId={}", cmd, devId);
				}
			}
		}
	}

	private void handleYardEntryCameraMessage(String topic, JsonNode message, String cmd) {
		String devId = firstNonBlank(text(message.path("devId")), extractDeviceIdFromTopic(topic));
		JsonNode content = message.path("content");
		OffsetDateTime observedAt = parseDeviceTime(firstNonBlank(text(content.path("alarmTime")), text(message.path("utcTs"))));

		if ("devOffline".equals(cmd)) {
			log.warn("Yard entry smart camera {} reported offline", devId);
			return;
		}
		if ("heartbeat".equals(cmd) || "devVerInfo".equals(cmd) || "getVerInfoRsp".equals(cmd)) {
			log.debug("Yard entry smart camera {} is online, cmd={}", devId, cmd);
			return;
		}
		if (!"devAlarm".equals(cmd)) {
			log.debug("Ignored yard entry smart camera command {} from devId={}", cmd, devId);
			return;
		}

		String alarmType = text(content.path("alarmType"));
		String plate = firstNonBlank(text(content.path("plateNum")), text(content.path("plateNumVDC")));
		String plateColor = extractPlateColor(content);
		flowLog.info(
				"节点=总入口相机收到告警 event=YARD_SMART_CAMERA_ALARM devId={} topic={} alarmType={} plate={} plateColor={} observedAt={} accepted={}",
				devId,
				topic,
				alarmType,
				plate,
				plateColor,
				observedAt,
				!isBlank(plate) && shouldRegisterSmartCameraPlate(alarmType, content));
		if (!isBlank(plate) && shouldRegisterSmartCameraPlate(alarmType, content)) {
			registerYardEntryFromDevice(plate, observedAt, "SMART_CAMERA", plateColor);
		}
	}

	private void handleSmartCameraWillMessage(String topic, JsonNode message) {
		String devId = firstNonBlank(text(message.path("devId")), extractDeviceIdFromTopic(topic));
		DeviceGatewayProperties.LaneBinding binding = cameraBinding(devId);
		if (binding != null) {
			updateLaneDeviceStatus(binding.getLaneId(), "OFFLINE", now(), "智能相机离线");
		}
	}

	private void handleSmartCameraAlarm(
			String devId,
			DeviceGatewayProperties.LaneBinding binding,
			JsonNode content,
			OffsetDateTime observedAt) {
		String alarmType = text(content.path("alarmType"));
		if (isSmartCameraLaneStatusAlarmType(alarmType)) {
			handleSmartCameraLaneStatusAlarm(devId, binding, content, observedAt, alarmType);
			return;
		}
		if (!isSmartCameraLaneEntryAlarmType(alarmType)) {
			flowLog.info(
					"节点=车道相机告警忽略 event=LANE_CAMERA_ALARM_IGNORED laneId={} devId={} alarmType={} observedAt={} reason=UNSUPPORTED_ALARM",
					binding.getLaneId(),
					devId,
					alarmType,
					observedAt);
			log.debug("Ignored smart camera alarm type {} for lane {}", alarmType, binding.getLaneId());
			return;
		}

		String plate = firstNonBlank(text(content.path("plateNum")), text(content.path("plateNumVDC")));
		if (!isBlank(plate) && shouldRegisterSmartCameraLaneEntryPlate(alarmType, content)) {
			flowLog.info(
					"节点=车道入口识别过车 event=LANE_CAMERA_PASS laneId={} devId={} alarmType={} plate={} plateColor={} observedAt={} inOut={}",
					binding.getLaneId(),
					devId,
					alarmType,
					plate,
					extractPlateColor(content),
					observedAt,
					text(content.path("inOut")));
			registerVehicleEntryFromDevice(
					binding.getLaneId(),
					plate,
					observedAt,
					"出租车",
					"SMART_CAMERA");
			updateLaneDeviceStatus(binding.getLaneId(), "ONLINE", observedAt, "入口相机识别车牌: " + plate);
			return;
		}

		if ("1".equals(alarmType)) {
			flowLog.info(
					"节点=车道入口过车未识别车牌 event=LANE_CAMERA_PASS_NO_PLATE laneId={} devId={} alarmType={} observedAt={} inOut={}",
					binding.getLaneId(),
					devId,
					alarmType,
					observedAt,
					text(content.path("inOut")));
			updateLaneDeviceStatus(binding.getLaneId(), "ONLINE", observedAt, "入口相机触发但未识别车牌");
			return;
		}
	}

	private void handleSmartCameraLaneStatusAlarm(
			String devId,
			DeviceGatewayProperties.LaneBinding binding,
			JsonNode content,
			OffsetDateTime observedAt,
			String alarmType) {
		String plate = firstNonBlank(text(content.path("plateNum")), text(content.path("plateNumVDC")));
		AlarmMapping mapping = AlarmMapping.from(alarmType);
		if (mapping == null) {
			log.debug("Smart camera alarm type {} has no lane status mapping for lane {}", alarmType, binding.getLaneId());
			return;
		}
		if (mapping.endEvent()) {
			clearMotorStayLane(devId);
			flowLog.info(
					"节点=车道滞留解除 event=LANE_CAMERA_STAY_END laneId={} devId={} alarmType={} plate={} observedAt={} message={}",
					binding.getLaneId(),
					devId,
					alarmType,
					plate,
					observedAt,
					mapping.message());
			updateLaneDeviceStatus(binding.getLaneId(), "ONLINE", observedAt, mapping.message() + "解除");
			return;
		}
		rememberMotorStayLane(devId, binding);
		String message = mapping.message();
		if (!isBlank(plate)) {
			message += ": " + plate;
		}
		flowLog.info(
				"节点=车道滞留告警 event=LANE_CAMERA_STAY laneId={} devId={} alarmType={} plate={} observedAt={} message={}",
				binding.getLaneId(),
				devId,
				alarmType,
				plate,
				observedAt,
				message);
		updateLaneDeviceStatus(binding.getLaneId(), "DEGRADED", observedAt, message);
	}

	private DeviceGatewayProperties.LaneBinding resolveEntryBindingForSmartCamera(
			String devId,
			DeviceGatewayProperties.LaneBinding fallbackBinding,
			OffsetDateTime referenceTime) {
		if (!isActiveEntrySmartCamera(devId)) {
			return fallbackBinding;
		}
		String activeEntryLaneId = resolveOpenEntryLaneIdForDevice(referenceTime);
		if (isBlank(activeEntryLaneId)) {
			log.debug("Active-entry smart camera {} has no current open entry lane, fallback to {}", devId, fallbackBinding.getLaneId());
			return fallbackBinding;
		}
		DeviceGatewayProperties.LaneBinding activeBinding = bindingsByLaneId.get(activeEntryLaneId);
		if (activeBinding == null) {
			log.warn("Active-entry smart camera {} resolved lane {} but no binding is configured", devId, activeEntryLaneId);
			return fallbackBinding;
		}
		return activeBinding;
	}

	private DeviceGatewayProperties.LaneBinding resolveLaneStatusAlarmBinding(
			String devId,
			DeviceGatewayProperties.LaneBinding fallbackBinding,
			JsonNode content) {
		if (!isActiveEntrySmartCamera(devId)) {
			return fallbackBinding;
		}
		if (!"49665".equals(text(content.path("alarmType")))) {
			return fallbackBinding;
		}
		String laneId = activeEntryCameraMotorStayLaneIds.get(cameraDevIdKey(devId));
		DeviceGatewayProperties.LaneBinding originalBinding = bindingsByLaneId.get(laneId);
		return originalBinding == null ? fallbackBinding : originalBinding;
	}

	private void rememberMotorStayLane(String devId, DeviceGatewayProperties.LaneBinding binding) {
		if (isActiveEntrySmartCamera(devId)) {
			activeEntryCameraMotorStayLaneIds.put(cameraDevIdKey(devId), binding.getLaneId());
		}
	}

	private void clearMotorStayLane(String devId) {
		if (isActiveEntrySmartCamera(devId)) {
			activeEntryCameraMotorStayLaneIds.remove(cameraDevIdKey(devId));
		}
	}

	private boolean shouldRegisterSmartCameraPlate(String alarmType, JsonNode content) {
		return "1".equals(alarmType) || isSmartCameraEntryDirection(content);
	}

	private boolean shouldRegisterSmartCameraLaneEntryPlate(String alarmType, JsonNode content) {
		return isSmartCameraLaneEntryAlarmType(alarmType) && isSmartCameraEntryDirection(content);
	}

	private boolean isSmartCameraLaneEntryAlarmType(String alarmType) {
		return SMART_CAMERA_LANE_ENTRY_ALARM_TYPES.contains(alarmType);
	}

	private boolean isSmartCameraLaneStatusAlarmType(String alarmType) {
		return SMART_CAMERA_LANE_STATUS_ALARM_TYPES.contains(alarmType);
	}

	private boolean isSmartCameraEntryDirection(JsonNode content) {
		String inOut = text(content.path("inOut"));
		return isBlank(inOut) || "in".equalsIgnoreCase(inOut);
	}

	private void handleSmartCameraCountIgnored(DeviceGatewayProperties.LaneBinding binding, OffsetDateTime observedAt) {
		flowLog.info(
				"节点=入口相机计数忽略 event=LANE_CAMERA_PASS_COUNT_IGNORED laneId={} observedAt={} reason=EXIT_STATISTICS_USE_EXIT_LOOP",
				binding.getLaneId(),
				observedAt);
		updateLaneDeviceStatus(binding.getLaneId(), "ONLINE", observedAt, "入口相机计数上报已忽略，出场统计以出口地感为准");
	}

	private void handleSmartCameraPresenceIgnored(DeviceGatewayProperties.LaneBinding binding, OffsetDateTime observedAt) {
		flowLog.info(
				"节点=入口相机在位忽略 event=LANE_CAMERA_PRESENCE_IGNORED laneId={} observedAt={} reason=EXIT_STATISTICS_USE_EXIT_LOOP",
				binding.getLaneId(),
				observedAt);
		updateLaneDeviceStatus(binding.getLaneId(), "ONLINE", observedAt, "入口相机在位响应已忽略，出场统计以出口地感为准");
	}

	private void handleDidoStatusMessage(String topic, JsonNode message) {
		String didoDeviceId = extractDeviceIdFromTopic(topic);
		List<DeviceGatewayProperties.LaneBinding> bindings = bindingsByDidoDeviceId.getOrDefault(didoDeviceId, List.of());
		if (bindings.isEmpty()) {
			log.debug("DIDO status cannot be mapped to lane, deviceId={}", didoDeviceId);
			return;
		}

		OffsetDateTime observedAt = now();
		for (DeviceGatewayProperties.LaneBinding binding : bindings) {
			boolean entryDevice = didoDeviceId.equals(resolveEntryDidoDeviceId(binding));
			boolean exitDevice = didoDeviceId.equals(resolveExitDidoDeviceId(binding));
			String entrySignal = entryDevice
					? resolveSignalFromRelayFeedback(message, binding.getEntryGreenRelay(), binding.getEntryRedRelay())
					: null;
			String exitSignal = exitDevice
					? resolveSignalFromRelayFeedback(message, binding.getExitGreenRelay(), binding.getExitRedRelay())
					: null;
			if (!isBlank(entrySignal) || !isBlank(exitSignal)) {
				flowLog.info(
						"节点=DIDO灯态反馈 event=DIDO_SIGNAL_FEEDBACK deviceId={} laneId={} entrySignal={} exitSignal={} observedAt={} payload={}",
						didoDeviceId,
						binding.getLaneId(),
						nullToEmpty(entrySignal),
						nullToEmpty(exitSignal),
						observedAt,
						message);
				updateLaneSignalFeedback(
						binding.getLaneId(),
						entrySignal,
						exitSignal,
						observedAt,
						"DIDO 继电器状态已反馈");
			}
			if (properties.getDido().isExitTriggerEnabled()
					&& exitDevice
					&& !isBlank(binding.getExitTriggerInputKey())
					&& message.has(binding.getExitTriggerInputKey())) {
				handleLaneExitTriggerInput(didoDeviceId, binding, message.path(binding.getExitTriggerInputKey()), observedAt);
			}
			if (exitDevice && !isBlank(binding.getPresenceInputKey()) && message.has(binding.getPresenceInputKey())) {
				applyLanePresenceSignal(binding.getLaneId(), intValue(message.path(binding.getPresenceInputKey())) == 1, observedAt);
			}
		}
	}

	private void handleLaneExitTriggerInput(
			String didoDeviceId,
			DeviceGatewayProperties.LaneBinding binding,
			JsonNode inputNode,
			OffsetDateTime observedAt) {
		boolean triggered = discreteInputTriggered(inputNode);
		String inputStateKey = didoDeviceId + "|" + binding.getLaneId() + "|" + binding.getExitTriggerInputKey();
		Boolean previous = lastDidoInputStates.put(inputStateKey, triggered);
		flowLog.info(
				"节点=出口地感输入 event=EXIT_LOOP_INPUT deviceId={} laneId={} inputKey={} value={} triggered={} previous={} observedAt={}",
				didoDeviceId,
				binding.getLaneId(),
				binding.getExitTriggerInputKey(),
				inputNode,
				triggered,
				previous,
				observedAt);
		if (previous == null || previous || !triggered) {
			return;
		}
		flowLog.info(
				"节点=出口地感触发 event=EXIT_LOOP_TRIGGERED deviceId={} laneId={} inputKey={} observedAt={}",
				didoDeviceId,
				binding.getLaneId(),
				binding.getExitTriggerInputKey(),
				observedAt);
		applyLaneExitTrigger(binding.getLaneId(), observedAt);
	}

	private void syncDidoTrafficLightsForDevice(
			SimpleMqttClient client,
			String didoDeviceId,
			List<Lane> lanes) throws IOException {
		String topic = renderTopic(properties.getDido().getDownTopicTemplate(), didoDeviceId);
		String laneSignals = summarizeLaneSignals(lanes);
		if (didoPayloadMode().startsWith("hex-")) {
			byte[] command = buildDidoBatchHexCommand(didoDeviceId, lanes);
			if (command != null && command.length > 0) {
				client.publish(topic, command);
				flowLog.info(
						"节点=红绿灯批量下发 event=DIDO_TRAFFIC_LIGHT_COMMAND protocol=MQTT_HEX deviceId={} topic={} lanes={} payloadHex={}",
						didoDeviceId,
						topic,
						laneSignals,
						bytesToHex(command));
			}
			return;
		}

		ObjectNode payload = objectMapper.createObjectNode();
		for (Lane lane : lanes) {
			DeviceGatewayProperties.LaneBinding binding = bindingsByLaneId.get(lane.getId());
			if (binding == null) {
				continue;
			}
			if (isEntryDidoDevice(didoDeviceId, binding)) {
				putRelayState(payload, binding.getEntryRedRelay(), !"GREEN".equals(lane.getEntrySignal()));
				putRelayState(payload, binding.getEntryGreenRelay(), "GREEN".equals(lane.getEntrySignal()));
			}
			if (isExitDidoDevice(didoDeviceId, binding)) {
				putRelayState(payload, binding.getExitRedRelay(), !"GREEN".equals(lane.getExitSignal()));
				putRelayState(payload, binding.getExitGreenRelay(), "GREEN".equals(lane.getExitSignal()));
			}
		}
		if (payload.isEmpty()) {
			return;
		}
		payload.put("res", nextMessageId("dido"));
		String payloadText = objectMapper.writeValueAsString(payload);
		client.publish(topic, payloadText);
		flowLog.info(
				"节点=红绿灯批量下发 event=DIDO_TRAFFIC_LIGHT_COMMAND protocol=MQTT_JSON deviceId={} topic={} lanes={} payload={}",
				didoDeviceId,
				topic,
				laneSignals,
				payloadText);
	}

	private byte[] publishDidoRelayHex(
			SimpleMqttClient client,
			String didoDeviceId,
			String relay,
			boolean on) throws IOException {
		if (isBlank(relay)) {
			return new byte[0];
		}
		byte[] command = tcpDidoCommandService.buildRelayCommand(relayIndex(relay), on, didoHexProtocol());
		client.publish(renderTopic(properties.getDido().getDownTopicTemplate(), didoDeviceId), command);
		return command;
	}

	private byte[] buildDidoBatchHexCommand(String didoDeviceId, List<Lane> lanes) {
		int stateMask = 0;
		int enableMask = 0;
		for (Lane lane : lanes) {
			DeviceGatewayProperties.LaneBinding binding = bindingsByLaneId.get(lane.getId());
			if (binding == null) {
				continue;
			}
			if (isEntryDidoDevice(didoDeviceId, binding)) {
				int[] entryRed = applyRelayMask(binding.getEntryRedRelay(), !"GREEN".equals(lane.getEntrySignal()), stateMask, enableMask);
				stateMask = entryRed[0];
				enableMask = entryRed[1];
				int[] entryGreen = applyRelayMask(binding.getEntryGreenRelay(), "GREEN".equals(lane.getEntrySignal()), stateMask, enableMask);
				stateMask = entryGreen[0];
				enableMask = entryGreen[1];
			}
			if (isExitDidoDevice(didoDeviceId, binding)) {
				int[] exitRed = applyRelayMask(binding.getExitRedRelay(), !"GREEN".equals(lane.getExitSignal()), stateMask, enableMask);
				stateMask = exitRed[0];
				enableMask = exitRed[1];
				int[] exitGreen = applyRelayMask(binding.getExitGreenRelay(), "GREEN".equals(lane.getExitSignal()), stateMask, enableMask);
				stateMask = exitGreen[0];
				enableMask = exitGreen[1];
			}
		}
		if (enableMask == 0) {
			return null;
		}
		return tcpDidoCommandService.buildRelayCommand(stateMask, enableMask, didoHexProtocol());
	}

	private int[] applyRelayMask(String relay, boolean on, int stateMask, int enableMask) {
		if (isBlank(relay)) {
			return new int[] { stateMask, enableMask };
		}
		int bit = 1 << (relayIndex(relay) - 1);
		int nextStateMask = on ? (stateMask | bit) : (stateMask & ~bit);
		int nextEnableMask = enableMask | bit;
		return new int[] { nextStateMask, nextEnableMask };
	}

	private String buildDidoDeviceSyncState(String didoDeviceId, List<Lane> lanes) {
		return lanes.stream()
				.map(lane -> {
					DeviceGatewayProperties.LaneBinding binding = bindingsByLaneId.get(lane.getId());
					if (binding == null) {
						return lane.getId();
					}
					StringBuilder state = new StringBuilder(lane.getId());
					if (isEntryDidoDevice(didoDeviceId, binding)) {
						state.append(":")
								.append(firstNonBlank(binding.getEntryRedRelay(), "-"))
								.append("=")
								.append(lane.getEntrySignal())
								.append(":")
								.append(firstNonBlank(binding.getEntryGreenRelay(), "-"))
								.append("=")
								.append(lane.getEntrySignal());
					}
					if (isExitDidoDevice(didoDeviceId, binding)) {
						state.append(":")
								.append(firstNonBlank(binding.getExitRedRelay(), "-"))
								.append("=")
								.append(lane.getExitSignal())
								.append(":")
								.append(firstNonBlank(binding.getExitGreenRelay(), "-"))
								.append("=")
								.append(lane.getExitSignal());
					}
					return state.toString();
				})
				.sorted()
				.reduce((left, right) -> left + "|" + right)
				.orElse("");
	}

	private String summarizeLaneSignals(List<Lane> lanes) {
		return lanes.stream()
				.map(lane -> lane.getId() + "(entry=" + lane.getEntrySignal() + ",exit=" + lane.getExitSignal() + ",count=" + lane.getVehicleCount() + ")")
				.collect(Collectors.joining(","));
	}

	private void syncParkingCamera(
			SimpleMqttClient client,
			Lane lane,
			DeviceGatewayProperties.LaneBinding binding,
			String ledMessage) throws IOException {
		if (!properties.getParkingMf().isEnabled() || isBlank(binding.getMfSn()) || isBlank(binding.getMfGroupId())) {
			return;
		}

		ObjectNode ledData = objectMapper.createObjectNode();
		ledData.put("groupId", binding.getMfGroupId());
		ledData.put("voice", ledMessage);
		ArrayNode show = ledData.putArray("show");
		show.addObject().put("text", truncate(firstNonBlank(lane.getCurrentPlate(), ledMessage), 64));
		publishParkingMfCommand(client, binding, "ledControl", ledData);

		String gateAction = "GREEN".equals(lane.getEntrySignal()) ? "open" : "close";
		String gateKey = lane.getId() + "|" + binding.getMfGroupId();
		if (!Objects.equals(lastGateActions.get(gateKey), gateAction)) {
			ObjectNode gateData = objectMapper.createObjectNode();
			gateData.put("groupId", binding.getMfGroupId());
			gateData.put("action", gateAction);
			publishParkingMfCommand(client, binding, "ioOutput", gateData);
			lastGateActions.put(gateKey, gateAction);
		}
	}

	private void subscribeConfiguredTopics(SimpleMqttClient client) throws IOException {
		Set<String> topicFilters = new LinkedHashSet<>();
		if (properties.getParkingMf().isEnabled()) {
			topicFilters.add(properties.getParkingMf().getUpTopicFilter());
		}
		if (properties.getSmartCamera().isEnabled()) {
			topicFilters.add(properties.getSmartCamera().getUpTopicFilter());
			topicFilters.add(properties.getSmartCamera().getWillTopicFilter());
		}
		if (properties.getDido().isEnabled()) {
			topicFilters.add(properties.getDido().getUpTopicFilter());
		}
		for (String topicFilter : topicFilters) {
			if (!isBlank(topicFilter)) {
				client.subscribe(topicFilter);
			}
		}
	}

	private void requestInitialDeviceState(SimpleMqttClient client) {
		if (properties.getSmartCamera().isEnabled() && properties.getSmartCamera().isRequestVersionOnConnect()) {
			for (DeviceGatewayProperties.LaneBinding binding : uniqueCameraBindings()) {
				try {
					publishSmartCameraCommand(client, binding, "getVerInfo", objectMapper.createObjectNode());
				} catch (Exception ex) {
					log.warn("Failed to request smart camera version for lane {}", binding.getLaneId(), ex);
				}
			}
		}
		if (properties.getDido().isEnabled()) {
			if (didoPayloadMode().startsWith("hex-")
					&& (properties.getDido().isEnableRemoteConfigOnConnect()
							|| properties.getDido().isEnableRelayUploadOnConnect())) {
				for (String didoDeviceId : bindingsByDidoDeviceId.keySet()) {
					try {
						String topic = renderTopic(properties.getDido().getDownTopicTemplate(), didoDeviceId);
						if (properties.getDido().isEnableRemoteConfigOnConnect()) {
							client.publish(topic, CX_ENABLE_REMOTE_CONFIG_COMMAND);
							flowLog.info(
									"节点=DIDO启动指令 event=DIDO_STARTUP_COMMAND protocol=MQTT_HEX deviceId={} topic={} command=ENABLE_REMOTE_CONFIG payloadHex={}",
									didoDeviceId,
									topic,
									bytesToHex(CX_ENABLE_REMOTE_CONFIG_COMMAND));
						}
						if (properties.getDido().isEnableRelayUploadOnConnect()) {
							client.publish(topic, CX_ENABLE_RELAY_UPLOAD_COMMAND);
							flowLog.info(
									"节点=DIDO启动指令 event=DIDO_STARTUP_COMMAND protocol=MQTT_HEX deviceId={} topic={} command=ENABLE_RELAY_UPLOAD payloadHex={}",
									didoDeviceId,
									topic,
									bytesToHex(CX_ENABLE_RELAY_UPLOAD_COMMAND));
						}
					} catch (Exception ex) {
						log.warn("Failed to publish CX DIDO startup command for device {}", didoDeviceId, ex);
					}
				}
				return;
			}
			for (String didoDeviceId : bindingsByDidoDeviceId.keySet()) {
				try {
					ObjectNode payload = objectMapper.createObjectNode();
					payload.put("readall", 0);
					payload.put("res", nextMessageId("dido-read"));
					String topic = renderTopic(properties.getDido().getDownTopicTemplate(), didoDeviceId);
					String payloadText = objectMapper.writeValueAsString(payload);
					client.publish(topic, payloadText);
					flowLog.info(
							"节点=DIDO启动指令 event=DIDO_STARTUP_COMMAND protocol=MQTT_JSON deviceId={} topic={} command=READ_ALL payload={}",
							didoDeviceId,
							topic,
							payloadText);
				} catch (Exception ex) {
					log.warn("Failed to request DIDO state for device {}", didoDeviceId, ex);
				}
			}
		}
	}

	private void publishParkingMfCommand(
			SimpleMqttClient client,
			DeviceGatewayProperties.LaneBinding binding,
			String cmd,
			ObjectNode data) throws IOException {
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("cmd", cmd);
		payload.put("msgId", nextMessageId(cmd));
		payload.put("timestamp", System.currentTimeMillis());
		payload.put("sn", binding.getMfSn());
		payload.set("data", data);
		String topic = renderTopic(properties.getParkingMf().getDownTopicTemplate(), binding);
		String payloadText = objectMapper.writeValueAsString(payload);
		client.publish(topic, payloadText);
		flowLog.info(
				"节点=车道停车相机下发 event=PARKING_MF_DOWNLINK laneId={} sn={} groupId={} cmd={} topic={} payload={}",
				binding.getLaneId(),
				binding.getMfSn(),
				binding.getMfGroupId(),
				cmd,
				topic,
				payloadText);
	}

	private void publishParkingMfYardEntryLedControl(String sn, String groupId, String plate) throws IOException {
		SimpleMqttClient client = mqttClient;
		if (client == null || !client.isConnected() || isBlank(sn) || isBlank(plate)) {
			return;
		}
		ObjectNode payload = buildParkingMfYardEntryLedControlPayload(groupId, plate);
		String topic = renderParkingMfTopic(sn);
		String payloadText = objectMapper.writeValueAsString(payload);
		client.publish(topic, payloadText);
		flowLog.info(
				"节点=总入口MF屏显下发 event=YARD_MF_LED_CONTROL sn={} groupId={} plate={} topic={} payload={}",
				sn,
				groupId,
				plate,
				topic,
				payloadText);
	}

	private ObjectNode buildParkingMfYardEntryLedControlPayload(String groupId, String plate) {
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("cmd", "ledControl");
		payload.put("msgId", nextMessageId("ledControl"));
		payload.put("timestamp", System.currentTimeMillis());

		ObjectNode data = payload.putObject("data");
		data.put("groupId", nullToEmpty(groupId));
		data.put("voice", plate);
		ArrayNode show = data.putArray("show");
		show.addObject().put("text", plate);
		return payload;
	}

	private void publishParkingMfResponse(
			DeviceGatewayProperties.LaneBinding binding,
			String sn,
			String cmd,
			String sourceMsgId) throws IOException {
		publishParkingMfResponse(binding, sn, cmd, sourceMsgId, null);
	}

	private void publishParkingMfResponse(
			DeviceGatewayProperties.LaneBinding binding,
			String sn,
			String cmd,
			String sourceMsgId,
			JsonNode responseData) throws IOException {
		publishParkingMfResponse(
				renderTopic(properties.getParkingMf().getDownTopicTemplate(), binding),
				firstNonBlank(sn, binding.getMfSn()),
				cmd,
				sourceMsgId,
				responseData);
	}

	private void publishParkingMfResponse(
			String sn,
			String cmd,
			String sourceMsgId) throws IOException {
		publishParkingMfResponse(sn, cmd, sourceMsgId, null);
	}

	private void publishParkingMfResponse(
			String sn,
			String cmd,
			String sourceMsgId,
			JsonNode responseData) throws IOException {
		publishParkingMfResponse(renderParkingMfTopic(sn), sn, cmd, sourceMsgId, responseData);
	}

	private void publishParkingMfResponse(
			String topic,
			String sn,
			String cmd,
			String sourceMsgId,
			JsonNode responseData) throws IOException {
		SimpleMqttClient client = mqttClient;
		if (client == null || !client.isConnected()) {
			return;
		}
		ObjectNode payload = buildParkingMfResponsePayload(sn, cmd, sourceMsgId, responseData);
		String payloadText = objectMapper.writeValueAsString(payload);
		client.publish(topic, payloadText);
		flowLog.info(
				"节点=MF相机确认回包 event=PARKING_MF_RESPONSE sn={} cmd={} msgId={} topic={} payload={}",
				sn,
				cmd,
				payload.path("msgId").asText(),
				topic,
				payloadText);
	}

	private ObjectNode buildParkingMfResponsePayload(String sn, String cmd, String sourceMsgId, JsonNode responseData) {
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("cmd", cmd);
		payload.put("msgId", firstNonBlank(sourceMsgId, nextMessageId(cmd)));
		payload.put("timestamp", System.currentTimeMillis());
		payload.put("sn", sn);
		payload.put("timezone", "Asia/Shanghai");
		payload.set("data", buildParkingMfResponseData(responseData));
		return payload;
	}

	private ObjectNode buildParkingMfResponseData(JsonNode sourceData) {
		ObjectNode data = objectMapper.createObjectNode();
		if (sourceData == null || sourceData.isMissingNode() || sourceData.isNull()) {
			return data;
		}
		copyJsonField(sourceData, data, "deviceNo");
		copyJsonField(sourceData, data, "groupId");
		copyJsonField(sourceData, data, "plateNo");
		copyJsonField(sourceData, data, "plateColor");
		copyJsonField(sourceData, data, "carImg");
		copyJsonField(sourceData, data, "parkingTime");
		copyJsonField(sourceData, data, "confidence");
		copyJsonField(sourceData, data, "carBrand");
		copyJsonField(sourceData, data, "realTime");
		copyJsonField(sourceData, data, "state");
		copyJsonField(sourceData, data, "uploadTime");
		return data;
	}

	private void copyJsonField(JsonNode source, ObjectNode target, String fieldName) {
		if (source.has(fieldName) && !source.get(fieldName).isNull()) {
			target.set(fieldName, source.get(fieldName).deepCopy());
		}
	}

	private void publishSmartCameraCommand(
			SimpleMqttClient client,
			DeviceGatewayProperties.LaneBinding binding,
			String cmd,
			ObjectNode content) throws IOException {
		if (isBlank(binding.getCameraDevId())) {
			return;
		}
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("cmd", cmd);
		payload.put("msgId", nextMessageId(cmd));
		payload.put("devId", binding.getCameraDevId());
		payload.put("utcTs", System.currentTimeMillis());
		payload.set("content", content);
		String topic = renderTopic(properties.getSmartCamera().getDownTopicTemplate(), binding);
		String payloadText = objectMapper.writeValueAsString(payload);
		client.publish(topic, payloadText);
		flowLog.info(
				"节点=SmartCamera指令下发 event=SMART_CAMERA_COMMAND laneId={} devId={} cmd={} topic={} payload={}",
				binding.getLaneId(),
				binding.getCameraDevId(),
				cmd,
				topic,
				payloadText);
	}

	private String renderTopic(String template, DeviceGatewayProperties.LaneBinding binding) {
		return template
				.replace("{laneId}", nullToEmpty(binding.getLaneId()))
				.replace("{mfSn}", nullToEmpty(binding.getMfSn()))
				.replace("{mfGroupId}", nullToEmpty(binding.getMfGroupId()))
				.replace("{cameraDevId}", nullToEmpty(binding.getCameraDevId()))
				.replace("{entryDidoDeviceId}", nullToEmpty(resolveEntryDidoDeviceId(binding)))
				.replace("{exitDidoDeviceId}", nullToEmpty(resolveExitDidoDeviceId(binding)))
				.replace("{didoDeviceId}", nullToEmpty(binding.getDidoDeviceId()));
	}

	private String renderTopic(String template, String deviceId) {
		return template
				.replace("{didoDeviceId}", deviceId)
				.replace("{cameraDevId}", deviceId)
				.replace("{deviceId}", deviceId);
	}

	private String renderParkingMfTopic(String sn) {
		return properties.getParkingMf().getDownTopicTemplate()
				.replace("{mfSn}", nullToEmpty(sn))
				.replace("{deviceId}", nullToEmpty(sn));
	}

	private void putRelayState(ObjectNode payload, String relayKey, boolean on) {
		if (!isBlank(relayKey)) {
			payload.put(relayKey, didoRelayValue(on));
		}
	}

	private String resolveRelayKey(DeviceGatewayProperties.LaneBinding binding, String relayTarget) {
		String normalizedTarget = normalizeRelayTarget(relayTarget);
		return switch (normalizedTarget) {
			case "ENTRY_RED" -> binding.getEntryRedRelay();
			case "ENTRY_GREEN" -> binding.getEntryGreenRelay();
			case "EXIT_RED" -> binding.getExitRedRelay();
			case "EXIT_GREEN" -> binding.getExitGreenRelay();
			default -> relayTarget;
		};
	}

	private String resolveRelayDidoDeviceId(DeviceGatewayProperties.LaneBinding binding, String relayTarget) {
		String normalizedTarget = normalizeRelayTarget(relayTarget);
		return switch (normalizedTarget) {
			case "ENTRY_RED", "ENTRY_GREEN" -> resolveEntryDidoDeviceId(binding);
			case "EXIT_RED", "EXIT_GREEN" -> resolveExitDidoDeviceId(binding);
			default -> firstNonBlank(resolveEntryDidoDeviceId(binding), resolveExitDidoDeviceId(binding));
		};
	}

	private String relayDisplayName(String relayTarget) {
		return switch (normalizeRelayTarget(relayTarget)) {
			case "ENTRY_RED" -> "入口红灯";
			case "ENTRY_GREEN" -> "入口绿灯";
			case "EXIT_RED" -> "出口红灯";
			case "EXIT_GREEN" -> "出口绿灯";
			default -> relayTarget;
		};
	}

	private String normalizeRelayTarget(String relayTarget) {
		return relayTarget == null ? "" : relayTarget.trim().toUpperCase(Locale.ROOT).replace('-', '_');
	}

	private int didoRelayValue(boolean on) {
		String mode = properties.getDido().getRelayMode();
		if (!on) {
			return 100000;
		}
		if ("pulse_ms".equalsIgnoreCase(mode)) {
			return 900000 + Math.max(1, Math.min(properties.getDido().getPulseMilliseconds(), 9999));
		}
		if ("pulse_seconds".equalsIgnoreCase(mode)) {
			return 200000 + Math.max(1, Math.min(properties.getDido().getPulseMilliseconds() / 1000, 9999));
		}
		if ("pulse_minutes".equalsIgnoreCase(mode)) {
			return 800000 + Math.max(1, Math.min(properties.getDido().getPulseMilliseconds() / 60000, 9999));
		}
		return 110000;
	}

	private String didoPayloadMode() {
		String mode = properties.getDido().getPayloadMode();
		return isBlank(mode) ? "json" : mode.trim().toLowerCase(Locale.ROOT);
	}

	private String didoHexProtocol() {
		return "hex-a3".equals(didoPayloadMode()) ? "A3" : "A1";
	}

	private int relayIndex(String relay) {
		String digits = relay.replaceAll("\\D+", "");
		if (digits.isBlank()) {
			throw new IllegalArgumentException("Relay key must contain a number: " + relay);
		}
		return Integer.parseInt(digits);
	}

	private String resolveSignalFromRelayFeedback(JsonNode message, String greenRelayKey, String redRelayKey) {
		boolean hasGreen = !isBlank(greenRelayKey) && message.has(greenRelayKey);
		boolean hasRed = !isBlank(redRelayKey) && message.has(redRelayKey);
		if (!hasGreen && !hasRed) {
			return null;
		}
		boolean greenOn = hasGreen && relayFeedbackOn(message.path(greenRelayKey));
		boolean redOn = hasRed && relayFeedbackOn(message.path(redRelayKey));
		if (hasGreen && !hasRed) {
			return greenOn ? "GREEN" : "RED";
		}
		if (hasRed && !hasGreen) {
			return redOn ? "RED" : "GREEN";
		}
		if (greenOn && !redOn) {
			return "GREEN";
		}
		if (redOn && !greenOn) {
			return "RED";
		}
		if (greenOn) {
			log.warn("DIDO relay feedback has both red and green active, prefer GREEN");
			return "GREEN";
		}
		return "RED";
	}

	private boolean relayFeedbackOn(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return false;
		}
		if (node.isBoolean()) {
			return node.asBoolean();
		}
		if (node.isNumber()) {
			int value = node.asInt();
			return value != 0 && value != 100000;
		}
		String value = node.asText("").trim();
		if (value.isBlank()) {
			return false;
		}
		return switch (value.toUpperCase(Locale.ROOT)) {
			case "1", "TRUE", "ON", "OPEN", "GREEN", "110000" -> true;
			default -> false;
		};
	}

	private boolean discreteInputTriggered(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return false;
		}
		if (node.isBoolean()) {
			return node.asBoolean();
		}
		if (node.isNumber()) {
			return node.asInt() == 1;
		}
		String value = node.asText("").trim();
		if (value.isBlank()) {
			return false;
		}
		return switch (value.toUpperCase(Locale.ROOT)) {
			case "1", "TRUE", "ON", "OPEN", "TRIGGERED" -> true;
			default -> false;
		};
	}

	private List<DeviceGatewayProperties.LaneBinding> findMfBindings(String sn, String groupId, String deviceNo) {
		List<DeviceGatewayProperties.LaneBinding> result = new ArrayList<>();
		if (!isBlank(groupId)) {
			result.addAll(bindingsByMfGroupId.getOrDefault(groupId, List.of()));
		}
		if (result.isEmpty() && !isBlank(deviceNo)) {
			result.addAll(bindingsByMfDeviceNo.getOrDefault(deviceNo, List.of()));
		}
		if (result.isEmpty() && !isBlank(sn)) {
			result.addAll(bindingsByMfSn.getOrDefault(sn, List.of()));
		}
		if (!isBlank(sn)) {
			result = result.stream()
					.filter(binding -> isBlank(binding.getMfSn()) || sn.equals(binding.getMfSn()))
					.toList();
		}
		return result;
	}

	private DeviceGatewayProperties.LaneBinding firstBinding(List<DeviceGatewayProperties.LaneBinding> bindings) {
		return bindings.isEmpty() ? null : bindings.get(0);
	}

	private List<DeviceGatewayProperties.LaneBinding> uniqueCameraBindings() {
		Map<String, DeviceGatewayProperties.LaneBinding> unique = new HashMap<>();
		for (DeviceGatewayProperties.LaneBinding binding : bindingsByCameraDevId.values()) {
			unique.putIfAbsent(binding.getCameraDevId(), binding);
		}
		return List.copyOf(unique.values());
	}

	private void addBinding(
			Map<String, List<DeviceGatewayProperties.LaneBinding>> target,
			String key,
			DeviceGatewayProperties.LaneBinding binding) {
		if (!isBlank(key)) {
			target.computeIfAbsent(key, ignored -> new ArrayList<>()).add(binding);
		}
	}

	private void addDidoBinding(String didoDeviceId, DeviceGatewayProperties.LaneBinding binding) {
		if (isBlank(didoDeviceId)) {
			return;
		}
		List<DeviceGatewayProperties.LaneBinding> bindings = bindingsByDidoDeviceId.computeIfAbsent(didoDeviceId, ignored -> new ArrayList<>());
		if (!bindings.contains(binding)) {
			bindings.add(binding);
		}
	}

	private void addCameraBinding(String cameraDevId, DeviceGatewayProperties.LaneBinding binding) {
		if (!isBlank(cameraDevId)) {
			bindingsByCameraDevId.put(cameraDevIdKey(cameraDevId), binding);
		}
	}

	private DeviceGatewayProperties.LaneBinding cameraBinding(String cameraDevId) {
		return bindingsByCameraDevId.get(cameraDevIdKey(cameraDevId));
	}

	private boolean cameraDevIdMatches(String actual, String expected) {
		return !isBlank(actual) && !isBlank(expected) && cameraDevIdKey(actual).equals(cameraDevIdKey(expected));
	}

	private String cameraDevIdKey(String cameraDevId) {
		return isBlank(cameraDevId) ? "" : cameraDevId.trim().toUpperCase(Locale.ROOT);
	}

	private void addDidoLane(Map<String, List<Lane>> target, String didoDeviceId, Lane lane) {
		if (isBlank(didoDeviceId)) {
			return;
		}
		List<Lane> lanes = target.computeIfAbsent(didoDeviceId, ignored -> new ArrayList<>());
		if (lanes.stream().noneMatch(existing -> existing.getId().equals(lane.getId()))) {
			lanes.add(lane);
		}
	}

	private String resolveEntryDidoDeviceId(DeviceGatewayProperties.LaneBinding binding) {
		return firstNonBlank(binding.getEntryDidoDeviceId(), binding.getDidoDeviceId());
	}

	private String resolveExitDidoDeviceId(DeviceGatewayProperties.LaneBinding binding) {
		return firstNonBlank(binding.getExitDidoDeviceId(), binding.getDidoDeviceId());
	}

	private boolean isEntryDidoDevice(String didoDeviceId, DeviceGatewayProperties.LaneBinding binding) {
		return !isBlank(didoDeviceId) && didoDeviceId.equals(resolveEntryDidoDeviceId(binding));
	}

	private boolean isExitDidoDevice(String didoDeviceId, DeviceGatewayProperties.LaneBinding binding) {
		return !isBlank(didoDeviceId) && didoDeviceId.equals(resolveExitDidoDeviceId(binding));
	}

	private boolean isYardEntryCamera(String devId) {
		return cameraDevIdMatches(devId, properties.getSmartCamera().getYardEntryCameraDevId());
	}

	private boolean isYardEntryParkingMf(String sn, String groupId, String deviceNo) {
		DeviceGatewayProperties.ParkingMfProperties parkingMf = properties.getParkingMf();
		boolean snConfigured = !isBlank(parkingMf.getYardEntrySn());
		boolean groupConfigured = !isBlank(parkingMf.getYardEntryGroupId());
		boolean deviceConfigured = !isBlank(parkingMf.getYardEntryDeviceNo());
		if (!snConfigured && !groupConfigured && !deviceConfigured) {
			return false;
		}
		if (snConfigured) {
			return matchesConfigured(parkingMf.getYardEntrySn(), sn);
		}
		return matchesConfigured(parkingMf.getYardEntryGroupId(), groupId)
				&& matchesConfigured(parkingMf.getYardEntryDeviceNo(), deviceNo);
	}

	private boolean matchesConfigured(String expected, String actual) {
		return isBlank(expected) || expected.equals(actual);
	}

	private void logParkingMfYardConfigMismatch(String sn, String groupId, String deviceNo) {
		DeviceGatewayProperties.ParkingMfProperties parkingMf = properties.getParkingMf();
		boolean groupMismatch = !isBlank(parkingMf.getYardEntryGroupId()) && !Objects.equals(parkingMf.getYardEntryGroupId(), groupId);
		boolean deviceMismatch = !isBlank(parkingMf.getYardEntryDeviceNo()) && !Objects.equals(parkingMf.getYardEntryDeviceNo(), deviceNo);
		if (groupMismatch || deviceMismatch) {
			log.warn(
					"Parking MF plateResult is treated as yard entry because SN matched, but configured group/device does not match payload. sn={} groupId={} configuredGroupId={} deviceNo={} configuredDeviceNo={}. Fix .env or leave APP_DEVICE_PARKING_MF_YARD_ENTRY_DEVICE_NO blank.",
					sn,
					groupId,
					nullToEmpty(parkingMf.getYardEntryGroupId()),
					deviceNo,
					nullToEmpty(parkingMf.getYardEntryDeviceNo()));
		}
	}

	private boolean isActiveEntrySmartCamera(String devId) {
		return cameraDevIdMatches(devId, properties.getSmartCamera().getActiveEntryCameraDevId());
	}

	private void updateLaneDeviceStatus(String laneId, String sensorStatus, OffsetDateTime observedAt, String ledMessage) {
		OperationsService operationsService = operationsServiceProvider.getIfAvailable();
		if (operationsService != null) {
			operationsService.updateLaneDeviceStatus(laneId, sensorStatus, observedAt, ledMessage);
		}
	}

	private String resolveOpenEntryLaneIdForDevice(OffsetDateTime referenceTime) {
		OperationsService operationsService = operationsServiceProvider.getIfAvailable();
		if (operationsService == null) {
			return null;
		}
		return operationsService.resolveOpenEntryLaneIdForDevice(referenceTime);
	}

	private void registerVehicleEntryFromDevice(String laneId, String plate, OffsetDateTime entryTime, String vehicleType, String source) {
		OperationsService operationsService = operationsServiceProvider.getIfAvailable();
		if (operationsService != null) {
			log.info("Lane entry registration from MQTT laneId={} plate={} source={} entryTime={}", laneId, plate, source, entryTime);
			flowLog.info(
					"节点=车道入场登记请求 event=LANE_ENTRY_REGISTER_REQUEST laneId={} plate={} vehicleType={} source={} entryTime={}",
					laneId,
					plate,
					vehicleType,
					source,
					entryTime);
			operationsService.registerVehicleEntryFromDevice(laneId, plate, entryTime, vehicleType, source);
		} else {
			log.warn("Cannot register lane entry because OperationsService is unavailable laneId={} plate={} source={}", laneId, plate, source);
		}
	}

	private void registerYardEntryFromDevice(String plate, OffsetDateTime capturedAt, String source) {
		registerYardEntryFromDevice(plate, capturedAt, source, null);
	}

	private void registerYardEntryFromDevice(String plate, OffsetDateTime capturedAt, String source, String plateColor) {
		OperationsService operationsService = operationsServiceProvider.getIfAvailable();
		if (operationsService != null) {
			DispatchTicket ticket = operationsService.registerYardEntry(new YardEntryPayload(plate, "出租车", source, capturedAt, plateColor));
			if (ticket == null) {
				log.info("Yard entry registration from MQTT ignored plate={} plateColor={} source={} capturedAt={}", plate, plateColor, source, capturedAt);
				flowLog.info(
						"节点=总入口抓拍忽略 event=YARD_ENTRY_IGNORED plate={} plateColor={} source={} capturedAt={}",
						plate,
						plateColor,
						source,
						capturedAt);
				return;
			}
			log.info(
					"Yard entry registration from MQTT completed plate={} plateColor={} source={} capturedAt={} ticketId={} status={} assignedLane={}",
					plate,
					plateColor,
					source,
					capturedAt,
					ticket.getId(),
					ticket.getStatus(),
					ticket.getAssignedLaneId());
			flowLog.info(
					"节点=总入口抓拍登记完成 event=YARD_ENTRY_REGISTERED plate={} plateColor={} source={} capturedAt={} ticketId={} status={} assignedLane={}",
					plate,
					plateColor,
					source,
					capturedAt,
					ticket.getId(),
					ticket.getStatus(),
					ticket.getAssignedLaneId());
		} else {
			log.warn("Cannot register yard entry because OperationsService is unavailable plate={} source={} capturedAt={}", plate, source, capturedAt);
		}
	}

	private void applyLanePresenceSignal(String laneId, boolean haveCar, OffsetDateTime observedAt) {
		OperationsService operationsService = operationsServiceProvider.getIfAvailable();
		if (operationsService != null) {
			operationsService.applyLanePresenceSignal(laneId, haveCar, observedAt);
		}
	}

	private void applyLaneExitTrigger(String laneId, OffsetDateTime observedAt) {
		OperationsService operationsService = operationsServiceProvider.getIfAvailable();
		if (operationsService != null) {
			flowLog.info("节点=出口地感执行业务 event=EXIT_LOOP_APPLY laneId={} observedAt={}", laneId, observedAt);
			operationsService.applyLaneExitTrigger(laneId, observedAt);
		}
	}

	private void updateLaneSignalFeedback(
			String laneId,
			String entrySignal,
			String exitSignal,
			OffsetDateTime observedAt,
			String message) {
		OperationsService operationsService = operationsServiceProvider.getIfAvailable();
		if (operationsService != null) {
			operationsService.updateLaneSignalFeedback(laneId, entrySignal, exitSignal, observedAt, message);
		}
	}

	private String resolveLedMessage(Lane lane) {
		String message;
		if ("OFFLINE".equals(lane.getMode()) || "OFFLINE".equals(lane.getSensorStatus())) {
			message = "设备离线，等待现场复位";
		} else if ("GREEN".equals(lane.getExitSignal())) {
			message = "出口放行，请按序通行";
		} else if ("FULL".equals(lane.getStatus())) {
			message = "车道已满，入口禁入";
		} else if ("GREEN".equals(lane.getEntrySignal())) {
			message = "入口开放，请驶入本车道";
		} else if ("RED".equals(lane.getEntrySignal())) {
			message = "入口待命，请按绿灯车道通行";
		} else if ("BUSY".equals(lane.getStatus())) {
			message = "车道繁忙，请减速慢行";
		} else {
			message = "请有序通行";
		}
		if (lane.isPriority()) {
			message += " | 当前优先放行";
		}
		return message;
	}

	private boolean topicMatchesFilter(String topic, String filter) {
		if (isBlank(topic) || isBlank(filter)) {
			return false;
		}
		String[] topicParts = topic.split("/", -1);
		String[] filterParts = filter.split("/", -1);
		for (int index = 0; index < filterParts.length; index++) {
			if (index >= topicParts.length) {
				return "#".equals(filterParts[index]);
			}
			String filterPart = filterParts[index];
			if ("#".equals(filterPart)) {
				return true;
			}
			if (!"+".equals(filterPart) && !filterPart.equals(topicParts[index])) {
				return false;
			}
		}
		return topicParts.length == filterParts.length;
	}

	private String extractMfSnFromTopic(String topic) {
		String[] parts = topic.split("/");
		return parts.length > 1 ? parts[1] : null;
	}

	private String extractDeviceIdFromTopic(String topic) {
		String[] parts = topic.split("/");
		for (int index = 0; index < parts.length - 1; index++) {
			if ("device".equals(parts[index])) {
				return parts[index + 1];
			}
		}
		return null;
	}

	private OffsetDateTime parseDeviceTime(String value) {
		if (isBlank(value)) {
			return now();
		}
		String trimmed = value.trim();
		if (trimmed.matches("\\d+")) {
			long numeric = Long.parseLong(trimmed);
			long millis = numeric > 10_000_000_000L ? numeric : numeric * 1000;
			return OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), DEVICE_ZONE);
		}
		try {
			return OffsetDateTime.parse(trimmed);
		} catch (DateTimeParseException ignored) {
			// continue with vendor-local formats
		}
		try {
			return LocalDateTime.parse(trimmed, DASH_TIME_FORMATTER).atOffset(DEVICE_ZONE);
		} catch (DateTimeParseException ignored) {
			// continue with slash separated format
		}
		try {
			return LocalDateTime.parse(trimmed, SLASH_TIME_FORMATTER).atOffset(DEVICE_ZONE);
		} catch (DateTimeParseException ignored) {
			log.debug("Unrecognized device time '{}', fallback to current time", value);
			return now();
		}
	}

	private OffsetDateTime observedAtFromMessage(JsonNode message) {
		JsonNode content = message.path("content");
		return parseDeviceTime(firstNonBlank(text(content.path("alarmTime")), text(message.path("utcTs"))));
	}

	private String extractPlateColor(JsonNode node) {
		return firstNonBlank(
				text(node.path("plateColor")),
				text(node.path("plateColour")),
				text(node.path("plate_color")),
				text(node.path("plateColorType")),
				text(node.path("plateColorName")));
	}

	private OffsetDateTime now() {
		return OffsetDateTime.now(DEVICE_ZONE);
	}

	private int intValue(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return 0;
		}
		if (node.isNumber()) {
			return node.asInt();
		}
		String value = node.asText("");
		if (value.isBlank()) {
			return 0;
		}
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}

	private String text(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		if (node.isTextual()) {
			return node.asText();
		}
		if (node.isNumber() || node.isBoolean()) {
			return node.asText();
		}
		return null;
	}

	private String firstNonBlank(String... values) {
		for (String value : values) {
			if (!isBlank(value)) {
				return value;
			}
		}
		return null;
	}

	private String nextMessageId(String prefix) {
		return prefix + "-" + messageCounter.incrementAndGet();
	}

	private String truncate(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength);
	}

	private String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private static byte[] hexBytes(String hex) {
		String cleaned = hex.replaceAll("\\s+", "");
		byte[] bytes = new byte[cleaned.length() / 2];
		for (int index = 0; index < bytes.length; index++) {
			bytes[index] = (byte) Integer.parseInt(cleaned.substring(index * 2, index * 2 + 2), 16);
		}
		return bytes;
	}

	private static String bytesToHex(byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			return "";
		}
		StringBuilder builder = new StringBuilder(bytes.length * 2);
		for (byte value : bytes) {
			builder.append(String.format("%02X", value & 0xFF));
		}
		return builder.toString();
	}

	private void closeClient() {
		SimpleMqttClient client = mqttClient;
		mqttClient = null;
		if (client != null) {
			client.disconnect();
		}
	}

	private record AlarmMapping(String type, String level, String message, boolean endEvent) {

		static AlarmMapping from(String alarmType) {
			return switch (alarmType) {
				case "49406" -> new AlarmMapping("TF_CARD_FAULT", "WARNING", "TF 卡异常", false);
				case "49407" -> new AlarmMapping("PLATE_MISMATCH", "WARNING", "车牌不一致", false);
				case "49409" -> new AlarmMapping("MOTOR_STAY", "WARNING", "机动车滞留", false);
				case "49411" -> new AlarmMapping("NON_MOTOR_STAY", "WARNING", "非机动车滞留", false);
				case "49412" -> new AlarmMapping("BARRIER_ABNORMAL", "DANGER", "道闸异常", false);
				case "49413" -> new AlarmMapping("LANE_CONGESTION", "DANGER", "车道拥堵", false);
				case "49414" -> new AlarmMapping("PEDESTRIAN_LOITERING", "WARNING", "行人徘徊", false);
				case "49415" -> new AlarmMapping("BARRIER2_ABNORMAL", "DANGER", "第二道闸异常", false);
				case "49664" -> new AlarmMapping("TAILGATING", "DANGER", "车辆跟车", false);
				case "49665" -> new AlarmMapping("MOTOR_STAY", "INFO", "机动车滞留", true);
				case "49667" -> new AlarmMapping("NON_MOTOR_STAY", "INFO", "非机动车滞留", true);
				case "49668" -> new AlarmMapping("BARRIER_ABNORMAL", "INFO", "道闸异常", true);
				case "49669" -> new AlarmMapping("LANE_CONGESTION", "INFO", "车道拥堵", true);
				case "49670" -> new AlarmMapping("PEDESTRIAN_LOITERING", "INFO", "行人徘徊", true);
				case "49671" -> new AlarmMapping("BARRIER2_ABNORMAL", "INFO", "第二道闸异常", true);
				default -> null;
			};
		}
	}
}
