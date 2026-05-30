package com.smartlane.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

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
		"app.device.lanes[0].lane-id=L08",
		"app.device.lanes[0].entry-dido-device-id=DIDO-ENTRY-01",
		"app.device.lanes[0].entry-green-relay=A08",
		"app.device.lanes[0].exit-dido-device-id=DIDO-EXIT-01",
		"app.device.lanes[0].exit-green-relay=A08",
		"app.device.lanes[0].exit-trigger-input-key=B08",
		"app.dispatch.entry-enabled-default=true",
		"app.dispatch.exit-enabled-default=true",
		"app.dispatch.assignment-reserve-minutes=5"
})
class MqttSplitDidoDeviceTests {

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
	void exitTriggerShouldOnlyBeHandledFromExitDidoDevice() {
		laneRepository.save(buildLane("L08", "L08", "8号车道"));
		operationsService.registerVehicleEntryFromDevice("L08", "苏A11111", at("2026-05-06T22:30:00+08:00"), "出租车", "SMART_CAMERA");
		openExitSignal("L08");

		ingestDidoStatus("DIDO-ENTRY-01", 0);
		ingestDidoStatus("DIDO-ENTRY-01", 1);

		assertThat(laneRepository.findById("L08").orElseThrow().getVehicleCount()).isEqualTo(1);
		assertThat(entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc("L08"))
				.extracting(EntryLog::getPlate)
				.containsExactly("苏A11111");

		ingestDidoStatus("DIDO-EXIT-01", 0);
		ingestDidoStatus("DIDO-EXIT-01", 1);

		assertThat(laneRepository.findById("L08").orElseThrow().getVehicleCount()).isZero();
		assertThat(entryLogRepository.findAllByOrderByEntryTimeDesc().stream()
				.filter(log -> "苏A11111".equals(log.getPlate()))
				.findFirst()
				.orElseThrow()
				.getExitTime()).isNotNull();
	}

	@Test
	void didoSignalMismatchShouldClearCachedSyncStateForRepublish() {
		laneRepository.save(buildLane("L08", "L08", "8号车道"));
		Lane target = operationsService.getLanes().stream()
				.filter(lane -> "L08".equals(lane.getId()))
				.findFirst()
				.orElseThrow();
		String oppositeRelayValue = "GREEN".equals(target.getEntrySignal()) ? "100000" : "110000";
		@SuppressWarnings("unchecked")
		Map<String, String> syncStates = (Map<String, String>) ReflectionTestUtils.getField(
				mqttDeviceGateway,
				"lastDidoDeviceSyncStates");
		assertThat(syncStates).isNotNull();
		syncStates.put("DIDO-ENTRY-01", "cached");

		ingestDidoStatus("DIDO-ENTRY-01", oppositeRelayValue, 0);

		assertThat(syncStates).doesNotContainKey("DIDO-ENTRY-01");
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

	private void ingestDidoStatus(String deviceId, int inputState) {
		ingestDidoStatus(deviceId, "110000", inputState);
	}

	private void ingestDidoStatus(String deviceId, String relayValue, int inputState) {
		String payload = """
				{
				  "ID": "%s",
				  "A08": %s,
				  "B08": %d
				}
				""".formatted(deviceId, relayValue, inputState);
		ReflectionTestUtils.invokeMethod(
				mqttDeviceGateway,
				"handleRawMqttMessage",
				"/device/" + deviceId + "/update",
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

	private OffsetDateTime at(String value) {
		return OffsetDateTime.parse(value);
	}

	private OffsetDateTime now() {
		return OffsetDateTime.now(ZoneOffset.ofHours(8));
	}
}
