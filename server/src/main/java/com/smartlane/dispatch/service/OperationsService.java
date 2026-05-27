package com.smartlane.dispatch.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.smartlane.dispatch.device.LaneDeviceGateway;
import com.smartlane.dispatch.dto.BlacklistPayload;
import com.smartlane.dispatch.dto.DashboardPayload;
import com.smartlane.dispatch.dto.DispatchBoardView;
import com.smartlane.dispatch.dto.DispatchConfigRequest;
import com.smartlane.dispatch.dto.DispatchConfigView;
import com.smartlane.dispatch.dto.DispatchRuntimeRequest;
import com.smartlane.dispatch.dto.LaneSensorPayload;
import com.smartlane.dispatch.dto.ManualDispatchRequest;
import com.smartlane.dispatch.dto.RelayControlRequest;
import com.smartlane.dispatch.dto.SignalOverrideRequest;
import com.smartlane.dispatch.dto.ScreenEventView;
import com.smartlane.dispatch.dto.ThroughputPoint;
import com.smartlane.dispatch.dto.VehicleEntryPayload;
import com.smartlane.dispatch.dto.YardEntryPayload;
import com.smartlane.dispatch.entity.BlacklistRecord;
import com.smartlane.dispatch.entity.DispatchConfig;
import com.smartlane.dispatch.entity.DispatchTicket;
import com.smartlane.dispatch.entity.EntryLog;
import com.smartlane.dispatch.entity.Lane;
import com.smartlane.dispatch.entity.ScreenHandledEvent;
import com.smartlane.dispatch.repository.BlacklistRecordRepository;
import com.smartlane.dispatch.repository.DispatchConfigRepository;
import com.smartlane.dispatch.repository.DispatchTicketRepository;
import com.smartlane.dispatch.repository.EntryLogRepository;
import com.smartlane.dispatch.repository.LaneRepository;
import com.smartlane.dispatch.repository.ScreenHandledEventRepository;

import jakarta.annotation.PostConstruct;

@Service
@Transactional(readOnly = true)
public class OperationsService {

	private static final Logger log = LoggerFactory.getLogger(OperationsService.class);
	private static final long SCREEN_BOARD_DIAGNOSTIC_LOG_INTERVAL_MS = 30000L;

	private static final Pattern FIRST_NUMBER = Pattern.compile("\\d+");
	private static final Pattern NUMERIC_RANGE = Pattern.compile("(\\d+)\\s*-\\s*(\\d+)");

	private static final String ENTRY_LANE_ORDER_KEY = "entry_lane_order";
	private static final String ENTRY_DISPATCH_ENABLED_KEY = "entry_dispatch_enabled";
	private static final String EXIT_DISPATCH_ENABLED_KEY = "exit_dispatch_enabled";
	private static final String ACTIVE_ENTRY_LANE_KEY = "active_entry_lane";
	private static final String ACTIVE_EXIT_LANE_KEY = "active_exit_lane";
	private static final String ASSIGNMENT_RESERVE_MINUTES_KEY = "assignment_reserve_minutes";
	private static final String LAST_DAILY_RESET_AT_KEY = "last_daily_reset_at";
	private static final String LEGACY_EXIT_LANE_ORDER_KEY = "exit_lane_order";
	private static final String LEGACY_ENTRY_DISPATCH_CURSOR_KEY = "entry_dispatch_cursor";
	private static final String LEGACY_ENTRY_DISPATCH_PAUSED_LANE_KEY = "entry_dispatch_paused_lane";

	private static final List<String> VALID_SIGNAL_STATES = List.of("RED", "GREEN", "OFFLINE");
	private static final List<String> VALID_LANE_MODES = List.of("AUTO", "MANUAL", "OFFLINE");
	private static final List<String> VALID_RELAY_CONTROL_TARGETS = List.of("ENTRY_RED", "ENTRY_GREEN", "EXIT_RED", "EXIT_GREEN");
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
	private final DispatchConfigRepository dispatchConfigRepository;
	private final EntryLogRepository entryLogRepository;
	private final BlacklistRecordRepository blacklistRecordRepository;
	private final DispatchTicketRepository dispatchTicketRepository;
	private final BroadcastService broadcastService;
	private final ApplicationEventPublisher eventPublisher;
	private final DashboardCacheService dashboardCacheService;
	private final LaneDeviceGateway laneDeviceGateway;
	private final LaneRuntimeStateService laneRuntimeStateService;
	private final ScreenHandledEventRepository screenHandledEventRepository;
	private final List<String> defaultEntryLaneOrder;
	private final boolean defaultEntryDispatchEnabled;
	private final boolean defaultExitDispatchEnabled;
	private final long assignmentReserveMinutes;
	private final AtomicLong lastScreenBoardDiagnosticLogAt = new AtomicLong(0L);

	public OperationsService(
			LaneRepository laneRepository,
			DispatchConfigRepository dispatchConfigRepository,
			EntryLogRepository entryLogRepository,
			BlacklistRecordRepository blacklistRecordRepository,
			DispatchTicketRepository dispatchTicketRepository,
			BroadcastService broadcastService,
			ApplicationEventPublisher eventPublisher,
			DashboardCacheService dashboardCacheService,
			LaneDeviceGateway laneDeviceGateway,
			LaneRuntimeStateService laneRuntimeStateService,
			ScreenHandledEventRepository screenHandledEventRepository,
			@Value("${app.dispatch.entry-lane-order:}") String entryLaneOrder,
			@Value("${app.dispatch.entry-enabled-default:false}") boolean entryDispatchEnabled,
			@Value("${app.dispatch.exit-enabled-default:false}") boolean exitDispatchEnabled,
			@Value("${app.dispatch.assignment-reserve-minutes:2}") long assignmentReserveMinutes) {
		this.laneRepository = laneRepository;
		this.dispatchConfigRepository = dispatchConfigRepository;
		this.entryLogRepository = entryLogRepository;
		this.blacklistRecordRepository = blacklistRecordRepository;
		this.dispatchTicketRepository = dispatchTicketRepository;
		this.broadcastService = broadcastService;
		this.eventPublisher = eventPublisher;
		this.dashboardCacheService = dashboardCacheService;
		this.laneDeviceGateway = laneDeviceGateway;
		this.laneRuntimeStateService = laneRuntimeStateService;
		this.screenHandledEventRepository = screenHandledEventRepository;
		this.defaultEntryLaneOrder = parseLaneOrder(entryLaneOrder);
		this.defaultEntryDispatchEnabled = entryDispatchEnabled;
		this.defaultExitDispatchEnabled = exitDispatchEnabled;
		this.assignmentReserveMinutes = Math.max(1L, assignmentReserveMinutes);
	}

	@PostConstruct
	void removeObsoleteDispatchConfigKeys() {
		clearDispatchConfig(LEGACY_EXIT_LANE_ORDER_KEY);
		clearDispatchConfig(LEGACY_ENTRY_DISPATCH_CURSOR_KEY);
		clearDispatchConfig(LEGACY_ENTRY_DISPATCH_PAUSED_LANE_KEY);
	}

	@Transactional
	public DashboardPayload getDashboard() {
		return dashboardCacheService.getDashboard().orElseGet(() -> {
			OffsetDateTime referenceTime = now();
			DashboardPayload payload = buildDashboardPayload(referenceTime);
			dashboardCacheService.cacheDashboard(payload);
			return payload;
		});
	}

	@Transactional
	public List<Lane> getLanes() {
		return refreshLaneRuntime(now());
	}

	@Transactional
	public DispatchBoardView getDispatchBoard() {
		OffsetDateTime referenceTime = now();
		List<Lane> lanes = refreshLaneRuntime(referenceTime);
		return buildDispatchBoard(referenceTime, lanes);
	}

	@Transactional
	public String resolveOpenEntryLaneIdForDevice(OffsetDateTime referenceTime) {
		OffsetDateTime resolvedTime = resolveTime(referenceTime);
		List<Lane> lanes = laneRepository.findAllByOrderByCodeAsc();
		return resolveOpenEntryLaneId(lanes, resolvedTime);
	}

	public List<ScreenEventView> getScreenEvents() {
		return getScreenEvents(null, null, null);
	}

	public List<ScreenEventView> getScreenEvents(String type, OffsetDateTime occurredAtFrom, OffsetDateTime occurredAtTo) {
		return getScreenEvents(type, occurredAtFrom, occurredAtTo, false);
	}

	public List<ScreenEventView> getScreenEvents(String type, OffsetDateTime occurredAtFrom, OffsetDateTime occurredAtTo, boolean includeHandled) {
		OffsetDateTime referenceTime = now();
		OffsetDateTime currentCycleStart = includeHandled ? null : currentDailyResetAt();
		List<DispatchTicket> tickets = dispatchTicketRepository.findAllByOrderByYardEntryTimeDesc();
		List<ScreenEventView> events = new ArrayList<>();

		for (DispatchTicket ticket : tickets) {
			if (currentCycleStart != null && ticketTime(ticket).isBefore(currentCycleStart)) {
				continue;
			}
			blacklistRecordRepository.findFirstByPlateIgnoreCaseAndActiveTrue(ticket.getPlate())
					.ifPresent(record -> events.add(screenEvent(
							"blacklist",
							"BL-" + ticket.getId(),
							ticket.getPlate(),
							"黑名单车辆，请及时处理",
							ticketTime(ticket),
							ticket.getId(),
							ticket.getAssignedLaneName())));

			if ("ENTERED_MISMATCH".equals(ticket.getStatus())) {
				events.add(screenEvent(
						"wrong_lane",
						"WL-" + ticket.getId(),
						ticket.getPlate(),
						"未按引导进入指定车道，请及时处理",
						firstNonNull(ticket.getLaneEntryTime(), ticket.getYardEntryTime()),
						ticket.getId(),
						firstNonBlank(ticket.getActualLaneName(), ticket.getAssignedLaneName())));
			}

			if ("EXPIRED".equals(ticket.getStatus()) || "NO_LANE_AVAILABLE".equals(ticket.getStatus())) {
				events.add(screenEvent(
						"not_entered",
						"NE-" + ticket.getId(),
						ticket.getPlate(),
						"总入口已进场但未进入车道，请及时处理",
						ticketTime(ticket),
						ticket.getId(),
						ticket.getAssignedLaneName()));
			}
		}

		for (Lane lane : refreshLaneRuntime(referenceTime)) {
			OffsetDateTime sensorTime = firstNonNull(lane.getLastSensorAt(), lane.getLastActionAt(), referenceTime);
			if (!"ONLINE".equals(defaultSensorStatus(lane.getSensorStatus()))
					&& (currentCycleStart == null || !sensorTime.isBefore(currentCycleStart))) {
				events.add(screenEvent(
						"other",
						"DV-" + lane.getId() + "-" + nullToEmpty(lane.getSensorStatus()),
						firstNonBlank(lane.getCurrentPlate(), lane.getLastEntryPlate(), "-"),
						lane.getName() + " 设备状态异常，请立即排查",
						sensorTime,
						lane.getId(),
						lane.getName()));
			}
		}

		Map<String, OffsetDateTime> handledEventTimes = screenHandledEventRepository.findAll().stream()
				.collect(Collectors.toMap(ScreenHandledEvent::getId, ScreenHandledEvent::getHandledAt, (first, second) -> first));
		Set<String> handledEventIds = handledEventTimes.keySet();

		return events.stream()
				.map(event -> withHandledState(event, handledEventTimes.get(event.id())))
				.filter(event -> includeHandled || !handledEventIds.contains(event.id()))
				.filter(event -> isBlank(type) || type.equalsIgnoreCase(event.type()))
				.filter(event -> occurredAtFrom == null || event.occurredAt() == null || !event.occurredAt().isBefore(occurredAtFrom))
				.filter(event -> occurredAtTo == null || event.occurredAt() == null || !event.occurredAt().isAfter(occurredAtTo))
				.sorted(Comparator.comparing(ScreenEventView::occurredAt, Comparator.nullsLast(Comparator.reverseOrder())))
				.toList();
	}

