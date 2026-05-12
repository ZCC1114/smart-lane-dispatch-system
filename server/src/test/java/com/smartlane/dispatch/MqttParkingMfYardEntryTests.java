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
import com.smartlane.dispatch.entity.DispatchTicket;
import com.smartlane.dispatch.entity.Lane;
import com.smartlane.dispatch.repository.DispatchConfigRepository;
import com.smartlane.dispatch.repository.DispatchTicketRepository;
import com.smartlane.dispatch.repository.EntryLogRepository;
import com.smartlane.dispatch.repository.LaneRepository;
import com.smartlane.dispatch.service.LaneRuntimeStateService;

@SpringBootTest(properties = {
		"app.device.gateway=mqtt",
		"app.device.mqtt.enabled=false",
		"app.device.parking-mf.enabled=true",
		"app.device.parking-mf.yard-entry-sn=00E02721A3A7",
		"app.device.parking-mf.yard-entry-device-no=22K5000202407828",
		"app.device.smart-camera.enabled=false",
		"app.device.dido.enabled=false",
		"app.dispatch.entry-enabled-default=true",
		"app.dispatch.exit-enabled-default=true",
		"app.dispatch.assignment-reserve-minutes=5"
})
class MqttParkingMfYardEntryTests {

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
	void parkingMfPlateResultFromYardEntryCameraShouldReserveLaneWithoutCountingVehicleInLane() {
		Lane firstLane = buildLane("L01", "L01", "1号车道");
		firstLane.setCapacity(1);
		Lane secondLane = buildLane("L02", "L02", "2号车道");
		secondLane.setCapacity(2);
		laneRepository.saveAll(List.of(firstLane, secondLane));

		String payload = """
				{
				  "cmd": "plateResult",
				  "data": {
				    "carImg": "https://img.bolinkpay.com/picture/00E02721A3A7/22K5000202407828/20260512/14/20260512_145336_000.jpg",
				    "confidence": 27,
				    "deviceNo": "22K5000202407828",
				    "groupId": "9QHZNII",
				    "parkingTime": "2026-05-12 14:53:36",
				    "plateColor": "BLUE",
				    "plateNo": "苏B3R89T",
				    "realTime": true,
				    "uploadTime": 1778568817063
				  },
				  "msgId": "C_6a02ce717f3eb46e4e2dda0f",
				  "sn": "00E02721A3A7",
				  "timestamp": 1778568817063,
				  "timezone": "Asia/Shanghai"
				}
				""";

		ReflectionTestUtils.invokeMethod(
				mqttDeviceGateway,
				"handleRawMqttMessage",
				"/00E02721A3A7/mf/up",
				payload.getBytes(StandardCharsets.UTF_8));

		DispatchTicket ticket = dispatchTicketRepository.findAllByOrderByYardEntryTimeDesc().getFirst();
		assertThat(ticket.getPlate()).isEqualTo("苏B3R89T");
		assertThat(ticket.getStatus()).isEqualTo("ASSIGNED");
		assertThat(ticket.getAssignedLaneId()).isEqualTo("L01");
		assertThat(laneRepository.findById("L01").orElseThrow().getVehicleCount()).isZero();
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
