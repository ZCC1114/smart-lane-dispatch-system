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
		"app.device.dido.exit-trigger-enabled=false",
		"app.device.lanes[0].lane-id=L01",
		"app.device.lanes[0].dido-device-id=DIDO-EXIT-01",
		"app.device.lanes[0].exit-trigger-input-key=B01",
		"app.dispatch.entry-enabled-default=true",
		"app.dispatch.exit-enabled-default=true",
		"app.dispatch.assignment-reserve-minutes=5"
})
class MqttDidoExitTriggerDisabledTests {

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
	void didoExitInputShouldNotCloseVehiclesWhenExitTriggerIsDisabled() {
		laneRepository.save(buildLane("L01", "L01", "1号车道"));
		operationsService.registerVehicleEntryFromDevice("L01", "苏B9T107", at("2026-05-22T13:48:44+08:00"), "出租车", "SMART_CAMERA");

		ingestDidoStatus(0);
		ingestDidoStatus(1);

		assertThat(laneRepository.findById("L01").orElseThrow().getVehicleCount()).isEqualTo(1);
		assertThat(entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc("L01"))
				.extracting(EntryLog::getPlate)
				.containsExactly("苏B9T107");
		assertThat(entryLogRepository.findAllByOrderByEntryTimeDesc().getFirst().getExitTime()).isNull();
	}

	private void ingestDidoStatus(int inputState) {
		String payload = """
				{
				  "ID": "DIDO-EXIT-01",
				  "A01": 1,
				  "B01": %d
				}
				""".formatted(inputState);
		ReflectionTestUtils.invokeMethod(
				mqttDeviceGateway,
				"handleRawMqttMessage",
				"/device/DIDO-EXIT-01/update",
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

	private OffsetDateTime at(String value) {
		return OffsetDateTime.parse(value);
	}
}