	public List<EntryLog> getRecentEntryLogs(int limit) {
		OffsetDateTime currentCycleStart = currentDailyResetAt();
		return entryLogRepository.findAllByOrderByEntryTimeDesc().stream()
				.filter(log -> currentCycleStart == null || !log.getEntryTime().isBefore(currentCycleStart))
				.limit(Math.max(1, limit))
				.toList();
	}

	public List<DispatchTicket> getRecentYardEntries(int limit) {
		OffsetDateTime currentCycleStart = currentDailyResetAt();
		return dispatchTicketRepository.findAllByOrderByYardEntryTimeDesc().stream()
				.filter(ticket -> currentCycleStart == null || !ticketTime(ticket).isBefore(currentCycleStart))
				.limit(Math.max(1, limit))
				.toList();
	}

	public List<DispatchTicket> getRecentGuideAssignments(int limit) {
		OffsetDateTime currentCycleStart = currentDailyResetAt();
		return dispatchTicketRepository.findAllByOrderByYardEntryTimeDesc().stream()
				.filter(ticket -> !isBlank(ticket.getAssignedLaneId()) || !isBlank(ticket.getAssignedLaneName()))
				.filter(ticket -> currentCycleStart == null || !guideAssignmentTime(ticket).isBefore(currentCycleStart))
				.sorted(Comparator.comparing(
						this::guideAssignmentTime,
						Comparator.nullsLast(Comparator.reverseOrder())))
				.limit(Math.max(1, limit))
				.toList();
	}

	public List<ScreenEventView> getScreenBoardEvents(int perTypeLimit) {
		int limit = Math.max(1, perTypeLimit);
		OffsetDateTime currentCycleStart = currentDailyResetAt();
		Map<String, Integer> typeCounts = new HashMap<>();
		return getScreenEvents(null, null, null, true).stream()
				.filter(event -> currentCycleStart == null || event.occurredAt() == null || !event.occurredAt().isBefore(currentCycleStart))
				.filter(event -> typeCounts.merge(event.type(), 1, Integer::sum) <= limit)
				.toList();
	}

	public Map<String, List<DispatchTicket>> getScreenLaneVehicles() {
		List<Lane> lanes = refreshLaneRuntime(now());
		Map<String, List<DispatchTicket>> result = new HashMap<>();
		for (Lane lane : lanes) {
			result.put(lane.getId(), dispatchTicketRepository.findByActualLaneIdAndExitTimeIsNullAndClosedAtIsNullOrderByLaneEntryTimeAsc(lane.getId()));
		}
		return result;
	}

	@Transactional
	public void handleScreenEvent(String eventId) {
		if (!isBlank(eventId)) {
			screenHandledEventRepository.findById(eventId).orElseGet(() -> screenHandledEventRepository.save(ScreenHandledEvent.builder()
					.id(eventId)
					.handledAt(now())
					.operator("大屏")
					.build()));
		}
	}

	public DispatchConfigView getDispatchConfig() {
		return new DispatchConfigView(
				currentLaneOrderValue(ENTRY_LANE_ORDER_KEY, defaultEntryLaneOrder),
				currentBooleanConfig(ENTRY_DISPATCH_ENABLED_KEY, defaultEntryDispatchEnabled),
				currentBooleanConfig(EXIT_DISPATCH_ENABLED_KEY, defaultExitDispatchEnabled),
				currentStringConfig(ACTIVE_ENTRY_LANE_KEY, null),
				currentStringConfig(ACTIVE_EXIT_LANE_KEY, null),
				currentAssignmentReserveMinutes());
	}

	@Transactional
	public DispatchConfigView updateDispatchConfig(DispatchConfigRequest request) {
		List<String> laneOrder = parseLaneOrder(request.entryLaneOrder());
		if (laneOrder.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入口开放顺序配置不能为空");
		}

		OffsetDateTime referenceTime = now();
		saveDispatchConfig(ENTRY_LANE_ORDER_KEY, request.entryLaneOrder(), referenceTime);
		if (request.entryDispatchEnabled() != null) {
			saveDispatchConfig(ENTRY_DISPATCH_ENABLED_KEY, request.entryDispatchEnabled().toString(), referenceTime);
		}
		if (request.exitDispatchEnabled() != null) {
			saveDispatchConfig(EXIT_DISPATCH_ENABLED_KEY, request.exitDispatchEnabled().toString(), referenceTime);
		}
		if (request.assignmentReserveMinutes() != null) {
			saveDispatchConfig(ASSIGNMENT_RESERVE_MINUTES_KEY, request.assignmentReserveMinutes().toString(), referenceTime);
		}

		List<Lane> lanes = laneRepository.findAllByOrderByCodeAsc();
		saveActiveLaneConfig(ACTIVE_ENTRY_LANE_KEY, firstOrderedLaneId(lanes, laneOrder), referenceTime);
		saveActiveLaneConfig(ACTIVE_EXIT_LANE_KEY, firstOrderedLaneId(lanes, laneOrder), referenceTime);
		refreshLaneRuntime(referenceTime);
		invalidateRuntimeViews("dispatch_config_updated");
		return getDispatchConfig();
	}

	@Transactional
	public DispatchConfigView updateDispatchRuntime(DispatchRuntimeRequest request) {
		OffsetDateTime referenceTime = now();
		saveDispatchConfig(ENTRY_DISPATCH_ENABLED_KEY, request.entryDispatchEnabled().toString(), referenceTime);
		saveDispatchConfig(EXIT_DISPATCH_ENABLED_KEY, request.exitDispatchEnabled().toString(), referenceTime);

		List<Lane> lanes = laneRepository.findAllByOrderByCodeAsc();
		List<String> laneOrder = currentLaneOrder(ENTRY_LANE_ORDER_KEY, defaultEntryLaneOrder);
		if (request.entryDispatchEnabled() && isBlank(currentStringConfig(ACTIVE_ENTRY_LANE_KEY, null))) {
			saveActiveLaneConfig(ACTIVE_ENTRY_LANE_KEY, firstOrderedLaneId(lanes, laneOrder), referenceTime);
		}
		if (request.exitDispatchEnabled() && isBlank(currentStringConfig(ACTIVE_EXIT_LANE_KEY, null))) {
			saveActiveLaneConfig(ACTIVE_EXIT_LANE_KEY, firstOrderedLaneId(lanes, laneOrder), referenceTime);
		}

		refreshLaneRuntime(referenceTime);
		invalidateRuntimeViews("dispatch_runtime_updated");
		return getDispatchConfig();
	}

	@Transactional
	public DispatchConfigView dailyReset() {
		OffsetDateTime referenceTime = now();
		List<Lane> lanes = laneRepository.findAllByOrderByCodeAsc();
		List<String> laneOrder = currentLaneOrder(ENTRY_LANE_ORDER_KEY, defaultEntryLaneOrder);

		entryLogRepository.findByExitTimeIsNullOrderByEntryTimeAsc().forEach(log -> log.setExitTime(referenceTime));

		for (DispatchTicket ticket : dispatchTicketRepository.findByClosedAtIsNullOrderByYardEntryTimeAsc()) {
			ticket.setClosedAt(referenceTime);
			if (ticket.getExitTime() == null) {
				ticket.setExitTime(referenceTime);
			}
			ticket.setNotes(firstNonBlank(ticket.getNotes(), "日清关闭当前周期记录"));
		}
		laneRuntimeStateService.clearAll();
		laneDeviceGateway.clearSyncState();

		for (Lane lane : lanes) {
			lane.setVehicleCount(0);
			lane.setCurrentPlate(null);
			lane.setPriority(false);
			lane.setLastEntryPlate(null);
			lane.setLastEntryAt(null);
			lane.setQueueHeadAt(null);
			lane.setMode("AUTO");
			lane.setSensorStatus("ONLINE");
			lane.setLastSensorAt(referenceTime);
			lane.setLastActionAt(referenceTime);
			lane.setStatus(resolveStatusForLane(lane, 0L));
		}

		saveDispatchConfig(ENTRY_DISPATCH_ENABLED_KEY, Boolean.TRUE.toString(), referenceTime);
		saveDispatchConfig(EXIT_DISPATCH_ENABLED_KEY, Boolean.TRUE.toString(), referenceTime);
		saveActiveLaneConfig(ACTIVE_ENTRY_LANE_KEY, firstOrderedLaneId(lanes, laneOrder), referenceTime);
		saveActiveLaneConfig(ACTIVE_EXIT_LANE_KEY, firstOrderedLaneId(lanes, laneOrder), referenceTime);
		saveDispatchConfig(LAST_DAILY_RESET_AT_KEY, referenceTime.toString(), referenceTime);

		refreshLaneRuntime(referenceTime);
		invalidateRuntimeViews("daily_reset");
		return getDispatchConfig();
	}

	public List<EntryLog> getLogs(String query, String status, String laneId, OffsetDateTime entryTimeFrom, OffsetDateTime entryTimeTo) {
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
				.filter(log -> entryTimeFrom == null || !log.getEntryTime().isBefore(entryTimeFrom))
				.filter(log -> entryTimeTo == null || !log.getEntryTime().isAfter(entryTimeTo))
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
		lane.setMode(request.mode());
		lane.setLastActionAt(referenceTime);
		if ("MANUAL".equals(request.mode())) {
			laneRuntimeStateService.recordTarget(lane.getId(), request.entrySignal(), request.exitSignal(), request.reason(), referenceTime);
		} else if ("OFFLINE".equals(request.mode())) {
			laneRuntimeStateService.recordTarget(lane.getId(), "OFFLINE", "OFFLINE", request.reason(), referenceTime);
		} else {
			laneRuntimeStateService.clearManualTarget(lane.getId());
		}
		lane.setStatus(resolveStatusForLane(lane, pendingAssignmentCounts(referenceTime).getOrDefault(lane.getId(), 0L)));
		return persistLaneRuntime(lane.getId(), referenceTime, "signal_override");
	}

	public void controlLaneRelay(String laneId, RelayControlRequest request) {
		validateRelayControlTarget(request.target());
		Lane lane = requireLane(laneId);
		laneDeviceGateway.controlRelay(lane, request.target(), Boolean.TRUE.equals(request.on()), request.reason());
	}

	@Transactional
	public void restoreAutoControl() {
		OffsetDateTime referenceTime = now();
		laneDeviceGateway.clearSyncState();
		saveDispatchConfig(ENTRY_DISPATCH_ENABLED_KEY, Boolean.TRUE.toString(), referenceTime);
		saveDispatchConfig(EXIT_DISPATCH_ENABLED_KEY, Boolean.TRUE.toString(), referenceTime);
		for (Lane lane : laneRepository.findAllByOrderByCodeAsc()) {
			lane.setMode("AUTO");
			lane.setLastActionAt(referenceTime);
			laneRuntimeStateService.clearManualTarget(lane.getId());
		}
		refreshLaneRuntime(referenceTime);
		invalidateRuntimeViews("restore_auto");
	}

	@Transactional
	public void globalLockdown() {
		OffsetDateTime referenceTime = now();
		saveDispatchConfig(ENTRY_DISPATCH_ENABLED_KEY, Boolean.FALSE.toString(), referenceTime);
		saveDispatchConfig(EXIT_DISPATCH_ENABLED_KEY, Boolean.FALSE.toString(), referenceTime);
		saveActiveLaneConfig(ACTIVE_ENTRY_LANE_KEY, null, referenceTime);
		saveActiveLaneConfig(ACTIVE_EXIT_LANE_KEY, null, referenceTime);
		for (Lane lane : laneRepository.findAllByOrderByCodeAsc()) {
			lane.setMode("MANUAL");
			lane.setStatus("FULL");
			lane.setLastActionAt(referenceTime);
			laneRuntimeStateService.recordTarget(lane.getId(), "RED", "RED", "全域锁死，请等待指挥中心", referenceTime);
		}
		refreshLaneRuntime(referenceTime);
		invalidateRuntimeViews("global_lockdown");
	}

