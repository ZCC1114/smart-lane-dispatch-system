package com.smartlane.dispatch.service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.smartlane.dispatch.device.LaneDeviceGateway;
import com.smartlane.dispatch.dto.BlacklistPayload;
import com.smartlane.dispatch.dto.DashboardPayload;
import com.smartlane.dispatch.dto.DispatchBoardView;
import com.smartlane.dispatch.dto.DispatchConfigRequest;
import com.smartlane.dispatch.dto.DispatchConfigView;
import com.smartlane.dispatch.dto.DispatchRuntimeRequest;
import com.smartlane.dispatch.dto.EntryLogView;
import com.smartlane.dispatch.dto.LaneActivePlateView;
import com.smartlane.dispatch.dto.LaneSensorPayload;
import com.smartlane.dispatch.dto.ManualDispatchRequest;
import com.smartlane.dispatch.dto.PageResult;
import com.smartlane.dispatch.dto.RelayControlRequest;
import com.smartlane.dispatch.dto.SignalOverrideRequest;
import com.smartlane.dispatch.dto.ScreenEventView;
import com.smartlane.dispatch.dto.ThroughputPoint;
import com.smartlane.dispatch.dto.VehicleEntryPayload;
import com.smartlane.dispatch.dto.YardEntryPayload;
import com.smartlane.dispatch.dto.WhitelistImportProgress;
import com.smartlane.dispatch.dto.WhitelistImportResult;
import com.smartlane.dispatch.dto.WhitelistSettingsRequest;
import com.smartlane.dispatch.dto.WhitelistSettingsView;
import com.smartlane.dispatch.dto.WhitelistPayload;
import com.smartlane.dispatch.entity.BlacklistRecord;
import com.smartlane.dispatch.entity.DispatchConfig;
import com.smartlane.dispatch.entity.DispatchTicket;
import com.smartlane.dispatch.entity.EntryLog;
import com.smartlane.dispatch.entity.Lane;
import com.smartlane.dispatch.entity.ScreenAcknowledgedEvent;
import com.smartlane.dispatch.entity.ScreenHandledEvent;
import com.smartlane.dispatch.entity.WhitelistRecord;
import com.smartlane.dispatch.repository.BlacklistRecordRepository;
import com.smartlane.dispatch.repository.DispatchConfigRepository;
import com.smartlane.dispatch.repository.DispatchTicketRepository;
import com.smartlane.dispatch.repository.EntryLogRepository;
import com.smartlane.dispatch.repository.LaneRepository;
import com.smartlane.dispatch.repository.ScreenAcknowledgedEventRepository;
import com.smartlane.dispatch.repository.ScreenHandledEventRepository;
import com.smartlane.dispatch.repository.WhitelistRecordRepository;

import jakarta.annotation.PostConstruct;

@Service
@Transactional(readOnly = true)
public class OperationsService {

	private static final Logger log = LoggerFactory.getLogger(OperationsService.class);
	private static final Logger flowLog = LoggerFactory.getLogger("vehicle-flow");
	private static final long SCREEN_BOARD_DIAGNOSTIC_LOG_INTERVAL_MS = 30000L;

	private static final Pattern FIRST_NUMBER = Pattern.compile("\\d+");
	private static final Pattern NUMERIC_RANGE = Pattern.compile("(\\d+)\\s*-\\s*(\\d+)");

	private static final String ENTRY_LANE_ORDER_KEY = "entry_lane_order";
	private static final String ENTRY_DISPATCH_ENABLED_KEY = "entry_dispatch_enabled";
	private static final String EXIT_DISPATCH_ENABLED_KEY = "exit_dispatch_enabled";
	private static final String LANE_DISPATCH_DISABLED_KEY = "lane_dispatch_disabled";
	private static final String ACTIVE_ENTRY_LANE_KEY = "active_entry_lane";
	private static final String ACTIVE_EXIT_LANE_KEY = "active_exit_lane";
	private static final String ENTRY_LANE_OPENED_AT_KEY_PREFIX = "entry_lane_opened_at_";
	private static final String EXIT_LANE_OPENED_AT_KEY_PREFIX = "exit_lane_opened_at_";
	private static final String ACTIVE_SIGNAL_LANE_KEY = "active_signal_lane";
	private static final String ACTIVE_SIGNAL_DIRECTION_KEY = "active_signal_direction";
	private static final String ASSIGNMENT_RESERVE_MINUTES_KEY = "assignment_reserve_minutes";
	private static final String LAST_DAILY_RESET_AT_KEY = "last_daily_reset_at";
	private static final String WHITELIST_FILTER_ENABLED_KEY = "whitelist_filter_enabled";
	private static final String EXIT_HANDOFF_MANUAL_CONFIRM_KEY_PREFIX = "exit_handoff_manual_confirm_";
	private static final String EXIT_HANDOFF_MANUAL_CONFIRM_NOTE_PREFIX = "出口交接残留超过阈值，需人工确认";
	private static final String LEGACY_EXIT_LANE_ORDER_KEY = "exit_lane_order";
	private static final String LEGACY_ENTRY_DISPATCH_CURSOR_KEY = "entry_dispatch_cursor";
	private static final String LEGACY_ENTRY_DISPATCH_PAUSED_LANE_KEY = "entry_dispatch_paused_lane";

	private static final List<String> VALID_SIGNAL_STATES = List.of("RED", "GREEN", "OFFLINE");
	private static final List<String> VALID_RELAY_CONTROL_TARGETS = List.of("ENTRY_RED", "ENTRY_GREEN", "EXIT_RED", "EXIT_GREEN");
	private static final List<String> VALID_BLACKLIST_LEVELS = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
	private static final List<String> VALID_SENSOR_STATES = List.of("ONLINE", "DEGRADED", "OFFLINE");
	private static final List<String> VALID_SIGNAL_DIRECTIONS = List.of("ENTRY", "EXIT");
	private static final String LOG_ALARM_TYPE_NONE = "none";
	private static final List<String> VALID_COMMAND_TYPES = List.of(
			"FORCE_OPEN_GATE",
			"MANUAL_ENTRY",
			"PLATE_CORRECTION",
			"TEMP_ALLOW",
			"CORRECT_COUNT",
			"ADD_PLACEHOLDER_PLATES",
			"ADD_REAL_PLATE",
			"SET_PRIORITY");
	private static final List<String> VALID_LOG_ALARM_TYPES = List.of("blacklist", "not_whitelisted", "wrong_lane", "not_entered", LOG_ALARM_TYPE_NONE);
	private static final String ENTRY_LOG_STATUS_OUT_OF_WINDOW = "IGNORED_OUT_OF_WINDOW";
	private static final String DISPATCH_STATUS_ENTRY_OUT_OF_WINDOW = "ENTRY_OUT_OF_WINDOW";
	private static final int ENTRY_HANDOFF_TRIGGER_THRESHOLD = 2;
	private static final int LANE_REMAINING_CLEAR_THRESHOLD = 3;
	private static final int EXIT_HANDOFF_TRIGGER_THRESHOLD = 3;
	private static final int DEFAULT_PAGE_SIZE = 10;
	private static final int MAX_PAGE_SIZE = 200;

	public enum LaneExitTriggerAction {
		CURRENT_DEDUCTED,
		HANDOFF_BUFFERED,
		HANDOFF_COMPLETED,
		IGNORED_NO_ACTIVE_EXIT,
		IGNORED_NOT_CURRENT_OR_NEXT
	}

	public record LaneExitTriggerResult(
			Lane lane,
			LaneExitTriggerAction action,
			String activeExitLaneIdBefore,
			String activeExitLaneIdAfter,
			String nextHandoffLaneId,
			int previousVehicleCount,
			int currentVehicleCount,
			int deductedCount,
			int handoffCount,
			int handoffThreshold) {
	}

	private final LaneRepository laneRepository;
	private final DispatchConfigRepository dispatchConfigRepository;
	private final EntryLogRepository entryLogRepository;
	private final BlacklistRecordRepository blacklistRecordRepository;
	private final WhitelistRecordRepository whitelistRecordRepository;
	private final DispatchTicketRepository dispatchTicketRepository;
	private final BroadcastService broadcastService;
	private final ApplicationEventPublisher eventPublisher;
	private final DashboardCacheService dashboardCacheService;
	private final WhitelistCacheService whitelistCacheService;
	private final LaneDeviceGateway laneDeviceGateway;
	private final LaneRuntimeStateService laneRuntimeStateService;
	private final ScreenAcknowledgedEventRepository screenAcknowledgedEventRepository;
	private final ScreenHandledEventRepository screenHandledEventRepository;
	private final List<String> defaultEntryLaneOrder;
	private final boolean defaultEntryDispatchEnabled;
	private final boolean defaultExitDispatchEnabled;
	private final long assignmentReserveMinutes;
	private final AtomicLong lastScreenBoardDiagnosticLogAt = new AtomicLong(0L);
	private final Map<String, Set<String>> entryHandoffTriggerPlates = new ConcurrentHashMap<>();
	private final Map<String, OffsetDateTime> entryHandoffFirstObservedAt = new ConcurrentHashMap<>();
	private final Map<String, Integer> exitHandoffTriggerCounts = new ConcurrentHashMap<>();
	private final Map<String, WhitelistImportProgressState> whitelistImportProgress = new ConcurrentHashMap<>();

