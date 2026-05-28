package com.smartlane.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartlane.dispatch.device.DeviceGatewayProperties;
import com.smartlane.dispatch.device.MqttDeviceGateway;
import com.smartlane.dispatch.device.SimpleMqttClient;
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
		"app.device.parking-mf.yard-entry-device-no=09K2900202441623",
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
	private DeviceGatewayProperties deviceGatewayProperties;

	@Autowired
	private MqttDeviceGateway mqttDeviceGateway;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void setUp() {
		entryLogRepository.deleteAll();
		dispatchTicketRepository.deleteAll();
		dispatchConfigRepository.deleteAll();
		laneRepository.deleteAll();
		laneRuntimeStateService.clearAll();
		mqttDeviceGateway.clearSyncState();
		ReflectionTestUtils.setField(mqttDeviceGateway, "mqttClient", null);
		deviceGatewayProperties.getParkingMf().setYardEntrySn("00E02721A3A7");
		deviceGatewayProperties.getParkingMf().setYardEntryGroupId(null);
		deviceGatewayProperties.getParkingMf().setYardEntryDeviceNo("09K2900202441623");
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
				    "carImg": "https://img.bolinkpay.com/picture/00E02721A3A7/09K2900202441623/20260512/14/20260512_145336_000.jpg",
				    "confidence": 27,
				    "deviceNo": "09K2900202441623",
				    "groupId": "9QHZNII",
				    "parkingTime": "2026-05-12 14:53:36",
				    "plateColor": "BLUE",
				    "plateNo": "苏B3T530",
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
		assertThat(ticket.getPlate()).isEqualTo("苏B3T530");
		assertThat(ticket.getStatus()).isEqualTo("ASSIGNED");
		assertThat(ticket.getAssignedLaneId()).isEqualTo("L01");
		assertThat(laneRepository.findById("L01").orElseThrow().getVehicleCount()).isZero();
		assertThat(entryLogRepository.findAllByOrderByEntryTimeDesc())
				.singleElement()
				.extracting(log -> log.getPlate(), log -> log.getLaneId(), log -> log.getExitTime())
				.containsExactly("苏B3T530", "L01", null);
	}

	@Test
	void parkingMfPlateResultFromYardEntryCameraShouldSendLedControlWithPlateOnly() throws Exception {
		laneRepository.save(buildLane("L01", "L01", "1号车道"));
		SimpleMqttClient client = mock(SimpleMqttClient.class);
		when(client.isConnected()).thenReturn(true);
		ReflectionTestUtils.setField(mqttDeviceGateway, "mqttClient", client);

		String payload = """
				{
				  "cmd": "plateResult",
				  "data": {
				    "carImg": "https://img.bolinkpay.com/picture/00E02721A3A7/09K2900202441623/20260528/19/20260528_195323_000.jpg",
				    "confidence": 27,
				    "deviceNo": "09K2900202441623",
				    "groupId": "9QHZNII",
				    "parkingTime": "2026-05-28 19:53:23",
				    "plateColor": "BLUE",
				    "plateNo": "苏B6T728",
				    "realTime": true,
				    "uploadTime": 1779969203650
				  },
				  "msgId": "C_6a182cb37f3e754c05842bc8",
				  "sn": "00E02721A3A7",
				  "timestamp": 1779969203650,
				  "timezone": "Asia/Shanghai"
				}
				""";

		ReflectionTestUtils.invokeMethod(
				mqttDeviceGateway,
				"handleRawMqttMessage",
				"/00E02721A3A7/mf/up",
				payload.getBytes(StandardCharsets.UTF_8));

		ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
		verify(client).publish(topicCaptor.capture(), payloadCaptor.capture());

		assertThat(topicCaptor.getValue()).isEqualTo("/00E02721A3A7/mf/down");
		JsonNode downlink = objectMapper.readTree(payloadCaptor.getValue());
		assertThat(downlink.path("cmd").asText()).isEqualTo("ledControl");
		assertThat(downlink.path("msgId").asText()).startsWith("ledControl-");
		assertThat(downlink.path("timestamp").asLong()).isGreaterThan(0L);
		assertThat(downlink.has("sn")).isFalse();
		assertThat(downlink.has("timezone")).isFalse();

		JsonNode data = downlink.path("data");
		assertThat(data.path("groupId").asText()).isEqualTo("9QHZNII");
		assertThat(data.path("voice").asText()).isEqualTo("苏B6T728");
		assertThat(data.has("qrCode")).isFalse();
		assertThat(data.path("show").isArray()).isTrue();
		assertThat(data.path("show")).hasSize(1);
		assertThat(data.path("show").get(0).path("text").asText()).isEqualTo("苏B6T728");
	}

	@Test
	void parkingMfPlateResultResponseShouldEchoPlateData() throws Exception {
		JsonNode sourceData = objectMapper.readTree("""
				{
				  "carBrand": "",
				  "carImg": "https://img.bolinkpay.com/picture/00E02721A3A7/09K2900202441623/20260528/10/20260528_100351_000.jpg",
				  "confidence": 27,
				  "deviceNo": "09K2900202441623",
				  "groupId": "9QHZNII",
				  "parkingTime": "2026-05-28 10:03:51",
				  "plateColor": "BLUE",
				  "plateNo": "苏B0T908",
				  "realTime": true,
				  "state": "1",
				  "uploadTime": 1779933831610
				}
				""");

		ObjectNode payload = ReflectionTestUtils.invokeMethod(
				mqttDeviceGateway,
				"buildParkingMfResponsePayload",
				"00E02721A3A7",
				"plateResultResp",
				"C_6a17a2877f3e754c05841885",
				sourceData);

		assertThat(payload.path("cmd").asText()).isEqualTo("plateResultResp");
		assertThat(payload.path("msgId").asText()).isEqualTo("C_6a17a2877f3e754c05841885");
		assertThat(payload.path("sn").asText()).isEqualTo("00E02721A3A7");
		assertThat(payload.path("timezone").asText()).isEqualTo("Asia/Shanghai");
		assertThat(payload.path("data").path("deviceNo").asText()).isEqualTo("09K2900202441623");
		assertThat(payload.path("data").path("groupId").asText()).isEqualTo("9QHZNII");
		assertThat(payload.path("data").path("plateNo").asText()).isEqualTo("苏B0T908");
		assertThat(payload.path("data").path("plateColor").asText()).isEqualTo("BLUE");
		assertThat(payload.path("data").path("carImg").asText()).startsWith("https://img.bolinkpay.com/");
		assertThat(payload.path("data").path("parkingTime").asText()).isEqualTo("2026-05-28 10:03:51");
		assertThat(payload.path("data").path("confidence").asInt()).isEqualTo(27);
		assertThat(payload.path("data").path("carBrand").asText()).isEmpty();
		assertThat(payload.path("data").path("realTime").asBoolean()).isTrue();
		assertThat(payload.path("data").path("state").asText()).isEqualTo("1");
		assertThat(payload.path("data").path("uploadTime").asLong()).isEqualTo(1779933831610L);
	}

	@Test
	void parkingMfBluePlateWithoutTaxiPatternShouldBeDroppedBeforeAssignment() {
		laneRepository.save(buildLane("L01", "L01", "1号车道"));

		String payload = """
				{
				  "cmd": "plateResult",
				  "data": {
				    "confidence": 27,
				    "deviceNo": "09K2900202441623",
				    "groupId": "9QHZNII",
				    "parkingTime": "2026-05-22 18:56:41",
				    "plateColor": "BLUE",
				    "plateNo": "苏B3R89T",
				    "realTime": true,
				    "uploadTime": 1779447401000
				  },
				  "msgId": "C_6a0fdrop",
				  "sn": "00E02721A3A7",
				  "timestamp": 1779447401000,
				  "timezone": "Asia/Shanghai"
				}
				""";

		ReflectionTestUtils.invokeMethod(
				mqttDeviceGateway,
				"handleRawMqttMessage",
				"/00E02721A3A7/mf/up",
				payload.getBytes(StandardCharsets.UTF_8));

		assertThat(dispatchTicketRepository.findAll()).isEmpty();
		assertThat(entryLogRepository.findAll()).isEmpty();
		assertThat(laneRepository.findById("L01").orElseThrow().getVehicleCount()).isZero();
	}

	@Test
	void parkingMfGreenPlateShouldBypassBlueTaxiPattern() {
		laneRepository.save(buildLane("L01", "L01", "1号车道"));

		String payload = """
				{
				  "cmd": "plateResult",
				  "data": {
				    "confidence": 27,
				    "deviceNo": "09K2900202441623",
				    "groupId": "9QHZNII",
				    "parkingTime": "2026-05-22 18:56:41",
				    "plateColor": "GREEN",
				    "plateNo": "苏BD12345",
				    "realTime": true,
				    "uploadTime": 1779447401000
				  },
				  "msgId": "C_6a0fgreen",
				  "sn": "00E02721A3A7",
				  "timestamp": 1779447401000,
				  "timezone": "Asia/Shanghai"
				}
				""";

		ReflectionTestUtils.invokeMethod(
				mqttDeviceGateway,
				"handleRawMqttMessage",
				"/00E02721A3A7/mf/up",
				payload.getBytes(StandardCharsets.UTF_8));

		DispatchTicket ticket = dispatchTicketRepository.findAllByOrderByYardEntryTimeDesc().getFirst();
		assertThat(ticket.getPlate()).isEqualTo("苏BD12345");
		assertThat(ticket.getStatus()).isEqualTo("ASSIGNED");
		assertThat(ticket.getAssignedLaneId()).isEqualTo("L01");
		assertThat(entryLogRepository.findAllByOrderByEntryTimeDesc())
				.singleElement()
				.extracting(log -> log.getPlate(), log -> log.getLaneId(), log -> log.getExitTime())
				.containsExactly("苏BD12345", "L01", null);
	}

	@Test
	void parkingMfPlateResultShouldStillUseYardEntryWhenSnMatchesButDeviceNoIsStale() {
		deviceGatewayProperties.getParkingMf().setYardEntryGroupId("9QHZNII");
		deviceGatewayProperties.getParkingMf().setYardEntryDeviceNo("22K5000202407828");
		laneRepository.save(buildLane("L01", "L01", "1号车道"));

		String payload = """
				{
				  "cmd": "plateResult",
				  "data": {
				    "confidence": 27,
				    "deviceNo": "09K2900202441623",
				    "groupId": "9QHZNII",
				    "parkingTime": "2026-05-22 18:56:41",
				    "plateColor": "BLUE",
				    "plateNo": "苏B5T008",
				    "realTime": true,
				    "uploadTime": 1779447401000
				  },
				  "msgId": "C_6a0fdemo",
				  "sn": "00E02721A3A7",
				  "timestamp": 1779447401000,
				  "timezone": "Asia/Shanghai"
				}
				""";

		ReflectionTestUtils.invokeMethod(
				mqttDeviceGateway,
				"handleRawMqttMessage",
				"/00E02721A3A7/mf/up",
				payload.getBytes(StandardCharsets.UTF_8));

		DispatchTicket ticket = dispatchTicketRepository.findAllByOrderByYardEntryTimeDesc().getFirst();
		assertThat(ticket.getPlate()).isEqualTo("苏B5T008");
		assertThat(ticket.getSource()).isEqualTo("ALPR_YARD");
		assertThat(ticket.getStatus()).isEqualTo("ASSIGNED");
		assertThat(ticket.getAssignedLaneId()).isEqualTo("L01");
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
