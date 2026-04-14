package com.smartlane.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.smartlane.dispatch.entity.Lane;
import com.smartlane.dispatch.repository.AlertEventRepository;
import com.smartlane.dispatch.repository.BlacklistRecordRepository;
import com.smartlane.dispatch.repository.EntryLogRepository;
import com.smartlane.dispatch.repository.LaneRepository;

@SpringBootTest(properties = {
		"app.bootstrap-admin.enabled=true",
		"app.bootstrap-admin.username=bootstrap-admin",
		"app.bootstrap-admin.password=Bootstrap#2026!",
		"app.bootstrap-admin.display-name=系统引导管理员",
		"app.bootstrap-admin.station=总控中心"
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
	private BlacklistRecordRepository blacklistRecordRepository;

	@Autowired
	private AlertEventRepository alertEventRepository;

	@BeforeEach
	void setUp() {
		alertEventRepository.deleteAll();
		entryLogRepository.deleteAll();
		blacklistRecordRepository.deleteAll();
		laneRepository.deleteAll();
	}

	@Test
	void vehicleEntryShouldCreateRealtimeLogAndBlacklistAlert() throws Exception {
		laneRepository.save(buildLane("L01", "A01", "入口一号道"));
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

		mockMvc.perform(post("/api/integration/vehicle-entries")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "laneId": "L01",
					  "plate": "沪A12345",
					  "vehicleType": "社会车辆",
					  "source": "alpr",
					  "entryTime": "2026-04-13T08:30:00+08:00"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.plate").value("沪A12345"))
			.andExpect(jsonPath("$.source").value("ALPR"));

		assertThat(entryLogRepository.findAll()).hasSize(1);
		assertThat(alertEventRepository.findAllByOrderByCreatedAtDesc())
				.anySatisfy(alert -> {
					assertThat(alert.getType()).isEqualTo("BLACKLIST_HIT");
					assertThat(alert.getPlate()).isEqualTo("沪A12345");
				});
	}

	@Test
	void autoDispatchShouldRespectFifoAndPriorityLane() throws Exception {
		laneRepository.saveAll(List.of(
				buildLane("L01", "A01", "入口一号道"),
				buildLane("L02", "A02", "入口二号道")));
		String token = loginAndGetToken();

		postVehicleEntry(token, "L01", "沪A11111", "2026-04-13T08:00:00+08:00");
		postVehicleEntry(token, "L02", "沪A22222", "2026-04-13T08:10:00+08:00");

		mockMvc.perform(get("/api/lanes")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id=='L01')].exitSignal").value("GREEN"))
			.andExpect(jsonPath("$[?(@.id=='L02')].exitSignal").value("RED"));

		mockMvc.perform(post("/api/dispatch/manual")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "laneId": "L02",
					  "commandType": "SET_PRIORITY",
					  "reason": "特殊车辆优先",
					  "markPriority": true
					}
					"""))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/lanes")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id=='L01')].exitSignal").value("RED"))
			.andExpect(jsonPath("$[?(@.id=='L02')].exitSignal").value("GREEN"));
	}

	private void postVehicleEntry(String token, String laneId, String plate, String entryTime) throws Exception {
		mockMvc.perform(post("/api/integration/vehicle-entries")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "laneId": "%s",
					  "plate": "%s",
					  "vehicleType": "社会车辆",
					  "source": "alpr",
					  "entryTime": "%s"
					}
					""".formatted(laneId, plate, entryTime)))
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
