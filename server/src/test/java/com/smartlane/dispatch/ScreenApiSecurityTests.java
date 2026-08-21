package com.smartlane.dispatch;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
		"app.bootstrap-admin.enabled=true",
		"app.bootstrap-admin.username=security-admin",
		"app.bootstrap-admin.display-name=安全测试管理员",
		"app.bootstrap-admin.station=安全测试",
		"app.led.guide.ip=approved-led.test.invalid"
})
@AutoConfigureMockMvc
class ScreenApiSecurityTests {
	private static final String TEST_ADMIN_PASSWORD = "T3st!" + UUID.randomUUID();

	@DynamicPropertySource
	static void bootstrapAdminProperties(DynamicPropertyRegistry registry) {
		registry.add("app.bootstrap-admin.password", () -> TEST_ADMIN_PASSWORD);
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void onlyStandaloneBoardReadShouldBePublic() throws Exception {
		mockMvc.perform(get("/api/screen/board"))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/screen/events"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void anonymousScreenWritesAndLedTestsShouldBeRejected() throws Exception {
		mockMvc.perform(post("/api/screen/daily-reset"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/screen/simulate/global-exit"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/screen/events/BL-TEST/handle"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/screen/led-test/send")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@WithMockUser(roles = "VIEWER")
	void authenticatedUserWithoutOperationsRoleShouldBeForbidden() throws Exception {
		mockMvc.perform(post("/api/screen/daily-reset"))
				.andExpect(status().isForbidden());
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void ledTestShouldRejectTargetsOutsideServerAllowlist() throws Exception {
		mockMvc.perform(post("/api/screen/led-test/send")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "ip": "unapproved-led.test.invalid",
						  "port": 5005,
						  "generation": "6",
						  "model": "Bx6E",
						  "segments": [{"text": "测试", "fontSize": 12, "color": "RED"}],
						  "screenWidth": 192,
						  "screenHeight": 96,
						  "columns": 2,
						  "rows": 6
						}
						"""))
				.andExpect(status().isForbidden())
				.andExpect(content().string("LED 测试目标不在允许范围内"));
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void unknownJsonFieldsShouldBeRejectedWithoutLeakingParserDetails() throws Exception {
		mockMvc.perform(post("/api/screen/simulate/lane-entry")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "laneId": "L01",
						  "plate": "沪A12345",
						  "isAdministrator": true
						}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(content().string(containsString("未定义字段")))
				.andExpect(content().string(not(containsString("isAdministrator"))));
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void maliciousScreenQueryParametersShouldBeRejected() throws Exception {
		mockMvc.perform(get("/api/screen/events")
				.param("query", "<img src=x onerror=alert(1)>"))
				.andExpect(status().isBadRequest())
				.andExpect(content().string("请求参数校验失败"));
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void maliciousTextAndForgedAuditFieldsShouldBeRejected() throws Exception {
		mockMvc.perform(post("/api/integration/yard-entries")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "plate": "<script>alert(1)</script>",
						  "vehicleType": "出租车",
						  "source": "SECURITY_TEST"
						}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(content().string(containsString("车牌号码")));

		mockMvc.perform(post("/api/blacklist")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "plate": "沪A12345",
						  "reason": "测试",
						  "level": "HIGH",
						  "operator": "伪造管理员",
						  "active": true
						}
						"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void blacklistAuditOperatorShouldComeFromAuthenticatedPrincipal() throws Exception {
		String token = loginAndGetToken();

		mockMvc.perform(post("/api/blacklist")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "plate": "沪A98765",
						  "reason": "安全测试",
						  "level": "HIGH",
						  "active": true
						}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operator").value("安全测试管理员"));
	}

	private String loginAndGetToken() throws Exception {
		String responseBody = mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "username": "security-admin",
						  "password": "%s"
						}
						""".formatted(TEST_ADMIN_PASSWORD)))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		JsonNode response = objectMapper.readTree(responseBody);
		return response.path("token").asText();
	}
}