	public OperationsService(
			LaneRepository laneRepository,
			DispatchConfigRepository dispatchConfigRepository,
			EntryLogRepository entryLogRepository,
			BlacklistRecordRepository blacklistRecordRepository,
			WhitelistRecordRepository whitelistRecordRepository,
			DispatchTicketRepository dispatchTicketRepository,
			BroadcastService broadcastService,
			ApplicationEventPublisher eventPublisher,
			DashboardCacheService dashboardCacheService,
			WhitelistCacheService whitelistCacheService,
			LaneDeviceGateway laneDeviceGateway,
			LaneRuntimeStateService laneRuntimeStateService,
			ScreenAcknowledgedEventRepository screenAcknowledgedEventRepository,
			ScreenHandledEventRepository screenHandledEventRepository,
			@Value("${app.dispatch.entry-lane-order:}") String entryLaneOrder,
			@Value("${app.dispatch.entry-enabled-default:false}") boolean entryDispatchEnabled,
			@Value("${app.dispatch.exit-enabled-default:false}") boolean exitDispatchEnabled,
			@Value("${app.dispatch.assignment-reserve-minutes:2}") long assignmentReserveMinutes) {
		this.laneRepository = laneRepository;
		this.dispatchConfigRepository = dispatchConfigRepository;
		this.entryLogRepository = entryLogRepository;
		this.blacklistRecordRepository = blacklistRecordRepository;
		this.whitelistRecordRepository = whitelistRecordRepository;
		this.dispatchTicketRepository = dispatchTicketRepository;
		this.broadcastService = broadcastService;
		this.eventPublisher = eventPublisher;
		this.dashboardCacheService = dashboardCacheService;
		this.whitelistCacheService = whitelistCacheService;
		this.laneDeviceGateway = laneDeviceGateway;
		this.laneRuntimeStateService = laneRuntimeStateService;
		this.screenAcknowledgedEventRepository = screenAcknowledgedEventRepository;
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
		clearDispatchConfig(ACTIVE_SIGNAL_LANE_KEY);
		clearDispatchConfig(ACTIVE_SIGNAL_DIRECTION_KEY);
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

	public List<Lane> getLaneSignalTargets() {
		return laneRepository.findAllByOrderByCodeAsc();
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

	@Transactional
	public List<ScreenEventView> getScreenEvents() {
		return getScreenEvents(null, null, null);
	}

	@Transactional
	public List<ScreenEventView> getScreenEvents(String type, OffsetDateTime occurredAtFrom, OffsetDateTime occurredAtTo) {
		return getScreenEvents(type, occurredAtFrom, occurredAtTo, false);
	}

	@Transactional
	public List<ScreenEventView> getScreenEvents(String type, OffsetDateTime occurredAtFrom, OffsetDateTime occurredAtTo, boolean includeHandled) {
		return getScreenEvents(type, null, occurredAtFrom, occurredAtTo, includeHandled);
	}

	@Transactional
	public List<ScreenEventView> getScreenEvents(
			String type,
			String query,
			OffsetDateTime occurredAtFrom,
			OffsetDateTime occurredAtTo,
			boolean includeHandled) {
		OffsetDateTime referenceTime = now();
		expireStaleDispatchTickets(referenceTime);
		OffsetDateTime currentCycleStart = includeHandled ? null : currentDailyResetAt();
		List<DispatchTicket> tickets = screenEventCandidateTickets(occurredAtFrom, occurredAtTo);
		Map<String, BlacklistRecord> activeBlacklistByPlate = activeBlacklistByPlate(tickets);
		List<ScreenEventView> events = new ArrayList<>();

		for (DispatchTicket ticket : tickets) {
			if (currentCycleStart != null && ticketTime(ticket).isBefore(currentCycleStart)) {
				continue;
			}
			boolean blacklisted = activeBlacklistByPlate.containsKey(normalizePlate(ticket.getPlate()));
			if (blacklisted) {
				events.add(screenEvent(
						"blacklist",
						"BL-" + ticket.getId(),
						ticket.getPlate(),
						"黑名单车辆，请及时处理",
						ticketTime(ticket),
						ticket.getId(),
						ticket.getAssignedLaneName()));
			}

			if (!blacklisted && "NOT_WHITELISTED".equals(ticket.getStatus())) {
				events.add(screenEvent(
						"not_whitelisted",
						"NW-" + ticket.getId(),
						ticket.getPlate(),
						"非白名单车辆，总入口识别后已禁止参与车道分配",
						ticketTime(ticket),
						ticket.getId(),
						firstNonBlank(ticket.getAssignedLaneName(), "总入口")));
			}

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
			if (shouldCreateDeviceStatusEvent(lane)
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

		Map<String, Lane> lanesById = laneRepository.findAllByOrderByCodeAsc().stream()
				.collect(Collectors.toMap(Lane::getId, lane -> lane, (first, second) -> first));
		for (DispatchConfig config : dispatchConfigRepository.findAll()) {
			if (!config.getConfigKey().startsWith(EXIT_HANDOFF_MANUAL_CONFIRM_KEY_PREFIX)) {
				continue;
			}
			OffsetDateTime eventTime = config.getUpdatedAt();
			if (currentCycleStart != null && eventTime != null && eventTime.isBefore(currentCycleStart)) {
				continue;
			}
			String laneId = config.getConfigKey().substring(EXIT_HANDOFF_MANUAL_CONFIRM_KEY_PREFIX.length());
			Lane lane = lanesById.get(laneId);
			String laneName = lane == null ? laneId : lane.getName();
			int remainingCount = exitHandoffManualConfirmRemainingCount(config.getConfigValue());
			String toLaneId = exitHandoffManualConfirmToLaneId(config.getConfigValue());
			events.add(screenEvent(
					"other",
					"EH-" + laneId,
					lane == null ? "-" : firstNonBlank(lane.getCurrentPlate(), lane.getLastEntryPlate(), "-"),
					laneName + " 出口交接后仍残留 " + remainingCount + " 辆，请人工确认后清空",
					eventTime,
					laneId,
					isBlank(toLaneId) ? laneName : laneName + " -> " + toLaneId));
		}

		Map<String, OffsetDateTime> acknowledgedEventTimes = screenAcknowledgedEventRepository.findAll().stream()
				.collect(Collectors.toMap(ScreenAcknowledgedEvent::getId, ScreenAcknowledgedEvent::getAcknowledgedAt, (first, second) -> first));
		Map<String, OffsetDateTime> handledEventTimes = screenHandledEventRepository.findAll().stream()
				.collect(Collectors.toMap(ScreenHandledEvent::getId, ScreenHandledEvent::getHandledAt, (first, second) -> first));
		Set<String> handledEventIds = handledEventTimes.keySet();

		return events.stream()
				.map(event -> withEventState(event, acknowledgedEventTimes.get(event.id()), handledEventTimes.get(event.id())))
				.filter(event -> includeHandled || !handledEventIds.contains(event.id()))
				.filter(event -> screenEventMatchesPlateQuery(event, query))
				.filter(event -> isBlank(type) || type.equalsIgnoreCase(event.type()))
				.filter(event -> occurredAtFrom == null || event.occurredAt() == null || !event.occurredAt().isBefore(occurredAtFrom))
				.filter(event -> occurredAtTo == null || event.occurredAt() == null || !event.occurredAt().isAfter(occurredAtTo))
				.sorted(Comparator.comparing(ScreenEventView::occurredAt, Comparator.nullsLast(Comparator.reverseOrder())))
				.toList();
	}

	@Transactional
	public PageResult<ScreenEventView> getScreenEvents(
			String type,
			OffsetDateTime occurredAtFrom,
			OffsetDateTime occurredAtTo,
			boolean includeHandled,
			int page,
			int pageSize) {
		return getScreenEvents(type, occurredAtFrom, occurredAtTo, includeHandled, null, page, pageSize);
	}

	@Transactional
	public PageResult<ScreenEventView> getScreenEvents(
			String type,
			OffsetDateTime occurredAtFrom,
			OffsetDateTime occurredAtTo,
			boolean includeHandled,
			Boolean handled,
			int page,
			int pageSize) {
		return getScreenEvents(type, null, occurredAtFrom, occurredAtTo, includeHandled, handled, page, pageSize);
	}

	@Transactional
	public PageResult<ScreenEventView> getScreenEvents(
			String type,
			String query,
			OffsetDateTime occurredAtFrom,
			OffsetDateTime occurredAtTo,
			boolean includeHandled,
			Boolean handled,
			int page,
			int pageSize) {
		List<ScreenEventView> events = getScreenEvents(type, query, occurredAtFrom, occurredAtTo, includeHandled).stream()
				.filter(event -> handled == null || event.handled() == handled.booleanValue())
				.toList();
		return pageFromList(events, page, pageSize);
	}

	@Transactional
	public List<ScreenEventView> exportScreenEvents(
			String type,
			OffsetDateTime occurredAtFrom,
			OffsetDateTime occurredAtTo,
			Boolean handled) {
		return exportScreenEvents(type, null, occurredAtFrom, occurredAtTo, handled);
	}

	@Transactional
	public List<ScreenEventView> exportScreenEvents(
			String type,
			String query,
			OffsetDateTime occurredAtFrom,
			OffsetDateTime occurredAtTo,
			Boolean handled) {
		return getScreenEvents(type, query, occurredAtFrom, occurredAtTo, true).stream()
				.filter(event -> handled == null || event.handled() == handled.booleanValue())
				.toList();
	}

	private boolean screenEventMatchesPlateQuery(ScreenEventView event, String query) {
		String normalizedQuery = normalizePlate(query);
		if (isBlank(normalizedQuery)) {
			return true;
		}
		String normalizedPlate = normalizePlate(event.plate());
		return !isBlank(normalizedPlate) && normalizedPlate.contains(normalizedQuery);
	}

	private List<DispatchTicket> screenEventCandidateTickets(OffsetDateTime occurredAtFrom, OffsetDateTime occurredAtTo) {
		OffsetDateTime currentCycleStart = currentDailyResetAt();
		OffsetDateTime from = occurredAtFrom == null && occurredAtTo == null ? currentCycleStart : occurredAtFrom;
		OffsetDateTime to = occurredAtTo;
		if (from != null && to != null) {
			return dispatchTicketRepository.findByYardEntryTimeBetweenOrderByYardEntryTimeDesc(from.minusDays(1), to.plusDays(1));
		}
		if (from != null) {
			return dispatchTicketRepository.findByYardEntryTimeGreaterThanEqualOrderByYardEntryTimeDesc(from.minusDays(1));
		}
		if (to != null) {
			return dispatchTicketRepository.findByYardEntryTimeLessThanEqualOrderByYardEntryTimeDesc(to.plusDays(1));
		}
		return dispatchTicketRepository.findAllByOrderByYardEntryTimeDesc();
	}

	private Map<String, BlacklistRecord> activeBlacklistByPlate(List<DispatchTicket> tickets) {
		Set<String> plates = tickets.stream()
				.map(DispatchTicket::getPlate)
				.filter(plate -> !isBlank(plate))
				.map(this::normalizePlate)
				.collect(Collectors.toSet());
		return activeBlacklistByPlate(plates);
	}

	private Map<String, BlacklistRecord> activeBlacklistByPlate(Collection<String> plates) {
		Set<String> normalizedPlates = plates.stream()
				.filter(plate -> !isBlank(plate))
				.map(this::normalizePlate)
				.collect(Collectors.toSet());
		if (normalizedPlates.isEmpty()) {
			return Map.of();
		}
		return blacklistRecordRepository.findByActiveTrueAndPlateIn(normalizedPlates).stream()
				.collect(Collectors.toMap(
						record -> normalizePlate(record.getPlate()),
						record -> record,
						(first, ignored) -> first));
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

	@Transactional
	public List<ScreenEventView> getScreenBoardEvents(int perTypeLimit) {
		int limit = Math.max(1, perTypeLimit);
		OffsetDateTime currentCycleStart = currentDailyResetAt();
		Map<String, Integer> typeCounts = new HashMap<>();
		return getScreenEvents(null, null, null, false).stream()
				.filter(event -> currentCycleStart == null || event.occurredAt() == null || !event.occurredAt().isBefore(currentCycleStart))
				.filter(ScreenEventView::acknowledged)
				.filter(event -> typeCounts.merge(event.type(), 1, Integer::sum) <= limit)
				.toList();
	}

	@Transactional
	public List<ScreenEventView> getPendingScreenBoardEvents(int perTypeLimit) {
		int limit = Math.max(1, perTypeLimit);
		OffsetDateTime currentCycleStart = currentDailyResetAt();
		Map<String, Integer> typeCounts = new HashMap<>();
		return getScreenEvents(null, null, null, false).stream()
				.filter(event -> currentCycleStart == null || event.occurredAt() == null || !event.occurredAt().isBefore(currentCycleStart))
				.filter(event -> !event.acknowledged())
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
		handleScreenEvents(eventId == null ? List.of() : List.of(eventId));
	}

	@Transactional
	public void handleScreenEvents(List<String> eventIds) {
		if (eventIds == null || eventIds.isEmpty()) {
			return;
		}
		OffsetDateTime referenceTime = now();
		boolean changed = false;
		for (String eventId : normalizedEventIds(eventIds)) {
			if (!screenHandledEventRepository.existsById(eventId)) {
				screenHandledEventRepository.save(ScreenHandledEvent.builder()
						.id(eventId)
						.handledAt(referenceTime)
						.operator("大屏")
						.build());
				changed = true;
			}
		}
		if (changed) {
			invalidateRuntimeViews("screen_events_handled");
		}
	}

	@Transactional
	public void handleUnhandledScreenEvents(String type, OffsetDateTime occurredAtFrom, OffsetDateTime occurredAtTo) {
		handleUnhandledScreenEvents(type, null, occurredAtFrom, occurredAtTo);
	}

	@Transactional
	public void handleUnhandledScreenEvents(String type, String query, OffsetDateTime occurredAtFrom, OffsetDateTime occurredAtTo) {
		List<String> eventIds = getScreenEvents(type, query, occurredAtFrom, occurredAtTo, true).stream()
				.filter(event -> !event.handled())
				.map(ScreenEventView::id)
				.toList();
		handleScreenEvents(eventIds);
	}

	@Transactional
	public void acknowledgeScreenEvent(String eventId) {
		acknowledgeScreenEvents(eventId == null ? List.of() : List.of(eventId));
	}

	@Transactional
	public void acknowledgeScreenEvents(List<String> eventIds) {
		if (eventIds == null || eventIds.isEmpty()) {
			return;
		}
		OffsetDateTime referenceTime = now();
		boolean changed = false;
		for (String eventId : normalizedEventIds(eventIds)) {
			if (!screenAcknowledgedEventRepository.existsById(eventId)) {
				screenAcknowledgedEventRepository.save(ScreenAcknowledgedEvent.builder()
						.id(eventId)
						.acknowledgedAt(referenceTime)
						.operator("大屏")
						.build());
				changed = true;
			}
		}
		if (changed) {
			invalidateRuntimeViews("screen_events_acknowledged");
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

	public OffsetDateTime getLastDailyResetAt() {
		String value = currentStringConfig(LAST_DAILY_RESET_AT_KEY, null);
		if (isBlank(value)) {
			return null;
		}
		try {
			OffsetDateTime resetAt = OffsetDateTime.parse(value.trim());
			return resetAt.isAfter(now().plusMinutes(1)) ? null : resetAt;
			} catch (DateTimeParseException ignored) {
			return null;
		}
	}

	public boolean dailyResetCompletedOn(LocalDate date, ZoneId zoneId) {
		OffsetDateTime resetAt = getLastDailyResetAt();
		return resetAt != null && resetAt.atZoneSameInstant(zoneId).toLocalDate().equals(date);
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
		saveActiveEntrySignalConfig(firstOrderedLaneId(lanes, laneOrder), referenceTime);
		saveActiveExitSignalConfig(null, referenceTime);
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
		if (request.entryDispatchEnabled()) {
			if (isBlank(currentStringConfig(ACTIVE_ENTRY_LANE_KEY, null))) {
				saveActiveEntrySignalConfig(firstOrderedLaneId(lanes, laneOrder), referenceTime);
			}
		} else {
			saveActiveEntrySignalConfig(null, referenceTime);
		}
		if (request.exitDispatchEnabled()) {
			if (isBlank(currentStringConfig(ACTIVE_EXIT_LANE_KEY, null))) {
				saveActiveExitSignalConfig(nextEligibleExitLaneId(sortLanesByOrder(lanes, laneOrder), null, null), referenceTime);
			}
		} else {
			saveActiveExitSignalConfig(null, referenceTime);
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
		entryHandoffTriggerPlates.clear();
		exitHandoffTriggerCounts.clear();
		clearExitHandoffManualConfirms();

		saveDispatchConfig(ENTRY_DISPATCH_ENABLED_KEY, Boolean.TRUE.toString(), referenceTime);
		saveDispatchConfig(EXIT_DISPATCH_ENABLED_KEY, Boolean.TRUE.toString(), referenceTime);
		String resetEntryLaneId = firstOrderedLaneId(lanes, laneOrder);
		saveActiveEntrySignalConfig(resetEntryLaneId, referenceTime);
		saveActiveExitSignalConfig(resetEntryLaneId, referenceTime);
		saveDispatchConfig(LAST_DAILY_RESET_AT_KEY, referenceTime.toString(), referenceTime);
		flowLog.info(
				"节点=日清重置完成 event=DAILY_RESET_COMPLETED resetEntryLane={} laneOrder={} lanesCleared={} at={}",
				nullToEmpty(resetEntryLaneId),
				laneOrderDescription(sortLanesByOrder(lanes, laneOrder), laneOrder),
				lanes.size(),
				referenceTime);

		refreshLaneRuntime(referenceTime);
		invalidateRuntimeViews("daily_reset");
		return getDispatchConfig();
	}

	public PageResult<EntryLogView> getLogs(
			String query,
			String status,
			String laneId,
			OffsetDateTime entryTimeFrom,
			OffsetDateTime entryTimeTo,
			String alarmType,
			int page,
			int pageSize) {
		int normalizedPage = normalizePage(page);
		int normalizedPageSize = normalizePageSize(pageSize);
		String normalizedAlarmType = normalizeAlarmTypeFilter(alarmType);
		List<EntryLogView> allItems = queryEntryLogViews(
				blankToNull(query),
				blankToNull(status),
				blankToNull(laneId),
				entryTimeFrom,
				entryTimeTo);
		List<EntryLogView> filteredItems = filterEntriesByAlarmType(allItems, normalizedAlarmType);
		return pageFromList(filteredItems, normalizedPage, normalizedPageSize);
	}

	@Transactional
	public List<EntryLogView> exportLogs(
			String query,
			String status,
			String laneId,
			OffsetDateTime entryTimeFrom,
			OffsetDateTime entryTimeTo,
			String alarmType) {
		String normalizedAlarmType = normalizeAlarmTypeFilter(alarmType);
		List<EntryLogView> allItems = queryEntryLogViews(
				blankToNull(query),
				blankToNull(status),
				blankToNull(laneId),
				entryTimeFrom,
				entryTimeTo);
		return filterEntriesByAlarmType(allItems, normalizedAlarmType);
	}

	public List<LaneActivePlateView> getActiveLanePlates(String laneId) {
		requireLane(laneId);
		return entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc(laneId).stream()
				.map(LaneActivePlateView::from)
				.toList();
	}

	@Transactional
	public void correctActiveLanePlate(String laneId, String entryLogId, String plate) {
		OffsetDateTime referenceTime = now();
		Lane lane = requireLane(laneId);
		correctActiveLanePlate(lane, entryLogId, plate, referenceTime);
		refreshLaneRuntime(referenceTime);
		invalidateRuntimeViews("manual_plate_corrected");
	}

	private void correctActiveLanePlate(
			Lane lane,
			String entryLogId,
			String plate,
			OffsetDateTime referenceTime) {
		EntryLog entryLog = entryLogRepository.findById(entryLogId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "车辆流水不存在"));
		if (entryLog.getExitTime() != null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能修改仍在场的车辆车牌");
		}
		if (!lane.getId().equals(entryLog.getLaneId())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该在场车牌不属于指定车道");
		}

		String previousPlate = normalizePlate(entryLog.getPlate());
		String correctedPlate = normalizePlate(plate);
		if (isBlank(correctedPlate)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "车牌号码不能为空");
		}
		if (correctedPlate.equals(previousPlate)) {
			return;
		}

		List<DispatchTicket> previousPlateTickets = dispatchTicketRepository
				.findByPlateIgnoreCaseAndClosedAtIsNullOrderByYardEntryTimeDesc(previousPlate);
		DispatchTicket matchedTicket = findDispatchTicketForLog(entryLog, previousPlateTickets);
		List<EntryLog> conflictingLogs = entryLogRepository
				.findByPlateIgnoreCaseAndExitTimeIsNullOrderByEntryTimeAsc(correctedPlate).stream()
				.filter(log -> !Objects.equals(log.getId(), entryLog.getId()))
				.toList();
		List<DispatchTicket> conflictingTickets = dispatchTicketRepository
				.findByPlateIgnoreCaseAndClosedAtIsNullOrderByYardEntryTimeDesc(correctedPlate).stream()
				.filter(ticket -> ticket.getExitTime() == null)
				.filter(ticket -> !"NOT_WHITELISTED".equals(ticket.getStatus()))
				.filter(ticket -> matchedTicket == null || !Objects.equals(ticket.getId(), matchedTicket.getId()))
				.toList();
		if (!conflictingLogs.isEmpty() || !conflictingTickets.isEmpty()) {
			closeExistingPlateRecordsForManualPlateChange(
					correctedPlate,
					conflictingLogs,
					conflictingTickets,
					referenceTime);
		}

		entryLog.setPlate(correctedPlate);
		entryLogRepository.save(entryLog);
		if (matchedTicket != null) {
			matchedTicket.setPlate(correctedPlate);
			String correctionNote = "人工更正车牌：" + previousPlate + "→" + correctedPlate;
			if (isBlank(matchedTicket.getNotes())) {
				matchedTicket.setNotes(correctionNote);
			} else if (!matchedTicket.getNotes().contains(correctionNote)) {
				matchedTicket.setNotes(matchedTicket.getNotes() + "；" + correctionNote);
			}
			dispatchTicketRepository.save(matchedTicket);
		}

		if (previousPlate.equals(normalizePlate(lane.getCurrentPlate()))) {
			lane.setCurrentPlate(correctedPlate);
		}
		if (previousPlate.equals(normalizePlate(lane.getLastEntryPlate()))) {
			lane.setLastEntryPlate(correctedPlate);
		}
		lane.setLastActionAt(referenceTime);

		flowLog.warn(
				"节点=人工修改在场车牌 event=MANUAL_ACTIVE_PLATE_CORRECTED entryLogId={} laneId={} laneName={} previousPlate={} correctedPlate={} ticketId={} closedConflictLogs={} closedConflictTickets={} observedAt={}",
				entryLog.getId(),
				lane.getId(),
				lane.getName(),
				previousPlate,
				correctedPlate,
				matchedTicket == null ? "" : matchedTicket.getId(),
				conflictingLogs.size(),
				conflictingTickets.size(),
				referenceTime);
	}

	@PreAuthorize("hasRole('ADMIN')")
	public PageResult<BlacklistRecord> getBlacklist(String query, int page, int pageSize) {
		int normalizedPage = normalizePage(page);
		int normalizedPageSize = normalizePageSize(pageSize);
		Page<BlacklistRecord> records = blacklistRecordRepository.search(
				blankToNull(query),
				PageRequest.of(normalizedPage - 1, normalizedPageSize));
		return PageResult.of(records.getContent(), records.getTotalElements(), normalizedPage, normalizedPageSize);
	}

	private List<EntryLogView> queryEntryLogViews(
			String query,
			String status,
			String laneId,
			OffsetDateTime entryTimeFrom,
			OffsetDateTime entryTimeTo) {
		Page<EntryLog> logPage = entryLogRepository.searchLogs(
				query,
				status,
				laneId,
				entryTimeFrom,
				entryTimeTo,
				Pageable.unpaged());
		List<EntryLog> logs = logPage.getContent();
		List<DispatchTicket> ticketOnlyCandidates = queryTicketOnlyLogCandidates(query, status, laneId, entryTimeFrom, entryTimeTo);
		Set<String> plates = Stream.concat(
						logs.stream().map(EntryLog::getPlate),
						ticketOnlyCandidates.stream().map(DispatchTicket::getPlate))
				.filter(plate -> !isBlank(plate))
				.map(this::normalizePlate)
				.collect(Collectors.toSet());
		List<DispatchTicket> tickets = plates.isEmpty()
				? List.of()
				: dispatchTicketRepository.findByPlateInOrderByYardEntryTimeDesc(plates);
		Map<String, List<DispatchTicket>> ticketsByPlate = tickets.stream()
				.filter(ticket -> !isBlank(ticket.getPlate()))
				.collect(Collectors.groupingBy(ticket -> normalizePlate(ticket.getPlate())));
		Map<String, BlacklistRecord> blacklistedPlates = activeBlacklistByPlate(plates);
		Set<String> matchedTicketIds = new LinkedHashSet<>();
		List<LogViewCandidate> candidates = new ArrayList<>();
		for (EntryLog log : logs) {
			String normalizedPlate = normalizePlate(log.getPlate());
			DispatchTicket ticket = findDispatchTicketForLog(log, ticketsByPlate.getOrDefault(normalizedPlate, List.of()));
			if (ticket != null) {
				matchedTicketIds.add(ticket.getId());
			}
			String alarmType = resolveLogAlarmType(log, ticket, blacklistedPlates);
			EntryLogView view = EntryLogView.from(log, ticket, alarmType);
			candidates.add(new LogViewCandidate(view, view.entryTime(), ticket == null ? null : ticket.getId()));
		}
		for (DispatchTicket ticket : ticketOnlyCandidates) {
			if (matchedTicketIds.contains(ticket.getId())) {
				continue;
			}
			String alarmType = resolveTicketAlarmType(ticket, blacklistedPlates);
			if (isBlank(alarmType)) {
				continue;
			}
			EntryLogView view = EntryLogView.from(ticket, alarmType);
			candidates.add(new LogViewCandidate(view, view.entryTime(), ticket.getId()));
		}
		return candidates.stream()
				.sorted(Comparator
						.comparing(LogViewCandidate::sortTime, Comparator.nullsLast(Comparator.reverseOrder()))
						.thenComparing(candidate -> candidate.view().id(), Comparator.nullsLast(Comparator.reverseOrder())))
				.map(LogViewCandidate::view)
				.toList();
	}

	private List<DispatchTicket> queryTicketOnlyLogCandidates(
			String query,
			String status,
			String laneId,
			OffsetDateTime entryTimeFrom,
			OffsetDateTime entryTimeTo) {
		return dispatchTicketsByYardEntryTime(entryTimeFrom, entryTimeTo).stream()
				.filter(ticket -> ticketMatchesLogQuery(ticket, query))
				.filter(ticket -> ticketMatchesLogStatus(ticket, status))
				.filter(ticket -> ticketMatchesLogLane(ticket, laneId))
				.toList();
	}

	private List<DispatchTicket> dispatchTicketsByYardEntryTime(OffsetDateTime entryTimeFrom, OffsetDateTime entryTimeTo) {
		if (entryTimeFrom != null && entryTimeTo != null) {
			return dispatchTicketRepository.findByYardEntryTimeBetweenOrderByYardEntryTimeDesc(entryTimeFrom, entryTimeTo);
		}
		if (entryTimeFrom != null) {
			return dispatchTicketRepository.findByYardEntryTimeGreaterThanEqualOrderByYardEntryTimeDesc(entryTimeFrom);
		}
		if (entryTimeTo != null) {
			return dispatchTicketRepository.findByYardEntryTimeLessThanEqualOrderByYardEntryTimeDesc(entryTimeTo);
		}
		return dispatchTicketRepository.findAllByOrderByYardEntryTimeDesc();
	}

	private boolean ticketMatchesLogQuery(DispatchTicket ticket, String query) {
		return isBlank(query)
				|| (!isBlank(ticket.getPlate()) && normalizePlate(ticket.getPlate()).contains(normalizePlate(query)));
	}

	private boolean ticketMatchesLogStatus(DispatchTicket ticket, String status) {
		return isBlank(status) || status.equalsIgnoreCase(ticket.getStatus());
	}

	private boolean ticketMatchesLogLane(DispatchTicket ticket, String laneId) {
		if (isBlank(laneId)) {
			return true;
		}
		return laneId.equalsIgnoreCase(ticket.getActualLaneId())
				|| laneId.equalsIgnoreCase(ticket.getAssignedLaneId());
	}

	private List<EntryLogView> filterEntriesByAlarmType(List<EntryLogView> items, String alarmType) {
		if (isBlank(alarmType)) {
			return items;
		}
		if (LOG_ALARM_TYPE_NONE.equals(alarmType)) {
			return items.stream()
					.filter(item -> isBlank(item.alarmType()))
					.toList();
		}
		return items.stream()
				.filter(item -> alarmType.equals(item.alarmType()))
				.toList();
	}

	private String normalizeAlarmTypeFilter(String alarmType) {
		String normalized = blankToNull(alarmType);
		if (normalized == null) {
			return null;
		}
		String normalizedLower = normalized.toLowerCase(Locale.ROOT);
		if (!VALID_LOG_ALARM_TYPES.contains(normalizedLower)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "告警类型筛选参数非法");
		}
		return normalizedLower;
	}

	private String resolveLogAlarmType(
			EntryLog log,
			DispatchTicket ticket,
			Map<String, BlacklistRecord> blacklistedPlates) {
		if (log == null || isBlank(log.getPlate())) {
			return null;
		}
		String normalizedPlate = normalizePlate(log.getPlate());
		if (blacklistedPlates.containsKey(normalizedPlate)) {
			return "blacklist";
		}
		if (ticket == null) {
			return null;
		}
		if ("NOT_WHITELISTED".equals(ticket.getStatus())) {
			return "not_whitelisted";
		}
		if ("ENTERED_MISMATCH".equals(ticket.getStatus())) {
			return "wrong_lane";
		}
		if ("EXPIRED".equals(ticket.getStatus()) || "NO_LANE_AVAILABLE".equals(ticket.getStatus())) {
			return "not_entered";
		}
		return null;
	}

	private String resolveTicketAlarmType(
			DispatchTicket ticket,
			Map<String, BlacklistRecord> blacklistedPlates) {
		if (ticket == null || isBlank(ticket.getPlate())) {
			return null;
		}
		String normalizedPlate = normalizePlate(ticket.getPlate());
		if (blacklistedPlates.containsKey(normalizedPlate)) {
			return "blacklist";
		}
		if ("NOT_WHITELISTED".equals(ticket.getStatus())) {
			return "not_whitelisted";
		}
		if ("ENTERED_MISMATCH".equals(ticket.getStatus())) {
			return "wrong_lane";
		}
		if ("EXPIRED".equals(ticket.getStatus()) || "NO_LANE_AVAILABLE".equals(ticket.getStatus())) {
			return "not_entered";
		}
		return null;
	}

	public PageResult<WhitelistRecord> getWhitelist(String query, int page, int pageSize) {
		int normalizedPage = normalizePage(page);
		int normalizedPageSize = normalizePageSize(pageSize);
		Page<WhitelistRecord> records = whitelistRecordRepository.search(
				blankToNull(query),
				PageRequest.of(normalizedPage - 1, normalizedPageSize));
		return PageResult.of(records.getContent(), records.getTotalElements(), normalizedPage, normalizedPageSize);
	}

	@Transactional
	public WhitelistRecord createWhitelist(WhitelistPayload payload, String operator) {
		validateWhitelistPayload(payload);
		String normalizedPlate = normalizePlate(payload.plate());
		if (whitelistRecordRepository.existsByPlate(normalizedPlate)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "车牌已在白名单中");
		}
		OffsetDateTime referenceTime = now();
		String normalizedOperator = firstNonBlank(operator, "系统管理员");
		WhitelistRecord record = WhitelistRecord.builder()
				.id("WT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT))
				.plate(normalizedPlate)
				.createdAt(referenceTime)
				.updatedAt(referenceTime)
				.createdBy(normalizedOperator)
				.updatedBy(normalizedOperator)
				.build();
		WhitelistRecord saved = whitelistRecordRepository.save(record);
		refreshWhitelistCacheAndInvalidate("whitelist_created");
		return saved;
	}

	@Transactional
	public WhitelistRecord updateWhitelist(String id, WhitelistPayload payload, String operator) {
		validateWhitelistPayload(payload);
		WhitelistRecord record = whitelistRecordRepository.findById(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "白名单记录不存在"));
		String normalizedPlate = normalizePlate(payload.plate());
		if (!record.getPlate().equals(normalizedPlate) && whitelistRecordRepository.existsByPlate(normalizedPlate)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "车牌已在其他白名单记录中");
		}
		record.setPlate(normalizedPlate);
		record.setUpdatedAt(now());
		record.setUpdatedBy(firstNonBlank(operator, "系统管理员"));
		WhitelistRecord saved = whitelistRecordRepository.save(record);
		refreshWhitelistCacheAndInvalidate("whitelist_updated");
		return saved;
	}

	@Transactional
	public void deleteWhitelist(String id) {
		if (!whitelistRecordRepository.existsById(id)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "白名单记录不存在");
		}
		whitelistRecordRepository.deleteById(id);
		refreshWhitelistCacheAndInvalidate("whitelist_deleted");
	}

	public WhitelistSettingsView getWhitelistSettings() {
		return new WhitelistSettingsView(currentBooleanConfig(WHITELIST_FILTER_ENABLED_KEY, false));
	}

	@Transactional
	public WhitelistSettingsView updateWhitelistSettings(WhitelistSettingsRequest request) {
		OffsetDateTime referenceTime = now();
		saveDispatchConfig(WHITELIST_FILTER_ENABLED_KEY, Boolean.toString(request.filterEnabled()), referenceTime);
		invalidateRuntimeViews("whitelist_settings_updated");
		return getWhitelistSettings();
	}

	public WhitelistImportProgress getWhitelistImportProgress(String jobId) {
		if (isBlank(jobId)) {
			return new WhitelistImportProgress("", "WAITING", 0, 0, 0, 0, 0, 0, 0, 0, "等待导入任务开始", null, null);
		}
		WhitelistImportProgressState progress = whitelistImportProgress.get(jobId.trim());
		if (progress == null) {
			return new WhitelistImportProgress(jobId.trim(), "WAITING", 0, 0, 0, 0, 0, 0, 0, 0, "等待服务端接收文件", null, null);
		}
		return progress.snapshot();
	}

	@Transactional
	public WhitelistImportResult importWhitelist(MultipartFile file, String operator) {
		return importWhitelist(file, operator, null);
	}

	@Transactional
	public WhitelistImportResult importWhitelist(MultipartFile file, String operator, String jobId) {
		WhitelistImportProgressState progress = beginWhitelistImportProgress(jobId);
		try {
			ParsedWhitelistImport parsed = parseWhitelistImport(file, progress);
			if (parsed.plates().isEmpty()) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导入文件中没有有效车牌号");
			}

			String normalizedOperator = firstNonBlank(operator, "系统管理员");
			OffsetDateTime referenceTime = now();
			int createdCount = 0;
			int updatedCount = 0;
			List<List<String>> batches = partition(parsed.plates(), 1000);
			int totalBatches = Math.max(1, batches.size());
			for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
				List<String> batch = batches.get(batchIndex);
				Map<String, WhitelistRecord> existingByPlate = whitelistRecordRepository.findByPlateIn(batch).stream()
						.collect(Collectors.toMap(
								record -> normalizePlate(record.getPlate()),
								record -> record,
								(first, ignored) -> first));
				List<WhitelistRecord> records = new ArrayList<>(batch.size());
				for (String plate : batch) {
					WhitelistRecord record = existingByPlate.get(plate);
					if (record == null) {
						createdCount++;
						record = WhitelistRecord.builder()
								.id("WT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT))
								.plate(plate)
								.createdAt(referenceTime)
								.createdBy(normalizedOperator)
								.build();
					} else {
						updatedCount++;
					}
					record.setUpdatedAt(referenceTime);
					record.setUpdatedBy(normalizedOperator);
					records.add(record);
				}
				whitelistRecordRepository.saveAll(records);
				int percent = 40 + (int) Math.round(((batchIndex + 1) * 55.0) / totalBatches);
				progress.update(
						"WRITING",
						Math.min(95, percent),
						parsed.totalRows(),
						parsed.validRows(),
						parsed.plates().size(),
						createdCount,
						updatedCount,
						parsed.duplicateRows(),
						parsed.invalidRows(),
						"正在写入数据库：" + (batchIndex + 1) + "/" + totalBatches + " 批");
			}
			progress.update(
					"CACHING",
					98,
					parsed.totalRows(),
					parsed.validRows(),
					parsed.plates().size(),
					createdCount,
					updatedCount,
					parsed.duplicateRows(),
					parsed.invalidRows(),
					"正在刷新白名单缓存");
			refreshWhitelistCacheAndInvalidate("whitelist_imported");
			WhitelistImportResult result = new WhitelistImportResult(
					parsed.totalRows(),
					parsed.validRows(),
					parsed.plates().size(),
					createdCount,
					updatedCount,
					parsed.duplicateRows(),
					parsed.invalidRows());
			progress.complete(result);
			return result;
		}
		catch (ResponseStatusException | DataAccessException | IllegalArgumentException exception) {
			progress.fail(importFailureMessage(exception));
			throw exception;
		}
	}

	@Transactional
	@PreAuthorize("hasRole('ADMIN')")
	public BlacklistRecord createBlacklist(BlacklistPayload payload, String operator) {
		validateBlacklistPayload(payload);
		BlacklistRecord record = BlacklistRecord.builder()
				.id("BL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT))
				.plate(normalizePlate(payload.plate()))
				.reason(payload.reason())
				.level(payload.level())
				.effectiveDate(now())
				.operator(operator)
				.active(payload.active())
				.build();
		BlacklistRecord saved = blacklistRecordRepository.save(record);
		invalidateRuntimeViews("blacklist_created");
		return saved;
	}

	@Transactional
	@PreAuthorize("hasRole('ADMIN')")
	public BlacklistRecord updateBlacklist(String id, BlacklistPayload payload, String operator) {
		validateBlacklistPayload(payload);
		BlacklistRecord record = blacklistRecordRepository.findById(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "黑名单记录不存在"));
		record.setPlate(normalizePlate(payload.plate()));
		record.setReason(payload.reason());
		record.setLevel(payload.level());
		record.setOperator(operator);
		record.setActive(payload.active());
		record.setEffectiveDate(now());
		BlacklistRecord saved = blacklistRecordRepository.save(record);
		invalidateRuntimeViews("blacklist_updated");
		return saved;
	}

	@Transactional
	@PreAuthorize("hasRole('ADMIN')")
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
		Lane lane = requireLane(laneId);
		Set<String> disabledLaneIds = currentLaneDispatchDisabledIds();
		if (disabledLaneIds.contains(laneId)
				&& ("GREEN".equals(request.entrySignal()) || "GREEN".equals(request.exitSignal()))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该车道已关闭参与分配，不能手动打开该车道红绿灯");
		}
		OffsetDateTime referenceTime = now();
		lane.setMode("AUTO");
		lane.setLastActionAt(referenceTime);
		laneRuntimeStateService.clearManualTarget(lane.getId());
		if ("GREEN".equals(request.entrySignal())) {
			clearTailStayProtectionIfLaneHasCapacity(lane, referenceTime, "manual_entry_signal_override");
		}
		boolean allRedRequest = "RED".equals(request.entrySignal()) && "RED".equals(request.exitSignal());
		String currentActiveEntryLaneId = currentStringConfig(ACTIVE_ENTRY_LANE_KEY, null);
		String currentActiveExitLaneId = currentStringConfig(ACTIVE_EXIT_LANE_KEY, null);
		if ("GREEN".equals(request.entrySignal())) {
			saveActiveEntrySignalConfig(lane.getId(), referenceTime);
		} else if ("RED".equals(request.entrySignal())
				&& (lane.getId().equals(currentActiveEntryLaneId) || (allRedRequest && isBlank(currentActiveEntryLaneId)))) {
			List<Lane> lanes = laneRepository.findAllByOrderByCodeAsc();
			List<String> laneOrder = currentLaneOrder(ENTRY_LANE_ORDER_KEY, defaultEntryLaneOrder);
			advanceEntrySignal(
					referenceTime,
					lane.getId(),
					lanes,
					laneOrder,
					pendingAssignmentCounts(referenceTime),
					disabledLaneIds);
		}
		if ("GREEN".equals(request.exitSignal())) {
			saveActiveExitSignalConfig(lane.getId(), referenceTime);
		} else if ("RED".equals(request.exitSignal())
				&& (lane.getId().equals(currentActiveExitLaneId) || (allRedRequest && isBlank(currentActiveExitLaneId)))) {
			advanceExitSignalAfterCleared(referenceTime, lane.getId(), false, "manual_exit_signal_override");
		}
		lane.setStatus(resolveStatusForLane(lane, pendingAssignmentCounts(referenceTime).getOrDefault(lane.getId(), 0L)));
		return persistLaneRuntime(lane.getId(), referenceTime, "signal_override");
	}

