package com.smartlane.dispatch.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.smartlane.dispatch.device.LaneDeviceGateway;
import com.smartlane.dispatch.dto.BlacklistPayload;
import com.smartlane.dispatch.dto.DashboardMetric;
import com.smartlane.dispatch.dto.DashboardPayload;
import com.smartlane.dispatch.dto.LaneSensorPayload;
import com.smartlane.dispatch.dto.ManualDispatchRequest;
import com.smartlane.dispatch.dto.SignalOverrideRequest;
import com.smartlane.dispatch.dto.ThroughputPoint;
import com.smartlane.dispatch.dto.VehicleEntryPayload;
import com.smartlane.dispatch.entity.AlertEvent;
import com.smartlane.dispatch.entity.BlacklistRecord;
import com.smartlane.dispatch.entity.EntryLog;
import com.smartlane.dispatch.entity.Lane;
import com.smartlane.dispatch.repository.AlertEventRepository;
import com.smartlane.dispatch.repository.BlacklistRecordRepository;
import com.smartlane.dispatch.repository.EntryLogRepository;
import com.smartlane.dispatch.repository.LaneRepository;

@Service
@Transactional(readOnly = true)
public class OperationsService {

	private static final List<String> VALID_SIGNAL_STATES = List.of("RED", "YELLOW", "GREEN", "OFFLINE");
	private static final List<String> VALID_LANE_MODES = List.of("AUTO", "MANUAL", "OFFLINE");
	private static final List<String> VALID_BLACKLIST_LEVELS = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
	private static final List<String> VALID_SENSOR_STATES = List.of("ONLINE", "DEGRADED", "OFFLINE");
	private static final List<String> VALID_COMMAND_TYPES = List.of(
			"FORCE_OPEN_GATE",
			"MANUAL_ENTRY",
			"PLATE_CORRECTION",
			"TEMP_ALLOW",
			"CORRECT_COUNT",
			"SET_PRIORITY");

	private final LaneRepository laneRepository;
	private final EntryLogRepository entryLogRepository;
	private final BlacklistRecordRepository blacklistRecordRepository;
	private final AlertEventRepository alertEventRepository;
	private final BroadcastService broadcastService;
	private final DashboardCacheService dashboardCacheService;
	private final LaneDeviceGateway laneDeviceGateway;

	public OperationsService(
			LaneRepository laneRepository,
			EntryLogRepository entryLogRepository,
			BlacklistRecordRepository blacklistRecordRepository,
			AlertEventRepository alertEventRepository,
			BroadcastService broadcastService,
			DashboardCacheService dashboardCacheService,
			LaneDeviceGateway laneDeviceGateway) {
		this.laneRepository = laneRepository;
		this.entryLogRepository = entryLogRepository;
		this.blacklistRecordRepository = blacklistRecordRepository;
		this.alertEventRepository = alertEventRepository;
		this.broadcastService = broadcastService;
		this.dashboardCacheService = dashboardCacheService;
		this.laneDeviceGateway = laneDeviceGateway;
	}

	public DashboardPayload getDashboard() {
		return dashboardCacheService.getDashboard().orElseGet(() -> {
			DashboardPayload payload = buildDashboardPayload();
			dashboardCacheService.cacheDashboard(payload);
			return payload;
		});
	}

	public List<Lane> getLanes() {
		return laneRepository.findAllByOrderByCodeAsc();
	}

	public List<AlertEvent> getAlerts() {
		return alertEventRepository.findAllByOrderByCreatedAtDesc();
	}