	@Transactional
	public void dispatchManual(ManualDispatchRequest request) {
		validateCommandType(request.commandType());
		Lane lane = requireLane(request.laneId());
		OffsetDateTime referenceTime = now();
		String normalizedPlate = isBlank(request.plate()) ? normalizePlate(lane.getCurrentPlate()) : normalizePlate(request.plate());
		String vehicleType = isBlank(request.vehicleType()) ? "出租车" : request.vehicleType();
		int previousVehicleCount = lane.getVehicleCount();

		switch (request.commandType()) {
			case "FORCE_OPEN_GATE", "MANUAL_ENTRY", "TEMP_ALLOW" -> {
				lane.setMode("MANUAL");
				laneRuntimeStateService.recordTarget(lane.getId(), "GREEN", "GREEN", "人工放行", referenceTime);
				lane.setVehicleCount(Math.min(lane.getCapacity(), lane.getVehicleCount() + 1));
				updateQueueHeadAtForObservedCountChange(lane, previousVehicleCount, lane.getVehicleCount(), referenceTime);
				lane.setLastEntryAt(referenceTime);
				lane.setLastEntryPlate(normalizedPlate);
				if (!isBlank(normalizedPlate)) {
					entryLogRepository.save(newEntryLog(
							normalizedPlate,
							lane,
							vehicleType,
							"MANUAL",
							"MANUAL",
							"控制台",
							referenceTime));
					dispatchTicketRepository.save(newDispatchTicket(
							normalizedPlate,
							lane,
							vehicleType,
							"MANUAL",
							"DIRECT_ENTERED",
							"控制台",
							referenceTime,
							"人工放行"));
				}
			}
			case "PLATE_CORRECTION" -> {
				if (!isBlank(normalizedPlate)) {
					lane.setCurrentPlate(normalizedPlate);
					lane.setLastEntryPlate(normalizedPlate);
					lane.setLastEntryAt(referenceTime);
				}
			}
			case "CORRECT_COUNT" -> {
				int correctedCount = request.correctedVehicleCount() == null ? lane.getVehicleCount() : Math.max(0, request.correctedVehicleCount());
				lane.setVehicleCount(Math.min(lane.getCapacity(), correctedCount));
				updateQueueHeadAtForObservedCountChange(lane, previousVehicleCount, lane.getVehicleCount(), referenceTime);
				reconcileLaneQueue(lane, lane.getVehicleCount(), referenceTime);
				if (lane.getVehicleCount() == 0 && lane.getId().equals(currentStringConfig(ACTIVE_EXIT_LANE_KEY, null))) {
					advanceExitLane(referenceTime, lane.getId());
				}
			}
			case "SET_PRIORITY" -> lane.setPriority(request.markPriority() == null ? !lane.isPriority() : request.markPriority());
			default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的指令类型");
		}

		lane.setLastActionAt(referenceTime);
		lane.setStatus(resolveStatusForLane(lane, pendingAssignmentCounts(referenceTime).getOrDefault(lane.getId(), 0L)));
		refreshLaneRuntime(referenceTime);
		invalidateRuntimeViews("manual_dispatch");
	}