	public void controlLaneRelay(String laneId, RelayControlRequest request) {
		validateRelayControlTarget(request.target());
		Lane lane = requireLane(laneId);
		String normalizedTarget = request.target().trim().toUpperCase(Locale.ROOT).replace('-', '_');
		if (Boolean.TRUE.equals(request.on()) && ("ENTRY_GREEN".equals(normalizedTarget) || "EXIT_GREEN".equals(normalizedTarget))) {
			Set<String> disabledLaneIds = currentLaneDispatchDisabledIds();
			if (disabledLaneIds.contains(laneId)) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该车道已关闭参与分配，不能手动打开该车道红绿灯");
			}
			OffsetDateTime referenceTime = now();
			if ("ENTRY_GREEN".equals(normalizedTarget)) {
				saveActiveEntrySignalConfig(lane.getId(), referenceTime);
			} else {
				saveActiveExitSignalConfig(lane.getId(), referenceTime);
			}
			persistLaneRuntime(laneId, referenceTime, "relay_green_redirected_to_active_signal");
			return;
		}
		laneDeviceGateway.controlRelay(lane, normalizedTarget, Boolean.TRUE.equals(request.on()), request.reason());
	}

	@Transactional
	public void restoreAutoControl() {
		OffsetDateTime referenceTime = now();
		laneDeviceGateway.clearSyncState();
		entryHandoffTriggerPlates.clear();
		exitHandoffTriggerCounts.clear();
		saveDispatchConfig(ENTRY_DISPATCH_ENABLED_KEY, Boolean.TRUE.toString(), referenceTime);
		saveDispatchConfig(EXIT_DISPATCH_ENABLED_KEY, Boolean.TRUE.toString(), referenceTime);
		List<Lane> lanes = laneRepository.findAllByOrderByCodeAsc();
		List<String> laneOrder = currentLaneOrder(ENTRY_LANE_ORDER_KEY, defaultEntryLaneOrder);
		for (Lane lane : lanes) {
			lane.setMode("AUTO");
			lane.setLastActionAt(referenceTime);
			laneRuntimeStateService.clearManualTarget(lane.getId());
		}
		saveActiveEntrySignalConfig(firstOrderedLaneId(lanes, laneOrder), referenceTime);
		saveActiveExitSignalConfig(nextEligibleExitLaneId(sortLanesByOrder(lanes, laneOrder), null, null), referenceTime);
		refreshLaneRuntime(referenceTime);
		invalidateRuntimeViews("restore_auto");
	}

	@Transactional
	public void globalLockdown() {
		OffsetDateTime referenceTime = now();
		entryHandoffTriggerPlates.clear();
		exitHandoffTriggerCounts.clear();
		saveDispatchConfig(ENTRY_DISPATCH_ENABLED_KEY, Boolean.FALSE.toString(), referenceTime);
		saveDispatchConfig(EXIT_DISPATCH_ENABLED_KEY, Boolean.FALSE.toString(), referenceTime);
		clearActiveSignalConfig(referenceTime);
		for (Lane lane : laneRepository.findAllByOrderByCodeAsc()) {
			lane.setMode("AUTO");
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
				lane.setMode("AUTO");
				laneRuntimeStateService.clearManualTarget(lane.getId());
				saveActiveEntrySignalConfig(lane.getId(), referenceTime);
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
				if (isBlank(normalizedPlate)) {
					throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "车牌号码不能为空");
				}
				EntryLog currentEntryLog = findActiveEntryLogInLane(lane.getId(), normalizePlate(lane.getCurrentPlate()));
				if (currentEntryLog == null) {
					throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前车道没有可修改的在场车牌流水");
				}
				correctActiveLanePlate(
						lane,
						currentEntryLog.getId(),
						normalizedPlate,
						referenceTime);
			}
			case "CORRECT_COUNT" -> {
				int correctedCount = request.correctedVehicleCount() == null ? lane.getVehicleCount() : Math.max(0, request.correctedVehicleCount());
				lane.setVehicleCount(Math.min(lane.getCapacity(), correctedCount));
				updateQueueHeadAtForObservedCountChange(lane, previousVehicleCount, lane.getVehicleCount(), referenceTime);
				reconcileLaneQueue(lane, lane.getVehicleCount(), referenceTime);
				advanceExitSignalIfCurrentCleared(lane, referenceTime);
			}
			case "ADD_PLACEHOLDER_PLATES" -> addPlaceholderPlateCount(lane, request.placeholderCount(), previousVehicleCount, referenceTime);
			case "ADD_REAL_PLATE" -> addManualCorrectedPlate(
					lane,
					request.plate(),
					vehicleType,
					previousVehicleCount,
					referenceTime);
			case "SET_PRIORITY" -> lane.setPriority(request.markPriority() == null ? !lane.isPriority() : request.markPriority());
			default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的指令类型");
		}

		lane.setLastActionAt(referenceTime);
		lane.setStatus(resolveStatusForLane(lane, pendingAssignmentCounts(referenceTime).getOrDefault(lane.getId(), 0L)));
		refreshLaneRuntime(referenceTime);
		invalidateRuntimeViews("manual_dispatch");
	}

	private void addPlaceholderPlateCount(Lane lane, Integer requestedCount, int previousVehicleCount, OffsetDateTime referenceTime) {
		int count = requestedCount == null ? 0 : requestedCount;
		if (count <= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新增占位车牌数量必须大于 0");
		}
		int remainingCapacity = Math.max(0, lane.getCapacity() - lane.getVehicleCount());
		if (remainingCapacity <= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前车道已满");
		}
		if (count > remainingCapacity) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前车道最多只能新增 " + remainingCapacity + " 个占位车牌");
		}
		lane.setVehicleCount(lane.getVehicleCount() + count);
		updateQueueHeadAtForObservedCountChange(lane, previousVehicleCount, lane.getVehicleCount(), referenceTime);
		flowLog.info(
				"节点=人工新增占位车牌 event=MANUAL_PLACEHOLDER_PLATES_ADDED laneId={} laneName={} addedCount={} previousCount={} currentCount={} observedAt={}",
				lane.getId(),
				lane.getName(),
				count,
				previousVehicleCount,
				lane.getVehicleCount(),
				referenceTime);
	}

	private void addManualCorrectedPlate(
			Lane lane,
			String plate,
			String vehicleType,
			int previousVehicleCount,
			OffsetDateTime referenceTime) {
		String normalizedPlate = normalizePlate(plate);
		if (isBlank(normalizedPlate)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "车牌号码不能为空");
		}
		if (lane.getVehicleCount() >= lane.getCapacity()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前车道已满");
		}
		List<EntryLog> existingActiveLogs = entryLogRepository.findByPlateIgnoreCaseAndExitTimeIsNullOrderByEntryTimeAsc(normalizedPlate);
		List<DispatchTicket> existingOpenTickets = dispatchTicketRepository
				.findByPlateIgnoreCaseAndClosedAtIsNullOrderByYardEntryTimeDesc(normalizedPlate).stream()
				.filter(ticket -> ticket.getExitTime() == null)
				.filter(ticket -> !"NOT_WHITELISTED".equals(ticket.getStatus()))
				.toList();
		if (!existingActiveLogs.isEmpty() || !existingOpenTickets.isEmpty()) {
				closeExistingPlateRecordsForManualPlateChange(
					normalizedPlate,
					existingActiveLogs,
					existingOpenTickets,
					referenceTime);
		}
		lane.setVehicleCount(lane.getVehicleCount() + 1);
		updateQueueHeadAtForObservedCountChange(lane, previousVehicleCount, lane.getVehicleCount(), referenceTime);
		lane.setLastEntryAt(referenceTime);
		lane.setLastEntryPlate(normalizedPlate);
		if (isBlank(lane.getCurrentPlate())) {
			lane.setCurrentPlate(normalizedPlate);
		}
		entryLogRepository.save(newEntryLog(
				normalizedPlate,
				lane,
				vehicleType,
				"DIRECT_ENTERED",
				"MANUAL_CORRECTION",
				"控制台",
				referenceTime));
		dispatchTicketRepository.save(newDispatchTicket(
				normalizedPlate,
				lane,
				vehicleType,
				"MANUAL_CORRECTION",
				"DIRECT_ENTERED",
				"控制台",
				referenceTime,
				"信号灯控制台人工补录真实车牌"));
		flowLog.info(
				"节点=人工新增真实车牌 event=MANUAL_REAL_PLATE_ADDED laneId={} laneName={} plate={} previousCount={} currentCount={} observedAt={}",
				lane.getId(),
				lane.getName(),
				normalizedPlate,
				previousVehicleCount,
				lane.getVehicleCount(),
				referenceTime);
	}

	private void closeExistingPlateRecordsForManualPlateChange(
			String plate,
			List<EntryLog> activeLogs,
			List<DispatchTicket> openTickets,
			OffsetDateTime referenceTime) {
		Set<String> affectedLaneIds = new LinkedHashSet<>();
		for (EntryLog log : activeLogs) {
			log.setExitTime(referenceTime);
			affectedLaneIds.add(log.getLaneId());
		}
		if (!activeLogs.isEmpty()) {
			entryLogRepository.saveAll(activeLogs);
		}

		String correctionNote = "人工车牌校正时关闭旧未出场记录（出口地感无法识别实际车牌）";
		for (DispatchTicket ticket : openTickets) {
			if (!isBlank(ticket.getActualLaneId())) {
				affectedLaneIds.add(ticket.getActualLaneId());
			}
			if (isBlank(ticket.getNotes())) {
				ticket.setNotes(correctionNote);
			} else if (!ticket.getNotes().contains(correctionNote)) {
				ticket.setNotes(ticket.getNotes() + "；" + correctionNote);
			}
			closeDispatchTicket(ticket, referenceTime);
		}

		for (String affectedLaneId : affectedLaneIds) {
			laneRepository.findById(affectedLaneId).ifPresent(affectedLane -> {
				List<EntryLog> remainingLogs = entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc(affectedLaneId);
				if (remainingLogs.isEmpty()) {
					affectedLane.setCurrentPlate(null);
					affectedLane.setQueueHeadAt(null);
				} else {
					affectedLane.setCurrentPlate(remainingLogs.getFirst().getPlate());
					affectedLane.setQueueHeadAt(remainingLogs.getFirst().getEntryTime());
				}
			});
		}

		flowLog.warn(
				"节点=人工车牌校正清理旧记录 event=MANUAL_PLATE_STALE_RECORDS_CLOSED plate={} closedLogs={} closedTickets={} affectedLanes={} observedAt={} reason=EXIT_LOOP_CANNOT_IDENTIFY_ACTUAL_PLATE",
				plate,
				activeLogs.size(),
				openTickets.size(),
				affectedLaneIds,
				referenceTime);
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
	public Lane updateLaneDispatchEnabled(String laneId, boolean dispatchEnabled) {
		requireLane(laneId);
		OffsetDateTime referenceTime = now();
		Set<String> disabledLaneIds = currentLaneDispatchDisabledIds();
		if (dispatchEnabled) {
			disabledLaneIds.remove(laneId);
		} else {
			disabledLaneIds.add(laneId);
		}
		saveLaneDispatchDisabledConfig(disabledLaneIds, referenceTime);
		return persistLaneRuntime(laneId, referenceTime, "lane_dispatch_status_updated");
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
		advanceExitSignalIfCurrentCleared(lane, observedAt);
		return persistLaneRuntime(lane.getId(), observedAt, "lane_sensor_ingested");
	}

	@Transactional
	public DispatchTicket registerYardEntry(YardEntryPayload payload) {
		OffsetDateTime capturedAt = resolveTime(payload.capturedAt());
		String normalizedPlate = normalizePlate(payload.plate());
		String normalizedSource = isBlank(payload.source()) ? "YARD_CAMERA" : payload.source().toUpperCase(Locale.ROOT);
		if (LicensePlateRules.shouldIgnoreYardEntry(normalizedPlate, payload.plateColor())) {
			log.info(
					"Yard entry capture ignored because blue taxi plate pattern did not match plate={} plateColor={} source={} capturedAt={}",
					normalizedPlate,
					payload.plateColor(),
					normalizedSource,
					capturedAt);
			flowLog.info(
					"节点=总入口抓拍忽略 event=YARD_ENTRY_IGNORED plate={} plateColor={} source={} capturedAt={} reason=PLATE_RULE_NOT_MATCHED",
					normalizedPlate,
					payload.plateColor(),
					normalizedSource,
					capturedAt);
			return null;
		}

		if (currentBooleanConfig(WHITELIST_FILTER_ENABLED_KEY, false) && !whitelistCacheService.contains(normalizedPlate)) {
			return registerNonWhitelistedYardEntry(normalizedPlate, payload, normalizedSource, capturedAt);
		}

		expireStaleDispatchTickets(capturedAt);
		DispatchTicket duplicateOpenTicket = findLatestOpenTicketByPlate(normalizedPlate);
		if (duplicateOpenTicket != null) {
			flowLog.info(
					"节点=总入口重复抓拍忽略 event=YARD_ENTRY_DUPLICATE_IGNORED plate={} plateColor={} source={} capturedAt={} existingTicketId={} existingStatus={} assignedLane={} actualLane={} reason=OPEN_TICKET_EXISTS",
					normalizedPlate,
					payload.plateColor(),
					normalizedSource,
					capturedAt,
					duplicateOpenTicket.getId(),
					duplicateOpenTicket.getStatus(),
					nullToEmpty(duplicateOpenTicket.getAssignedLaneId()),
					nullToEmpty(duplicateOpenTicket.getActualLaneId()));
			return duplicateOpenTicket;
		}

		List<Lane> lanes = laneRepository.findAllByOrderByCodeAsc();
		List<String> laneOrder = currentLaneOrder(ENTRY_LANE_ORDER_KEY, defaultEntryLaneOrder);
		boolean entryDispatchBefore = currentBooleanConfig(ENTRY_DISPATCH_ENABLED_KEY, defaultEntryDispatchEnabled);
		String activeEntryLaneBefore = currentStringConfig(ACTIVE_ENTRY_LANE_KEY, null);
		log.info(
				"Yard entry capture received plate={} source={} capturedAt={} entryDispatchBefore={} activeEntryLaneBefore={} lanes={} laneOrder={}",
				normalizedPlate,
				normalizedSource,
				capturedAt,
				entryDispatchBefore,
				activeEntryLaneBefore,
				lanes.size(),
				laneOrder);
		flowLog.info(
				"节点=总入口抓拍入场 event=YARD_ENTRY_CAPTURE plate={} plateColor={} source={} capturedAt={} entryDispatchBefore={} activeEntryLaneBefore={} lanes={} laneOrder={}",
				normalizedPlate,
				payload.plateColor(),
				normalizedSource,
				capturedAt,
				entryDispatchBefore,
				activeEntryLaneBefore,
				lanes.size(),
				laneOrder);
		ensureEntryDispatchRunningForYardCapture(normalizedSource, capturedAt, lanes, laneOrder);
		Map<String, Long> pendingCounts = pendingAssignmentCounts(capturedAt);
		boolean entryDispatchEnabled = currentBooleanConfig(ENTRY_DISPATCH_ENABLED_KEY, defaultEntryDispatchEnabled);
		Set<String> disabledLaneIds = currentLaneDispatchDisabledIds();
		String activeAutoEntryLaneId = entryDispatchEnabled
				? resolveAndPersistActiveEntryLaneId(lanes, laneOrder, pendingCounts, disabledLaneIds, capturedAt)
				: null;
		Lane targetLane = laneById(lanes, activeAutoEntryLaneId);
		log.info(
				"Yard entry assignment decision plate={} source={} entryDispatchEnabled={} activeEntryLane={} targetLane={} pendingForTarget={} vehicleCount={} capacity={}",
				normalizedPlate,
				normalizedSource,
				entryDispatchEnabled,
				activeAutoEntryLaneId,
				targetLane == null ? null : targetLane.getId(),
				targetLane == null ? null : pendingCounts.getOrDefault(targetLane.getId(), 0L),
				targetLane == null ? null : targetLane.getVehicleCount(),
				targetLane == null ? null : targetLane.getCapacity());

		DispatchTicket ticket = DispatchTicket.builder()
				.id("DSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT))
				.plate(normalizedPlate)
				.yardEntryTime(capturedAt)
				.vehicleType(isBlank(payload.vehicleType()) ? "出租车" : payload.vehicleType())
				.source(normalizedSource)
				.operator("总入口抓拍")
				.build();

		if (targetLane != null
				&& canReserveEntrySlot(targetLane, pendingCounts.getOrDefault(targetLane.getId(), 0L), disabledLaneIds)) {
			ticket.setAssignedLaneId(targetLane.getId());
			ticket.setAssignedLaneName(targetLane.getName());
			ticket.setAssignedAt(capturedAt);
			ticket.setStatus("ASSIGNED");
			ticket.setNotes("大屏指引前往 " + targetLane.getName());
			dispatchTicketRepository.save(ticket);
			log.info(
					"Yard entry assigned plate={} ticketId={} laneId={} laneName={} capturedAt={} entryLogCreated=false",
					ticket.getPlate(),
					ticket.getId(),
					targetLane.getId(),
					targetLane.getName(),
					capturedAt);
			flowLog.info(
					"节点=总入口分配车道 event=YARD_ENTRY_ASSIGNED plate={} ticketId={} laneId={} laneName={} capturedAt={} pendingForLane={} vehicleCount={} capacity={} entryLogCreated=false",
					ticket.getPlate(),
					ticket.getId(),
					targetLane.getId(),
					targetLane.getName(),
					capturedAt,
					pendingCounts.getOrDefault(targetLane.getId(), 0L),
					targetLane.getVehicleCount(),
					targetLane.getCapacity());

			pendingCounts.merge(targetLane.getId(), 1L, Long::sum);
			if (!canReserveEntrySlot(targetLane, pendingCounts.getOrDefault(targetLane.getId(), 0L), disabledLaneIds)) {
				advanceEntrySignal(capturedAt, targetLane.getId(), lanes, laneOrder, pendingCounts, disabledLaneIds);
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
			flowLog.warn(
					"节点=总入口无可分配车道 event=YARD_ENTRY_NO_LANE plate={} ticketId={} source={} capturedAt={} entryDispatchEnabled={} activeEntryLane={} lanes={} pendingCounts={}",
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

	private DispatchTicket registerNonWhitelistedYardEntry(
			String normalizedPlate,
			YardEntryPayload payload,
			String normalizedSource,
			OffsetDateTime capturedAt) {
		expireStaleDispatchTickets(capturedAt);
		DispatchTicket existingTicket = findLatestNonWhitelistedTicketByPlate(normalizedPlate);
		if (existingTicket != null) {
			flowLog.info(
					"节点=总入口非白名单重复抓拍忽略 event=YARD_ENTRY_NON_WHITELISTED_DUPLICATE plate={} plateColor={} source={} capturedAt={} existingTicketId={} reason=OPEN_NON_WHITELISTED_TICKET_EXISTS",
					normalizedPlate,
					payload.plateColor(),
					normalizedSource,
					capturedAt,
					existingTicket.getId());
			return existingTicket;
		}
		DispatchTicket ticket = DispatchTicket.builder()
				.id("DSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT))
				.plate(normalizedPlate)
				.yardEntryTime(capturedAt)
				.vehicleType(isBlank(payload.vehicleType()) ? "出租车" : payload.vehicleType())
				.status("NOT_WHITELISTED")
				.source(normalizedSource)
				.operator("总入口抓拍")
				.notes("非白名单车辆，总入口不参与车道分配")
				.build();
		dispatchTicketRepository.save(ticket);
		log.warn(
				"Yard entry rejected by whitelist plate={} plateColor={} source={} capturedAt={} ticketId={}",
				normalizedPlate,
				payload.plateColor(),
				normalizedSource,
				capturedAt,
				ticket.getId());
		flowLog.warn(
				"节点=总入口非白名单拦截 event=YARD_ENTRY_NON_WHITELISTED plate={} plateColor={} source={} capturedAt={} ticketId={} reason=WHITELIST_MISSED",
				normalizedPlate,
				payload.plateColor(),
				normalizedSource,
				capturedAt,
				ticket.getId());
		invalidateRuntimeViews("yard_entry_non_whitelisted");
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
				markLaneSensorHealthy(lane, entryTime, "vehicle_entry_duplicate_ignored");
				lane.setLastEntryAt(entryTime);
				lane.setLastEntryPlate(plate);
				lane.setLastActionAt(entryTime);
				flowLog.info(
						"节点=车道重复入场忽略 event=LANE_ENTRY_DUPLICATE_IGNORED laneId={} laneName={} plate={} source={} entryTime={} ticketId={} reason=ALREADY_IN_SAME_LANE",
						lane.getId(),
						lane.getName(),
						plate,
						source,
						entryTime,
						activeTicket.getId());
				persistLaneRuntime(lane.getId(), entryTime, "vehicle_entry_duplicate_ignored");
				return activeLogInLane;
			}
			flowLog.warn(
					"节点=车道入场拒绝 event=LANE_ENTRY_REJECTED laneId={} laneName={} plate={} source={} entryTime={} existingLane={} existingTicketId={} reason=PLATE_ALREADY_ACTIVE",
					lane.getId(),
					lane.getName(),
					plate,
					source,
					entryTime,
					firstNonBlank(activeTicket.getActualLaneId(), activeTicket.getAssignedLaneId()),
					activeTicket.getId());
			throw new ResponseStatusException(
					HttpStatus.CONFLICT,
					"车牌已在" + firstNonBlank(activeTicket.getActualLaneName(), activeTicket.getAssignedLaneName(), activeTicket.getActualLaneId(), "其他车道") + "未出场，不能重复入场");
		}
		DispatchTicket ticket = findLatestPendingTicketByPlate(plate);
		if (ticket == null) {
			ticket = findLatestRecoverableExpiredTicketByPlate(plate, entryTime);
		}
		if (activeLogInLane != null && ticket == null) {
			markLaneSensorHealthy(lane, entryTime, "vehicle_entry_duplicate_ignored");
			lane.setLastEntryAt(entryTime);
			lane.setLastEntryPlate(plate);
			lane.setLastActionAt(entryTime);
			flowLog.info(
					"节点=车道重复入场忽略 event=LANE_ENTRY_DUPLICATE_IGNORED laneId={} laneName={} plate={} source={} entryTime={} reason=ACTIVE_LOG_IN_SAME_LANE",
					lane.getId(),
					lane.getName(),
					plate,
					source,
					entryTime);
			persistLaneRuntime(lane.getId(), entryTime, "vehicle_entry_duplicate_ignored");
			return activeLogInLane;
		}

		EntryLaneWindow entryLaneWindow = currentEntryLaneWindow();
		if (entryLaneWindow != null && !entryLaneWindow.accepts(lane.getId())) {
			return ignoreOutOfWindowLaneEntry(lane, plate, vehicleType, source, entryTime, ticket, entryLaneWindow);
		}

		int previousVehicleCount = lane.getVehicleCount();
		lane.setVehicleCount(Math.min(lane.getCapacity(), lane.getVehicleCount() + 1));
		updateQueueHeadAtForObservedCountChange(lane, previousVehicleCount, lane.getVehicleCount(), entryTime);
		markLaneSensorHealthy(lane, entryTime, "vehicle_entry_registered");
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
		recordEntryHandoffIfNeeded(lane, plate, entryTime);
		flowLog.info(
				"节点=车道入场登记完成 event=LANE_ENTRY_REGISTERED laneId={} laneName={} plate={} vehicleType={} source={} entryTime={} previousCount={} currentCount={} ticketMatched={}",
				lane.getId(),
				lane.getName(),
				plate,
				vehicleType,
				source,
				entryTime,
				previousVehicleCount,
				lane.getVehicleCount(),
				ticket != null);
		refreshLaneRuntime(entryTime);
		invalidateRuntimeViews("vehicle_entry_captured");
		return entryLog;
	}

	@Transactional
	public Lane updateLaneDeviceStatus(String laneId, String sensorStatus, OffsetDateTime observedAt, String ledMessage) {
		validateSensorStatus(sensorStatus);
		Lane lane = requireLane(laneId);
		OffsetDateTime referenceTime = resolveTime(observedAt);
		String previousSensorStatus = lane.getSensorStatus();
		lane.setSensorStatus(sensorStatus);
		lane.setLastSensorAt(referenceTime);
		lane.setLastActionAt(referenceTime);
		flowLog.info(
				"节点=车道设备状态更新 event=LANE_DEVICE_STATUS laneId={} laneName={} previousSensorStatus={} nextSensorStatus={} message={} impact={} observedAt={}",
				lane.getId(),
				lane.getName(),
				nullToEmpty(previousSensorStatus),
				sensorStatus,
				nullToEmpty(ledMessage),
				"DEGRADED".equals(sensorStatus) ? "按车道已满保护处理，入口自动调度会跳过该车道" : "设备状态恢复或更新",
				referenceTime);
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
		OffsetDateTime referenceTime = resolveTime(observedAt);
		flowLog.info(
				"节点=设备灯态反馈入库 event=SIGNAL_DEVICE_FEEDBACK laneId={} entrySignal={} exitSignal={} observedAt={} message={}",
				laneId,
				nullToEmpty(entrySignal),
				nullToEmpty(exitSignal),
				referenceTime,
				message);
		laneRuntimeStateService.recordDeviceFeedback(laneId, entrySignal, exitSignal, referenceTime, message);
		invalidateRuntimeViews("signal_feedback_updated");
	}

	@Transactional
	public EntryLog registerVehicleEntryFromDevice(String laneId, String plate, OffsetDateTime entryTime, String vehicleType, String source) {
		return registerVehicleEntry(new VehicleEntryPayload(laneId, plate, vehicleType, source, entryTime));
	}

	private EntryLog ignoreOutOfWindowLaneEntry(
			Lane lane,
			String plate,
			String vehicleType,
			String source,
			OffsetDateTime entryTime,
			DispatchTicket ticket,
			EntryLaneWindow entryLaneWindow) {
		markLaneSensorHealthy(lane, entryTime, "vehicle_entry_out_of_window_ignored");
		lane.setLastActionAt(entryTime);
		EntryLog entryLog = newEntryLog(
				plate,
				lane,
				vehicleType,
				ENTRY_LOG_STATUS_OUT_OF_WINDOW,
				source,
				"设备采集",
				entryTime);
		entryLog.setExitTime(entryTime);
		entryLog = entryLogRepository.save(entryLog);
		closeDispatchTicketForOutOfWindowLaneEntry(ticket, lane, source, entryTime, entryLaneWindow);
		flowLog.warn(
				"节点=车道入场越级忽略 event=LANE_ENTRY_OUT_OF_WINDOW_IGNORED laneId={} laneName={} plate={} source={} entryTime={} activeEntryLane={} nextEntryLane={} assignedLane={} ticketId={} reason=NOT_ACTIVE_OR_NEXT_ENTRY_LANE",
				lane.getId(),
				lane.getName(),
				plate,
				source,
				entryTime,
				entryLaneWindow.activeLaneId(),
				nullToEmpty(entryLaneWindow.nextLaneId()),
				ticket == null ? "" : nullToEmpty(ticket.getAssignedLaneId()),
				ticket == null ? "" : ticket.getId());
		persistLaneRuntime(lane.getId(), entryTime, "vehicle_entry_out_of_window_ignored");
		invalidateRuntimeViews("vehicle_entry_out_of_window_ignored");
		return entryLog;
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
		markLaneSensorHealthy(lane, referenceTime, "pass_count_delta");

		if (delta < 0) {
			int exitCount = Math.min(previousVehicleCount, Math.abs(delta));
			lane.setVehicleCount(Math.max(0, previousVehicleCount - exitCount));
			updateQueueHeadAtForObservedCountChange(lane, previousVehicleCount, lane.getVehicleCount(), referenceTime);
			closeExitedVehicles(lane, exitCount, referenceTime);
			advanceExitSignalIfCurrentCleared(lane, referenceTime);
		} else {
			lane.setVehicleCount(Math.min(lane.getCapacity(), previousVehicleCount + delta));
			updateQueueHeadAtForObservedCountChange(lane, previousVehicleCount, lane.getVehicleCount(), referenceTime);
		}

		flowLog.info(
				"节点=车道计数变更 event=PASS_COUNT_DELTA laneId={} delta={} observedAt={} previousCount={} currentCount={}",
				laneId,
				delta,
				referenceTime,
				previousVehicleCount,
				lane.getVehicleCount());
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
		markLaneSensorHealthy(lane, referenceTime, "vehicle_exit_registered");
		lane.setLastActionAt(referenceTime);
		lane.setVehicleCount(Math.max(0, previousVehicleCount - 1));
		updateQueueHeadAtForObservedCountChange(lane, previousVehicleCount, lane.getVehicleCount(), referenceTime);

		matchedLog.setExitTime(referenceTime);
		entryLogRepository.save(matchedLog);
		closeDispatchTicketForExit(matchedLog, referenceTime);
		refreshLaneHeadAfterVehicleExit(lane, activeLogs, matchedLog);
		advanceExitSignalIfCurrentCleared(lane, referenceTime);

		flowLog.info(
				"节点=车辆出场登记完成 event=LANE_EXIT_REGISTERED laneId={} laneName={} plate={} observedAt={} previousCount={} currentCount={} source=MANUAL_OR_API",
				lane.getId(),
				lane.getName(),
				normalizedPlate,
				referenceTime,
				previousVehicleCount,
				lane.getVehicleCount());
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
		OffsetDateTime referenceTime = resolveTime(observedAt);
		List<DispatchTicket> openTickets = dispatchTicketRepository.findByActualLaneIdAndExitTimeIsNullAndClosedAtIsNullOrderByLaneEntryTimeAsc(lane.getId());
		List<EntryLog> activeLogs = entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc(lane.getId());
		int remainingCount = Stream.of(lane.getVehicleCount(), openTickets.size(), activeLogs.size())
				.max(Integer::compareTo)
				.orElse(0);
		flowLog.info(
				"节点=车道兜底清空请求 event=LANE_REMAINING_CLEAR_REQUEST laneId={} laneName={} observedAt={} vehicleCount={} openTickets={} activeLogs={} remainingCount={} reason={}",
				lane.getId(),
				lane.getName(),
				referenceTime,
				lane.getVehicleCount(),
				openTickets.size(),
				activeLogs.size(),
				remainingCount,
				reason);
		boolean exitHandoffManualConfirm = hasExitHandoffManualConfirm(lane.getId());
		if (remainingCount > LANE_REMAINING_CLEAR_THRESHOLD && !exitHandoffManualConfirm) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "车道剩余车辆超过 3 辆，不能使用兜底清空");
		}

		String activeExitLaneId = currentStringConfig(ACTIVE_EXIT_LANE_KEY, null);
		if (!lane.getId().equals(activeExitLaneId) && !exitHandoffManualConfirm) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能对当前出口开放车道使用兜底清空");
		}
		long pendingCount = pendingAssignmentCounts(referenceTime).getOrDefault(lane.getId(), 0L);
		Set<String> disabledLaneIds = currentLaneDispatchDisabledIds();
		boolean entryStillOpen = lane.getId().equals(currentStringConfig(ACTIVE_ENTRY_LANE_KEY, null))
				&& canReserveEntrySlot(lane, pendingCount, disabledLaneIds);
		if (entryStillOpen) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该车道入口仍在开放，不能使用兜底清空");
		}

		String clearReason = firstNonBlank(reason, "现场确认车道剩余车辆已全部驶出");
		int previousVehicleCount = lane.getVehicleCount();
		List<EntryLog> logsToClose = activeLogs;

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

		if ("MANUAL".equals(lane.getMode())) {
			lane.setMode("AUTO");
			laneRuntimeStateService.clearManualTarget(lane.getId());
		}
		lane.setVehicleCount(0);
		lane.setCurrentPlate(null);
		lane.setQueueHeadAt(null);
		lane.setPriority(false);
		lane.setLastActionAt(referenceTime);
		updateQueueHeadAtForObservedCountChange(lane, previousVehicleCount, lane.getVehicleCount(), referenceTime);
		if (lane.getId().equals(activeExitLaneId)) {
			advanceExitSignalAfterCleared(referenceTime, lane.getId());
		}
		clearExitHandoffManualConfirm(lane.getId());
		flowLog.info(
				"节点=车道兜底清空完成 event=LANE_REMAINING_CLEARED laneId={} laneName={} observedAt={} previousCount={} closedLogs={} closedTickets={} reason={}",
				lane.getId(),
				lane.getName(),
				referenceTime,
				previousVehicleCount,
				logsToClose.size(),
				openTickets.size(),
				clearReason);
		return persistLaneRuntime(laneId, referenceTime, "lane_remaining_cleared");
	}

	@Transactional
	public Lane applyLaneExitTrigger(String laneId, OffsetDateTime observedAt) {
		return applyLaneExitTriggerWithResult(laneId, observedAt).lane();
	}

	@Transactional
	public LaneExitTriggerResult applyLaneExitTriggerWithResult(String laneId, OffsetDateTime observedAt) {
		Lane lane = requireLane(laneId);
		int previousVehicleCount = lane.getVehicleCount();
		OffsetDateTime referenceTime = resolveTime(observedAt);
		List<Lane> lanes = laneRepository.findAllByOrderByCodeAsc();
		List<String> laneOrder = currentLaneOrder(ENTRY_LANE_ORDER_KEY, defaultEntryLaneOrder);
		boolean exitDispatchEnabled = currentBooleanConfig(EXIT_DISPATCH_ENABLED_KEY, defaultExitDispatchEnabled);
		String activeExitLaneId = exitDispatchEnabled
				? currentStringConfig(ACTIVE_EXIT_LANE_KEY, null)
				: null;
		if (laneId.equals(activeExitLaneId)) {
			Lane updatedLane = exitLaneByLoopTrigger(laneId, referenceTime, "lane_exit_triggered");
			String activeExitLaneIdAfter = currentStringConfig(ACTIVE_EXIT_LANE_KEY, null);
			return new LaneExitTriggerResult(
					updatedLane,
					LaneExitTriggerAction.CURRENT_DEDUCTED,
					activeExitLaneId,
					activeExitLaneIdAfter,
					null,
					previousVehicleCount,
					updatedLane.getVehicleCount(),
					Math.max(0, previousVehicleCount - updatedLane.getVehicleCount()),
					0,
					EXIT_HANDOFF_TRIGGER_THRESHOLD);
		}

		if (isBlank(activeExitLaneId)) {
			flowLog.info(
					"节点=非出口放行地感忽略 event=EXIT_LOOP_NON_ACTIVE_IGNORED laneId={} laneName={} observedAt={} activeExitLane={} reason=NO_ACTIVE_EXIT",
						lane.getId(),
						lane.getName(),
						referenceTime,
						"");
			Lane updatedLane = persistLaneRuntime(laneId, referenceTime, "exit_loop_non_active_ignored");
			return new LaneExitTriggerResult(
					updatedLane,
					LaneExitTriggerAction.IGNORED_NO_ACTIVE_EXIT,
					activeExitLaneId,
					currentStringConfig(ACTIVE_EXIT_LANE_KEY, null),
					null,
					previousVehicleCount,
					updatedLane.getVehicleCount(),
					0,
					0,
					EXIT_HANDOFF_TRIGGER_THRESHOLD);
		}

		List<Lane> orderedLanes = sortLanesByOrder(lanes, laneOrder);
		String nextHandoffLaneId = nextExitHandoffLaneId(orderedLanes, activeExitLaneId);
		if (!laneId.equals(nextHandoffLaneId)) {
				flowLog.info(
						"节点=非相邻出口地感忽略 event=EXIT_LOOP_NON_ACTIVE_IGNORED laneId={} laneName={} observedAt={} activeExitLane={} nextHandoffLane={} reason=NOT_CURRENT_OR_NEXT",
						lane.getId(),
						lane.getName(),
						referenceTime,
						activeExitLaneId,
						nullToEmpty(nextHandoffLaneId));
			Lane updatedLane = persistLaneRuntime(laneId, referenceTime, "exit_loop_non_active_ignored");
			return new LaneExitTriggerResult(
					updatedLane,
					LaneExitTriggerAction.IGNORED_NOT_CURRENT_OR_NEXT,
					activeExitLaneId,
					currentStringConfig(ACTIVE_EXIT_LANE_KEY, null),
					nextHandoffLaneId,
					previousVehicleCount,
					updatedLane.getVehicleCount(),
					0,
					0,
					EXIT_HANDOFF_TRIGGER_THRESHOLD);
		}

		int handoffCount = incrementExitHandoffCount(activeExitLaneId, laneId);
		flowLog.info(
				"节点=出口相邻车道交接缓存 event=EXIT_HANDOFF_PROGRESS fromLane={} toLane={} observedAt={} count={} threshold={} action=BUFFER_WITHOUT_DEDUCT",
				activeExitLaneId,
				laneId,
				referenceTime,
				handoffCount,
				EXIT_HANDOFF_TRIGGER_THRESHOLD);
		if (handoffCount >= EXIT_HANDOFF_TRIGGER_THRESHOLD) {
			applyBufferedExitHandoffTriggers(laneId, handoffCount, referenceTime);
			completeExitHandoff(activeExitLaneId, laneId, referenceTime);
			Lane updatedLane = persistLaneRuntime(laneId, referenceTime, "exit_handoff_completed");
			return new LaneExitTriggerResult(
					updatedLane,
					LaneExitTriggerAction.HANDOFF_COMPLETED,
					activeExitLaneId,
					currentStringConfig(ACTIVE_EXIT_LANE_KEY, null),
					nextHandoffLaneId,
					previousVehicleCount,
					updatedLane.getVehicleCount(),
					Math.max(0, previousVehicleCount - updatedLane.getVehicleCount()),
					handoffCount,
					EXIT_HANDOFF_TRIGGER_THRESHOLD);
		}
		Lane updatedLane = persistLaneRuntime(laneId, referenceTime, "exit_handoff_trigger_buffered");
		return new LaneExitTriggerResult(
				updatedLane,
				LaneExitTriggerAction.HANDOFF_BUFFERED,
				activeExitLaneId,
				currentStringConfig(ACTIVE_EXIT_LANE_KEY, null),
				nextHandoffLaneId,
				previousVehicleCount,
				updatedLane.getVehicleCount(),
				0,
				handoffCount,
				EXIT_HANDOFF_TRIGGER_THRESHOLD);
	}

	private Lane exitLaneByLoopTrigger(String laneId, OffsetDateTime observedAt, String action) {
		Lane lane = requireLane(laneId);
		OffsetDateTime referenceTime = resolveTime(observedAt);
		List<DispatchTicket> visibleTickets = dispatchTicketRepository.findByActualLaneIdAndExitTimeIsNullAndClosedAtIsNullOrderByLaneEntryTimeAsc(lane.getId());
		flowLog.info(
				"节点=出口地感处理车辆 event=EXIT_LOOP_PROCESS laneId={} laneName={} observedAt={} action={} vehicleCount={} visibleTickets={}",
				lane.getId(),
				lane.getName(),
				referenceTime,
				action,
				lane.getVehicleCount(),
				visibleTickets.size());
		if (!visibleTickets.isEmpty()) {
			return closeScreenExitTicket(visibleTickets.getFirst(), referenceTime, action);
		}

		List<EntryLog> activeLogs = entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc(lane.getId());
		if (!activeLogs.isEmpty()) {
			return closeScreenExitLog(activeLogs.getFirst(), referenceTime, action);
		}

		return applyPassCountDelta(laneId, -1, referenceTime);
	}

	private void applyBufferedExitHandoffTriggers(String laneId, int triggerCount, OffsetDateTime referenceTime) {
		int bufferedCount = Math.min(Math.max(0, triggerCount), EXIT_HANDOFF_TRIGGER_THRESHOLD);
		for (int index = 0; index < bufferedCount; index++) {
			exitLaneByLoopTrigger(laneId, referenceTime, "exit_handoff_buffered_trigger");
		}
		flowLog.info(
				"节点=出口交接缓存补扣 event=EXIT_HANDOFF_BUFFER_APPLIED laneId={} triggerCount={} appliedCount={} observedAt={}",
				laneId,
				triggerCount,
				bufferedCount,
				referenceTime);
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
		markLaneSensorHealthy(lane, referenceTime, "lane_exit_by_loop");
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
		advanceExitSignalIfCurrentCleared(lane, referenceTime);
		flowLog.info(
				"节点=出口地感扣减车辆 event=LANE_EXIT_BY_LOOP laneId={} laneName={} plate={} ticketId={} observedAt={} action={} previousCount={} currentCount={} remainingTickets={}",
				lane.getId(),
				lane.getName(),
				ticket.getPlate(),
				ticket.getId(),
				referenceTime,
				action,
				previousVehicleCount,
				lane.getVehicleCount(),
				remainingTickets.size());
		return persistLaneRuntime(lane.getId(), referenceTime, action);
	}

	private Lane closeScreenExitLog(EntryLog log, OffsetDateTime referenceTime, String action) {
		Lane lane = requireLane(log.getLaneId());
		List<EntryLog> activeLogs = entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc(lane.getId());
		log.setExitTime(referenceTime);
		entryLogRepository.save(log);
		closeDispatchTicketForExit(log, referenceTime);

		int previousVehicleCount = lane.getVehicleCount();
		markLaneSensorHealthy(lane, referenceTime, "lane_exit_by_loop");
		lane.setLastActionAt(referenceTime);
		lane.setVehicleCount(Math.max(0, previousVehicleCount - 1));
		updateQueueHeadAtForObservedCountChange(lane, previousVehicleCount, lane.getVehicleCount(), referenceTime);
		refreshLaneHeadAfterVehicleExit(lane, activeLogs, log);
		advanceExitSignalIfCurrentCleared(lane, referenceTime);
		flowLog.info(
				"节点=出口地感扣减车辆 event=LANE_EXIT_BY_LOOP laneId={} laneName={} plate={} observedAt={} action={} previousCount={} currentCount={} remainingLogs={}",
				lane.getId(),
				lane.getName(),
				log.getPlate(),
				referenceTime,
				action,
				previousVehicleCount,
				lane.getVehicleCount(),
				Math.max(0, activeLogs.size() - 1));
		return persistLaneRuntime(lane.getId(), referenceTime, action);
	}

	@Transactional
	public Lane applyLanePresenceSignal(String laneId, boolean haveCar, OffsetDateTime observedAt) {
		Lane lane = requireLane(laneId);
		OffsetDateTime referenceTime = resolveTime(observedAt);
		markLaneSensorHealthy(lane, referenceTime, "lane_presence_polled");
		lane.setLastActionAt(referenceTime);
		if (!haveCar && entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc(laneId).isEmpty()) {
			int previousVehicleCount = lane.getVehicleCount();
			lane.setVehicleCount(0);
			lane.setCurrentPlate(null);
			lane.setQueueHeadAt(null);
			lane.setPriority(false);
			flowLog.info(
					"节点=在位为空清空车道 event=LANE_PRESENCE_CLEARED laneId={} laneName={} observedAt={} previousCount={} haveCar={}",
					lane.getId(),
					lane.getName(),
					referenceTime,
					previousVehicleCount,
					haveCar);
		}
		return persistLaneRuntime(laneId, referenceTime, "lane_presence_polled");
	}

	private DashboardPayload buildDashboardPayload(OffsetDateTime referenceTime) {
		List<Lane> lanes = refreshLaneRuntime(referenceTime);
		List<EntryLog> logs = entryLogRepository.findAllByOrderByEntryTimeDesc();
		return new DashboardPayload(referenceTime, buildThroughput(logs), lanes, buildDispatchBoard(referenceTime, lanes));
	}

	private DispatchBoardView buildDispatchBoard(OffsetDateTime referenceTime, List<Lane> lanes) {
		expireStaleDispatchTickets(referenceTime);
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
		List<String> laneOrder = currentLaneOrder(ENTRY_LANE_ORDER_KEY, defaultEntryLaneOrder);
		Map<String, Long> pendingCounts = pendingAssignmentCounts(referenceTime);
		boolean entryDispatchEnabled = currentBooleanConfig(ENTRY_DISPATCH_ENABLED_KEY, defaultEntryDispatchEnabled);
		boolean exitDispatchEnabled = currentBooleanConfig(EXIT_DISPATCH_ENABLED_KEY, defaultExitDispatchEnabled);
		Set<String> disabledLaneIds = currentLaneDispatchDisabledIds();
		String activeEntryLaneId = entryDispatchEnabled
				? resolveAndPersistActiveEntryLaneId(lanes, laneOrder, pendingCounts, disabledLaneIds, referenceTime)
				: null;
		String activeExitLaneId = exitDispatchEnabled
				? resolveAndPersistActiveExitLaneId(lanes, laneOrder, referenceTime, activeEntryLaneId)
				: null;
		if (!entryDispatchEnabled) {
			saveActiveEntrySignalConfig(null, referenceTime);
		}
		if (!exitDispatchEnabled) {
			saveActiveExitSignalConfig(null, referenceTime);
		}
		return new DispatchBoardView(
				referenceTime,
				activeEntryLaneId,
				activeExitLaneId,
				laneName(lanes, activeEntryLaneId),
				laneName(lanes, activeExitLaneId),
				entryDispatchEnabled,
				exitDispatchEnabled,
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
		OffsetDateTime recoverAfter = referenceTime.minusMinutes(currentAssignmentReserveMinutes());
		List<EntryLog> activeYardLogs = entryLogRepository.findByExitTimeIsNullOrderByEntryTimeAsc().stream()
				.filter(log -> "ALPR_YARD".equalsIgnoreCase(log.getSource()))
				.filter(log -> log.getEntryTime() != null && log.getEntryTime().isAfter(recoverAfter))
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
		Set<String> disabledLaneIds = currentLaneDispatchDisabledIds();
		Map<String, Long> pendingCounts = pendingAssignmentCounts(referenceTime);
		boolean entryDispatchEnabled = currentBooleanConfig(ENTRY_DISPATCH_ENABLED_KEY, defaultEntryDispatchEnabled);
		boolean exitDispatchEnabled = currentBooleanConfig(EXIT_DISPATCH_ENABLED_KEY, defaultExitDispatchEnabled);
		String activeEntryLaneId = entryDispatchEnabled
				? resolveAndPersistActiveEntryLaneId(lanes, laneOrder, pendingCounts, disabledLaneIds, referenceTime)
				: null;
		String activeExitLaneId = exitDispatchEnabled
				? resolveAndPersistActiveExitLaneId(lanes, laneOrder, referenceTime, activeEntryLaneId)
				: null;
		if (!entryDispatchEnabled) {
			saveActiveEntrySignalConfig(null, referenceTime);
		}
		if (!exitDispatchEnabled) {
			saveActiveExitSignalConfig(null, referenceTime);
		}

		for (Lane lane : lanes) {
			long reservedCount = pendingCounts.getOrDefault(lane.getId(), 0L);
			lane.setDispatchEnabled(!disabledLaneIds.contains(lane.getId()));
			lane.setReservedCount(Math.toIntExact(Math.min(Integer.MAX_VALUE, reservedCount)));
			lane.setAvailableSlots(Math.max(0, lane.getCapacity() - lane.getVehicleCount() - lane.getReservedCount()));
			EntryLog headLog = headLogs.get(lane.getId());
			if (headLog != null) {
				lane.setCurrentPlate(headLog.getPlate());
			} else if (lane.getVehicleCount() <= 0) {
				lane.setCurrentPlate(null);
			}
			lane.setStatus(resolveStatusForLane(lane, reservedCount));
			String previousEntrySignal = lane.getEntrySignal();
			String previousExitSignal = lane.getExitSignal();
			if ("OFFLINE".equals(lane.getMode())) {
				lane.setEntrySignal("OFFLINE");
				lane.setExitSignal("OFFLINE");
				laneRuntimeStateService.recordRenderedState(lane.getId(), "OFFLINE", "OFFLINE", "车道已设为离线", referenceTime);
				logSignalDecisionIfChanged(
						lane,
						previousEntrySignal,
						previousExitSignal,
						"OFFLINE",
						false,
						false,
					activeEntryLaneId,
					activeExitLaneId,
					reservedCount,
					disabledLaneIds,
					entryDispatchEnabled,
					exitDispatchEnabled,
					referenceTime);
			} else {
				if ("MANUAL".equals(lane.getMode())) {
					lane.setMode("AUTO");
					laneRuntimeStateService.clearManualTarget(lane.getId());
				}
			boolean entryOpenNow = entryDispatchEnabled
					&& lane.getId().equals(activeEntryLaneId)
					&& canReserveEntrySlot(lane, reservedCount, disabledLaneIds);
			boolean exitOpenNow = exitDispatchEnabled && lane.getId().equals(activeExitLaneId);
				applyAutomaticSignals(
						lane,
						entryOpenNow,
						exitOpenNow);
				laneRuntimeStateService.recordRenderedState(lane.getId(), lane.getEntrySignal(), lane.getExitSignal(), resolveLedMessage(lane), referenceTime);
				logSignalDecisionIfChanged(
						lane,
						previousEntrySignal,
						previousExitSignal,
						"ACTIVE_SIGNAL",
						entryOpenNow,
						exitOpenNow,
					activeEntryLaneId,
					activeExitLaneId,
					reservedCount,
					disabledLaneIds,
					entryDispatchEnabled,
					exitDispatchEnabled,
					referenceTime);
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

	private void logSignalDecisionIfChanged(
			Lane lane,
			String previousEntrySignal,
			String previousExitSignal,
			String decisionMode,
			boolean entryOpenNow,
			boolean exitOpenNow,
			String activeEntryLaneId,
			String activeExitLaneId,
			long reservedCount,
			Set<String> disabledLaneIds,
			boolean entryDispatchEnabled,
			boolean exitDispatchEnabled,
			OffsetDateTime referenceTime) {
		if (Objects.equals(previousEntrySignal, lane.getEntrySignal())
				&& Objects.equals(previousExitSignal, lane.getExitSignal())) {
			return;
		}
		flowLog.info(
				"节点=红绿灯切换原因 event=SIGNAL_DECISION laneId={} laneName={} decisionMode={} laneMode={} status={} previousEntry={} previousExit={} nextEntry={} nextExit={} entryOpenNow={} exitOpenNow={} activeEntryLane={} activeExitLane={} vehicleCount={} reservedCount={} availableSlots={} entryReason={} exitReason={} at={}",
				lane.getId(),
				lane.getName(),
				decisionMode,
				lane.getMode(),
				lane.getStatus(),
				nullToEmpty(previousEntrySignal),
				nullToEmpty(previousExitSignal),
				lane.getEntrySignal(),
				lane.getExitSignal(),
				entryOpenNow,
				exitOpenNow,
				nullToEmpty(activeEntryLaneId),
				nullToEmpty(activeExitLaneId),
				lane.getVehicleCount(),
				reservedCount,
				lane.getAvailableSlots(),
				entrySignalReason(lane, decisionMode, entryOpenNow, activeEntryLaneId, disabledLaneIds, entryDispatchEnabled, reservedCount),
				exitSignalReason(lane, decisionMode, exitOpenNow, activeExitLaneId, exitDispatchEnabled),
				referenceTime);
	}

	private String entrySignalReason(
			Lane lane,
			String decisionMode,
			boolean entryOpenNow,
			String activeEntryLaneId,
			Set<String> disabledLaneIds,
			boolean entryDispatchEnabled,
			long reservedCount) {
		if ("OFFLINE".equals(decisionMode) || "OFFLINE".equals(lane.getMode())) {
			return "车道离线，入口灯置为离线/关闭";
		}
		if (!entryDispatchEnabled) {
			return "入口自动调度关闭，入口灯保持红灯";
		}
		if (entryOpenNow) {
			return "该车道是当前入口开放车道，且仍有可用名额，入口灯切为绿灯";
		}
		if (!canReserveEntrySlot(lane, reservedCount, disabledLaneIds)) {
			return entryBlockReason(lane, reservedCount, disabledLaneIds);
		}
		if (isBlank(activeEntryLaneId)) {
			return "没有选出入口开放车道，入口灯保持红灯";
		}
		return "该车道不是当前入口开放车道，当前入口开放车道为 " + activeEntryLaneId;
	}

	private String exitSignalReason(
			Lane lane,
			String decisionMode,
			boolean exitOpenNow,
			String activeExitLaneId,
			boolean exitDispatchEnabled) {
		if ("OFFLINE".equals(decisionMode) || "OFFLINE".equals(lane.getMode())) {
			return "车道离线，出口灯置为离线/关闭";
		}
		if (!exitDispatchEnabled) {
			return "出口自动调度关闭，出口灯保持红灯";
		}
		if (exitOpenNow) {
			return "该车道是当前出口开放车道，出口灯切为绿灯";
		}
		if (lane.getVehicleCount() <= 0) {
			return "车道当前车辆数为 0，无待出场车辆，出口灯保持红灯";
		}
		if (isBlank(activeExitLaneId)) {
			return "没有选出出口开放车道，出口灯保持红灯";
		}
		return "该车道不是当前出口开放车道，当前出口开放车道为 " + activeExitLaneId;
	}

	private String resolveLedMessage(Lane lane) {
		if ("OFFLINE".equals(lane.getMode())) {
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
		Set<String> disabledLaneIds = currentLaneDispatchDisabledIds();
		if (!currentBooleanConfig(ENTRY_DISPATCH_ENABLED_KEY, defaultEntryDispatchEnabled)) {
			return null;
		}
		return resolveAndPersistActiveEntryLaneId(lanes, laneOrder, pendingCounts, disabledLaneIds, referenceTime);
	}

	private String resolveOpenExitLaneId(List<Lane> lanes, OffsetDateTime referenceTime, String activeEntryLaneId) {
		List<String> laneOrder = currentLaneOrder(ENTRY_LANE_ORDER_KEY, defaultEntryLaneOrder);
		if (!currentBooleanConfig(EXIT_DISPATCH_ENABLED_KEY, defaultExitDispatchEnabled)) {
			return null;
		}
		return resolveAndPersistActiveExitLaneId(lanes, laneOrder, referenceTime, activeEntryLaneId);
	}

	private ActiveSignal resolveAndPersistActiveSignal(
			List<Lane> lanes,
			List<String> laneOrder,
			Map<String, Long> pendingCounts,
			Set<String> disabledLaneIds,
			OffsetDateTime referenceTime) {
		boolean entryEnabled = currentBooleanConfig(ENTRY_DISPATCH_ENABLED_KEY, defaultEntryDispatchEnabled);
		boolean exitEnabled = currentBooleanConfig(EXIT_DISPATCH_ENABLED_KEY, defaultExitDispatchEnabled);
		if (!entryEnabled && !exitEnabled) {
			saveActiveSignalConfig(null, null, referenceTime);
			return null;
		}
		ActiveSignal currentSignal = currentActiveSignal();
		ActiveSignal resolvedSignal = resolveActiveSignal(
				lanes,
				laneOrder,
				pendingCounts,
				disabledLaneIds,
				currentSignal,
				entryEnabled,
				exitEnabled);
		saveActiveSignalConfig(
				resolvedSignal == null ? null : resolvedSignal.laneId(),
				resolvedSignal == null ? null : resolvedSignal.direction(),
				referenceTime);
		return resolvedSignal;
	}

	private ActiveSignal resolveActiveSignal(
			List<Lane> lanes,
			List<String> laneOrder,
			Map<String, Long> pendingCounts,
			Set<String> disabledLaneIds,
			ActiveSignal currentSignal,
			boolean entryEnabled,
			boolean exitEnabled) {
		List<Lane> orderedLanes = sortLanesByOrder(lanes, laneOrder);
		if (currentSignal != null && "ENTRY".equals(currentSignal.direction())) {
			Lane currentLane = laneById(orderedLanes, currentSignal.laneId());
			if (entryEnabled
					&& currentLane != null
					&& canReserveEntrySlot(currentLane, pendingCounts.getOrDefault(currentLane.getId(), 0L), disabledLaneIds)) {
				return currentSignal;
			}
			if (entryEnabled) {
				String nextEntryLaneId = nextEligibleEntryLaneId(orderedLanes, currentSignal.laneId(), pendingCounts, disabledLaneIds);
				if (!isBlank(nextEntryLaneId)) {
					return new ActiveSignal(nextEntryLaneId, "ENTRY");
				}
			}
			if (exitEnabled) {
				String nextExitLaneId = nextEligibleExitLaneId(orderedLanes, currentSignal.laneId(), null);
				if (!isBlank(nextExitLaneId)) {
					return new ActiveSignal(nextExitLaneId, "EXIT");
				}
			}
		}
		if (currentSignal != null && "EXIT".equals(currentSignal.direction())) {
			Lane currentLane = laneById(orderedLanes, currentSignal.laneId());
			if (exitEnabled && currentLane != null && canActivateLane(currentLane) && hasExitEvidence(currentLane)) {
				return currentSignal;
			}
			if (exitEnabled) {
				String nextExitLaneId = nextEligibleExitLaneId(orderedLanes, currentSignal.laneId(), null);
				if (!isBlank(nextExitLaneId)) {
					return new ActiveSignal(nextExitLaneId, "EXIT");
				}
			}
			if (entryEnabled) {
				String nextEntryLaneId = nextEligibleEntryLaneId(orderedLanes, currentSignal.laneId(), pendingCounts, disabledLaneIds);
				if (!isBlank(nextEntryLaneId)) {
					return new ActiveSignal(nextEntryLaneId, "ENTRY");
				}
			}
		}
		if (entryEnabled) {
			String entryLaneId = nextEligibleEntryLaneId(orderedLanes, null, pendingCounts, disabledLaneIds);
			if (!isBlank(entryLaneId)) {
				return new ActiveSignal(entryLaneId, "ENTRY");
			}
		}
		if (exitEnabled) {
			String exitLaneId = nextEligibleExitLaneId(orderedLanes, null, null);
			if (!isBlank(exitLaneId)) {
				return new ActiveSignal(exitLaneId, "EXIT");
			}
		}
		return null;
	}

	private ActiveSignal currentActiveSignal() {
		String laneId = currentStringConfig(ACTIVE_SIGNAL_LANE_KEY, null);
		String direction = currentStringConfig(ACTIVE_SIGNAL_DIRECTION_KEY, null);
		if (!isBlank(laneId) && VALID_SIGNAL_DIRECTIONS.contains(direction)) {
			return new ActiveSignal(laneId, direction);
		}
		String activeEntryLaneId = currentStringConfig(ACTIVE_ENTRY_LANE_KEY, null);
		if (!isBlank(activeEntryLaneId)) {
			return new ActiveSignal(activeEntryLaneId, "ENTRY");
		}
		String activeExitLaneId = currentStringConfig(ACTIVE_EXIT_LANE_KEY, null);
		if (!isBlank(activeExitLaneId)) {
			return new ActiveSignal(activeExitLaneId, "EXIT");
		}
		return null;
	}

	private String resolveAndPersistActiveEntryLaneId(
			List<Lane> lanes,
			List<String> laneOrder,
			Map<String, Long> pendingCounts,
			OffsetDateTime referenceTime) {
		return resolveAndPersistActiveEntryLaneId(lanes, laneOrder, pendingCounts, currentLaneDispatchDisabledIds(), referenceTime);
	}

	private String resolveAndPersistActiveEntryLaneId(
			List<Lane> lanes,
			List<String> laneOrder,
			Map<String, Long> pendingCounts,
			Set<String> disabledLaneIds,
			OffsetDateTime referenceTime) {
		String currentLaneId = currentStringConfig(ACTIVE_ENTRY_LANE_KEY, null);
		String resolvedLaneId = resolveActiveEntryLaneId(
				lanes,
				laneOrder,
				pendingCounts,
				currentLaneId,
				disabledLaneIds);
		logEntryActiveLaneSwitch(lanes, laneOrder, pendingCounts, currentLaneId, resolvedLaneId, disabledLaneIds, referenceTime);
		saveActiveEntrySignalConfig(resolvedLaneId, referenceTime);
		return resolvedLaneId;
	}

	private String resolveAndPersistActiveExitLaneId(List<Lane> lanes, List<String> laneOrder, OffsetDateTime referenceTime, String activeEntryLaneId) {
		String currentLaneId = currentStringConfig(ACTIVE_EXIT_LANE_KEY, null);
		String resolvedLaneId = resolveActiveExitLaneId(lanes, laneOrder, activeEntryLaneId, currentLaneId);
		logExitActiveLaneSwitch(lanes, laneOrder, currentLaneId, resolvedLaneId, activeEntryLaneId, referenceTime);
		saveActiveExitSignalConfig(resolvedLaneId, referenceTime);
		return resolvedLaneId;
	}

	private String resolveActiveEntryLaneId(
			List<Lane> lanes,
			List<String> laneOrder,
			Map<String, Long> pendingCounts,
			String currentLaneId,
			Set<String> disabledLaneIds) {
		List<Lane> orderedLanes = sortLanesByOrder(lanes, laneOrder);
		Lane currentLane = laneById(orderedLanes, currentLaneId);
		if (currentLane != null
				&& canReserveEntrySlot(currentLane, pendingCounts.getOrDefault(currentLane.getId(), 0L), disabledLaneIds)) {
			return currentLane.getId();
		}
		return nextEligibleEntryLaneId(orderedLanes, currentLaneId, pendingCounts, disabledLaneIds);
	}

	private String resolveActiveExitLaneId(List<Lane> lanes, List<String> laneOrder, String activeEntryLaneId, String currentLaneId) {
		List<Lane> orderedLanes = sortLanesByOrder(lanes, laneOrder);
		Lane currentLane = laneById(orderedLanes, currentLaneId);
		if (isEligibleCurrentExitLane(orderedLanes, currentLane, activeEntryLaneId)) {
			return currentLane.getId();
		}
		return nextEligibleExitLaneId(orderedLanes, currentLaneId, activeEntryLaneId);
	}

	private boolean canReserveEntrySlot(Lane lane, long pendingCount, Set<String> disabledLaneIds) {
		if (disabledLaneIds.contains(lane.getId())) {
			return false;
		}
		return canActivateLane(lane)
				&& !"DEGRADED".equals(lane.getSensorStatus())
				&& lane.getVehicleCount() + pendingCount < lane.getCapacity();
	}

	private boolean canReserveEntrySlot(Lane lane, long pendingCount) {
		return canReserveEntrySlot(lane, pendingCount, currentLaneDispatchDisabledIds());
	}

	private boolean canActivateLane(Lane lane) {
		return !"OFFLINE".equals(lane.getMode())
				&& lane.getCapacity() > 0;
	}

	private boolean hasExitEvidence(Lane lane) {
		if (lane == null) {
			return false;
		}
		if (lane.getVehicleCount() > 0) {
			return true;
		}
		if (!entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc(lane.getId()).isEmpty()) {
			return true;
		}
		return !dispatchTicketRepository.findByActualLaneIdAndExitTimeIsNullAndClosedAtIsNullOrderByLaneEntryTimeAsc(lane.getId()).isEmpty();
	}

	private void logEntryActiveLaneSwitch(
			List<Lane> lanes,
			List<String> laneOrder,
			Map<String, Long> pendingCounts,
			String currentLaneId,
			String resolvedLaneId,
			Set<String> disabledLaneIds,
			OffsetDateTime referenceTime) {
		if (Objects.equals(currentLaneId, resolvedLaneId)) {
			return;
		}
		List<Lane> orderedLanes = sortLanesByOrder(lanes, laneOrder);
		Lane previousLane = laneById(orderedLanes, currentLaneId);
		Lane nextLane = laneById(orderedLanes, resolvedLaneId);
		flowLog.info(
				"节点=入口开放车道切换 event=ENTRY_ACTIVE_LANE_SWITCH previous={} previousName={} next={} nextName={} switchReason={} selectReason={} laneOrder={} at={}",
				nullToEmpty(currentLaneId),
				previousLane == null ? "" : previousLane.getName(),
				nullToEmpty(resolvedLaneId),
				nextLane == null ? "" : nextLane.getName(),
				entrySwitchReason(previousLane, currentLaneId, pendingCounts, disabledLaneIds),
				entrySelectReason(orderedLanes, laneOrder, currentLaneId, nextLane, pendingCounts),
				laneOrderDescription(orderedLanes, laneOrder),
				referenceTime);
	}

	private void logExitActiveLaneSwitch(
			List<Lane> lanes,
			List<String> laneOrder,
			String currentLaneId,
			String resolvedLaneId,
			String activeEntryLaneId,
			OffsetDateTime referenceTime) {
		if (Objects.equals(currentLaneId, resolvedLaneId)) {
			return;
		}
		List<Lane> orderedLanes = sortLanesByOrder(lanes, laneOrder);
		Lane previousLane = laneById(orderedLanes, currentLaneId);
		Lane nextLane = laneById(orderedLanes, resolvedLaneId);
		flowLog.info(
				"节点=出口开放车道切换 event=EXIT_ACTIVE_LANE_SWITCH previous={} previousName={} next={} nextName={} activeEntryLane={} switchReason={} selectReason={} laneOrder={} at={}",
				nullToEmpty(currentLaneId),
				previousLane == null ? "" : previousLane.getName(),
				nullToEmpty(resolvedLaneId),
				nextLane == null ? "" : nextLane.getName(),
				nullToEmpty(activeEntryLaneId),
				exitSwitchReason(orderedLanes, previousLane, currentLaneId, activeEntryLaneId),
				exitSelectReason(orderedLanes, laneOrder, currentLaneId, nextLane, activeEntryLaneId),
				laneOrderDescription(orderedLanes, laneOrder),
				referenceTime);
	}

	private String entrySwitchReason(
			Lane previousLane,
			String currentLaneId,
			Map<String, Long> pendingCounts,
			Set<String> disabledLaneIds) {
		if (isBlank(currentLaneId)) {
			return "当前没有入口开放车道，需要按入口顺序选择一条可进车车道";
		}
		if (previousLane == null) {
			return "原入口开放车道 " + currentLaneId + " 不在当前车道配置中";
		}
		return entryBlockReason(previousLane, pendingCounts.getOrDefault(previousLane.getId(), 0L), disabledLaneIds);
	}

	private String entrySelectReason(
			List<Lane> orderedLanes,
			List<String> laneOrder,
			String currentLaneId,
			Lane nextLane,
			Map<String, Long> pendingCounts) {
		if (nextLane == null) {
			return "按入口顺序配置 " + laneOrderDescription(orderedLanes, laneOrder) + " 查找后，没有可开放入口的车道";
		}
		return "按入口顺序配置 " + laneOrderDescription(orderedLanes, laneOrder)
				+ " 从 " + firstNonBlank(currentLaneId, "起点") + " 后查找，选择 "
				+ nextLane.getName() + "；" + laneEntryStateSummary(nextLane, pendingCounts.getOrDefault(nextLane.getId(), 0L));
	}

	private String exitSwitchReason(List<Lane> orderedLanes, Lane previousLane, String currentLaneId, String activeEntryLaneId) {
		if (isBlank(currentLaneId)) {
			return "当前没有出口开放车道，需要按出口队列选择";
		}
		if (previousLane == null) {
			return "原出口开放车道 " + currentLaneId + " 不在当前车道配置中";
		}
		if (!canActivateLane(previousLane)) {
			return "原出口开放车道不可用：" + laneActivationBlockReason(previousLane);
		}
		return "当前出口车道已由人工确认、相邻车道地感交接或设备状态变化触发重新选择";
	}

	private String exitSelectReason(
			List<Lane> orderedLanes,
			List<String> laneOrder,
			String currentLaneId,
			Lane nextLane,
			String activeEntryLaneId) {
		if (nextLane == null) {
			return "按入口顺序配置 " + laneOrderDescription(orderedLanes, laneOrder) + " 查找后，没有可开放出口的车道";
		}
		if (nextLane.getVehicleCount() > 0) {
			return "从 " + firstNonBlank(currentLaneId, "起点") + " 后查找，选择下一条有车的 "
					+ nextLane.getName() + "，当前车辆数=" + nextLane.getVehicleCount();
		}
		return "从 " + firstNonBlank(currentLaneId, "起点") + " 后查找，选择下一条有出场证据的 " + nextLane.getName();
	}

	private String entryBlockReason(Lane lane, long pendingCount, Set<String> disabledLaneIds) {
		if (lane == null) {
			return "车道不存在";
		}
		if (disabledLaneIds.contains(lane.getId())) {
			return "该车道已关闭分配，跳过入口调度";
		}
		String activationBlockReason = laneActivationBlockReason(lane);
		if (!isBlank(activationBlockReason)) {
			return activationBlockReason;
		}
		if ("DEGRADED".equals(lane.getSensorStatus())) {
			return "车道尾部滞留判满，系统按车道已满保护处理，入口灯转红并切换下一车道";
		}
		int occupied = lane.getVehicleCount() + Math.toIntExact(Math.min(Integer.MAX_VALUE, pendingCount));
		if (occupied >= lane.getCapacity()) {
			return "车道停满：当前车辆数 " + lane.getVehicleCount()
					+ " + 预分配 " + pendingCount
					+ " >= 容量 " + lane.getCapacity()
					+ "，入口灯转红并切换下一车道";
		}
		return "车道不满足入口开放条件：" + laneEntryStateSummary(lane, pendingCount);
	}

	private String laneActivationBlockReason(Lane lane) {
		if (lane == null) {
			return "车道不存在";
		}
		if ("OFFLINE".equals(lane.getMode())) {
			return "车道模式为 OFFLINE";
		}
		if (lane.getCapacity() <= 0) {
			return "车道容量未配置或为 0";
		}
		return "";
	}

	private String laneEntryStateSummary(Lane lane, long pendingCount) {
		return "车辆数=" + lane.getVehicleCount()
				+ "，预分配=" + pendingCount
				+ "，容量=" + lane.getCapacity()
				+ "，设备状态=" + nullToEmpty(lane.getSensorStatus())
				+ "，模式=" + nullToEmpty(lane.getMode());
	}

	private String laneOrderDescription(List<Lane> orderedLanes, List<String> laneOrder) {
		String orderedNames = orderedLanes.stream()
				.map(lane -> lane.getName() + "(" + lane.getId() + ")")
				.collect(Collectors.joining("->"));
		if (laneOrder.isEmpty()) {
			return "未单独配置，按车道编号自然顺序 " + orderedNames;
		}
		return "配置=" + laneOrder + "，排序结果 " + orderedNames;
	}

	private void advanceEntrySignal(
			OffsetDateTime referenceTime,
			String currentLaneId,
			List<Lane> lanes,
			List<String> laneOrder,
			Map<String, Long> pendingCounts,
			Set<String> disabledLaneIds) {
		String nextLaneId = nextEligibleEntryLaneId(
				sortLanesByOrder(lanes, laneOrder),
				currentLaneId,
				pendingCounts,
				disabledLaneIds);
		logEntryActiveLaneSwitch(lanes, laneOrder, pendingCounts, currentLaneId, nextLaneId, disabledLaneIds, referenceTime);
		saveActiveEntrySignalConfig(nextLaneId, referenceTime);
	}

	private void advanceExitSignalAfterCleared(OffsetDateTime referenceTime, String currentLaneId) {
		advanceExitSignalAfterCleared(referenceTime, currentLaneId, true, "exit_signal_advanced_after_cleared");
	}

	private void advanceExitSignalAfterCleared(
			OffsetDateTime referenceTime,
			String currentLaneId,
			boolean clearTailStayProtection,
			String action) {
		List<Lane> lanes = laneRepository.findAllByOrderByCodeAsc();
		List<String> laneOrder = currentLaneOrder(ENTRY_LANE_ORDER_KEY, defaultEntryLaneOrder);
		List<Lane> orderedLanes = sortLanesByOrder(lanes, laneOrder);
		String nextLaneId = nextEligibleExitLaneId(orderedLanes, currentLaneId, null);
		logExitActiveLaneSwitch(lanes, laneOrder, currentLaneId, nextLaneId, null, referenceTime);
		if (clearTailStayProtection) {
			clearTailStayProtectionBeforeExitTarget(orderedLanes, currentLaneId, nextLaneId, referenceTime, action);
		}
		saveActiveExitSignalConfig(nextLaneId, referenceTime);
	}

	private void advanceExitSignalIfCurrentCleared(Lane lane, OffsetDateTime referenceTime) {
		String activeExitLaneId = currentStringConfig(ACTIVE_EXIT_LANE_KEY, null);
		if (!lane.getId().equals(activeExitLaneId)) {
			return;
		}
		if (hasExitEvidence(lane)) {
			return;
		}
		advanceExitSignalAfterCleared(referenceTime, lane.getId());
	}

	private void clearTailStayProtectionBeforeExitTarget(
			List<Lane> orderedLanes,
			String fromLaneId,
			String toLaneId,
			OffsetDateTime referenceTime,
			String action) {
		if (orderedLanes.isEmpty()
				|| isBlank(fromLaneId)
				|| isBlank(toLaneId)
				|| Objects.equals(fromLaneId, toLaneId)) {
			return;
		}
		int fromIndex = laneIndex(orderedLanes, fromLaneId);
		int toIndex = laneIndex(orderedLanes, toLaneId);
		if (fromIndex < 0 || toIndex < 0) {
			return;
		}
		int index = fromIndex;
		while (index != toIndex) {
			Lane lane = orderedLanes.get(index);
			clearTailStayProtectionIfLaneHasCapacity(lane, referenceTime, action);
			index = (index + 1) % orderedLanes.size();
			if (index == fromIndex) {
				return;
			}
		}
	}

	private String nextEligibleEntryLaneId(List<Lane> orderedLanes, String currentLaneId, Map<String, Long> pendingCounts) {
		return nextEligibleEntryLaneId(orderedLanes, currentLaneId, pendingCounts, currentLaneDispatchDisabledIds());
	}

	private String nextEligibleEntryLaneId(
			List<Lane> orderedLanes,
			String currentLaneId,
			Map<String, Long> pendingCounts,
			Set<String> disabledLaneIds) {
		if (orderedLanes.isEmpty()) {
			return null;
		}
		int currentIndex = laneIndex(orderedLanes, currentLaneId);
		int startIndex = currentIndex >= 0 ? (currentIndex + 1) % orderedLanes.size() : 0;
		for (int offset = 0; offset < orderedLanes.size(); offset++) {
			Lane lane = orderedLanes.get((startIndex + offset) % orderedLanes.size());
			if (canReserveEntrySlot(lane, pendingCounts.getOrDefault(lane.getId(), 0L), disabledLaneIds)) {
				return lane.getId();
			}
		}
		return null;
	}

	private String nextEligibleExitLaneId(List<Lane> orderedLanes, String currentLaneId, String ignoredActiveEntryLaneId) {
		if (orderedLanes.isEmpty()) {
			return null;
		}
		int currentIndex = laneIndex(orderedLanes, currentLaneId);
		int startIndex = currentIndex >= 0 ? (currentIndex + 1) % orderedLanes.size() : 0;
		for (int offset = 0; offset < orderedLanes.size(); offset++) {
			Lane lane = orderedLanes.get((startIndex + offset) % orderedLanes.size());
			if (!canActivateLane(lane)) {
				continue;
			}
			if (hasExitEvidence(lane)) {
				return lane.getId();
			}
		}
		return null;
	}

	private EntryLaneWindow currentEntryLaneWindow() {
		if (!currentBooleanConfig(ENTRY_DISPATCH_ENABLED_KEY, defaultEntryDispatchEnabled)) {
			return null;
		}
		String activeEntryLaneId = currentStringConfig(ACTIVE_ENTRY_LANE_KEY, null);
		if (isBlank(activeEntryLaneId)) {
			return null;
		}
		List<Lane> orderedLanes = sortLanesByOrder(
				laneRepository.findAllByOrderByCodeAsc(),
				currentLaneOrder(ENTRY_LANE_ORDER_KEY, defaultEntryLaneOrder));
		if (laneIndex(orderedLanes, activeEntryLaneId) < 0) {
			return null;
		}
		return new EntryLaneWindow(activeEntryLaneId, nextOrderedLaneId(orderedLanes, activeEntryLaneId));
	}

	private void recordEntryHandoffIfNeeded(Lane enteredLane, String plate, OffsetDateTime referenceTime) {
		if (enteredLane == null || isBlank(plate)) {
			return;
		}
		if (!currentBooleanConfig(ENTRY_DISPATCH_ENABLED_KEY, defaultEntryDispatchEnabled)) {
			entryHandoffTriggerPlates.clear();
			return;
		}
		String activeEntryLaneId = currentStringConfig(ACTIVE_ENTRY_LANE_KEY, null);
		if (isBlank(activeEntryLaneId)) {
			return;
		}
		if (enteredLane.getId().equals(activeEntryLaneId)) {
			clearEntryHandoffCountsFrom(activeEntryLaneId);
			return;
		}

		List<Lane> orderedLanes = sortLanesByOrder(
				laneRepository.findAllByOrderByCodeAsc(),
				currentLaneOrder(ENTRY_LANE_ORDER_KEY, defaultEntryLaneOrder));
		String nextLaneId = nextOrderedLaneId(orderedLanes, activeEntryLaneId);
		if (!enteredLane.getId().equals(nextLaneId)) {
			return;
		}

		String key = entryHandoffKey(activeEntryLaneId, nextLaneId);
		Set<String> plates = entryHandoffTriggerPlates.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet());
		if (!plates.add(plate)) {
			return;
		}
		entryHandoffFirstObservedAt.putIfAbsent(key, referenceTime);

		flowLog.info(
				"节点=入口相邻车道交接计数 event=ENTRY_HANDOFF_PROGRESS fromLane={} toLane={} plate={} observedAt={} count={} threshold={}",
				activeEntryLaneId,
				nextLaneId,
				plate,
				referenceTime,
				plates.size(),
				ENTRY_HANDOFF_TRIGGER_THRESHOLD);
		if (plates.size() < ENTRY_HANDOFF_TRIGGER_THRESHOLD) {
			return;
		}

		flowLog.info(
				"节点=入口交接完成 event=ENTRY_HANDOFF_COMPLETED fromLane={} toLane={} triggerCount={} observedAt={} action=ADVANCE_ACTIVE_ENTRY_LANE",
				activeEntryLaneId,
				nextLaneId,
				plates.size(),
				referenceTime);
		OffsetDateTime openedAt = entryHandoffFirstObservedAt.getOrDefault(key, referenceTime);
		saveActiveEntrySignalConfig(nextLaneId, referenceTime, openedAt, "ENTRY_HANDOFF");
	}

	private String nextOrderedLaneId(List<Lane> orderedLanes, String currentLaneId) {
		if (orderedLanes.isEmpty()) {
			return null;
		}
		int currentIndex = laneIndex(orderedLanes, currentLaneId);
		if (currentIndex < 0) {
			return null;
		}
		return orderedLanes.get((currentIndex + 1) % orderedLanes.size()).getId();
	}

	private void clearEntryHandoffCountsFrom(String fromLaneId) {
		if (isBlank(fromLaneId) || entryHandoffTriggerPlates.isEmpty()) {
			return;
		}
		String prefix = fromLaneId + "->";
		entryHandoffTriggerPlates.keySet().removeIf(key -> key.startsWith(prefix));
		entryHandoffFirstObservedAt.keySet().removeIf(key -> key.startsWith(prefix));
	}

	private String entryHandoffKey(String fromLaneId, String toLaneId) {
		return firstNonBlank(fromLaneId, "") + "->" + firstNonBlank(toLaneId, "");
	}

	private boolean isEligibleCurrentExitLane(List<Lane> orderedLanes, Lane currentLane, String activeEntryLaneId) {
		return currentLane != null && canActivateLane(currentLane);
	}

	private String nextExitHandoffLaneId(List<Lane> orderedLanes, String currentLaneId) {
		if (orderedLanes.isEmpty()) {
			return null;
		}
		int currentIndex = laneIndex(orderedLanes, currentLaneId);
		if (currentIndex < 0) {
			return null;
		}
		for (int offset = 1; offset < orderedLanes.size(); offset++) {
			Lane lane = orderedLanes.get((currentIndex + offset) % orderedLanes.size());
			if (canActivateLane(lane)) {
				return lane.getId();
			}
		}
		return null;
	}

	private int incrementExitHandoffCount(String fromLaneId, String toLaneId) {
		String key = exitHandoffKey(fromLaneId, toLaneId);
		return exitHandoffTriggerCounts.merge(key, 1, Integer::sum);
	}

	private String exitHandoffKey(String fromLaneId, String toLaneId) {
		return firstNonBlank(fromLaneId, "") + "->" + firstNonBlank(toLaneId, "");
	}

	private void completeExitHandoff(String fromLaneId, String toLaneId, OffsetDateTime referenceTime) {
		Lane fromLane = requireLane(fromLaneId);
		List<DispatchTicket> openTickets = dispatchTicketRepository.findByActualLaneIdAndExitTimeIsNullAndClosedAtIsNullOrderByLaneEntryTimeAsc(fromLane.getId());
		List<EntryLog> activeLogs = entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc(fromLane.getId());
		int remainingCount = Stream.of(fromLane.getVehicleCount(), openTickets.size(), activeLogs.size())
				.max(Integer::compareTo)
				.orElse(0);
		OffsetDateTime protectedFrom = exitHandoffProtectedEntryOpenedAt(fromLaneId);
		List<DispatchTicket> protectedTickets = openTickets.stream()
				.filter(ticket -> isProtectedByEntryReopen(ticket, protectedFrom))
				.toList();
		List<DispatchTicket> ticketsToClose = openTickets.stream()
				.filter(ticket -> !isProtectedByEntryReopen(ticket, protectedFrom))
				.toList();
		List<EntryLog> protectedLogs = activeLogs.stream()
				.filter(log -> isProtectedByEntryReopen(log, protectedFrom))
				.toList();
		List<EntryLog> logsToClose = activeLogs.stream()
				.filter(log -> !isProtectedByEntryReopen(log, protectedFrom))
				.toList();
		String reason = "相邻车道 " + toLaneId + " 出口地感连续 " + EXIT_HANDOFF_TRIGGER_THRESHOLD + " 次确认交接";
		clearExitHandoffManualConfirm(fromLaneId);
		flowLog.info(
				"节点=出口交接自动清空上一车道 event=EXIT_HANDOFF_AUTO_CLEAR_PREVIOUS fromLane={} toLane={} remainingCount={} previousThreshold={} protectedFrom={} closedTickets={} protectedTickets={} closedLogs={} protectedLogs={} observedAt={} policy={}",
				fromLaneId,
				toLaneId,
				remainingCount,
				LANE_REMAINING_CLEAR_THRESHOLD,
				protectedFrom,
				ticketsToClose.size(),
				protectedTickets.size(),
				logsToClose.size(),
				protectedLogs.size(),
				referenceTime,
				protectedFrom == null ? "CLEAR_ALL" : "CLEAR_BEFORE_ENTRY_REOPEN");
		for (EntryLog log : logsToClose) {
			log.setExitTime(referenceTime);
		}
		if (!logsToClose.isEmpty()) {
			entryLogRepository.saveAll(logsToClose);
		}
		for (DispatchTicket ticket : ticketsToClose) {
			if (isBlank(ticket.getNotes())) {
				ticket.setNotes(reason);
			}
			closeDispatchTicket(ticket, referenceTime);
		}
		int previousVehicleCount = fromLane.getVehicleCount();
		applyProtectedExitHandoffRemainder(fromLane, protectedTickets, protectedLogs);
		fromLane.setPriority(false);
		fromLane.setLastActionAt(referenceTime);
		updateQueueHeadAtForObservedCountChange(fromLane, previousVehicleCount, fromLane.getVehicleCount(), referenceTime);
		flowLog.info(
				"节点=出口交接完成 event=EXIT_HANDOFF_COMPLETED fromLane={} toLane={} triggerCount={} previousCount={} currentCount={} closedLogs={} closedTickets={} protectedLogs={} protectedTickets={} protectedFrom={} observedAt={}",
				fromLaneId,
				toLaneId,
				EXIT_HANDOFF_TRIGGER_THRESHOLD,
				previousVehicleCount,
				fromLane.getVehicleCount(),
				logsToClose.size(),
				ticketsToClose.size(),
				protectedLogs.size(),
				protectedTickets.size(),
				protectedFrom,
				referenceTime);
		List<Lane> orderedLanes = sortLanesByOrder(
				laneRepository.findAllByOrderByCodeAsc(),
				currentLaneOrder(ENTRY_LANE_ORDER_KEY, defaultEntryLaneOrder));
		clearTailStayProtectionBeforeExitTarget(orderedLanes, fromLaneId, toLaneId, referenceTime, "exit_handoff_completed");
		saveActiveExitSignalConfig(toLaneId, referenceTime);
	}

	private OffsetDateTime exitHandoffProtectedEntryOpenedAt(String laneId) {
		OffsetDateTime exitOpenedAt = laneOpenedAt(EXIT_LANE_OPENED_AT_KEY_PREFIX, laneId);
		if (exitOpenedAt == null && Objects.equals(currentStringConfig(ACTIVE_EXIT_LANE_KEY, null), laneId)) {
			exitOpenedAt = currentConfigUpdatedAt(ACTIVE_EXIT_LANE_KEY);
		}
		OffsetDateTime entryOpenedAt = laneOpenedAt(ENTRY_LANE_OPENED_AT_KEY_PREFIX, laneId);
		if (entryOpenedAt == null && Objects.equals(currentStringConfig(ACTIVE_ENTRY_LANE_KEY, null), laneId)) {
			entryOpenedAt = currentConfigUpdatedAt(ACTIVE_ENTRY_LANE_KEY);
		}
		if (entryOpenedAt == null || exitOpenedAt == null || !entryOpenedAt.isAfter(exitOpenedAt)) {
			return null;
		}
		return entryOpenedAt;
	}

	private OffsetDateTime laneOpenedAt(String keyPrefix, String laneId) {
		if (isBlank(laneId)) {
			return null;
		}
		return currentConfigDateTime(keyPrefix + laneId);
	}

	private boolean isProtectedByEntryReopen(DispatchTicket ticket, OffsetDateTime protectedFrom) {
		if (protectedFrom == null) {
			return false;
		}
		OffsetDateTime entryTime = firstNonNull(ticket.getLaneEntryTime(), ticket.getYardEntryTime());
		return entryTime != null && !entryTime.isBefore(protectedFrom);
	}

	private boolean isProtectedByEntryReopen(EntryLog log, OffsetDateTime protectedFrom) {
		if (protectedFrom == null || log.getEntryTime() == null) {
			return false;
		}
		return !log.getEntryTime().isBefore(protectedFrom);
	}

	private void applyProtectedExitHandoffRemainder(
			Lane lane,
			List<DispatchTicket> protectedTickets,
			List<EntryLog> protectedLogs) {
		int protectedCount = Math.max(protectedTickets.size(), protectedLogs.size());
		lane.setVehicleCount(protectedCount);
		if (protectedCount == 0) {
			lane.setCurrentPlate(null);
			lane.setQueueHeadAt(null);
			return;
		}
		DispatchTicket headTicket = protectedTickets.stream()
				.min(Comparator.comparing(
						ticket -> firstNonNull(ticket.getLaneEntryTime(), ticket.getYardEntryTime()),
						Comparator.nullsLast(Comparator.naturalOrder())))
				.orElse(null);
		EntryLog headLog = protectedLogs.stream()
				.min(Comparator.comparing(
						EntryLog::getEntryTime,
						Comparator.nullsLast(Comparator.naturalOrder())))
				.orElse(null);
		OffsetDateTime ticketTime = headTicket == null
				? null
				: firstNonNull(headTicket.getLaneEntryTime(), headTicket.getYardEntryTime());
		OffsetDateTime logTime = headLog == null ? null : headLog.getEntryTime();
		if (headTicket != null && (headLog == null || logTime == null || (ticketTime != null && !ticketTime.isAfter(logTime)))) {
			lane.setCurrentPlate(headTicket.getPlate());
			lane.setQueueHeadAt(ticketTime);
			return;
		}
		if (headLog != null) {
			lane.setCurrentPlate(headLog.getPlate());
			lane.setQueueHeadAt(headLog.getEntryTime());
		}
	}

	private void markExitHandoffManualConfirm(
			Lane fromLane,
			String toLaneId,
			int remainingCount,
			List<DispatchTicket> openTickets,
			OffsetDateTime referenceTime) {
		String note = EXIT_HANDOFF_MANUAL_CONFIRM_NOTE_PREFIX
				+ "：相邻车道 " + toLaneId + " 出口地感连续 " + EXIT_HANDOFF_TRIGGER_THRESHOLD
				+ " 次确认交接，系统保留上一车道数据";
		for (DispatchTicket ticket : openTickets) {
			if (isBlank(ticket.getNotes())) {
				ticket.setNotes(note);
			} else if (!ticket.getNotes().contains(EXIT_HANDOFF_MANUAL_CONFIRM_NOTE_PREFIX)) {
				ticket.setNotes(ticket.getNotes() + "；" + note);
			}
		}
		if (!openTickets.isEmpty()) {
			dispatchTicketRepository.saveAll(openTickets);
		}
		saveDispatchConfig(
				exitHandoffManualConfirmKey(fromLane.getId()),
				"toLaneId=" + nullToEmpty(toLaneId) + ";remainingCount=" + remainingCount,
				referenceTime);
	}

	private boolean hasExitHandoffManualConfirm(String laneId) {
		return dispatchConfigRepository.existsById(exitHandoffManualConfirmKey(laneId));
	}

	private void clearExitHandoffManualConfirm(String laneId) {
		clearDispatchConfig(exitHandoffManualConfirmKey(laneId));
	}

	private void clearExitHandoffManualConfirms() {
		List<String> configKeys = dispatchConfigRepository.findAll().stream()
				.map(DispatchConfig::getConfigKey)
				.filter(configKey -> configKey.startsWith(EXIT_HANDOFF_MANUAL_CONFIRM_KEY_PREFIX))
				.toList();
		for (String configKey : configKeys) {
			clearDispatchConfig(configKey);
		}
	}

	private String exitHandoffManualConfirmKey(String laneId) {
		return EXIT_HANDOFF_MANUAL_CONFIRM_KEY_PREFIX + nullToEmpty(laneId);
	}

	private int exitHandoffManualConfirmRemainingCount(String configValue) {
		String value = exitHandoffManualConfirmValue(configValue, "remainingCount");
		if (isBlank(value)) {
			return 0;
		}
		try {
			return Math.max(0, Integer.parseInt(value));
		} catch (NumberFormatException ex) {
			return 0;
		}
	}

	private String exitHandoffManualConfirmToLaneId(String configValue) {
		return exitHandoffManualConfirmValue(configValue, "toLaneId");
	}

	private String exitHandoffManualConfirmValue(String configValue, String fieldName) {
		if (isBlank(configValue) || isBlank(fieldName)) {
			return "";
		}
		String prefix = fieldName + "=";
		for (String part : configValue.split(";")) {
			String trimmed = part.trim();
			if (trimmed.startsWith(prefix)) {
				return trimmed.substring(prefix.length()).trim();
			}
		}
		return "";
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
			closeProvisionalEntryLogForExpiredTicket(ticket, referenceTime);
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
			flowLog.info(
					"节点=总入口抓拍启动入口调度 event=ENTRY_DISPATCH_AUTO_START source={} at={} reason=YARD_CAPTURE",
					source,
					referenceTime);
			saveDispatchConfig(ENTRY_DISPATCH_ENABLED_KEY, Boolean.TRUE.toString(), referenceTime);
		}
		if (isBlank(currentStringConfig(ACTIVE_ENTRY_LANE_KEY, null))) {
			String firstLaneId = firstOrderedLaneId(lanes, laneOrder);
			if (isBlank(firstLaneId)) {
				log.warn("Cannot select active entry lane for yard capture source={} referenceTime={} lanes={} laneOrder={}", source, referenceTime, lanes.size(), laneOrder);
			}
			saveActiveEntrySignalConfig(firstLaneId, referenceTime);
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

	private void closeDispatchTicketForOutOfWindowLaneEntry(
			DispatchTicket ticket,
			Lane observedLane,
			String source,
			OffsetDateTime entryTime,
			EntryLaneWindow entryLaneWindow) {
		if (ticket == null) {
			return;
		}
		ticket.setActualLaneId(null);
		ticket.setActualLaneName(null);
		ticket.setLaneEntryTime(null);
		ticket.setExitTime(entryTime);
		ticket.setClosedAt(entryTime);
		ticket.setSource(source);
		ticket.setStatus(DISPATCH_STATUS_ENTRY_OUT_OF_WINDOW);
		ticket.setNotes("车道入口摄像头在非当前/下一入口放行车道识别到车辆，未计入车道停车数据；当前入口绿灯="
				+ entryLaneWindow.activeLaneId()
				+ "，下一入口车道="
				+ nullToEmpty(entryLaneWindow.nextLaneId())
				+ "，识别车道="
				+ observedLane.getId());
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

	private OffsetDateTime currentConfigDateTime(String configKey) {
		return dispatchConfigRepository.findById(configKey)
				.map(DispatchConfig::getConfigValue)
				.map(this::parseConfigDateTime)
				.orElse(null);
	}

	private OffsetDateTime currentConfigUpdatedAt(String configKey) {
		return dispatchConfigRepository.findById(configKey)
				.map(DispatchConfig::getUpdatedAt)
				.orElse(null);
	}

	private OffsetDateTime parseConfigDateTime(String value) {
		if (isBlank(value)) {
			return null;
		}
		try {
			return OffsetDateTime.parse(value);
		} catch (DateTimeParseException ignored) {
			return null;
		}
	}

	private void saveDispatchConfig(String configKey, String configValue, OffsetDateTime updatedAt) {
		DispatchConfig config = dispatchConfigRepository.findById(configKey)
				.orElseGet(() -> DispatchConfig.builder()
						.configKey(configKey)
						.updatedBy("控制台")
						.build());
		String previousValue = config.getConfigValue();
		String nextValue = configValue.trim();
		if (!Objects.equals(previousValue, nextValue) && isFlowRelevantConfig(configKey)) {
			flowLog.info(
					"节点=调度配置变更 event=DISPATCH_CONFIG_CHANGED key={} previous={} next={} at={}",
					configKey,
					nullToEmpty(previousValue),
					nextValue,
					updatedAt);
		}
		config.setConfigValue(nextValue);
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
		flowLog.info(
				"节点=当前开放车道变更 event=ACTIVE_LANE_CHANGED key={} previous={} next={} at={}",
				configKey,
				nullToEmpty(currentValue),
				nullToEmpty(laneId),
				updatedAt);
		if (isBlank(laneId)) {
			clearDispatchConfig(configKey);
			return;
		}
		saveDispatchConfig(configKey, laneId, updatedAt);
	}

	private void saveActiveEntrySignalConfig(String laneId, OffsetDateTime updatedAt) {
		saveActiveEntrySignalConfig(laneId, updatedAt, updatedAt, "ENTRY");
	}

	private void saveActiveEntrySignalConfig(String laneId, OffsetDateTime updatedAt, OffsetDateTime openedAt, String openedDirection) {
		clearLegacyActiveSignalConfig();
		String currentValue = currentStringConfig(ACTIVE_ENTRY_LANE_KEY, null);
		saveActiveLaneConfig(ACTIVE_ENTRY_LANE_KEY, laneId, updatedAt);
		if (!Objects.equals(currentValue, laneId)) {
			entryHandoffTriggerPlates.clear();
			entryHandoffFirstObservedAt.clear();
			recordLaneOpenedAt(ENTRY_LANE_OPENED_AT_KEY_PREFIX, laneId, openedAt, openedDirection);
		}
	}

	private void saveActiveExitSignalConfig(String laneId, OffsetDateTime updatedAt) {
		clearLegacyActiveSignalConfig();
		String currentValue = currentStringConfig(ACTIVE_EXIT_LANE_KEY, null);
		saveActiveLaneConfig(ACTIVE_EXIT_LANE_KEY, laneId, updatedAt);
		if (!Objects.equals(currentValue, laneId)) {
			exitHandoffTriggerCounts.clear();
			recordLaneOpenedAt(EXIT_LANE_OPENED_AT_KEY_PREFIX, laneId, updatedAt, "EXIT");
		}
	}

	private void recordLaneOpenedAt(String keyPrefix, String laneId, OffsetDateTime openedAt, String direction) {
		if (isBlank(laneId) || openedAt == null) {
			return;
		}
		saveDispatchConfig(keyPrefix + laneId, openedAt.toString(), openedAt);
		flowLog.info(
				"节点=车道放行批次记录 event=LANE_SIGNAL_OPENED direction={} laneId={} openedAt={}",
				direction,
				laneId,
				openedAt);
	}

	private void clearActiveSignalConfig(OffsetDateTime updatedAt) {
		boolean changed = !isBlank(currentStringConfig(ACTIVE_ENTRY_LANE_KEY, null))
				|| !isBlank(currentStringConfig(ACTIVE_EXIT_LANE_KEY, null))
				|| !isBlank(currentStringConfig(ACTIVE_SIGNAL_LANE_KEY, null))
				|| !isBlank(currentStringConfig(ACTIVE_SIGNAL_DIRECTION_KEY, null));
		clearLegacyActiveSignalConfig();
		saveActiveLaneConfig(ACTIVE_ENTRY_LANE_KEY, null, updatedAt);
		saveActiveLaneConfig(ACTIVE_EXIT_LANE_KEY, null, updatedAt);
		if (changed) {
			entryHandoffTriggerPlates.clear();
			entryHandoffFirstObservedAt.clear();
			exitHandoffTriggerCounts.clear();
			flowLog.info("节点=入口出口放行游标清空 event=ACTIVE_SIGNAL_CHANGED at={}", updatedAt);
		}
	}

	private void clearLegacyActiveSignalConfig() {
		clearDispatchConfig(ACTIVE_SIGNAL_LANE_KEY);
		clearDispatchConfig(ACTIVE_SIGNAL_DIRECTION_KEY);
	}

	private void saveActiveSignalConfig(String laneId, String direction, OffsetDateTime updatedAt) {
		String normalizedDirection = direction == null ? null : direction.trim().toUpperCase(Locale.ROOT);
		if (isBlank(laneId) || isBlank(normalizedDirection)) {
			clearActiveSignalConfig(updatedAt);
			return;
		}
		if (!VALID_SIGNAL_DIRECTIONS.contains(normalizedDirection)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "放行方向仅支持 ENTRY 或 EXIT");
		}
		if ("ENTRY".equals(normalizedDirection)) {
			saveActiveEntrySignalConfig(laneId, updatedAt);
		} else {
			saveActiveExitSignalConfig(laneId, updatedAt);
		}
	}

	private boolean isFlowRelevantConfig(String configKey) {
		return ENTRY_LANE_ORDER_KEY.equals(configKey)
				|| ENTRY_DISPATCH_ENABLED_KEY.equals(configKey)
				|| EXIT_DISPATCH_ENABLED_KEY.equals(configKey)
				|| LANE_DISPATCH_DISABLED_KEY.equals(configKey)
				|| ACTIVE_ENTRY_LANE_KEY.equals(configKey)
				|| ACTIVE_EXIT_LANE_KEY.equals(configKey)
				|| ACTIVE_SIGNAL_LANE_KEY.equals(configKey)
				|| ACTIVE_SIGNAL_DIRECTION_KEY.equals(configKey)
				|| ASSIGNMENT_RESERVE_MINUTES_KEY.equals(configKey)
				|| WHITELIST_FILTER_ENABLED_KEY.equals(configKey);
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

	private Set<String> currentLaneDispatchDisabledIds() {
		return parseLaneIdList(currentStringConfig(LANE_DISPATCH_DISABLED_KEY, null));
	}

	private void saveLaneDispatchDisabledConfig(Set<String> disabledLaneIds, OffsetDateTime updatedAt) {
		Set<String> normalized = parseLaneIdList(String.join(",", disabledLaneIds == null ? new LinkedHashSet<>() : disabledLaneIds));
		if (normalized.isEmpty()) {
			clearDispatchConfig(LANE_DISPATCH_DISABLED_KEY);
			return;
		}
		saveDispatchConfig(LANE_DISPATCH_DISABLED_KEY, String.join(",", normalized), updatedAt);
	}

	private Set<String> parseLaneIdList(String configuredLaneIds) {
		Set<String> laneIds = new LinkedHashSet<>();
		if (isBlank(configuredLaneIds)) {
			return laneIds;
		}
		for (String value : configuredLaneIds.split("[,，;；\\s]+")) {
			String laneId = value == null ? "" : value.trim();
			if (!laneId.isBlank()) {
				laneIds.add(laneId);
			}
		}
		return laneIds;
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
		} catch (DateTimeParseException ignored) {
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
				.filter(ticket -> !"NOT_WHITELISTED".equals(ticket.getStatus()))
				.findFirst()
				.orElse(null);
	}

	private DispatchTicket findLatestNonWhitelistedTicketByPlate(String plate) {
		if (isBlank(plate)) {
			return null;
		}
		return dispatchTicketRepository.findByPlateIgnoreCaseAndClosedAtIsNullOrderByYardEntryTimeDesc(plate).stream()
				.filter(ticket -> ticket.getExitTime() == null)
				.filter(ticket -> "NOT_WHITELISTED".equals(ticket.getStatus()))
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

	private DispatchTicket findDispatchTicketForLog(EntryLog log, List<DispatchTicket> tickets) {
		if (log == null || isBlank(log.getPlate())) {
			return null;
		}
		String plate = normalizePlate(log.getPlate());
		List<DispatchTicket> plateTickets = tickets.stream()
				.filter(ticket -> plate.equals(normalizePlate(ticket.getPlate())))
				.toList();

		DispatchTicket exactTimeMatch = plateTickets.stream()
				.filter(ticket -> entryLogTimeMatchesTicket(ticket, log))
				.findFirst()
				.orElse(null);
		if (exactTimeMatch != null) {
			return exactTimeMatch;
		}

		return plateTickets.stream()
				.filter(ticket -> entryLogLaneMatchesTicket(ticket, log))
				.filter(ticket -> entryLogFallsWithinTicketWindow(ticket, log))
				.findFirst()
				.orElse(null);
	}

	private boolean entryLogLaneMatchesTicket(DispatchTicket ticket, EntryLog log) {
		String ticketLaneId = firstNonBlank(ticket.getActualLaneId(), ticket.getAssignedLaneId());
		String ticketLaneName = firstNonBlank(ticket.getActualLaneName(), ticket.getAssignedLaneName());
		return (!isBlank(ticketLaneId) && ticketLaneId.equalsIgnoreCase(log.getLaneId()))
				|| (!isBlank(ticketLaneName) && ticketLaneName.equalsIgnoreCase(log.getLaneName()));
	}

	private boolean entryLogFallsWithinTicketWindow(DispatchTicket ticket, EntryLog log) {
		OffsetDateTime logTime = log.getEntryTime();
		OffsetDateTime start = firstNonNull(ticket.getYardEntryTime(), ticket.getAssignedAt(), ticket.getLaneEntryTime());
		OffsetDateTime end = firstNonNull(ticket.getClosedAt(), ticket.getExitTime(), now());
		return (start == null || !logTime.isBefore(start.minusMinutes(2)))
				&& (end == null || !logTime.isAfter(end.plusMinutes(2)));
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

	private void validateBlacklistPayload(BlacklistPayload payload) {
		if (!VALID_BLACKLIST_LEVELS.contains(payload.level())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "风险等级非法");
		}
	}

	private void validateWhitelistPayload(WhitelistPayload payload) {
		if (payload == null || isBlank(payload.plate())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "车牌不能为空");
		}
	}

	private void refreshWhitelistCacheAndInvalidate(String reason) {
		whitelistCacheService.refresh();
		invalidateRuntimeViews(reason);
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

	private WhitelistImportProgressState beginWhitelistImportProgress(String jobId) {
		OffsetDateTime referenceTime = now();
		cleanupWhitelistImportProgress(referenceTime);
		String normalizedJobId = isBlank(jobId) ? UUID.randomUUID().toString() : jobId.trim();
		WhitelistImportProgressState progress = new WhitelistImportProgressState(normalizedJobId, referenceTime);
		whitelistImportProgress.put(normalizedJobId, progress);
		return progress;
	}

	private void cleanupWhitelistImportProgress(OffsetDateTime referenceTime) {
		OffsetDateTime expiredBefore = referenceTime.minusHours(1);
		whitelistImportProgress.entrySet().removeIf(entry -> {
			WhitelistImportProgress snapshot = entry.getValue().snapshot();
			return snapshot.finishedAt() != null && snapshot.finishedAt().isBefore(expiredBefore);
		});
	}

	private String importFailureMessage(RuntimeException exception) {
		if (exception instanceof ResponseStatusException responseStatusException && !isBlank(responseStatusException.getReason())) {
			return responseStatusException.getReason();
		}
		return "白名单导入失败";
	}

	private ParsedWhitelistImport parseWhitelistImport(MultipartFile file, WhitelistImportProgressState progress) {
		if (file == null || file.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择要导入的 Excel 文件");
		}
		if (!isExcelFile(file)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只支持导入 xls 或 xlsx 格式的 Excel 文件");
		}
		try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
			if (workbook.getNumberOfSheets() == 0) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Excel 文件没有工作表");
			}
			Sheet sheet = workbook.getSheetAt(0);
			int estimatedRows = Math.max(1, sheet.getLastRowNum() + 1);
			DataFormatter formatter = new DataFormatter(Locale.CHINA);
			LinkedHashSet<String> plates = new LinkedHashSet<>();
			int totalRows = 0;
			int validRows = 0;
			int duplicateRows = 0;
			int invalidRows = 0;
			boolean firstValueSeen = false;
			progress.update("PARSING", 5, 0, 0, 0, 0, 0, 0, 0, "正在解析 Excel");
			for (Row row : sheet) {
				String rawPlate = formatter.formatCellValue(row.getCell(0)).trim();
				if (!firstValueSeen && !rawPlate.isBlank()) {
					firstValueSeen = true;
					if (rawPlate.contains("车牌")) {
						continue;
					}
				}
				totalRows++;
				if (rawPlate.isBlank()) {
					invalidRows++;
					continue;
				}
				String normalizedPlate = normalizePlate(rawPlate);
				if (isBlank(normalizedPlate)) {
					invalidRows++;
					continue;
				}
				validRows++;
				if (!plates.add(normalizedPlate)) {
					duplicateRows++;
				}
				if (totalRows % 200 == 0 || row.getRowNum() >= sheet.getLastRowNum()) {
					int percent = 5 + (int) Math.round((Math.min(row.getRowNum() + 1, estimatedRows) * 35.0) / estimatedRows);
					progress.update(
							"PARSING",
							Math.min(40, percent),
							totalRows,
							validRows,
							plates.size(),
							0,
							0,
							duplicateRows,
							invalidRows,
							"正在解析 Excel：" + totalRows + " 行");
				}
			}
			progress.update(
					"PARSING",
					40,
					totalRows,
					validRows,
					plates.size(),
					0,
					0,
					duplicateRows,
					invalidRows,
					"Excel 解析完成，准备写入数据库");
			return new ParsedWhitelistImport(totalRows, validRows, List.copyOf(plates), duplicateRows, invalidRows);
		}
		catch (ResponseStatusException exception) {
			throw exception;
		}
		catch (IOException | EncryptedDocumentException | IllegalArgumentException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Excel 文件解析失败，请确认文件格式为 xls 或 xlsx");
		}
	}

	private boolean isExcelFile(MultipartFile file) {
		String filename = file.getOriginalFilename();
		if (isBlank(filename)) {
			return false;
		}
		String lowerFilename = filename.toLowerCase(Locale.ROOT);
		return lowerFilename.endsWith(".xls") || lowerFilename.endsWith(".xlsx");
	}

	private <T> List<List<T>> partition(List<T> values, int batchSize) {
		if (values.isEmpty()) {
			return List.of();
		}
		int normalizedBatchSize = Math.max(1, batchSize);
		List<List<T>> batches = new ArrayList<>();
		for (int start = 0; start < values.size(); start += normalizedBatchSize) {
			batches.add(values.subList(start, Math.min(values.size(), start + normalizedBatchSize)));
		}
		return batches;
	}

	private String resolveStatusForLane(Lane lane, long reservedCount) {
		if ("OFFLINE".equals(lane.getMode())) {
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

	private void markLaneSensorHealthy(Lane lane, OffsetDateTime observedAt, String action) {
		String previousSensorStatus = lane.getSensorStatus();
		lane.setSensorStatus("ONLINE");
		lane.setLastSensorAt(observedAt);
		if ("DEGRADED".equals(previousSensorStatus)) {
			flowLog.info(
					"节点=车道滞留保护恢复 event=LANE_TAIL_STAY_RECOVERED laneId={} laneName={} previousSensorStatus={} nextSensorStatus=ONLINE observedAt={} action={}",
					lane.getId(),
					lane.getName(),
					previousSensorStatus,
					observedAt,
					action);
		}
	}

	private void clearTailStayProtectionIfLaneHasCapacity(Lane lane, OffsetDateTime referenceTime, String action) {
		if (!"DEGRADED".equals(lane.getSensorStatus())) {
			return;
		}
		long pendingCount = pendingAssignmentCounts(referenceTime).getOrDefault(lane.getId(), 0L);
		if (!canActivateLane(lane) || lane.getVehicleCount() + pendingCount >= lane.getCapacity()) {
			return;
		}
		markLaneSensorHealthy(lane, referenceTime, action);
	}

	private boolean shouldCreateDeviceStatusEvent(Lane lane) {
		return "OFFLINE".equals(defaultSensorStatus(lane.getSensorStatus()));
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

	private Set<String> normalizedEventIds(List<String> eventIds) {
		return eventIds.stream()
				.filter(id -> !isBlank(id))
				.map(String::trim)
				.collect(Collectors.toSet());
	}

	private ScreenEventView screenEvent(
			String type,
			String id,
			String plate,
			String message,
			OffsetDateTime occurredAt,
			String sourceId,
			String sourceName) {
		return new ScreenEventView(id, type, plate, message, occurredAt, sourceId, sourceName, false, null, false, null);
	}

	private ScreenEventView withEventState(ScreenEventView event, OffsetDateTime acknowledgedAt, OffsetDateTime handledAt) {
		return new ScreenEventView(
				event.id(),
				event.type(),
				event.plate(),
				event.message(),
				event.occurredAt(),
				event.sourceId(),
				event.sourceName(),
				acknowledgedAt != null,
				acknowledgedAt,
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

	private String blankToNull(String value) {
		return isBlank(value) ? null : value.trim();
	}

	private int normalizePage(int page) {
		return Math.max(1, page);
	}

	private int normalizePageSize(int pageSize) {
		if (pageSize <= 0) {
			return DEFAULT_PAGE_SIZE;
		}
		return Math.min(pageSize, MAX_PAGE_SIZE);
	}

	private <T> PageResult<T> pageFromList(List<T> values, int page, int pageSize) {
		int normalizedPage = normalizePage(page);
		int normalizedPageSize = normalizePageSize(pageSize);
		int fromIndex = Math.toIntExact(Math.min(values.size(), (long) (normalizedPage - 1) * normalizedPageSize));
		int toIndex = Math.min(values.size(), fromIndex + normalizedPageSize);
		return PageResult.of(values.subList(fromIndex, toIndex), values.size(), normalizedPage, normalizedPageSize);
	}

	private OffsetDateTime now() {
		return OffsetDateTime.now(ZoneOffset.ofHours(8));
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

	private record ActiveSignal(String laneId, String direction) {
	}

	private record EntryLaneWindow(String activeLaneId, String nextLaneId) {
		private boolean accepts(String laneId) {
			return Objects.equals(laneId, activeLaneId) || Objects.equals(laneId, nextLaneId);
		}
	}

	private record LogViewCandidate(EntryLogView view, OffsetDateTime sortTime, String ticketId) {
	}

	private static class WhitelistImportProgressState {
		private final String jobId;
		private final OffsetDateTime startedAt;
		private String status = "WAITING";
		private int percent = 0;
		private int totalRows = 0;
		private int validRows = 0;
		private int importedPlates = 0;
		private int createdCount = 0;
		private int updatedCount = 0;
		private int duplicateRows = 0;
		private int invalidRows = 0;
		private String message = "等待服务端接收文件";
		private OffsetDateTime finishedAt;

		private WhitelistImportProgressState(String jobId, OffsetDateTime startedAt) {
			this.jobId = jobId;
			this.startedAt = startedAt;
		}

		private synchronized void update(
				String status,
				int percent,
				int totalRows,
				int validRows,
				int importedPlates,
				int createdCount,
				int updatedCount,
				int duplicateRows,
				int invalidRows,
				String message) {
			this.status = status;
			this.percent = Math.max(0, Math.min(100, percent));
			this.totalRows = Math.max(0, totalRows);
			this.validRows = Math.max(0, validRows);
			this.importedPlates = Math.max(0, importedPlates);
			this.createdCount = Math.max(0, createdCount);
			this.updatedCount = Math.max(0, updatedCount);
			this.duplicateRows = Math.max(0, duplicateRows);
			this.invalidRows = Math.max(0, invalidRows);
			this.message = message;
		}

		private synchronized void complete(WhitelistImportResult result) {
			this.status = "DONE";
			this.percent = 100;
			this.totalRows = result.totalRows();
			this.validRows = result.validRows();
			this.importedPlates = result.importedPlates();
			this.createdCount = result.createdCount();
			this.updatedCount = result.updatedCount();
			this.duplicateRows = result.duplicateRows();
			this.invalidRows = result.invalidRows();
			this.message = "导入完成";
			this.finishedAt = OffsetDateTime.now(ZoneOffset.ofHours(8));
		}

		private synchronized void fail(String message) {
			this.status = "FAILED";
			this.message = message;
			this.finishedAt = OffsetDateTime.now(ZoneOffset.ofHours(8));
		}

		private synchronized WhitelistImportProgress snapshot() {
			return new WhitelistImportProgress(
					jobId,
					status,
					percent,
					totalRows,
					validRows,
					importedPlates,
					createdCount,
					updatedCount,
					duplicateRows,
					invalidRows,
					message,
					startedAt,
					finishedAt);
		}
	}

	private record ParsedWhitelistImport(
			int totalRows,
			int validRows,
			List<String> plates,
			int duplicateRows,
			int invalidRows) {
	}
}