	@Transactional
	public void resolveAlert(String alertId) {
		AlertEvent alert = alertEventRepository.findById(alertId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "预警不存在"));
		alert.setStatus("RESOLVED");
		alertEventRepository.save(alert);
		invalidateRuntimeViews("alert_resolved");
	}

	public List<EntryLog> getLogs(String query, String status, String laneId) {
		return entryLogRepository.findAllByOrderByEntryTimeDesc().stream()
				.filter(log -> isBlank(query)
						|| containsIgnoreCase(log.getId(), query)
						|| containsIgnoreCase(log.getPlate(), query)
						|| containsIgnoreCase(log.getLaneId(), query)
						|| containsIgnoreCase(log.getLaneName(), query)
						|| containsIgnoreCase(log.getSource(), query)
						|| containsIgnoreCase(log.getOperator(), query))
				.filter(log -> isBlank(status) || status.equalsIgnoreCase(log.getStatus()))
				.filter(log -> isBlank(laneId) || laneId.equalsIgnoreCase(log.getLaneId()))
				.toList();
	}

	public List<BlacklistRecord> getBlacklist(String query) {
		return blacklistRecordRepository.findAllByOrderByEffectiveDateDesc().stream()
				.filter(record -> isBlank(query)
						|| containsIgnoreCase(record.getPlate(), query)
						|| containsIgnoreCase(record.getReason(), query)
						|| containsIgnoreCase(record.getOperator(), query))
				.toList();
	}

	@Transactional
	public BlacklistRecord createBlacklist(BlacklistPayload payload) {
		validateBlacklistPayload(payload);
		BlacklistRecord record = BlacklistRecord.builder()
				.id("BL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT))
				.plate(normalizePlate(payload.plate()))
				.reason(payload.reason())
				.level(payload.level())
				.effectiveDate(now())
				.operator(payload.operator())
				.active(payload.active())
				.build();
		BlacklistRecord saved = blacklistRecordRepository.save(record);
		invalidateRuntimeViews("blacklist_created");
		return saved;
	}

	@Transactional
	public BlacklistRecord updateBlacklist(String id, BlacklistPayload payload) {
		validateBlacklistPayload(payload);
		BlacklistRecord record = blacklistRecordRepository.findById(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "黑名单记录不存在"));
		record.setPlate(normalizePlate(payload.plate()));
		record.setReason(payload.reason());
		record.setLevel(payload.level());
		record.setOperator(payload.operator());
		record.setActive(payload.active());
		record.setEffectiveDate(now());
		BlacklistRecord saved = blacklistRecordRepository.save(record);
		invalidateRuntimeViews("blacklist_updated");
		return saved;
	}

	@Transactional
	public void deleteBlacklist(String id) {
		if (!blacklistRecordRepository.existsById(id)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "黑名单记录不存在");
		}
		blacklistRecordRepository.deleteById(id);
		invalidateRuntimeViews("blacklist_deleted");
	}

	@Transactional
	public Lane overrideSignal(String laneId, SignalOverrideRequest request) {
		validateSignals(request.entrySignal(), request.exitSignal());
		validateLaneMode(request.mode());
		Lane lane = requireLane(laneId);
		OffsetDateTime referenceTime = now();
		lane.setEntrySignal(request.entrySignal());
		lane.setExitSignal(request.exitSignal());
		lane.setMode(request.mode());
		lane.setStatus(resolveStatusForLane(lane));
		lane.setLastActionAt(referenceTime);
		lane.setLedMessage(request.reason());
		alertEventRepository.save(newAlert(
				lane.getId(),
				lane.getName(),
				lane.getCurrentPlate(),
				"SIGNAL_OVERRIDE",
				"INFO",
				"OPEN",
				request.reason()));
		return persistLaneRuntime(lane.getId(), referenceTime, "signal_override");
	}

	@Transactional
	public void restoreAutoControl() {
		OffsetDateTime referenceTime = now();
		for (Lane lane : laneRepository.findAllByOrderByCodeAsc()) {
			lane.setMode("AUTO");
			lane.setLastActionAt(referenceTime);
			lane.setLedMessage("恢复自动联动");
		}
		refreshLaneRuntime(referenceTime);
		invalidateRuntimeViews("restore_auto");
	}

	@Transactional
	public void globalLockdown() {
		OffsetDateTime referenceTime = now();
		for (Lane lane : laneRepository.findAllByOrderByCodeAsc()) {
			lane.setMode("MANUAL");
			lane.setEntrySignal("RED");
			lane.setExitSignal("RED");
			lane.setStatus("FULL");
			lane.setLedMessage("全域锁死，请等待指挥中心");
			lane.setLastActionAt(referenceTime);
		}
		refreshLaneRuntime(referenceTime);
		alertEventRepository.save(newAlert("GLOBAL", "全域控制", null, "GLOBAL_LOCKDOWN", "CRITICAL", "OPEN", "已执行全域锁死"));
		invalidateRuntimeViews("global_lockdown");
	}

	@Transactional
	public void dispatchManual(ManualDispatchRequest request) {
		validateCommandType(request.commandType());
		Lane lane = requireLane(request.laneId());
		OffsetDateTime referenceTime = now();
		String normalizedPlate = isBlank(request.plate()) ? normalizePlate(lane.getCurrentPlate()) : normalizePlate(request.plate());
		String operator = "控制台";
		String vehicleType = isBlank(request.vehicleType()) ? "社会车辆" : request.vehicleType();
		boolean forceManualMode = switch (request.commandType()) {
			case "FORCE_OPEN_GATE", "MANUAL_ENTRY", "PLATE_CORRECTION", "TEMP_ALLOW" -> true;
			default -> false;
		};
		if (forceManualMode) {
			lane.setMode("MANUAL");
		}
		lane.setLastActionAt(referenceTime);

		switch (request.commandType()) {
			case "FORCE_OPEN_GATE", "MANUAL_ENTRY", "TEMP_ALLOW" -> {
				lane.setEntrySignal("GREEN");
				lane.setExitSignal("GREEN");
				lane.setVehicleCount(Math.min(lane.getCapacity(), lane.getVehicleCount() + 1));
				lane.setLastEntryAt(referenceTime);
				lane.setLastEntryPlate(normalizedPlate);
				lane.setLedMessage("人工指令放行");
				entryLogRepository.save(newEntryLog(
						Objects.requireNonNullElse(normalizedPlate, "MANUAL-PLATE"),
						lane,
						vehicleType,
						"MANUAL",
						"MANUAL",
						operator,
						referenceTime));
			}
			case "PLATE_CORRECTION" -> {
				if (!isBlank(normalizedPlate)) {
					lane.setLastEntryPlate(normalizedPlate);
					lane.setCurrentPlate(normalizedPlate);
					entryLogRepository.save(newEntryLog(normalizedPlate, lane, vehicleType, "MANUAL", "CORRECTION", operator, referenceTime));
				}
				lane.setLedMessage("车牌已人工修正");
			}
			case "CORRECT_COUNT" -> {
				int count = request.correctedVehicleCount() == null ? lane.getVehicleCount() : Math.max(0, request.correctedVehicleCount());
				lane.setVehicleCount(Math.min(lane.getCapacity(), count));
				reconcileLaneQueue(lane, lane.getVehicleCount(), referenceTime);
				lane.setLedMessage("车辆数已人工修正");
			}
			case "SET_PRIORITY" -> {
				lane.setPriority(true);
				lane.setLedMessage("已设为优先放行车道");
			}
			default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的指令类型");
		}

		if (Boolean.TRUE.equals(request.markPriority())) {
			lane.setPriority(true);
		}

		lane.setStatus(resolveStatusForLane(lane));
		if (!isBlank(normalizedPlate)) {
			createBlacklistHitAlertIfNeeded(lane, normalizedPlate);
		}
		if ("CORRECT_COUNT".equals(request.commandType()) || "SET_PRIORITY".equals(request.commandType())) {
			alertEventRepository.save(newAlert(
					lane.getId(),
					lane.getName(),
					normalizedPlate,
					"MANUAL_DISPATCH",
					"INFO",
					"OPEN",
					request.reason()));
		}
		refreshLaneRuntime(referenceTime);
		invalidateRuntimeViews("manual_dispatch");
	}

	@Transactional
	public Lane ingestLaneSensor(LaneSensorPayload payload) {
		validateSensorStatus(payload.sensorStatus());
		Lane lane = requireLane(payload.laneId());
		OffsetDateTime observedAt = resolveTime(payload.observedAt());
		if (payload.capacity() != null && payload.capacity() > 0) {
			lane.setCapacity(payload.capacity());
		}
		lane.setVehicleCount(Math.min(lane.getCapacity(), payload.vehicleCount()));
		lane.setSensorStatus(payload.sensorStatus());
		lane.setLastSensorAt(observedAt);
		lane.setLastActionAt(observedAt);
		reconcileLaneQueue(lane, lane.getVehicleCount(), observedAt);
		return persistLaneRuntime(lane.getId(), observedAt, "lane_sensor_ingested");
	}

	@Transactional
	public EntryLog registerVehicleEntry(VehicleEntryPayload payload) {
		Lane lane = requireLane(payload.laneId());
		OffsetDateTime entryTime = resolveTime(payload.entryTime());
		String plate = normalizePlate(payload.plate());
		String vehicleType = isBlank(payload.vehicleType()) ? "社会车辆" : payload.vehicleType();
		String source = isBlank(payload.source()) ? "ALPR" : payload.source().toUpperCase(Locale.ROOT);

		lane.setVehicleCount(Math.min(lane.getCapacity(), lane.getVehicleCount() + 1));
		lane.setSensorStatus(defaultSensorStatus(lane.getSensorStatus()));
		lane.setLastSensorAt(entryTime);
		lane.setLastEntryAt(entryTime);
		lane.setLastEntryPlate(plate);
		lane.setLastActionAt(entryTime);

		EntryLog entryLog = newEntryLog(plate, lane, vehicleType, "PASSED", source, "设备采集", entryTime);
		EntryLog saved = entryLogRepository.save(entryLog);
		createBlacklistHitAlertIfNeeded(lane, plate);
		persistLaneRuntime(lane.getId(), entryTime, "vehicle_entry_captured");
		return saved;
	}

	private DashboardPayload buildDashboardPayload() {
		List<Lane> lanes = laneRepository.findAllByOrderByCodeAsc();
		List<EntryLog> logs = entryLogRepository.findAllByOrderByEntryTimeDesc();
		List<AlertEvent> alerts = alertEventRepository.findAllByOrderByCreatedAtDesc();
		long activeLanes = lanes.stream().filter(lane -> !"OFFLINE".equals(lane.getStatus())).count();
		double averagePassMinutes = logs.stream()
				.filter(log -> log.getExitTime() != null)
				.mapToDouble(log -> Duration.between(log.getEntryTime(), log.getExitTime()).toMinutes() / 1.0)
				.average()
				.orElse(0.0);
		double healthPenalty = alerts.stream()
				.filter(alert -> "OPEN".equals(alert.getStatus()))
				.mapToDouble(alert -> switch (alert.getLevel()) {
					case "CRITICAL" -> 10.0;
					case "DANGER" -> 6.0;
					case "WARNING", "HIGH" -> 4.0;
					case "MEDIUM" -> 2.0;
					default -> 1.0;
				})
				.sum();
		double health = Math.max(0.0, 100.0 - healthPenalty);

		return new DashboardPayload(
				now(),
				new DashboardMetric(
						logs.size(),
						Math.round(averagePassMinutes * 10.0) / 10.0,
						(int) alerts.stream()
								.filter(alert -> "BLACKLIST_HIT".equals(alert.getType()) && !"RESOLVED".equals(alert.getStatus()))
								.count(),
						Math.round(health * 10.0) / 10.0,
						Math.round((activeLanes * 1000.0 / Math.max(lanes.size(), 1))) / 10.0),
				buildThroughput(logs),
				lanes,
				alerts.stream().limit(4).toList());
	}

	private List<ThroughputPoint> buildThroughput(List<EntryLog> logs) {
		OffsetDateTime reference = now().truncatedTo(ChronoUnit.HOURS);
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
		Map<OffsetDateTime, Long> counts = logs.stream()
				.collect(java.util.stream.Collectors.groupingBy(log -> log.getEntryTime().truncatedTo(ChronoUnit.HOURS),
						java.util.stream.Collectors.counting()));
		return java.util.stream.IntStream.rangeClosed(0, 6)
				.mapToObj(index -> reference.minusHours(6L - index))
				.map(hour -> new ThroughputPoint(hour.format(formatter), counts.getOrDefault(hour, 0L).intValue()))
				.toList();
	}

	private Lane persistLaneRuntime(String laneId, OffsetDateTime referenceTime, String action) {
		List<Lane> lanes = refreshLaneRuntime(referenceTime);
		invalidateRuntimeViews(action);
		return lanes.stream()
				.filter(candidate -> candidate.getId().equals(laneId))
				.findFirst()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "车道不存在"));
	}

	private List<Lane> refreshLaneRuntime(OffsetDateTime referenceTime) {
		List<Lane> lanes = laneRepository.findAllByOrderByCodeAsc();
		List<EntryLog> activeLogs = entryLogRepository.findByExitTimeIsNullOrderByEntryTimeAsc();
		Map<String, EntryLog> headLogs = new HashMap<>();
		for (EntryLog log : activeLogs) {
			headLogs.putIfAbsent(log.getLaneId(), log);
		}
		String releaseLaneId = selectReleaseLaneId(lanes, headLogs);
		for (Lane lane : lanes) {
			EntryLog headLog = headLogs.get(lane.getId());
			if (headLog != null) {
				lane.setCurrentPlate(headLog.getPlate());
			} else if (lane.getVehicleCount() <= 0) {
				lane.setCurrentPlate(null);
			}
			lane.setStatus(resolveStatusForLane(lane));
			if ("AUTO".equals(lane.getMode())) {
				applyAutomaticSignals(lane, lane.getId().equals(releaseLaneId));
			} else if ("OFFLINE".equals(lane.getMode())) {
				lane.setEntrySignal("OFFLINE");
				lane.setExitSignal("OFFLINE");
			}
			if (lane.getLastActionAt() == null) {
				lane.setLastActionAt(referenceTime);
			}
			laneDeviceGateway.syncLane(lane);
		}
		return laneRepository.saveAll(lanes);
	}

	private String selectReleaseLaneId(List<Lane> lanes, Map<String, EntryLog> headLogs) {
		return lanes.stream()
				.filter(lane -> "AUTO".equals(lane.getMode()))
				.filter(lane -> lane.getVehicleCount() > 0)
				.filter(lane -> !"OFFLINE".equals(resolveStatusForLane(lane)))
				.sorted(Comparator
						.comparing(Lane::isPriority).reversed()
						.thenComparing(lane -> queueHeadTime(lane, headLogs), Comparator.nullsLast(Comparator.naturalOrder()))
						.thenComparing(Lane::getCode))
				.map(Lane::getId)
				.findFirst()
				.orElse(null);
	}

	private OffsetDateTime queueHeadTime(Lane lane, Map<String, EntryLog> headLogs) {
		EntryLog headLog = headLogs.get(lane.getId());
		if (headLog != null) {
			return headLog.getEntryTime();
		}
		if (lane.getLastEntryAt() != null) {
			return lane.getLastEntryAt();
		}
		return lane.getLastActionAt();
	}

	private void applyAutomaticSignals(Lane lane, boolean releaseNow) {
		if ("OFFLINE".equals(lane.getStatus())) {
			lane.setEntrySignal("OFFLINE");
			lane.setExitSignal("OFFLINE");
			return;
		}
		if ("FULL".equals(lane.getStatus())) {
			lane.setEntrySignal("RED");
		} else if ("BUSY".equals(lane.getStatus())) {
			lane.setEntrySignal("YELLOW");
		} else {
			lane.setEntrySignal("GREEN");
		}
		if (lane.getVehicleCount() <= 0) {
			lane.setExitSignal("RED");
			return;
		}
		lane.setExitSignal(releaseNow ? "GREEN" : "RED");
	}

	private void reconcileLaneQueue(Lane lane, int targetCount, OffsetDateTime referenceTime) {
		List<EntryLog> activeLogs = entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc(lane.getId());
		if (activeLogs.size() <= targetCount) {
			if (targetCount == 0) {
				lane.setCurrentPlate(null);
				lane.setPriority(false);
			}
			return;
		}
		int exitCount = activeLogs.size() - targetCount;
		for (int index = 0; index < exitCount; index++) {
			activeLogs.get(index).setExitTime(referenceTime);
		}
		entryLogRepository.saveAll(activeLogs.subList(0, exitCount));
		if (exitCount > 0) {
			lane.setPriority(false);
		}
		if (targetCount == 0) {
			lane.setCurrentPlate(null);
		}
	}

	private void createBlacklistHitAlertIfNeeded(Lane lane, String plate) {
		blacklistRecordRepository.findFirstByPlateIgnoreCaseAndActiveTrue(plate)
				.ifPresent(record -> alertEventRepository.save(newAlert(
						lane.getId(),
						lane.getName(),
						plate,
						"BLACKLIST_HIT",
						"CRITICAL",
						"OPEN",
						"黑名单车辆命中: " + record.getReason())));
	}

	private Lane requireLane(String laneId) {
		return laneRepository.findById(laneId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "车道不存在"));
	}

	private EntryLog newEntryLog(
			String plate,
			Lane lane,
			String vehicleType,
			String status,
			String source,
			String operator,
			OffsetDateTime entryTime) {
		return EntryLog.builder()
				.id("LOG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT))
				.plate(plate)
				.laneId(lane.getId())
				.laneName(lane.getName())
				.entryTime(entryTime)
				.exitTime(null)
				.vehicleType(vehicleType)
				.status(status)
				.source(source)
				.operator(operator)
				.build();
	}

	private AlertEvent newAlert(String laneId, String laneName, String plate, String type, String level, String status, String message) {
		return AlertEvent.builder()
				.id("ALT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT))
				.laneId(laneId)
				.laneName(laneName)
				.plate(plate)
				.type(type)
				.level(level)
				.status(status)
				.message(message)
				.createdAt(now())
				.build();
	}

	private void validateSignals(String entrySignal, String exitSignal) {
		if (!VALID_SIGNAL_STATES.contains(entrySignal) || !VALID_SIGNAL_STATES.contains(exitSignal)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "信号灯状态非法");
		}
	}

	private void validateLaneMode(String mode) {
		if (!VALID_LANE_MODES.contains(mode)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "车道模式非法");
		}
	}

	private void validateBlacklistPayload(BlacklistPayload payload) {
		if (!VALID_BLACKLIST_LEVELS.contains(payload.level())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "风险等级非法");
		}
	}

	private void validateSensorStatus(String sensorStatus) {
		if (!VALID_SENSOR_STATES.contains(sensorStatus)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "传感状态非法");
		}
	}

	private void validateCommandType(String commandType) {
		if (!VALID_COMMAND_TYPES.contains(commandType)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "调度指令非法");
		}
	}

	private String resolveStatusForLane(Lane lane) {
		if ("OFFLINE".equals(lane.getMode()) || "OFFLINE".equals(lane.getSensorStatus())) {
			return "OFFLINE";
		}
		if (lane.getCapacity() <= 0) {
			return "OFFLINE";
		}
		double occupancyRate = lane.getVehicleCount() * 1.0 / lane.getCapacity();
		if (lane.getVehicleCount() >= lane.getCapacity() || ("DEGRADED".equals(lane.getSensorStatus()) && occupancyRate >= 0.85)) {
			return "FULL";
		}
		if (occupancyRate >= 0.7 || lane.getVehicleCount() >= Math.max(1, lane.getCapacity() - 1)) {
			return "BUSY";
		}
		return "OPEN";
	}

	private void invalidateRuntimeViews(String action) {
		dashboardCacheService.evictDashboard();
		broadcastService.operationsChanged(action);
	}

	private String defaultSensorStatus(String currentSensorStatus) {
		return isBlank(currentSensorStatus) ? "ONLINE" : currentSensorStatus;
	}

	private String normalizePlate(String plate) {
		if (plate == null) {
			return null;
		}
		return plate.replace("·", "").replace(" ", "").toUpperCase(Locale.ROOT);
	}

	private OffsetDateTime resolveTime(OffsetDateTime value) {
		return value == null ? now() : value;
	}

	private OffsetDateTime now() {
		return OffsetDateTime.now(ZoneOffset.ofHours(8));
	}

	private boolean containsIgnoreCase(String source, String query) {
		return source != null && query != null && source.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