	@Transactional
	public Lane updateLaneCapacity(String laneId, int capacity) {
		if (capacity <= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "车道容量必须大于 0");
		}
		Lane lane = requireLane(laneId);
		OffsetDateTime referenceTime = now();
		lane.setCapacity(capacity);
		lane.setLastActionAt(referenceTime);
		return persistLaneRuntime(laneId, referenceTime, "lane_capacity_updated");
	}

	@Transactional
	public Lane ingestLaneSensor(LaneSensorPayload payload) {
		validateSensorStatus(payload.sensorStatus());
		Lane lane = requireLane(payload.laneId());
		OffsetDateTime observedAt = resolveTime(payload.observedAt());
		int previousVehicleCount = lane.getVehicleCount();

		if (payload.capacity() != null && payload.capacity() > 0) {
			lane.setCapacity(payload.capacity());
		}
		lane.setVehicleCount(Math.min(lane.getCapacity(), payload.vehicleCount()));
		updateQueueHeadAtForObservedCountChange(lane, previousVehicleCount, lane.getVehicleCount(), observedAt);
		lane.setSensorStatus(payload.sensorStatus());
		lane.setLastSensorAt(observedAt);
		lane.setLastActionAt(observedAt);
		reconcileLaneQueue(lane, lane.getVehicleCount(), observedAt);
		if (lane.getVehicleCount() == 0 && lane.getId().equals(currentStringConfig(ACTIVE_EXIT_LANE_KEY, null))) {
			advanceExitLane(observedAt, lane.getId());
		}
		return persistLaneRuntime(lane.getId(), observedAt, "lane_sensor_ingested");
	}

	@Transactional
	public DispatchTicket registerYardEntry(YardEntryPayload payload) {
		OffsetDateTime capturedAt = resolveTime(payload.capturedAt());
		expireStaleDispatchTickets(capturedAt);

		List<Lane> lanes = laneRepository.findAllByOrderByCodeAsc();
		List<String> laneOrder = currentLaneOrder(ENTRY_LANE_ORDER_KEY, defaultEntryLaneOrder);
		String normalizedSource = isBlank(payload.source()) ? "YARD_CAMERA" : payload.source().toUpperCase(Locale.ROOT);
		boolean entryDispatchBefore = currentBooleanConfig(ENTRY_DISPATCH_ENABLED_KEY, defaultEntryDispatchEnabled);
		String activeEntryLaneBefore = currentStringConfig(ACTIVE_ENTRY_LANE_KEY, null);
		log.info(
				"Yard entry capture received plate={} source={} capturedAt={} entryDispatchBefore={} activeEntryLaneBefore={} lanes={} laneOrder={}",
				normalizePlate(payload.plate()),
				normalizedSource,
				capturedAt,
				entryDispatchBefore,
				activeEntryLaneBefore,
				lanes.size(),
				laneOrder);
		ensureEntryDispatchRunningForYardCapture(normalizedSource, capturedAt, lanes, laneOrder);
		Map<String, Long> pendingCounts = pendingAssignmentCounts(capturedAt);
		boolean entryDispatchEnabled = currentBooleanConfig(ENTRY_DISPATCH_ENABLED_KEY, defaultEntryDispatchEnabled);
		String activeAutoEntryLaneId = entryDispatchEnabled
				? resolveAndPersistActiveEntryLaneId(lanes, laneOrder, pendingCounts, capturedAt)
				: null;
		Lane targetLane = laneById(lanes, activeAutoEntryLaneId);
		log.info(
				"Yard entry assignment decision plate={} source={} entryDispatchEnabled={} activeEntryLane={} targetLane={} pendingForTarget={} vehicleCount={} capacity={}",
				normalizePlate(payload.plate()),
				normalizedSource,
				entryDispatchEnabled,
				activeAutoEntryLaneId,
				targetLane == null ? null : targetLane.getId(),
				targetLane == null ? null : pendingCounts.getOrDefault(targetLane.getId(), 0L),
				targetLane == null ? null : targetLane.getVehicleCount(),
				targetLane == null ? null : targetLane.getCapacity());

		DispatchTicket ticket = DispatchTicket.builder()
				.id("DSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT))
				.plate(normalizePlate(payload.plate()))
				.yardEntryTime(capturedAt)
				.vehicleType(isBlank(payload.vehicleType()) ? "出租车" : payload.vehicleType())
				.source(normalizedSource)
				.operator("总入口抓拍")
				.build();

		if (targetLane != null && canReserveEntrySlot(targetLane, pendingCounts.getOrDefault(targetLane.getId(), 0L))) {
			ticket.setAssignedLaneId(targetLane.getId());
			ticket.setAssignedLaneName(targetLane.getName());
			ticket.setAssignedAt(capturedAt);
			ticket.setStatus("ASSIGNED");
			ticket.setNotes("大屏指引前往 " + targetLane.getName());
			dispatchTicketRepository.save(ticket);
			entryLogRepository.save(newEntryLog(
					ticket.getPlate(),
					targetLane,
					ticket.getVehicleType(),
					"PASSED",
					ticket.getSource(),
					ticket.getOperator(),
					capturedAt));
			log.info(
					"Yard entry assigned plate={} ticketId={} laneId={} laneName={} capturedAt={} entryLogCreated=true",
					ticket.getPlate(),
					ticket.getId(),
					targetLane.getId(),
					targetLane.getName(),
					capturedAt);

			pendingCounts.merge(targetLane.getId(), 1L, Long::sum);
			if (!canReserveEntrySlot(targetLane, pendingCounts.getOrDefault(targetLane.getId(), 0L))) {
				advanceEntryLane(capturedAt, targetLane.getId(), lanes, laneOrder, pendingCounts);
			}
		} else {
			ticket.setStatus("NO_LANE_AVAILABLE");
			ticket.setNotes("当前没有可分配车道，请人工干预");
			dispatchTicketRepository.save(ticket);
			log.warn(
					"Yard entry could not assign lane plate={} ticketId={} source={} capturedAt={} entryDispatchEnabled={} activeEntryLane={} lanes={} pendingCounts={}",
					ticket.getPlate(),
					ticket.getId(),
					ticket.getSource(),
					capturedAt,
					entryDispatchEnabled,
					activeAutoEntryLaneId,
					lanes.size(),
					pendingCounts);
		}

		refreshLaneRuntime(capturedAt);
		invalidateRuntimeViews("yard_entry_registered");
		return ticket;
	}

	@Transactional
	public EntryLog registerVehicleEntry(VehicleEntryPayload payload) {
		Lane lane = requireLane(payload.laneId());
		OffsetDateTime entryTime = resolveTime(payload.entryTime());
		String plate = normalizePlate(payload.plate());
		String vehicleType = isBlank(payload.vehicleType()) ? "出租车" : payload.vehicleType();
		String source = isBlank(payload.source()) ? "LANE_CAMERA" : payload.source().toUpperCase(Locale.ROOT);

		expireStaleDispatchTickets(entryTime);
		closeStaleActiveEntryLogsForPlate(plate, entryTime);
		DispatchTicket activeTicket = findLatestEnteredTicketByPlate(plate);
		EntryLog activeLogInLane = findActiveEntryLogInLane(lane.getId(), plate);
		if (activeTicket != null) {
			if (lane.getId().equals(activeTicket.getActualLaneId()) && activeLogInLane != null) {
				lane.setSensorStatus(defaultSensorStatus(lane.getSensorStatus()));
				lane.setLastSensorAt(entryTime);
				lane.setLastEntryAt(entryTime);
				lane.setLastEntryPlate(plate);
				lane.setLastActionAt(entryTime);
				persistLaneRuntime(lane.getId(), entryTime, "vehicle_entry_duplicate_ignored");
				return activeLogInLane;
			}
			throw new ResponseStatusException(
					HttpStatus.CONFLICT,
					"车牌已在" + firstNonBlank(activeTicket.getActualLaneName(), activeTicket.getAssignedLaneName(), activeTicket.getActualLaneId(), "其他车道") + "未出场，不能重复入场");
		}
		DispatchTicket ticket = findLatestPendingTicketByPlate(plate);
		if (ticket == null) {
			ticket = findLatestRecoverableExpiredTicketByPlate(plate, entryTime);
		}
		if (activeLogInLane != null && ticket == null) {
			lane.setSensorStatus(defaultSensorStatus(lane.getSensorStatus()));
			lane.setLastSensorAt(entryTime);
			lane.setLastEntryAt(entryTime);
			lane.setLastEntryPlate(plate);
			lane.setLastActionAt(entryTime);
			persistLaneRuntime(lane.getId(), entryTime, "vehicle_entry_duplicate_ignored");
			return activeLogInLane;
		}

		int previousVehicleCount = lane.getVehicleCount();
		lane.setVehicleCount(Math.min(lane.getCapacity(), lane.getVehicleCount() + 1));
		updateQueueHeadAtForObservedCountChange(lane, previousVehicleCount, lane.getVehicleCount(), entryTime);
		lane.setSensorStatus(defaultSensorStatus(lane.getSensorStatus()));
		lane.setLastSensorAt(entryTime);
		lane.setLastEntryAt(entryTime);
		lane.setLastEntryPlate(plate);
		lane.setLastActionAt(entryTime);

		EntryLog entryLog = findActiveEntryLogForTicket(ticket, plate);
		if (entryLog == null) {
			entryLog = activeLogInLane == null
					? newEntryLog(plate, lane, vehicleType, "PASSED", source, "设备采集", entryTime)
					: activeLogInLane;
		}
		entryLog.setLaneId(lane.getId());
		entryLog.setLaneName(lane.getName());
		entryLog.setVehicleType(vehicleType);
		entryLog.setStatus("PASSED");
		entryLog.setSource(source);
		entryLog = entryLogRepository.save(entryLog);
		upsertDispatchTicketForLaneEntry(ticket, lane, plate, vehicleType, source, entryTime);
		refreshLaneRuntime(entryTime);
		invalidateRuntimeViews("vehicle_entry_captured");
		return entryLog;
	}

	@Transactional
	public Lane updateLaneDeviceStatus(String laneId, String sensorStatus, OffsetDateTime observedAt, String ledMessage) {
		validateSensorStatus(sensorStatus);
		Lane lane = requireLane(laneId);
		OffsetDateTime referenceTime = resolveTime(observedAt);
		lane.setSensorStatus(sensorStatus);
		lane.setLastSensorAt(referenceTime);
		lane.setLastActionAt(referenceTime);
		if (!isBlank(ledMessage)) {
			laneRuntimeStateService.recordDeviceMessage(laneId, ledMessage, referenceTime);
		}
		return persistLaneRuntime(laneId, referenceTime, "device_status_updated");
	}

	@Transactional
	public void updateLaneSignalFeedback(
			String laneId,
			String entrySignal,
			String exitSignal,
			OffsetDateTime observedAt,
			String message) {
		requireLane(laneId);
		validateOptionalSignal(entrySignal);
		validateOptionalSignal(exitSignal);
		laneRuntimeStateService.recordDeviceFeedback(laneId, entrySignal, exitSignal, resolveTime(observedAt), message);
		invalidateRuntimeViews("signal_feedback_updated");
	}

	@Transactional
	public EntryLog registerVehicleEntryFromDevice(String laneId, String plate, OffsetDateTime entryTime, String vehicleType, String source) {
		return registerVehicleEntry(new VehicleEntryPayload(laneId, plate, vehicleType, source, entryTime));
	}

	@Transactional
	public Lane applyPassCountDelta(String laneId, int delta, OffsetDateTime observedAt) {
		Lane lane = requireLane(laneId);
		OffsetDateTime referenceTime = resolveTime(observedAt);
		int previousVehicleCount = lane.getVehicleCount();
		lane.setSensorStatus(defaultSensorStatus(lane.getSensorStatus()));
		lane.setLastSensorAt(referenceTime);
		lane.setLastActionAt(referenceTime);

		if (delta == 0) {
			return persistLaneRuntime(laneId, referenceTime, "pass_count_heartbeat");
		}

		if (delta < 0) {
			int exitCount = Math.min(previousVehicleCount, Math.abs(delta));
			lane.setVehicleCount(Math.max(0, previousVehicleCount - exitCount));
			updateQueueHeadAtForObservedCountChange(lane, previousVehicleCount, lane.getVehicleCount(), referenceTime);
			closeExitedVehicles(lane, exitCount, referenceTime);
			if (lane.getVehicleCount() == 0 && lane.getId().equals(currentStringConfig(ACTIVE_EXIT_LANE_KEY, null))) {
				advanceExitLane(referenceTime, lane.getId());
			}
		} else {
			lane.setVehicleCount(Math.min(lane.getCapacity(), previousVehicleCount + delta));
			updateQueueHeadAtForObservedCountChange(lane, previousVehicleCount, lane.getVehicleCount(), referenceTime);
		}

		return persistLaneRuntime(laneId, referenceTime, "pass_count_delta");
	}

	@Transactional
	public EntryLog registerVehicleExit(String laneId, String plate, OffsetDateTime observedAt) {
		Lane lane = requireLane(laneId);
		OffsetDateTime referenceTime = resolveTime(observedAt);
		String normalizedPlate = normalizePlate(plate);
		if (isBlank(normalizedPlate)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "车牌号码不能为空");
		}

		List<EntryLog> activeLogs = entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc(lane.getId());
		EntryLog matchedLog = activeLogs.stream()
				.filter(log -> normalizedPlate.equals(normalizePlate(log.getPlate())))
				.findFirst()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "该车道内没有匹配的车辆，已忽略出场模拟"));

		int previousVehicleCount = lane.getVehicleCount();
		lane.setSensorStatus(defaultSensorStatus(lane.getSensorStatus()));
		lane.setLastSensorAt(referenceTime);
		lane.setLastActionAt(referenceTime);
		lane.setVehicleCount(Math.max(0, previousVehicleCount - 1));
		updateQueueHeadAtForObservedCountChange(lane, previousVehicleCount, lane.getVehicleCount(), referenceTime);

		matchedLog.setExitTime(referenceTime);
		entryLogRepository.save(matchedLog);
		closeDispatchTicketForExit(matchedLog, referenceTime);
		refreshLaneHeadAfterVehicleExit(lane, activeLogs, matchedLog);

		if (lane.getVehicleCount() == 0 && lane.getId().equals(currentStringConfig(ACTIVE_EXIT_LANE_KEY, null))) {
			advanceExitLane(referenceTime, lane.getId());
		}
		persistLaneRuntime(laneId, referenceTime, "vehicle_exit_captured");
		return matchedLog;
	}

	@Transactional
	public Lane simulateScreenLaneExit(String laneId, OffsetDateTime observedAt) {
		return exitLaneByLoopTrigger(laneId, observedAt, "screen_lane_exit_simulated");
	}

	@Transactional
	public Lane clearLaneRemainingVehicles(String laneId, OffsetDateTime observedAt, String reason) {
		Lane lane = requireLane(laneId);
		List<DispatchTicket> openTickets = dispatchTicketRepository.findByActualLaneIdAndExitTimeIsNullAndClosedAtIsNullOrderByLaneEntryTimeAsc(lane.getId());
		if (Math.max(lane.getVehicleCount(), openTickets.size()) > 3) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "车道剩余车辆超过 3 辆，不能使用兜底清空");
		}

		OffsetDateTime referenceTime = resolveTime(observedAt);
		String clearReason = firstNonBlank(reason, "现场确认车道剩余车辆已全部驶出");
		int previousVehicleCount = lane.getVehicleCount();
		List<EntryLog> activeLogs = entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc(lane.getId());
		List<EntryLog> logsToClose = activeLogs.stream()
				.filter(log -> openTickets.stream().anyMatch(ticket -> matchesActiveEntryLog(ticket, log)))
				.toList();
		if (openTickets.isEmpty()) {
			logsToClose = activeLogs.stream()
					.limit(Math.max(0, previousVehicleCount))
					.toList();
		}

		for (EntryLog log : logsToClose) {
			log.setExitTime(referenceTime);
		}
		if (!logsToClose.isEmpty()) {
			entryLogRepository.saveAll(logsToClose);
		}

		for (DispatchTicket ticket : openTickets) {
			if (isBlank(ticket.getNotes())) {
				ticket.setNotes(clearReason);
			}
			closeDispatchTicket(ticket, referenceTime);
		}

		lane.setVehicleCount(0);
		lane.setCurrentPlate(null);
		lane.setQueueHeadAt(null);
		lane.setPriority(false);
		lane.setLastActionAt(referenceTime);
		updateQueueHeadAtForObservedCountChange(lane, previousVehicleCount, lane.getVehicleCount(), referenceTime);
		if (lane.getId().equals(currentStringConfig(ACTIVE_EXIT_LANE_KEY, null))) {
			advanceExitLane(referenceTime, lane.getId());
		}
		return persistLaneRuntime(laneId, referenceTime, "lane_remaining_cleared");
	}

	@Transactional
	public Lane applyLaneExitTrigger(String laneId, OffsetDateTime observedAt) {
		return exitLaneByLoopTrigger(laneId, observedAt, "lane_exit_triggered");
	}

	private Lane exitLaneByLoopTrigger(String laneId, OffsetDateTime observedAt, String action) {
		Lane lane = requireLane(laneId);
		OffsetDateTime referenceTime = resolveTime(observedAt);
		List<DispatchTicket> visibleTickets = dispatchTicketRepository.findByActualLaneIdAndExitTimeIsNullAndClosedAtIsNullOrderByLaneEntryTimeAsc(lane.getId());
		if (!visibleTickets.isEmpty()) {
			return closeScreenExitTicket(visibleTickets.getFirst(), referenceTime, action);
		}

		List<EntryLog> activeLogs = entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc(lane.getId());
		if (!activeLogs.isEmpty()) {
			return closeScreenExitLog(activeLogs.getFirst(), referenceTime, action);
		}

		return applyPassCountDelta(laneId, -1, referenceTime);
	}

	@Transactional
	public Lane simulateScreenGlobalExit(OffsetDateTime observedAt) {
		OffsetDateTime referenceTime = resolveTime(observedAt);
		String activeExitLaneId = buildDispatchBoard(referenceTime, refreshLaneRuntime(referenceTime)).activeExitLaneId();
		if (isBlank(activeExitLaneId)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "暂无可出场车辆");
		}
		return simulateScreenLaneExit(activeExitLaneId, referenceTime);
	}

	private Lane closeScreenExitTicket(DispatchTicket ticket, OffsetDateTime referenceTime, String action) {
		Lane lane = requireLane(ticket.getActualLaneId());
		List<DispatchTicket> visibleTickets = dispatchTicketRepository.findByActualLaneIdAndExitTimeIsNullAndClosedAtIsNullOrderByLaneEntryTimeAsc(lane.getId());
		EntryLog matchedLog = entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc(lane.getId()).stream()
				.filter(log -> normalizePlate(ticket.getPlate()).equals(normalizePlate(log.getPlate())))
				.findFirst()
				.orElse(null);
		if (matchedLog != null) {
			matchedLog.setExitTime(referenceTime);
			entryLogRepository.save(matchedLog);
		}
		closeDispatchTicket(ticket, referenceTime);

		int previousVehicleCount = lane.getVehicleCount();
		List<DispatchTicket> remainingTickets = visibleTickets.stream()
				.filter(candidate -> !Objects.equals(candidate.getId(), ticket.getId()))
				.toList();
		int visibleRemaining = remainingTickets.size();
		lane.setSensorStatus(defaultSensorStatus(lane.getSensorStatus()));
		lane.setLastSensorAt(referenceTime);
		lane.setLastActionAt(referenceTime);
		lane.setVehicleCount(Math.max(visibleRemaining, Math.max(0, previousVehicleCount - 1)));
		updateQueueHeadAtForObservedCountChange(lane, previousVehicleCount, lane.getVehicleCount(), referenceTime);
		if (visibleRemaining == 0) {
			lane.setCurrentPlate(null);
			lane.setQueueHeadAt(null);
			lane.setPriority(false);
		} else {
			DispatchTicket nextInLane = remainingTickets.getFirst();
			lane.setCurrentPlate(nextInLane.getPlate());
			lane.setQueueHeadAt(firstNonNull(nextInLane.getLaneEntryTime(), nextInLane.getYardEntryTime()));
		}
		if (lane.getVehicleCount() == 0 && lane.getId().equals(currentStringConfig(ACTIVE_EXIT_LANE_KEY, null))) {
			advanceExitLane(referenceTime, lane.getId());
		}
		return persistLaneRuntime(lane.getId(), referenceTime, action);
	}

	private Lane closeScreenExitLog(EntryLog log, OffsetDateTime referenceTime, String action) {
		Lane lane = requireLane(log.getLaneId());
		List<EntryLog> activeLogs = entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc(lane.getId());
		log.setExitTime(referenceTime);
		entryLogRepository.save(log);
		closeDispatchTicketForExit(log, referenceTime);

		int previousVehicleCount = lane.getVehicleCount();
		lane.setSensorStatus(defaultSensorStatus(lane.getSensorStatus()));
		lane.setLastSensorAt(referenceTime);
		lane.setLastActionAt(referenceTime);
		lane.setVehicleCount(Math.max(0, previousVehicleCount - 1));
		updateQueueHeadAtForObservedCountChange(lane, previousVehicleCount, lane.getVehicleCount(), referenceTime);
		refreshLaneHeadAfterVehicleExit(lane, activeLogs, log);
		if (lane.getVehicleCount() == 0 && lane.getId().equals(currentStringConfig(ACTIVE_EXIT_LANE_KEY, null))) {
			advanceExitLane(referenceTime, lane.getId());
		}
		return persistLaneRuntime(lane.getId(), referenceTime, action);
	}

	@Transactional
	public Lane applyLanePresenceSignal(String laneId, boolean haveCar, OffsetDateTime observedAt) {
		Lane lane = requireLane(laneId);
		OffsetDateTime referenceTime = resolveTime(observedAt);
		lane.setSensorStatus(defaultSensorStatus(lane.getSensorStatus()));
		lane.setLastSensorAt(referenceTime);
		lane.setLastActionAt(referenceTime);
		if (!haveCar && entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc(laneId).isEmpty()) {
			lane.setVehicleCount(0);
			lane.setCurrentPlate(null);
			lane.setQueueHeadAt(null);
			lane.setPriority(false);
		}
		return persistLaneRuntime(laneId, referenceTime, "lane_presence_polled");
	}

	private DashboardPayload buildDashboardPayload(OffsetDateTime referenceTime) {
		List<Lane> lanes = refreshLaneRuntime(referenceTime);
		List<EntryLog> logs = entryLogRepository.findAllByOrderByEntryTimeDesc();
		return new DashboardPayload(referenceTime, buildThroughput(logs), lanes, buildDispatchBoard(referenceTime, lanes));
	}

	private DispatchBoardView buildDispatchBoard(OffsetDateTime referenceTime, List<Lane> lanes) {
		recoverActiveYardAssignmentsFromEntryLogs(referenceTime);
		List<DispatchTicket> activeTickets = dispatchTicketRepository.findByClosedAtIsNullOrderByYardEntryTimeAsc();
		List<DispatchTicket> waitingAssignments = activeTickets.stream()
				.filter(ticket -> "ASSIGNED".equals(ticket.getStatus()) && ticket.getLaneEntryTime() == null)
				.sorted(Comparator.comparing(
						DispatchTicket::getYardEntryTime,
						Comparator.nullsLast(Comparator.reverseOrder())))
				.limit(8)
				.toList();
		OffsetDateTime currentCycleStart = currentDailyResetAt();
		List<DispatchTicket> recentDispatches = dispatchTicketRepository.findAllByOrderByYardEntryTimeDesc().stream()
				.filter(ticket -> currentCycleStart == null || !ticketTime(ticket).isBefore(currentCycleStart))
				.limit(12)
				.toList();
		logScreenBoardDiagnosticIfNeeded(waitingAssignments, recentDispatches, currentCycleStart);
		String activeEntryLaneId = resolveOpenEntryLaneId(lanes, referenceTime);
		String activeExitLaneId = resolveOpenExitLaneId(lanes, referenceTime, activeEntryLaneId);
		return new DispatchBoardView(
				referenceTime,
				activeEntryLaneId,
				activeExitLaneId,
				laneName(lanes, activeEntryLaneId),
				laneName(lanes, activeExitLaneId),
				currentBooleanConfig(ENTRY_DISPATCH_ENABLED_KEY, defaultEntryDispatchEnabled),
				currentBooleanConfig(EXIT_DISPATCH_ENABLED_KEY, defaultExitDispatchEnabled),
				waitingAssignments,
				recentDispatches);
	}

	private void logScreenBoardDiagnosticIfNeeded(
			List<DispatchTicket> waitingAssignments,
			List<DispatchTicket> recentDispatches,
			OffsetDateTime currentCycleStart) {
		if (!waitingAssignments.isEmpty() || !recentDispatches.isEmpty()) {
			return;
		}
		long nowMs = System.currentTimeMillis();
		long previousLogAt = lastScreenBoardDiagnosticLogAt.get();
		if (nowMs - previousLogAt < SCREEN_BOARD_DIAGNOSTIC_LOG_INTERVAL_MS
				|| !lastScreenBoardDiagnosticLogAt.compareAndSet(previousLogAt, nowMs)) {
			return;
		}
		List<EntryLog> activeLogs = entryLogRepository.findByExitTimeIsNullOrderByEntryTimeAsc();
		if (activeLogs.isEmpty()) {
			return;
		}
		EntryLog latestLog = activeLogs.getLast();
		log.warn(
				"Screen board has active entry logs but no dispatch guide data. activeLogs={} latestPlate={} latestLane={} latestSource={} latestEntryTime={} entryDispatchEnabled={} activeEntryLane={} currentCycleStart={} openTickets={} totalTickets={}. This usually means total entrance plateResult was routed as lane entry instead of ALPR_YARD, or dispatch_tickets were not created.",
				activeLogs.size(),
				latestLog.getPlate(),
				latestLog.getLaneId(),
				latestLog.getSource(),
				latestLog.getEntryTime(),
				currentBooleanConfig(ENTRY_DISPATCH_ENABLED_KEY, defaultEntryDispatchEnabled),
				currentStringConfig(ACTIVE_ENTRY_LANE_KEY, null),
				currentCycleStart,
				dispatchTicketRepository.findByClosedAtIsNullOrderByYardEntryTimeAsc().size(),
				dispatchTicketRepository.count());
	}

	private void recoverActiveYardAssignmentsFromEntryLogs(OffsetDateTime referenceTime) {
		List<EntryLog> activeYardLogs = entryLogRepository.findByExitTimeIsNullOrderByEntryTimeAsc().stream()
				.filter(log -> "ALPR_YARD".equalsIgnoreCase(log.getSource()))
				.sorted(Comparator.comparing(EntryLog::getEntryTime, Comparator.nullsLast(Comparator.reverseOrder())))
				.limit(12)
				.toList();
		if (activeYardLogs.isEmpty()) {
			return;
		}
		List<DispatchTicket> changedTickets = new ArrayList<>();
		for (EntryLog log : activeYardLogs) {
			if (hasOpenGuideTicketForEntryLog(log)) {
				continue;
			}
			DispatchTicket ticket = findRecoverableTicketForEntryLog(log);
			if (ticket == null) {
				ticket = DispatchTicket.builder()
						.id("DSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT))
						.plate(normalizePlate(log.getPlate()))
						.yardEntryTime(log.getEntryTime())
						.vehicleType(log.getVehicleType())
						.source("ALPR_YARD")
						.operator("总入口抓拍")
						.build();
			}
			ticket.setAssignedLaneId(log.getLaneId());
			ticket.setAssignedLaneName(log.getLaneName());
			ticket.setAssignedAt(log.getEntryTime());
			ticket.setActualLaneId(null);
			ticket.setActualLaneName(null);
			ticket.setLaneEntryTime(null);
			ticket.setExitTime(null);
			ticket.setClosedAt(null);
			ticket.setStatus("ASSIGNED");
			ticket.setSource("ALPR_YARD");
			ticket.setOperator("总入口抓拍");
			ticket.setNotes("根据总入口在场流水自动恢复大屏引导");
			changedTickets.add(ticket);
		}
		if (!changedTickets.isEmpty()) {
			dispatchTicketRepository.saveAll(changedTickets);
			log.warn(
					"Recovered active yard guide assignments from entry logs count={} plates={} referenceTime={}",
					changedTickets.size(),
					changedTickets.stream().map(DispatchTicket::getPlate).toList(),
					referenceTime);
		}
	}

	private boolean hasOpenGuideTicketForEntryLog(EntryLog log) {
		return dispatchTicketRepository.findByPlateIgnoreCaseAndClosedAtIsNullOrderByYardEntryTimeDesc(log.getPlate()).stream()
				.anyMatch(ticket -> ticket.getExitTime() == null
						&& "ASSIGNED".equals(ticket.getStatus())
						&& ticket.getLaneEntryTime() == null
						&& entryLogTimeMatchesTicket(ticket, log));
	}

	private DispatchTicket findRecoverableTicketForEntryLog(EntryLog log) {
		return dispatchTicketRepository.findAllByOrderByYardEntryTimeDesc().stream()
				.filter(ticket -> normalizePlate(log.getPlate()).equals(normalizePlate(ticket.getPlate())))
				.filter(ticket -> entryLogTimeMatchesTicket(ticket, log))
				.filter(ticket -> ticket.getExitTime() == null)
				.filter(ticket -> ticket.getLaneEntryTime() == null)
				.findFirst()
				.orElse(null);
	}

	private List<ThroughputPoint> buildThroughput(List<EntryLog> logs) {
		OffsetDateTime reference = now().truncatedTo(ChronoUnit.HOURS);
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
		Map<OffsetDateTime, Long> counts = logs.stream()
				.collect(Collectors.groupingBy(log -> log.getEntryTime().truncatedTo(ChronoUnit.HOURS), Collectors.counting()));
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
		Map<String, EntryLog> headLogs = new HashMap<>();
		for (EntryLog log : entryLogRepository.findByExitTimeIsNullOrderByEntryTimeAsc()) {
			headLogs.putIfAbsent(log.getLaneId(), log);
		}

		List<String> laneOrder = currentLaneOrder(ENTRY_LANE_ORDER_KEY, defaultEntryLaneOrder);
		Map<String, Long> pendingCounts = pendingAssignmentCounts(referenceTime);
		boolean entryDispatchEnabled = currentBooleanConfig(ENTRY_DISPATCH_ENABLED_KEY, defaultEntryDispatchEnabled);
		boolean exitDispatchEnabled = currentBooleanConfig(EXIT_DISPATCH_ENABLED_KEY, defaultExitDispatchEnabled);
		String activeAutoEntryLaneId = entryDispatchEnabled
				? resolveAndPersistActiveEntryLaneId(lanes, laneOrder, pendingCounts, referenceTime)
				: null;
		String activeAutoExitLaneId = exitDispatchEnabled
				? resolveAndPersistActiveExitLaneId(lanes, laneOrder, referenceTime, activeAutoEntryLaneId)
				: null;

		for (Lane lane : lanes) {
			long reservedCount = pendingCounts.getOrDefault(lane.getId(), 0L);
			lane.setReservedCount(Math.toIntExact(Math.min(Integer.MAX_VALUE, reservedCount)));
			lane.setAvailableSlots(Math.max(0, lane.getCapacity() - lane.getVehicleCount() - lane.getReservedCount()));
			EntryLog headLog = headLogs.get(lane.getId());
			if (headLog != null) {
				lane.setCurrentPlate(headLog.getPlate());
			} else if (lane.getVehicleCount() <= 0) {
				lane.setCurrentPlate(null);
			}
			lane.setStatus(resolveStatusForLane(lane, reservedCount));
			if ("AUTO".equals(lane.getMode())) {
				applyAutomaticSignals(
						lane,
						entryDispatchEnabled && lane.getId().equals(activeAutoEntryLaneId) && canReserveEntrySlot(lane, reservedCount),
						exitDispatchEnabled && lane.getId().equals(activeAutoExitLaneId));
				laneRuntimeStateService.recordRenderedState(lane.getId(), lane.getEntrySignal(), lane.getExitSignal(), resolveLedMessage(lane), referenceTime);
			} else if ("MANUAL".equals(lane.getMode())) {
				applyManualSignals(lane, referenceTime);
			} else {
				lane.setEntrySignal("OFFLINE");
				lane.setExitSignal("OFFLINE");
				laneRuntimeStateService.recordRenderedState(lane.getId(), "OFFLINE", "OFFLINE", "车道已设为离线", referenceTime);
			}
			if (lane.getLastActionAt() == null) {
				lane.setLastActionAt(referenceTime);
			}
		}
		laneDeviceGateway.syncBatch(lanes);

		return laneRepository.saveAll(lanes).stream()
				.map(laneRuntimeStateService::applyRuntimeState)
				.toList();
	}

	private void applyAutomaticSignals(Lane lane, boolean entryOpenNow, boolean exitOpenNow) {
		if ("OFFLINE".equals(lane.getStatus())) {
			lane.setEntrySignal("OFFLINE");
			lane.setExitSignal("OFFLINE");
			return;
		}
		lane.setEntrySignal(entryOpenNow ? "GREEN" : "RED");
		lane.setExitSignal(exitOpenNow ? "GREEN" : "RED");
	}

	private void applyManualSignals(Lane lane, OffsetDateTime referenceTime) {
		String targetEntrySignal = laneRuntimeStateService.targetEntrySignal(lane.getId(), "RED");
		String targetExitSignal = laneRuntimeStateService.targetExitSignal(lane.getId(), "RED");
		if ("GREEN".equals(targetEntrySignal) && lane.getAvailableSlots() <= 0) {
			targetEntrySignal = "RED";
			laneRuntimeStateService.recordTarget(
					lane.getId(),
					"RED",
					targetExitSignal,
					"车道已满，手动入口绿灯已转红保护",
					referenceTime);
		}
		lane.setEntrySignal(targetEntrySignal);
		lane.setExitSignal(targetExitSignal);
		laneRuntimeStateService.recordRenderedState(lane.getId(), lane.getEntrySignal(), lane.getExitSignal(), resolveLedMessage(lane), referenceTime);
	}

	private String resolveLedMessage(Lane lane) {
		if ("OFFLINE".equals(lane.getMode()) || "OFFLINE".equals(lane.getSensorStatus())) {
			return "设备离线，等待现场复位";
		}
		if ("GREEN".equals(lane.getExitSignal())) {
			return "出口放行，请按序通行";
		}
		if ("GREEN".equals(lane.getEntrySignal())) {
			return lane.getReservedCount() > 0
					? "入口开放，按屏显指引驶入"
					: "入口开放，请驶入本车道";
		}
		if ("FULL".equals(lane.getStatus())) {
			return lane.getReservedCount() > 0
					? "车道预约已满，等待下一条车道"
					: "车道已满，入口禁入";
		}
		if ("BUSY".equals(lane.getStatus())) {
			return "车道繁忙，请减速慢行";
		}
		return "入口待命，请按屏显提示通行";
	}

	private String resolveOpenEntryLaneId(List<Lane> lanes, OffsetDateTime referenceTime) {
		List<String> laneOrder = currentLaneOrder(ENTRY_LANE_ORDER_KEY, defaultEntryLaneOrder);
		Map<String, Long> pendingCounts = pendingAssignmentCounts(referenceTime);
		if (!currentBooleanConfig(ENTRY_DISPATCH_ENABLED_KEY, defaultEntryDispatchEnabled)) {
			return null;
		}
		return resolveAndPersistActiveEntryLaneId(lanes, laneOrder, pendingCounts, referenceTime);
	}

	private String resolveOpenExitLaneId(List<Lane> lanes, OffsetDateTime referenceTime, String activeEntryLaneId) {
		List<String> laneOrder = currentLaneOrder(ENTRY_LANE_ORDER_KEY, defaultEntryLaneOrder);
		if (!currentBooleanConfig(EXIT_DISPATCH_ENABLED_KEY, defaultExitDispatchEnabled)) {
			return null;
		}
		return resolveAndPersistActiveExitLaneId(lanes, laneOrder, referenceTime, activeEntryLaneId);
	}

	private String resolveAndPersistActiveEntryLaneId(
			List<Lane> lanes,
			List<String> laneOrder,
			Map<String, Long> pendingCounts,
			OffsetDateTime referenceTime) {
		String currentLaneId = currentStringConfig(ACTIVE_ENTRY_LANE_KEY, null);
		String resolvedLaneId = resolveActiveEntryLaneId(lanes, laneOrder, pendingCounts, currentLaneId);
		saveActiveLaneConfig(ACTIVE_ENTRY_LANE_KEY, resolvedLaneId, referenceTime);
		return resolvedLaneId;
	}

	private String resolveAndPersistActiveExitLaneId(List<Lane> lanes, List<String> laneOrder, OffsetDateTime referenceTime, String activeEntryLaneId) {
		String currentLaneId = currentStringConfig(ACTIVE_EXIT_LANE_KEY, null);
		String resolvedLaneId = resolveActiveExitLaneId(lanes, laneOrder, activeEntryLaneId, currentLaneId);
		saveActiveLaneConfig(ACTIVE_EXIT_LANE_KEY, resolvedLaneId, referenceTime);
		return resolvedLaneId;
	}

	private String resolveActiveEntryLaneId(
			List<Lane> lanes,
			List<String> laneOrder,
			Map<String, Long> pendingCounts,
			String currentLaneId) {
		List<Lane> orderedLanes = sortLanesByOrder(lanes, laneOrder);
		Lane currentLane = laneById(orderedLanes, currentLaneId);
		if (currentLane != null && canReserveEntrySlot(currentLane, pendingCounts.getOrDefault(currentLane.getId(), 0L))) {
			return currentLane.getId();
		}
		return nextEligibleEntryLaneId(orderedLanes, currentLaneId, pendingCounts);
	}

	private String resolveActiveExitLaneId(List<Lane> lanes, List<String> laneOrder, String activeEntryLaneId, String currentLaneId) {
		List<Lane> orderedLanes = sortLanesByOrder(lanes, laneOrder);
		Lane currentLane = laneById(orderedLanes, currentLaneId);
		if (isEligibleCurrentExitLane(orderedLanes, currentLane, activeEntryLaneId)) {
			return currentLane.getId();
		}
		return nextEligibleExitLaneId(orderedLanes, currentLaneId, activeEntryLaneId);
	}

	private boolean canReserveEntrySlot(Lane lane, long pendingCount) {
		return canActivateLane(lane)
				&& !"DEGRADED".equals(lane.getSensorStatus())
				&& lane.getVehicleCount() + pendingCount < lane.getCapacity();
	}

	private boolean canActivateLane(Lane lane) {
		return !"OFFLINE".equals(lane.getMode())
				&& !"OFFLINE".equals(lane.getSensorStatus())
				&& lane.getCapacity() > 0;
	}

	private void advanceEntryLane(
			OffsetDateTime referenceTime,
			String currentLaneId,
			List<Lane> lanes,
			List<String> laneOrder,
			Map<String, Long> pendingCounts) {
		saveActiveLaneConfig(
				ACTIVE_ENTRY_LANE_KEY,
				nextEligibleEntryLaneId(sortLanesByOrder(lanes, laneOrder), currentLaneId, pendingCounts),
				referenceTime);
	}

	private void advanceExitLane(OffsetDateTime referenceTime, String currentLaneId) {
		List<Lane> lanes = laneRepository.findAllByOrderByCodeAsc();
		List<String> laneOrder = currentLaneOrder(ENTRY_LANE_ORDER_KEY, defaultEntryLaneOrder);
		String activeEntryLaneId = resolveOpenEntryLaneId(lanes, referenceTime);
		saveActiveLaneConfig(
				ACTIVE_EXIT_LANE_KEY,
				nextEligibleExitLaneId(sortLanesByOrder(lanes, laneOrder), currentLaneId, activeEntryLaneId),
				referenceTime);
	}

	private String nextEligibleEntryLaneId(List<Lane> orderedLanes, String currentLaneId, Map<String, Long> pendingCounts) {
		boolean accept = isBlank(currentLaneId);
		for (Lane lane : orderedLanes) {
			if (accept && canReserveEntrySlot(lane, pendingCounts.getOrDefault(lane.getId(), 0L))) {
				return lane.getId();
			}
			if (lane.getId().equals(currentLaneId)) {
				accept = true;
			}
		}
		return null;
	}

	private String nextEligibleExitLaneId(List<Lane> orderedLanes, String currentLaneId, String activeEntryLaneId) {
		int limitIndex = exitSearchLimitIndex(orderedLanes, activeEntryLaneId);
		if (limitIndex < 0) {
			return null;
		}

		String activeEntryFallback = null;
		int currentIndex = laneIndex(orderedLanes, currentLaneId);
		int startIndex = currentIndex >= 0 && currentIndex < limitIndex ? currentIndex + 1 : 0;
		for (int index = startIndex; index <= limitIndex; index++) {
			Lane lane = orderedLanes.get(index);
			if (!canActivateLane(lane)) {
				continue;
			}
			if (lane.getVehicleCount() > 0) {
				return lane.getId();
			}
			if (lane.getId().equals(activeEntryLaneId)) {
				activeEntryFallback = lane.getId();
			}
		}
		return activeEntryFallback;
	}

	private boolean isEligibleCurrentExitLane(List<Lane> orderedLanes, Lane currentLane, String activeEntryLaneId) {
		if (currentLane == null || !canActivateLane(currentLane) || currentLane.getVehicleCount() <= 0) {
			return false;
		}
		int currentIndex = laneIndex(orderedLanes, currentLane.getId());
		int limitIndex = exitSearchLimitIndex(orderedLanes, activeEntryLaneId);
		return currentIndex >= 0 && currentIndex <= limitIndex;
	}

	private int exitSearchLimitIndex(List<Lane> orderedLanes, String activeEntryLaneId) {
		int activeEntryIndex = laneIndex(orderedLanes, activeEntryLaneId);
		return activeEntryIndex >= 0 ? activeEntryIndex : orderedLanes.size() - 1;
	}

	private int laneIndex(List<Lane> orderedLanes, String laneId) {
		if (isBlank(laneId)) {
			return -1;
		}
		for (int index = 0; index < orderedLanes.size(); index++) {
			if (laneId.equals(orderedLanes.get(index).getId())) {
				return index;
			}
		}
		return -1;
	}

	private List<Lane> sortLanesByOrder(List<Lane> lanes, List<String> laneOrder) {
		return lanes.stream()
				.sorted((left, right) -> compareLaneOrder(left, right, laneOrder))
				.toList();
	}

	private Map<String, Long> pendingAssignmentCounts(OffsetDateTime referenceTime) {
		Map<String, Long> counts = new HashMap<>();
		for (DispatchTicket ticket : dispatchTicketRepository.findByClosedAtIsNullOrderByYardEntryTimeAsc()) {
			if ("ASSIGNED".equals(ticket.getStatus())
					&& ticket.getLaneEntryTime() == null
					&& !isBlank(ticket.getAssignedLaneId())) {
				counts.merge(ticket.getAssignedLaneId(), 1L, Long::sum);
			}
		}
		return counts;
	}

	private void expireStaleDispatchTickets(OffsetDateTime referenceTime) {
		long reserveMinutes = currentAssignmentReserveMinutes();
		OffsetDateTime threshold = referenceTime.minusMinutes(reserveMinutes);
		List<DispatchTicket> expiredTickets = dispatchTicketRepository.findByClosedAtIsNullOrderByYardEntryTimeAsc().stream()
				.filter(ticket -> "ASSIGNED".equals(ticket.getStatus()))
				.filter(ticket -> ticket.getLaneEntryTime() == null)
				.filter(ticket -> ticket.getYardEntryTime() != null && !ticket.getYardEntryTime().isAfter(threshold))
				.toList();
		for (DispatchTicket ticket : expiredTickets) {
			ticket.setStatus("EXPIRED");
			ticket.setClosedAt(referenceTime);
			ticket.setNotes("总入口入场后 " + reserveMinutes + " 分钟内未被任何车道入口摄像头识别，生成未进车道告警并释放预分配");
			log.warn(
					"Yard entry assignment expired plate={} ticketId={} assignedLane={} yardEntryTime={} referenceTime={} reserveMinutes={}",
					ticket.getPlate(),
					ticket.getId(),
					ticket.getAssignedLaneId(),
					ticket.getYardEntryTime(),
					referenceTime,
					reserveMinutes);
		}
		if (!expiredTickets.isEmpty()) {
			dispatchTicketRepository.saveAll(expiredTickets);
		}
	}

	private void ensureEntryDispatchRunningForYardCapture(
			String source,
			OffsetDateTime referenceTime,
			List<Lane> lanes,
			List<String> laneOrder) {
		if (!shouldAutoStartEntryDispatch(source)) {
			return;
		}
		if (!currentBooleanConfig(ENTRY_DISPATCH_ENABLED_KEY, defaultEntryDispatchEnabled)) {
			log.warn("Auto enabling entry dispatch for yard capture source={} referenceTime={}", source, referenceTime);
			saveDispatchConfig(ENTRY_DISPATCH_ENABLED_KEY, Boolean.TRUE.toString(), referenceTime);
		}
		if (isBlank(currentStringConfig(ACTIVE_ENTRY_LANE_KEY, null))) {
			String firstLaneId = firstOrderedLaneId(lanes, laneOrder);
			if (isBlank(firstLaneId)) {
				log.warn("Cannot select active entry lane for yard capture source={} referenceTime={} lanes={} laneOrder={}", source, referenceTime, lanes.size(), laneOrder);
			}
			saveActiveLaneConfig(ACTIVE_ENTRY_LANE_KEY, firstLaneId, referenceTime);
		}
	}

	private boolean shouldAutoStartEntryDispatch(String source) {
		String normalizedSource = source == null ? "" : source.toUpperCase(Locale.ROOT);
		return normalizedSource.contains("YARD")
				|| normalizedSource.contains("SCREEN_SIMULATION")
				|| normalizedSource.contains("SMART_CAMERA");
	}

	private void upsertDispatchTicketForLaneEntry(
			DispatchTicket ticket,
			Lane lane,
			String plate,
			String vehicleType,
			String source,
			OffsetDateTime entryTime) {
		if (ticket == null) {
			dispatchTicketRepository.save(newDispatchTicket(plate, lane, vehicleType, source, "DIRECT_ENTERED", "设备采集", entryTime, "缺少总入口预分配，按车道入口直接入场"));
			return;
		}

		if (ticket.getAssignedLaneId() == null) {
			ticket.setAssignedLaneId(lane.getId());
			ticket.setAssignedLaneName(lane.getName());
			ticket.setAssignedAt(ticket.getAssignedAt() == null ? entryTime : ticket.getAssignedAt());
		}
		ticket.setActualLaneId(lane.getId());
		ticket.setActualLaneName(lane.getName());
		ticket.setLaneEntryTime(entryTime);
		ticket.setClosedAt(null);
		ticket.setExitTime(null);
		ticket.setSource(source);
		boolean wasExpired = "EXPIRED".equals(ticket.getStatus());
		ticket.setStatus(lane.getId().equals(ticket.getAssignedLaneId()) ? "ENTERED" : "ENTERED_MISMATCH");
		if (!lane.getId().equals(ticket.getAssignedLaneId())) {
			ticket.setNotes("司机未按屏显进入推荐车道");
		} else if (wasExpired) {
			ticket.setNotes("未进车道告警后已确认进入推荐车道");
		}
		dispatchTicketRepository.save(ticket);
	}

	private DispatchTicket newDispatchTicket(
			String plate,
			Lane lane,
			String vehicleType,
			String source,
			String status,
			String operator,
			OffsetDateTime referenceTime,
			String notes) {
		return DispatchTicket.builder()
				.id("DSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT))
				.plate(plate)
				.yardEntryTime(referenceTime)
				.assignedLaneId(lane.getId())
				.assignedLaneName(lane.getName())
				.assignedAt(referenceTime)
				.actualLaneId(lane.getId())
				.actualLaneName(lane.getName())
				.laneEntryTime(referenceTime)
				.vehicleType(vehicleType)
				.status(status)
				.source(source)
				.operator(operator)
				.notes(notes)
				.build();
	}

	private void closeExitedVehicles(Lane lane, int exitCount, OffsetDateTime referenceTime) {
		if (exitCount <= 0) {
			return;
		}
		List<EntryLog> activeLogs = entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc(lane.getId());
		int closable = Math.min(exitCount, activeLogs.size());
		for (int index = 0; index < closable; index++) {
			EntryLog log = activeLogs.get(index);
			log.setExitTime(referenceTime);
			closeDispatchTicketForExit(log, referenceTime);
		}
		if (closable > 0) {
			entryLogRepository.saveAll(activeLogs.subList(0, closable));
		}
		if (lane.getVehicleCount() == 0) {
			lane.setCurrentPlate(null);
			lane.setQueueHeadAt(null);
			lane.setPriority(false);
		} else if (closable < activeLogs.size()) {
			lane.setCurrentPlate(activeLogs.get(closable).getPlate());
			lane.setQueueHeadAt(activeLogs.get(closable).getEntryTime());
		}
	}

	private void refreshLaneHeadAfterVehicleExit(Lane lane, List<EntryLog> activeLogs, EntryLog exitedLog) {
		List<EntryLog> remainingLogs = activeLogs.stream()
				.filter(log -> !Objects.equals(log.getId(), exitedLog.getId()))
				.toList();
		if (lane.getVehicleCount() == 0 || remainingLogs.isEmpty()) {
			lane.setCurrentPlate(null);
			lane.setQueueHeadAt(null);
			lane.setPriority(false);
			return;
		}
		EntryLog headLog = remainingLogs.getFirst();
		lane.setCurrentPlate(headLog.getPlate());
		lane.setQueueHeadAt(headLog.getEntryTime());
	}

	private void closeDispatchTicketForExit(EntryLog log, OffsetDateTime referenceTime) {
		DispatchTicket ticket = dispatchTicketRepository.findByPlateIgnoreCaseAndClosedAtIsNullOrderByYardEntryTimeDesc(log.getPlate()).stream()
				.filter(candidate -> log.getLaneId().equals(candidate.getActualLaneId()))
				.filter(candidate -> candidate.getLaneEntryTime() != null)
				.filter(candidate -> candidate.getExitTime() == null)
				.findFirst()
				.orElse(null);
		if (ticket == null) {
			return;
		}
		closeDispatchTicket(ticket, referenceTime);
	}

	private void closeDispatchTicket(DispatchTicket ticket, OffsetDateTime referenceTime) {
		ticket.setExitTime(referenceTime);
		ticket.setClosedAt(referenceTime);
		ticket.setStatus("EXITED");
		dispatchTicketRepository.save(ticket);
	}

	private void reconcileLaneQueue(Lane lane, int targetCount, OffsetDateTime referenceTime) {
		List<EntryLog> activeLogs = entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc(lane.getId());
		if (activeLogs.size() > targetCount) {
			closeExitedVehicles(lane, activeLogs.size() - targetCount, referenceTime);
		}
		if (targetCount == 0) {
			lane.setCurrentPlate(null);
			lane.setQueueHeadAt(null);
			lane.setPriority(false);
		} else if (!activeLogs.isEmpty()) {
			lane.setCurrentPlate(activeLogs.get(Math.min(activeLogs.size() - 1, Math.max(0, activeLogs.size() - targetCount))).getPlate());
			lane.setQueueHeadAt(activeLogs.get(Math.min(activeLogs.size() - 1, Math.max(0, activeLogs.size() - targetCount))).getEntryTime());
		}
	}

	private void updateQueueHeadAtForObservedCountChange(
			Lane lane,
			int previousVehicleCount,
			int currentVehicleCount,
			OffsetDateTime observedAt) {
		if (currentVehicleCount <= 0) {
			lane.setQueueHeadAt(null);
			return;
		}
		if (previousVehicleCount <= 0) {
			lane.setQueueHeadAt(observedAt);
		}
	}

	private String firstOrderedLaneId(List<Lane> lanes, List<String> laneOrder) {
		return sortLanesByOrder(lanes, laneOrder).stream()
				.filter(this::canActivateLane)
				.map(Lane::getId)
				.findFirst()
				.orElse(null);
	}

	private int compareLaneOrder(Lane left, Lane right, List<String> laneOrder) {
		return Comparator
				.comparingInt((Lane lane) -> laneOrderIndex(lane, laneOrder))
				.thenComparingInt(this::laneNaturalNumberOrMax)
				.thenComparing(Lane::getCode, Comparator.nullsLast(String::compareTo))
				.thenComparing(Lane::getId, Comparator.nullsLast(String::compareTo))
				.compare(left, right);
	}

	private int laneOrderIndex(Lane lane, List<String> laneOrder) {
		for (int index = 0; index < laneOrder.size(); index++) {
			if (laneMatchesOrderToken(lane, laneOrder.get(index))) {
				return index;
			}
		}
		return Integer.MAX_VALUE;
	}

	private boolean laneMatchesOrderToken(Lane lane, String token) {
		if (token.equals(normalizeOrderToken(lane.getId()))
				|| token.equals(normalizeOrderToken(lane.getCode()))
				|| token.equals(normalizeOrderToken(lane.getName()))) {
			return true;
		}
		if (token.chars().allMatch(Character::isDigit)) {
			Integer laneNumber = laneNaturalNumber(lane);
			return laneNumber != null && laneNumber == Integer.parseInt(token);
		}
		return false;
	}

	private int laneNaturalNumberOrMax(Lane lane) {
		Integer value = laneNaturalNumber(lane);
		return value == null ? Integer.MAX_VALUE : value;
	}

	private Integer laneNaturalNumber(Lane lane) {
		for (String candidate : new String[] { lane.getCode(), lane.getName(), lane.getId() }) {
			if (candidate == null) {
				continue;
			}
			Matcher matcher = FIRST_NUMBER.matcher(candidate);
			if (matcher.find()) {
				return Integer.parseInt(matcher.group());
			}
		}
		return null;
	}

	private List<String> currentLaneOrder(String configKey, List<String> fallback) {
		return dispatchConfigRepository.findById(configKey)
				.map(DispatchConfig::getConfigValue)
				.map(this::parseLaneOrder)
				.filter(order -> !order.isEmpty())
				.orElse(fallback);
	}

	private String currentLaneOrderValue(String configKey, List<String> fallback) {
		return dispatchConfigRepository.findById(configKey)
				.map(DispatchConfig::getConfigValue)
				.filter(value -> !parseLaneOrder(value).isEmpty())
				.orElse(String.join(",", fallback));
	}

	private boolean currentBooleanConfig(String configKey, boolean fallback) {
		return dispatchConfigRepository.findById(configKey)
				.map(DispatchConfig::getConfigValue)
				.map(this::parseBooleanConfig)
				.orElse(fallback);
	}

	private long currentAssignmentReserveMinutes() {
		return dispatchConfigRepository.findById(ASSIGNMENT_RESERVE_MINUTES_KEY)
				.map(DispatchConfig::getConfigValue)
				.map(this::parsePositiveLong)
				.filter(value -> value >= 1L && value <= 60L)
				.orElse(assignmentReserveMinutes);
	}

	private String currentStringConfig(String configKey, String fallback) {
		return dispatchConfigRepository.findById(configKey)
				.map(DispatchConfig::getConfigValue)
				.filter(value -> !isBlank(value))
				.orElse(fallback);
	}

	private void saveDispatchConfig(String configKey, String configValue, OffsetDateTime updatedAt) {
		DispatchConfig config = dispatchConfigRepository.findById(configKey)
				.orElseGet(() -> DispatchConfig.builder()
						.configKey(configKey)
						.updatedBy("控制台")
						.build());
		config.setConfigValue(configValue.trim());
		config.setUpdatedAt(updatedAt);
		config.setUpdatedBy("控制台");
		dispatchConfigRepository.save(config);
	}

	private void clearDispatchConfig(String configKey) {
		if (dispatchConfigRepository.existsById(configKey)) {
			dispatchConfigRepository.deleteById(configKey);
		}
	}

	private void saveActiveLaneConfig(String configKey, String laneId, OffsetDateTime updatedAt) {
		String currentValue = currentStringConfig(configKey, null);
		if (Objects.equals(currentValue, laneId)) {
			return;
		}
		if (isBlank(laneId)) {
			clearDispatchConfig(configKey);
			return;
		}
		saveDispatchConfig(configKey, laneId, updatedAt);
	}

	private List<String> parseLaneOrder(String configuredOrder) {
		if (isBlank(configuredOrder)) {
			return List.of();
		}
		String trimmed = configuredOrder.trim();
		Matcher rangeMatcher = NUMERIC_RANGE.matcher(trimmed);
		if (rangeMatcher.matches()) {
			int start = Integer.parseInt(rangeMatcher.group(1));
			int end = Integer.parseInt(rangeMatcher.group(2));
			int step = start <= end ? 1 : -1;
			return java.util.stream.IntStream.iterate(start, value -> value != end + step, value -> value + step)
					.mapToObj(String::valueOf)
					.toList();
		}
		List<String> tokens = new ArrayList<>();
		for (String value : trimmed.split("[,，;；\\s]+")) {
			String token = normalizeOrderToken(value);
			if (!token.isBlank()) {
				tokens.add(token);
			}
		}
		return tokens;
	}

	private boolean parseBooleanConfig(String value) {
		if (isBlank(value)) {
			return false;
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		return List.of("true", "1", "yes", "y", "on", "enabled").contains(normalized);
	}

	private long parsePositiveLong(String value) {
		if (isBlank(value)) {
			return assignmentReserveMinutes;
		}
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException ignored) {
			return assignmentReserveMinutes;
		}
	}

	private OffsetDateTime currentDailyResetAt() {
		String value = currentStringConfig(LAST_DAILY_RESET_AT_KEY, null);
		if (isBlank(value)) {
			return null;
		}
		try {
			OffsetDateTime resetAt = OffsetDateTime.parse(value.trim());
			if (resetAt.isAfter(now().plusMinutes(1))) {
				return null;
			}
			OffsetDateTime latestActiveEntryTime = latestActiveEntryLogTime();
			if (latestActiveEntryTime != null && resetAt.isAfter(latestActiveEntryTime.plusMinutes(1))) {
				log.warn(
						"Ignoring daily reset marker because it is after latest active entry log. resetAt={} latestActiveEntryTime={}. This usually means server clock/timezone is ahead of device timestamps.",
						resetAt,
						latestActiveEntryTime);
				return null;
			}
			return resetAt;
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private OffsetDateTime latestActiveEntryLogTime() {
		List<EntryLog> activeLogs = entryLogRepository.findByExitTimeIsNullOrderByEntryTimeAsc();
		if (activeLogs.isEmpty()) {
			return null;
		}
		return activeLogs.getLast().getEntryTime();
	}

	private DispatchTicket findLatestOpenTicketByPlate(String plate) {
		if (isBlank(plate)) {
			return null;
		}
		return dispatchTicketRepository.findByPlateIgnoreCaseAndClosedAtIsNullOrderByYardEntryTimeDesc(plate).stream()
				.filter(ticket -> ticket.getExitTime() == null)
				.findFirst()
				.orElse(null);
	}

	private DispatchTicket findLatestPendingTicketByPlate(String plate) {
		if (isBlank(plate)) {
			return null;
		}
		return dispatchTicketRepository.findByPlateIgnoreCaseAndClosedAtIsNullOrderByYardEntryTimeDesc(plate).stream()
				.filter(ticket -> ticket.getExitTime() == null)
				.filter(ticket -> ticket.getLaneEntryTime() == null)
				.findFirst()
				.orElse(null);
	}

	private DispatchTicket findLatestRecoverableExpiredTicketByPlate(String plate, OffsetDateTime referenceTime) {
		if (isBlank(plate)) {
			return null;
		}
		OffsetDateTime currentCycleStart = currentDailyResetAt();
		return dispatchTicketRepository.findAllByOrderByYardEntryTimeDesc().stream()
				.filter(ticket -> normalizePlate(plate).equals(normalizePlate(ticket.getPlate())))
				.filter(ticket -> "EXPIRED".equals(ticket.getStatus()))
				.filter(ticket -> ticket.getLaneEntryTime() == null)
				.filter(ticket -> ticket.getExitTime() == null)
				.filter(ticket -> currentCycleStart == null || ticketTime(ticket).isAfter(currentCycleStart) || ticketTime(ticket).isEqual(currentCycleStart))
				.filter(ticket -> ticket.getYardEntryTime() == null || !ticket.getYardEntryTime().isAfter(referenceTime))
				.findFirst()
				.orElse(null);
	}

	private DispatchTicket findLatestEnteredTicketByPlate(String plate) {
		if (isBlank(plate)) {
			return null;
		}
		return dispatchTicketRepository.findByPlateIgnoreCaseAndClosedAtIsNullOrderByYardEntryTimeDesc(plate).stream()
				.filter(ticket -> ticket.getExitTime() == null)
				.filter(ticket -> ticket.getLaneEntryTime() != null)
				.findFirst()
				.orElse(null);
	}

	private void closeProvisionalEntryLogForExpiredTicket(DispatchTicket ticket, OffsetDateTime referenceTime) {
		if (ticket == null || isBlank(ticket.getPlate()) || ticket.getYardEntryTime() == null || isBlank(ticket.getAssignedLaneId())) {
			return;
		}
		entryLogRepository.findByPlateIgnoreCaseAndExitTimeIsNullOrderByEntryTimeAsc(ticket.getPlate()).stream()
				.filter(log -> ticket.getAssignedLaneId().equals(log.getLaneId()))
				.filter(log -> ticket.getYardEntryTime().isEqual(log.getEntryTime()))
				.findFirst()
				.ifPresent(log -> log.setExitTime(referenceTime));
	}

	private void closeStaleActiveEntryLogsForPlate(String plate, OffsetDateTime referenceTime) {
		if (isBlank(plate)) {
			return;
		}
		List<EntryLog> activeLogs = entryLogRepository.findByPlateIgnoreCaseAndExitTimeIsNullOrderByEntryTimeAsc(plate);
		if (activeLogs.isEmpty()) {
			return;
		}
		List<DispatchTicket> openTickets = dispatchTicketRepository.findByPlateIgnoreCaseAndClosedAtIsNullOrderByYardEntryTimeDesc(plate).stream()
				.filter(ticket -> ticket.getExitTime() == null)
				.toList();
		DispatchTicket recoverableExpiredTicket = findLatestRecoverableExpiredTicketByPlate(plate, referenceTime);
		List<DispatchTicket> candidateTickets = openTickets;
		if (recoverableExpiredTicket != null) {
			candidateTickets = Stream.concat(openTickets.stream(), Stream.of(recoverableExpiredTicket)).toList();
		}
		List<DispatchTicket> activeTickets = candidateTickets;
		List<EntryLog> staleLogs = activeLogs.stream()
				.filter(log -> activeTickets.stream().noneMatch(ticket -> matchesActiveEntryLog(ticket, log)))
				.toList();
		if (staleLogs.isEmpty()) {
			return;
		}
		staleLogs.forEach(log -> log.setExitTime(referenceTime));
		entryLogRepository.saveAll(staleLogs);
	}

	private boolean matchesActiveEntryLog(DispatchTicket ticket, EntryLog log) {
		if (ticket == null || log == null) {
			return false;
		}
		String expectedLaneId = firstNonBlank(ticket.getActualLaneId(), ticket.getAssignedLaneId());
		return !isBlank(expectedLaneId)
				&& expectedLaneId.equals(log.getLaneId())
				&& entryLogTimeMatchesTicket(ticket, log);
	}

	private boolean entryLogTimeMatchesTicket(DispatchTicket ticket, EntryLog log) {
		return (ticket.getLaneEntryTime() != null && ticket.getLaneEntryTime().isEqual(log.getEntryTime()))
				|| (ticket.getYardEntryTime() != null && ticket.getYardEntryTime().isEqual(log.getEntryTime()));
	}

	private EntryLog findActiveEntryLogForTicket(DispatchTicket ticket, String plate) {
		if (ticket == null || isBlank(plate)) {
			return null;
		}
		return entryLogRepository.findAllByOrderByEntryTimeDesc().stream()
				.filter(log -> log.getExitTime() == null)
				.filter(log -> plate.equals(normalizePlate(log.getPlate())))
				.filter(log -> ticket.getYardEntryTime() == null || log.getEntryTime().isEqual(ticket.getYardEntryTime()))
				.findFirst()
				.orElse(null);
	}

	private EntryLog findActiveEntryLogInLane(String laneId, String plate) {
		if (isBlank(laneId) || isBlank(plate)) {
			return null;
		}
		return entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc(laneId).stream()
				.filter(log -> plate.equals(normalizePlate(log.getPlate())))
				.findFirst()
				.orElse(null);
	}

	private Lane laneById(List<Lane> lanes, String laneId) {
		if (isBlank(laneId)) {
			return null;
		}
		return lanes.stream()
				.filter(lane -> laneId.equals(lane.getId()))
				.findFirst()
				.orElse(null);
	}

	private String laneName(List<Lane> lanes, String laneId) {
		Lane lane = laneById(lanes, laneId);
		return lane == null ? null : lane.getName();
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

	private void validateSignals(String entrySignal, String exitSignal) {
		if (!VALID_SIGNAL_STATES.contains(entrySignal) || !VALID_SIGNAL_STATES.contains(exitSignal)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "信号灯状态非法");
		}
	}

	private void validateOptionalSignal(String signal) {
		if (!isBlank(signal) && !VALID_SIGNAL_STATES.contains(signal)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "信号灯状态非法");
		}
	}

	private void validateRelayControlTarget(String target) {
		String normalizedTarget = target == null ? null : target.trim().toUpperCase(Locale.ROOT).replace('-', '_');
		if (isBlank(normalizedTarget) || !VALID_RELAY_CONTROL_TARGETS.contains(normalizedTarget)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "继电器目标仅支持 ENTRY_RED、ENTRY_GREEN、EXIT_RED、EXIT_GREEN");
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

	private String resolveStatusForLane(Lane lane, long reservedCount) {
		if ("OFFLINE".equals(lane.getMode()) || "OFFLINE".equals(lane.getSensorStatus())) {
			return "OFFLINE";
		}
		if (lane.getCapacity() <= 0) {
			return "OFFLINE";
		}
		double occupancyRate = (lane.getVehicleCount() + reservedCount) * 1.0 / lane.getCapacity();
		if (lane.getVehicleCount() + reservedCount >= lane.getCapacity()
				|| "DEGRADED".equals(lane.getSensorStatus())) {
			return "FULL";
		}
		if (occupancyRate >= 0.7 || lane.getVehicleCount() + reservedCount >= Math.max(1, lane.getCapacity() - 1)) {
			return "BUSY";
		}
		return "OPEN";
	}

	private void invalidateRuntimeViews(String action) {
		dashboardCacheService.evictDashboard();
		broadcastService.operationsChanged(action);
		eventPublisher.publishEvent(new OperationsChangedEvent(action));
	}

	private String defaultSensorStatus(String currentSensorStatus) {
		return isBlank(currentSensorStatus) ? "ONLINE" : currentSensorStatus;
	}

	private String normalizeOrderToken(String value) {
		if (value == null) {
			return "";
		}
		return value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
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

	private ScreenEventView screenEvent(
			String type,
			String id,
			String plate,
			String message,
			OffsetDateTime occurredAt,
			String sourceId,
			String sourceName) {
		return new ScreenEventView(id, type, plate, message, occurredAt, sourceId, sourceName, false, null);
	}

	private ScreenEventView withHandledState(ScreenEventView event, OffsetDateTime handledAt) {
		return new ScreenEventView(
				event.id(),
				event.type(),
				event.plate(),
				event.message(),
				event.occurredAt(),
				event.sourceId(),
				event.sourceName(),
				handledAt != null,
				handledAt);
	}

	private OffsetDateTime ticketTime(DispatchTicket ticket) {
		return firstNonNull(ticket.getLaneEntryTime(), ticket.getAssignedAt(), ticket.getYardEntryTime(), ticket.getClosedAt(), now());
	}

	private OffsetDateTime guideAssignmentTime(DispatchTicket ticket) {
		return firstNonNull(ticket.getAssignedAt(), ticket.getYardEntryTime(), ticket.getClosedAt(), now());
	}

	@SafeVarargs
	private final <T> T firstNonNull(T... values) {
		for (T value : values) {
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	private String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private OffsetDateTime now() {
		return OffsetDateTime.now(ZoneOffset.ofHours(8));
	}

	private boolean containsIgnoreCase(String source, String query) {
		return source != null && query != null && source.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
	}

	private String firstNonBlank(String... values) {
		for (String value : values) {
			if (!isBlank(value)) {
				return value;
			}
		}
		return null;
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
