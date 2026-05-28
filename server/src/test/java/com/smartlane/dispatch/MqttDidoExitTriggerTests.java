package com.smartlane.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import com.smartlane.dispatch.device.MqttDeviceGateway;
import com.smartlane.dispatch.dto.SignalOverrideRequest;
import com.smartlane.dispatch.entity.EntryLog;
import com.smartlane.dispatch.entity.Lane;
import com.smartlane.dispatch.repository.DispatchConfigRepository;
import com.smartlane.dispatch.repository.DispatchTicketRepository;
import com.smartlane.dispatch.repository.EntryLogRepository;
import com.smartlane.dispatch.repository.LaneRepository;
import com.smartlane.dispatch.service.LaneRuntimeStateService;
import com.smartlane.dispatch.service.OperationsService;

@SpringBootTest(properties = {
		"app.device.gateway=mqtt",
		"app.device.mqtt.enabled=false",
		"app.device.dido.enabled=true",
		"app.device.dido.exit-trigger-enabled=true",
		"app.device.lanes[0].lane-id=L08",
		"app.device.lanes[0].dido-device-id=DIDO-01",
		"app.device.lanes[0].exit-trigger-input-key=B08",
		"app.dispatch.entry-enabled-default=true",
		"app.dispatch.exit-enabled-default=true",
		"app.dispatch.assignment-reserve-minutes=5"
})
class MqttDidoExitTriggerTests {

	@Autowired
	private LaneRepository laneRepository;

	@Autowired
	private EntryLogRepository entryLogRepository;

	@Autowired
	private DispatchTicketRepository dispatchTicketRepository;

	@Autowired
	private DispatchConfigRepository dispatchConfigRepository;

	@Autowired
	private LaneRuntimeStateService laneRuntimeStateService;

	@Autowired
	private OperationsService operationsService;

	@Autowired
	private MqttDeviceGateway mqttDeviceGateway;

	@BeforeEach
	void setUp() {
		entryLogRepository.deleteAll();
		dispatchTicketRepository.deleteAll();
		dispatchConfigRepository.deleteAll();
		laneRepository.deleteAll();
		laneRuntimeStateService.clearAll();
		mqttDeviceGateway.clearSyncState();
	}

	@Test
	void didoExitTriggerShouldCloseOldestVehicleOnRisingEdgeOnly() {
		laneRepository.save(buildLane("L08", "L08", "8号车道"));
		operationsService.registerVehicleEntryFromDevice("L08", "苏A11111", at("2026-05-06T22:30:00+08:00"), "出租车", "SMART_CAMERA");
		operationsService.registerVehicleEntryFromDevice("L08", "苏A22222", at("2026-05-06T22:31:00+08:00"), "出租车", "SMART_CAMERA");
		openExitSignal("L08");

		ingestDidoStatus(0, "2026-05-06T22:36:40+08:00");
		ingestDidoStatus(1, "2026-05-06T22:36:49+08:00");
		ingestDidoStatus(0, "2026-05-06T22:37:25+08:00");

		assertThat(laneRepository.findById("L08").orElseThrow().getVehicleCount()).isEqualTo(1);
		assertThat(entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc("L08"))
				.extracting(EntryLog::getPlate)
				.containsExactly("苏A22222");
		assertThat(entryLogRepository.findAllByOrderByEntryTimeDesc().stream()
				.filter(log -> "苏A11111".equals(log.getPlate()))
				.findFirst()
				.orElseThrow()
				.getExitTime()).isNotNull();
	}

	@Test
	void didoInitialHighStateShouldNotBeCountedAsExit() {
		laneRepository.save(buildLane("L08", "L08", "8号车道"));
		operationsService.registerVehicleEntryFromDevice("L08", "苏A33333", at("2026-05-06T22:30:00+08:00"), "出租车", "SMART_CAMERA");
		openExitSignal("L08");

		ingestDidoStatus(1, "2026-05-06T22:36:49+08:00");

		assertThat(laneRepository.findById("L08").orElseThrow().getVehicleCount()).isEqualTo(1);
		assertThat(entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc("L08"))
				.extracting(EntryLog::getPlate)
				.containsExactly("苏A33333");
	}

	private void openExitSignal(String laneId) {
		String entrySignal = operationsService.getLanes().stream()
				.filter(lane -> laneId.equals(lane.getId()))
				.findFirst()
				.map(Lane::getEntrySignal)
				.orElse("OFFLINE");
		operationsService.overrideSignal(
				laneId,
				new SignalOverrideRequest(laneId, entrySignal, "GREEN", null, "测试切换出口放行游标"));
	}

	private void ingestDidoStatus(int inputState, String observedAt) {
		String payload = """
				{
				  "ID": "DIDO-01",
				  "A01": 1,
				  "A08": 0,
				  "B08": %d,
				  "A96": 1,
				  "A97": 1,
				  "A98": 1,
				  "C95": "正常运行",
				  "C98": "记忆关闭",
				  "T01": 35,
				  "utcTs": "%s"
				}
				""".formatted(inputState, observedAt);
		ReflectionTestUtils.invokeMethod(
				mqttDeviceGateway,
				"handleRawMqttMessage",
				"/device/DIDO-01/update",
				payload.getBytes(StandardCharsets.UTF_8));
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
				.entrySignal("RED")
				.exitSignal("GREEN")
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

	private OffsetDateTime at(String value) {
		return OffsetDateTime.parse(value);
	}
}
