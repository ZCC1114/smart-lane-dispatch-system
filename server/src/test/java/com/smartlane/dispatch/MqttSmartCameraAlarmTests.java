package com.smartlane.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import com.smartlane.dispatch.device.MqttDeviceGateway;
import com.smartlane.dispatch.dto.YardEntryPayload;
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
		"app.device.smart-camera.enabled=true",
		"app.device.lanes[0].lane-id=L01",
		"app.device.lanes[0].camera-dev-id=18030023526b",
		"app.device.lanes[1].lane-id=L02",
		"app.device.smart-camera.active-entry-camera-dev-id=18030023526b",
		"app.dispatch.entry-enabled-default=true",
		"app.dispatch.exit-enabled-default=true",
		"app.dispatch.assignment-reserve-minutes=5"
})
class MqttSmartCameraAlarmTests {

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
	private MqttDeviceGateway mqttDeviceGateway;

	@Autowired
	private OperationsService operationsService;

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
	void smartCameraDevAlarmType1ShouldRegisterLaneOneEntry() {
		laneRepository.save(buildLane("L01", "L01", "1号车道"));

		ingestSmartCameraAlarm("1", "苏BFE9999", "in", "2026-05-06 16:58:53");

		assertThat(laneRepository.findById("L01").orElseThrow().getVehicleCount()).isEqualTo(1);
		List<EntryLog> logs = entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc("L01");
		assertThat(logs).hasSize(1);
		assertThat(logs.getFirst().getPlate()).isEqualTo("苏BFE9999");
		assertThat(logs.getFirst().getSource()).isEqualTo("SMART_CAMERA");
	}

	@Test
	void smartCameraDevIdMatchingShouldIgnoreCase() {
		laneRepository.save(buildLane("L01", "L01", "1号车道"));

		ingestSmartCameraAlarm("18030023526B", "1", "苏BFE6666", "in", "2026-05-06 16:59:53");

		assertThat(laneRepository.findById("L01").orElseThrow().getVehicleCount()).isEqualTo(1);
		assertThat(entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc("L01"))
				.extracting(EntryLog::getPlate)
				.containsExactly("苏BFE6666");
	}

	@Test
	void smartCameraDevAlarmType49409ShouldRegisterLaneOneEntry() {
		laneRepository.save(buildLane("L01", "L01", "1号车道"));

		ingestSmartCameraAlarm("49409", "苏BFE8888", "in", "2026-05-06 17:02:10");

		assertThat(laneRepository.findById("L01").orElseThrow().getVehicleCount()).isEqualTo(1);
		assertThat(entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc("L01"))
				.extracting(EntryLog::getPlate)
				.containsExactly("苏BFE8888");
	}

	@Test
	void smartCameraOtherAlarmTypesShouldBeIgnoredForLaneEntry() {
		laneRepository.save(buildLane("L01", "L01", "1号车道"));

		ingestSmartCameraAlarm("49406", "苏BFE7777", "in", "2026-05-06 17:05:30");

		assertThat(laneRepository.findById("L01").orElseThrow().getVehicleCount()).isZero();
		assertThat(entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc("L01")).isEmpty();
	}

	@Test
	void activeEntrySmartCameraShouldFollowCurrentOpenLane() {
		Lane laneOne = buildLane("L01", "L01", "1号车道");
		laneOne.setCapacity(1);
		Lane laneTwo = buildLane("L02", "L02", "2号车道");
		laneTwo.setCapacity(2);
		laneRepository.saveAll(List.of(laneOne, laneTwo));

		operationsService.registerYardEntry(new YardEntryPayload("苏BFE1000", "出租车", "SMART_CAMERA", at("2026-05-06T17:00:00+08:00")));

		ingestSmartCameraAlarm("1", "苏BFE2000", "in", "2026-05-06 17:00:30");

		assertThat(operationsService.getDispatchBoard().activeEntryLaneId()).isEqualTo("L02");
		assertThat(laneRepository.findById("L01").orElseThrow().getVehicleCount()).isZero();
		assertThat(laneRepository.findById("L02").orElseThrow().getVehicleCount()).isEqualTo(1);
		assertThat(entryLogRepository.findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc("L02"))
				.extracting(EntryLog::getPlate)
				.containsExactly("苏BFE2000");
	}

	private void ingestSmartCameraAlarm(String alarmType, String plate, String inOut, String alarmTime) {
		ingestSmartCameraAlarm("18030023526b", alarmType, plate, inOut, alarmTime);
	}

	private void ingestSmartCameraAlarm(String devId, String alarmType, String plate, String inOut, String alarmTime) {
		String payload = """
				{
				  "cmd": "devAlarm",
				  "msgId": "smart-camera-test",
				  "devId": "%s",
				  "utcTs": 1778057934,
				  "content": {
				    "alarmType": %s,
				    "plateNum": "%s",
				    "alarmTime": "%s",
				    "inOut": "%s"
				  }
				}
				""".formatted(devId, alarmType, plate, alarmTime, inOut);
		ReflectionTestUtils.invokeMethod(
				mqttDeviceGateway,
				"handleRawMqttMessage",
				"/device/%s/update".formatted(devId),
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
