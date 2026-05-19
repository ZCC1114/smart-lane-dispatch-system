package com.smartlane.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartlane.dispatch.entity.BlacklistRecord;
import com.smartlane.dispatch.entity.DispatchTicket;
import com.smartlane.dispatch.entity.EntryLog;
import com.smartlane.dispatch.entity.Lane;
import com.smartlane.dispatch.repository.BlacklistRecordRepository;
import com.smartlane.dispatch.repository.DispatchConfigRepository;
import com.smartlane.dispatch.repository.DispatchTicketRepository;
import com.smartlane.dispatch.repository.EntryLogRepository;
import com.smartlane.dispatch.repository.LaneRepository;
import com.smartlane.dispatch.service.LaneRuntimeStateService;
import com.smartlane.dispatch.service.OperationsService;

@SpringBootTest(properties = {
		"app.bootstrap-admin.enabled=true",
		"app.bootstrap-admin.username=bootstrap-admin",
		"app.bootstrap-admin.password=Bootstrap#2026!",
		"app.bootstrap-admin.display-name=系统引导管理员",
		"app.bootstrap-admin.station=总控中心",
		"app.dispatch.entry-enabled-default=true",
		"app.dispatch.exit-enabled-default=true",
		"app.dispatch.assignment-reserve-minutes=5"
})
@AutoConfigureMockMvc
class LaneOperationsFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private LaneRepository laneRepository;

	@Autowired
	private EntryLogRepository entryLogRepository;

	@Autowired
	private DispatchTicketRepository dispatchTicketRepository;

	@Autowired
	private BlacklistRecordRepository blacklistRecordRepository;

	@Autowired
	private DispatchConfigRepository dispatchConfigRepository;

	@Autowired
	private OperationsService operationsService;

	@Autowired
	private LaneRuntimeStateService laneRuntimeStateService;

	@BeforeEach
	void setUp() {
		entryLogRepository.deleteAll();
		dispatchTicketRepository.deleteAll();
		blacklistRecordRepository.deleteAll();
		dispatchConfigRepository.deleteAll();
		laneRepository.deleteAll();
		laneRuntimeStateService.clearAll();
	}

	@Test
	void yardEntryShouldReserveSlotAndAdvanceEntryLaneBeforeVehicleArrives() throws Exception {
		Lane firstLane = buildLane("L01", "L01", "1号车道");
		firstLane.setCapacity(1);
		Lane secondLane = buildLane("L02", "L02", "2号车道");
		secondLane.setCapacity(1);
		laneRepository.saveAll(List.of(firstLane, secondLane));
		String token = loginAndGetToken();
		String capturedAt = now().minusSeconds(10).toString();

		mockMvc.perform(post("/api/integration/yard-entries")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "plate": "沪A12345",
					  "vehicleType": "出租车",
					  "source": "yard-camera",
					  "capturedAt": "%s"
					}
					""".formatted(capturedAt)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ASSIGNED"))
			.andExpect(jsonPath("$.assignedLaneId").value("L01"));

		mockMvc.perform(get("/api/dispatch/board")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.activeEntryLaneId").value("L02"))
			.andExpect(jsonPath("$.waitingAssignments[0].plate").value("沪A12345"));

		mockMvc.perform(get("/api/lanes")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id=='L01')].reservedCount").value(1))
			.andExpect(jsonPath("$[?(@.id=='L01')].status").value("FULL"))
			.andExpect(jsonPath("$[?(@.id=='L01')].entrySignal").value("RED"))
			.andExpect(jsonPath("$[?(@.id=='L02')].entrySignal").value("GREEN"));
	}

	@Test
	void laneEntryShouldConsumeReservationAndMarkTicketEntered() throws Exception {
		Lane firstLane = buildLane("L01", "L01", "1号车道");
		firstLane.setCapacity(1);
		Lane secondLane = buildLane("L02", "L02", "2号车道");
		secondLane.setCapacity(1);
		laneRepository.saveAll(List.of(firstLane, secondLane));
		String token = loginAndGetToken();

		postYardEntry(token, "沪A12345", "2026-04-20T08:00:00+08:00");
		postVehicleEntry(token, "L01", "沪A12345", "2026-04-20T08:01:00+08:00");

		DispatchTicket ticket = dispatchTicketRepository.findAllByOrderByYardEntryTimeDesc().getFirst();
		assertThat(ticket.getStatus()).isEqualTo("ENTERED");
		assertThat(ticket.getAssignedLaneId()).isEqualTo("L01");
		assertThat(ticket.getActualLaneId()).isEqualTo("L01");
		assertThat(ticket.getLaneEntryTime()).isNotNull();

		mockMvc.perform(get("/api/lanes")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id=='L01')].vehicleCount").value(1))
			.andExpect(jsonPath("$[?(@.id=='L01')].reservedCount").value(0))
			.andExpect(jsonPath("$[?(@.id=='L01')].exitSignal").value("GREEN"));
	}

	@Test
	void enteringWrongLaneShouldBeMarkedAsMismatch() throws Exception {
		Lane firstLane = buildLane("L01", "L01", "1号车道");
		firstLane.setCapacity(1);
		Lane secondLane = buildLane("L02", "L02", "2号车道");
		secondLane.setCapacity(1);
		laneRepository.saveAll(List.of(firstLane, secondLane));
		String token = loginAndGetToken();

		postYardEntry(token, "沪A88888", "2026-04-20T08:00:00+08:00");
		postVehicleEntry(token, "L02", "沪A88888", "2026-04-20T08:01:00+08:00");

		DispatchTicket ticket = dispatchTicketRepository.findAllByOrderByYardEntryTimeDesc().getFirst();
		assertThat(ticket.getStatus()).isEqualTo("ENTERED_MISMATCH");
		assertThat(ticket.getAssignedLaneId()).isEqualTo("L01");
		assertThat(ticket.getActualLaneId()).isEqualTo("L02");
		assertThat(ticket.getNotes()).contains("未按屏显");

		Lane actualLane = laneRepository.findById("L02").orElseThrow();
		assertThat(actualLane.getVehicleCount()).isEqualTo(1);
	}

	@Test
	void manualSignalGreenShouldNotTakeOverAutomaticEntryDispatch() throws Exception {
		Lane firstLane = buildLane("L01", "L01", "1号车道");
		Lane secondLane = buildLane("L02", "L02", "2号车道");
		laneRepository.saveAll(List.of(firstLane, secondLane));
		String token = loginAndGetToken();

		postSignalOverride(token, "L02", "GREEN", "RED", "测试手动开放非自动车道入口灯");

		assertThat(operationsService.getDispatchBoard().activeEntryLaneId()).isEqualTo("L01");
		List<Lane> lanesAfterOverride = operationsService.getLanes();
		assertThat(lanesAfterOverride).filteredOn(lane -> "L01".equals(lane.getId()))
				.singleElement()
				.extracting(Lane::getEntrySignal)
				.isEqualTo("GREEN");
		assertThat(lanesAfterOverride).filteredOn(lane -> "L02".equals(lane.getId()))
				.singleElement()
				.extracting(Lane::getEntrySignal)
				.isEqualTo("GREEN");

		postYardEntry(token, "沪A55555", "2026-04-20T08:00:00+08:00");

		DispatchTicket ticket = dispatchTicketRepository.findAllByOrderByYardEntryTimeDesc().getFirst();
		assertThat(ticket.getAssignedLaneId()).isEqualTo("L01");
		assertThat(operationsService.getDispatchBoard().activeEntryLaneId()).isEqualTo("L01");
	}

	@Test
	void manualRedOnAutomaticEntryLaneShouldNotAdvanceAutomaticDispatch() throws Exception {
		Lane firstLane = buildLane("L01", "L01", "1号车道");
		Lane secondLane = buildLane("L02", "L02", "2号车道");
		laneRepository.saveAll(List.of(firstLane, secondLane));
		String token = loginAndGetToken();

		postSignalOverride(token, "L01", "RED", "RED", "测试手动关闭自动入口车道入口灯");

		assertThat(operationsService.getDispatchBoard().activeEntryLaneId()).isEqualTo("L01");
		assertThat(operationsService.getLanes()).filteredOn(lane -> "L01".equals(lane.getId()))
				.singleElement()
				.extracting(Lane::getEntrySignal)
				.isEqualTo("RED");

		postYardEntry(token, "沪A66666", "2026-04-20T08:00:00+08:00");

		DispatchTicket ticket = dispatchTicketRepository.findAllByOrderByYardEntryTimeDesc().getFirst();
		assertThat(ticket.getAssignedLaneId()).isEqualTo("L01");
		assertThat(operationsService.getDispatchBoard().activeEntryLaneId()).isEqualTo("L01");
	}

	@Test
	void manualEntryGreenShouldStillTurnRedWhenLaneIsFull() throws Exception {
		Lane firstLane = buildLane("L01", "L01", "1号车道");
		firstLane.setCapacity(1);
		firstLane.setVehicleCount(1);
		Lane secondLane = buildLane("L02", "L02", "2号车道");
		laneRepository.saveAll(List.of(firstLane, secondLane));
		String token = loginAndGetToken();

		mockMvc.perform(post("/api/signals/L01")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "laneId": "L01",
					  "entrySignal": "GREEN",
					  "exitSignal": "GREEN",
					  "mode": "MANUAL",
					  "reason": "测试满位保护"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entrySignal").value("RED"))
			.andExpect(jsonPath("$.exitSignal").value("GREEN"));

		assertThat(operationsService.getDispatchBoard().activeEntryLaneId()).isEqualTo("L02");
	}

	@Test
	void exitLaneShouldAdvanceOnlyAfterCurrentLaneIsFullyCleared() throws Exception {
		Lane firstLane = buildLane("L01", "L01", "1号车道");
		firstLane.setCapacity(2);
		Lane secondLane = buildLane("L02", "L02", "2号车道");
		secondLane.setCapacity(3);
		laneRepository.saveAll(List.of(firstLane, secondLane));
		String token = loginAndGetToken();

		postVehicleEntry(token, "L01", "沪A10001", "2026-04-20T08:00:00+08:00");
		postVehicleEntry(token, "L01", "沪A10002", "2026-04-20T08:02:00+08:00");
		postVehicleEntry(token, "L02", "沪A20001", "2026-04-20T08:04:00+08:00");

		mockMvc.perform(get("/api/lanes")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id=='L01')].exitSignal").value("GREEN"))
			.andExpect(jsonPath("$[?(@.id=='L02')].exitSignal").value("RED"));

		operationsService.applyPassCountDelta("L01", -1, OffsetDateTime.parse("2026-04-20T08:10:00+08:00"));

		mockMvc.perform(get("/api/lanes")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id=='L01')].vehicleCount").value(1))
			.andExpect(jsonPath("$[?(@.id=='L01')].exitSignal").value("GREEN"))
			.andExpect(jsonPath("$[?(@.id=='L02')].exitSignal").value("RED"));

		operationsService.applyPassCountDelta("L01", -1, OffsetDateTime.parse("2026-04-20T08:12:00+08:00"));

		mockMvc.perform(get("/api/lanes")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id=='L01')].vehicleCount").value(0))
			.andExpect(jsonPath("$[?(@.id=='L01')].exitSignal").value("RED"))
			.andExpect(jsonPath("$[?(@.id=='L02')].exitSignal").value("GREEN"));
	}

	@Test
	void exitLaneShouldHoldCurrentEntryLaneWhenNoLaneHasVehicles() throws Exception {
		Lane firstLane = buildLane("L01", "L01", "1号车道");
		Lane secondLane = buildLane("L02", "L02", "2号车道");
		laneRepository.saveAll(List.of(firstLane, secondLane));
		String token = loginAndGetToken();

		postVehicleEntry(token, "L01", "沪A10001", "2026-04-20T08:00:00+08:00");

		mockMvc.perform(get("/api/lanes")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id=='L01')].exitSignal").value("GREEN"));

		operationsService.applyPassCountDelta("L01", -1, OffsetDateTime.parse("2026-04-20T08:10:00+08:00"));

		assertThat(operationsService.getDispatchBoard().activeEntryLaneId()).isEqualTo("L01");
		assertThat(operationsService.getDispatchBoard().activeExitLaneId()).isEqualTo("L01");
		mockMvc.perform(get("/api/lanes")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id=='L01')].vehicleCount").value(0))
			.andExpect(jsonPath("$[?(@.id=='L01')].exitSignal").value("GREEN"))
			.andExpect(jsonPath("$[?(@.id=='L02')].exitSignal").value("RED"));
	}

	@Test
	void exitLaneShouldFollowFifoUntilCurrentEntryLaneAndThenHoldIt() throws Exception {
		Lane firstLane = buildLane("L01", "L01", "1号车道");
		firstLane.setCapacity(1);
		Lane secondLane = buildLane("L02", "L02", "2号车道");
		secondLane.setCapacity(1);
		Lane thirdLane = buildLane("L03", "L03", "3号车道");
		thirdLane.setCapacity(1);
		Lane fourthLane = buildLane("L04", "L04", "4号车道");
		fourthLane.setCapacity(3);
		laneRepository.saveAll(List.of(firstLane, secondLane, thirdLane, fourthLane));
		String token = loginAndGetToken();

		postVehicleEntry(token, "L01", "沪A10001", "2026-04-20T08:00:00+08:00");
		postVehicleEntry(token, "L02", "沪A20001", "2026-04-20T08:02:00+08:00");
		postVehicleEntry(token, "L03", "沪A30001", "2026-04-20T08:04:00+08:00");

		assertThat(operationsService.getDispatchBoard().activeEntryLaneId()).isEqualTo("L04");
		assertThat(operationsService.getDispatchBoard().activeExitLaneId()).isEqualTo("L01");

		operationsService.applyPassCountDelta("L01", -1, OffsetDateTime.parse("2026-04-20T08:10:00+08:00"));
		assertThat(operationsService.getDispatchBoard().activeExitLaneId()).isEqualTo("L02");

		operationsService.applyPassCountDelta("L02", -1, OffsetDateTime.parse("2026-04-20T08:12:00+08:00"));
		assertThat(operationsService.getDispatchBoard().activeExitLaneId()).isEqualTo("L03");

		operationsService.applyPassCountDelta("L03", -1, OffsetDateTime.parse("2026-04-20T08:14:00+08:00"));
		assertThat(operationsService.getDispatchBoard().activeEntryLaneId()).isEqualTo("L04");
		assertThat(operationsService.getDispatchBoard().activeExitLaneId()).isEqualTo("L04");

		mockMvc.perform(get("/api/lanes")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id=='L04')].vehicleCount").value(0))
			.andExpect(jsonPath("$[?(@.id=='L04')].entrySignal").value("GREEN"))
			.andExpect(jsonPath("$[?(@.id=='L04')].exitSignal").value("GREEN"));
	}

	@Test
	void screenLaneExitSimulationShouldExitByFirstInFirstOutWithoutPlate() throws Exception {
		Lane firstLane = buildLane("L01", "L01", "1号车道");
		firstLane.setCapacity(3);
		Lane secondLane = buildLane("L02", "L02", "2号车道");
		secondLane.setCapacity(3);
		laneRepository.saveAll(List.of(firstLane, secondLane));
		String token = loginAndGetToken();

		postVehicleEntry(token, "L01", "沪A10001", "2026-04-20T08:00:00+08:00");
		postVehicleEntry(token, "L01", "沪A10002", "2026-04-20T08:02:00+08:00");

		mockMvc.perform(post("/api/screen/simulate/lane-exit")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "laneId": "L01"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value("L01"))
			.andExpect(jsonPath("$.vehicleCount").value(1));

		assertThat(entryLogRepository.findAllByOrderByEntryTimeDesc().stream()
				.filter(log -> "沪A10001".equals(log.getPlate()))
				.findFirst()
				.orElseThrow()
				.getExitTime()).isNotNull();
		assertThat(entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc("L01"))
				.extracting(EntryLog::getPlate)
				.containsExactly("沪A10002");
	}

	@Test
	void screenGlobalExitSimulationShouldUseActiveExitLaneWithoutLaneId() throws Exception {
		Lane firstLane = buildLane("L01", "L01", "1号车道");
		firstLane.setCapacity(2);
		Lane secondLane = buildLane("L02", "L02", "2号车道");
		secondLane.setCapacity(3);
		laneRepository.saveAll(List.of(firstLane, secondLane));
		String token = loginAndGetToken();

		postVehicleEntry(token, "L01", "沪A10001", "2026-04-20T08:00:00+08:00");
		postVehicleEntry(token, "L01", "沪A10002", "2026-04-20T08:02:00+08:00");
		postVehicleEntry(token, "L02", "沪A20001", "2026-04-20T08:04:00+08:00");

		mockMvc.perform(post("/api/screen/simulate/global-exit")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value("L01"))
			.andExpect(jsonPath("$.vehicleCount").value(1));

		assertThat(entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc("L01"))
				.extracting(EntryLog::getPlate)
				.containsExactly("沪A10002");
		assertThat(entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc("L02"))
				.extracting(EntryLog::getPlate)
				.containsExactly("沪A20001");
	}

	@Test
	void repeatedLaneEntryForSameActivePlateShouldNotCreateSecondLog() throws Exception {
		laneRepository.save(buildLane("L01", "L01", "1号车道"));
		String token = loginAndGetToken();

		postVehicleEntry(token, "L01", "沪A787878", "2026-04-20T08:00:00+08:00");
		postVehicleEntry(token, "L01", "沪A787878", "2026-04-20T08:00:05+08:00");

		assertThat(entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc("L01"))
				.extracting(EntryLog::getPlate)
				.containsExactly("沪A787878");
		assertThat(laneRepository.findById("L01").orElseThrow().getVehicleCount()).isEqualTo(1);
	}

	@Test
	void screenLaneExitSimulationShouldCloseVisibleWrongLaneTicketFirst() throws Exception {
		Lane firstLane = buildLane("L01", "L01", "1号车道");
		firstLane.setCapacity(1);
		Lane secondLane = buildLane("L02", "L02", "2号车道");
		secondLane.setCapacity(3);
		laneRepository.saveAll(List.of(firstLane, secondLane));
		String token = loginAndGetToken();

		postYardEntry(token, "沪A00001", "2026-04-20T08:00:00+08:00");
		postYardEntry(token, "沪A676767", "2026-04-20T08:00:10+08:00");
		postVehicleEntry(token, "L01", "沪A676767", "2026-04-20T08:01:00+08:00");

		DispatchTicket wrongLaneTicket = dispatchTicketRepository.findAllByOrderByYardEntryTimeDesc().stream()
				.filter(ticket -> "沪A676767".equals(ticket.getPlate()))
				.findFirst()
				.orElseThrow();
		assertThat(wrongLaneTicket.getAssignedLaneId()).isEqualTo("L02");
		assertThat(wrongLaneTicket.getActualLaneId()).isEqualTo("L01");
		assertThat(wrongLaneTicket.getStatus()).isEqualTo("ENTERED_MISMATCH");

		mockMvc.perform(post("/api/screen/simulate/lane-exit")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "laneId": "L01"
					}
					"""))
			.andExpect(status().isOk());

		DispatchTicket closedTicket = dispatchTicketRepository.findById(wrongLaneTicket.getId()).orElseThrow();
		assertThat(closedTicket.getStatus()).isEqualTo("EXITED");
		assertThat(closedTicket.getExitTime()).isNotNull();
		assertThat(entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc("L01"))
				.extracting(EntryLog::getPlate)
				.doesNotContain("沪A676767");
	}

	@Test
	void dailyResetShouldClearLaneDataAndOpenFirstEntryLane() throws Exception {
		Lane firstLane = buildLane("L01", "L01", "1号车道");
		firstLane.setCapacity(2);
		Lane secondLane = buildLane("L02", "L02", "2号车道");
		secondLane.setCapacity(2);
		laneRepository.saveAll(List.of(firstLane, secondLane));
		String token = loginAndGetToken();

		postYardEntry(token, "沪A30001", "2026-04-20T08:00:00+08:00");
		postVehicleEntry(token, "L02", "沪A30001", "2026-04-20T08:01:00+08:00");
		postYardEntry(token, "沪A30002", "2026-04-20T08:02:00+08:00");

		mockMvc.perform(post("/api/dispatch/daily-reset")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entryDispatchEnabled").value(true))
			.andExpect(jsonPath("$.exitDispatchEnabled").value(true))
			.andExpect(jsonPath("$.activeEntryLaneId").value("L01"))
			.andExpect(jsonPath("$.activeExitLaneId").value("L01"));

		mockMvc.perform(get("/api/lanes")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id=='L01')].vehicleCount").value(0))
			.andExpect(jsonPath("$[?(@.id=='L02')].vehicleCount").value(0))
			.andExpect(jsonPath("$[?(@.id=='L01')].entrySignal").value("GREEN"))
			.andExpect(jsonPath("$[?(@.id=='L01')].exitSignal").value("GREEN"));

		assertThat(entryLogRepository.findAll()).isNotEmpty().allMatch(log -> log.getExitTime() != null);
		assertThat(dispatchTicketRepository.findAllByOrderByYardEntryTimeDesc())
				.isNotEmpty()
				.allMatch(ticket -> ticket.getClosedAt() != null)
				.anyMatch(ticket -> "ENTERED_MISMATCH".equals(ticket.getStatus()));

		mockMvc.perform(get("/api/screen/board")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.recentEntryLogs").isEmpty())
			.andExpect(jsonPath("$.guideAssignments").isEmpty())
			.andExpect(jsonPath("$.events").isEmpty());

		mockMvc.perform(get("/api/logs")
				.param("entryTimeFrom", "2026-04-20T00:00:00+08:00")
				.param("entryTimeTo", "2026-04-21T00:00:00+08:00")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.plate=='沪A30001')]").exists());

		mockMvc.perform(get("/api/screen/events")
				.param("includeHandled", "true")
				.param("occurredAtFrom", "2026-04-20T00:00:00+08:00")
				.param("occurredAtTo", "2026-04-21T00:00:00+08:00")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.type=='wrong_lane' && @.plate=='沪A30001')]").exists());
	}

	@Test
	void blacklistedVehicleEntryShouldStillCreateRealtimeLog() throws Exception {
		laneRepository.save(buildLane("L01", "L01", "1号车道"));
		blacklistRecordRepository.save(BlacklistRecord.builder()
				.id("BL-1001")
				.plate("沪A12345")
				.reason("重点关注车辆")
				.level("CRITICAL")
				.effectiveDate(now())
				.operator("ops")
				.active(true)
				.build());
		String token = loginAndGetToken();

		postVehicleEntry(token, "L01", "沪A12345", "2026-04-20T08:00:00+08:00");

		assertThat(entryLogRepository.findAll()).hasSize(1);
		assertThat(blacklistRecordRepository.findFirstByPlateIgnoreCaseAndActiveTrue("沪A12345")).isPresent();
	}

	@Test
	void expiredReservationShouldCloseProvisionalLogAndAllowLaterDirectEntry() throws Exception {
		laneRepository.save(buildLane("L02", "L02", "2号车道"));
		String token = loginAndGetToken();

		postYardEntry(token, "沪A92111", "2026-04-20T08:00:00+08:00");
		postYardEntry(token, "沪A80000", "2026-04-20T08:06:00+08:00");
		postVehicleEntry(token, "L02", "沪A92111", "2026-04-20T08:07:00+08:00");

		List<DispatchTicket> tickets = dispatchTicketRepository.findByPlateIgnoreCaseAndClosedAtIsNullOrderByYardEntryTimeDesc("沪A92111");
		assertThat(dispatchTicketRepository.findAllByOrderByYardEntryTimeDesc().stream()
				.filter(ticket -> "沪A92111".equals(ticket.getPlate()) && "EXPIRED".equals(ticket.getStatus())))
			.hasSize(1);
		assertThat(tickets)
			.singleElement()
			.extracting(DispatchTicket::getStatus, DispatchTicket::getActualLaneId)
			.containsExactly("DIRECT_ENTERED", "L02");

		List<EntryLog> logs = entryLogRepository.findAllByOrderByEntryTimeDesc().stream()
				.filter(log -> "沪A92111".equals(log.getPlate()))
				.toList();
		assertThat(logs).hasSize(2);
		assertThat(logs).filteredOn(log -> log.getExitTime() != null).hasSize(1);
		assertThat(logs).filteredOn(log -> log.getExitTime() == null)
				.singleElement()
				.extracting(EntryLog::getLaneId)
				.isEqualTo("L02");
	}

	@Test
	void screenBoardShouldKeepExpiredGuideAssignmentsInRecentGuideList() throws Exception {
		laneRepository.save(buildLane("L01", "L01", "1号车道"));
		String token = loginAndGetToken();

		postYardEntry(token, "沪A11111", "2026-04-20T08:00:00+08:00");
		postYardEntry(token, "沪A22222", "2026-04-20T08:02:00+08:00");

		mockMvc.perform(get("/api/screen/board")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.guideAssignments.length()").value(2))
			.andExpect(jsonPath("$.guideAssignments[0].plate").value("沪A22222"))
			.andExpect(jsonPath("$.guideAssignments[?(@.plate=='沪A11111' && @.status=='EXPIRED')]").exists());
	}

	@Test
	void vehicleAlreadyEnteredAnotherLaneShouldReturnConflictOnDuplicateEntry() throws Exception {
		Lane firstLane = buildLane("L01", "L01", "1号车道");
		Lane secondLane = buildLane("L02", "L02", "2号车道");
		laneRepository.saveAll(List.of(firstLane, secondLane));
		String token = loginAndGetToken();

		postVehicleEntry(token, "L01", "沪A55555", "2026-04-20T08:00:00+08:00");

		mockMvc.perform(post("/api/integration/vehicle-entries")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "laneId": "L02",
					  "plate": "沪A55555",
					  "vehicleType": "出租车",
					  "source": "lane-camera",
					  "entryTime": "2026-04-20T08:01:00+08:00"
					}
					"""))
			.andExpect(status().isConflict());

		assertThat(dispatchTicketRepository.findByPlateIgnoreCaseAndClosedAtIsNullOrderByYardEntryTimeDesc("沪A55555"))
				.singleElement()
				.extracting(DispatchTicket::getActualLaneId, DispatchTicket::getStatus)
				.containsExactly("L01", "DIRECT_ENTERED");
		assertThat(entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc("L02"))
				.extracting(EntryLog::getPlate)
				.doesNotContain("沪A55555");
	}

	@Test
	void laneCapacityUpdateShouldPersistToLaneSettings() throws Exception {
		laneRepository.save(buildLane("L01", "L01", "1号车道"));
		String token = loginAndGetToken();

		mockMvc.perform(put("/api/lanes/L01/capacity")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "capacity": 8
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.capacity").value(8));

		mockMvc.perform(get("/api/lanes")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id=='L01')].capacity").value(8));
	}

	@Test
	void runtimeStateShouldPreferRenderedTargetSignalsWhileDeviceFeedbackIsPending() {
		Lane lane = buildLane("L01", "L01", "1号车道");
		lane.setEntrySignal("GREEN");
		lane.setExitSignal("RED");

		laneRuntimeStateService.recordDeviceFeedback("L01", "RED", "RED", now(), "旧设备反馈");
		laneRuntimeStateService.recordRenderedState("L01", "GREEN", "RED", "已恢复自动控制", now());

		Lane applied = laneRuntimeStateService.applyRuntimeState(lane);
		assertThat(applied.getEntrySignal()).isEqualTo("GREEN");
		assertThat(applied.getExitSignal()).isEqualTo("RED");
		assertThat(applied.getLedStatus()).isEqualTo("PENDING");
	}

	private void postYardEntry(String token, String plate, String capturedAt) throws Exception {
		mockMvc.perform(post("/api/integration/yard-entries")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "plate": "%s",
					  "vehicleType": "出租车",
					  "source": "yard-camera",
					  "capturedAt": "%s"
					}
					""".formatted(plate, capturedAt)))
			.andExpect(status().isOk());
	}

	private void postVehicleEntry(String token, String laneId, String plate, String entryTime) throws Exception {
		mockMvc.perform(post("/api/integration/vehicle-entries")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "laneId": "%s",
					  "plate": "%s",
					  "vehicleType": "出租车",
					  "source": "lane-camera",
					  "entryTime": "%s"
					}
					""".formatted(laneId, plate, entryTime)))
			.andExpect(status().isOk());
	}

	private void postSignalOverride(String token, String laneId, String entrySignal, String exitSignal, String reason) throws Exception {
		mockMvc.perform(post("/api/signals/" + laneId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "laneId": "%s",
					  "entrySignal": "%s",
					  "exitSignal": "%s",
					  "mode": "MANUAL",
					  "reason": "%s"
					}
					""".formatted(laneId, entrySignal, exitSignal, reason)))
			.andExpect(status().isOk());
	}

	private String loginAndGetToken() throws Exception {
		String content = mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "username": "bootstrap-admin",
					  "password": "Bootstrap#2026!"
					}
					"""))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		JsonNode node = objectMapper.readTree(content);
		return node.get("token").asText();
	}

	private Lane buildLane(String id, String code, String name) {
		return Lane.builder()
				.id(id)
				.code(code)
				.name(name)
				.zone("A区")
				.type("ENTRY")
				.status("OPEN")
				.mode("AUTO")
				.capacity(3)
				.vehicleCount(0)
				.currentPlate(null)
				.lastActionAt(now())
				.entrySignal("GREEN")
				.exitSignal("RED")
				.ledMessage("初始化")
				.ledStatus("SYNCED")
				.priority(false)
				.sensorStatus("ONLINE")
				.lastSensorAt(now())
				.lastEntryPlate(null)
				.lastEntryAt(null)
				.build();
	}

	private OffsetDateTime now() {
		return OffsetDateTime.now(ZoneOffset.ofHours(8));
	}
}
