package com.smartlane.dispatch;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:bootstrap-admin-login-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
		"app.bootstrap-admin.enabled=true",
		"app.bootstrap-admin.username=bootstrap-admin",
		"app.bootstrap-admin.display-name=系统引导管理员",
		"app.bootstrap-admin.station=总控中心"
})
@AutoConfigureMockMvc
class BootstrapAdminInitializationTests {
	private static final String TEST_PASSWORD = "T3st!" + UUID.randomUUID();

	@DynamicPropertySource
	static void bootstrapAdminProperties(DynamicPropertyRegistry registry) {
		registry.add("app.bootstrap-admin.password", () -> TEST_PASSWORD);
	}

	@Autowired
	private MockMvc mockMvc;

	@Test
	void bootstrapAdminShouldBeAbleToLogin() throws Exception {
		mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "username": "bootstrap-admin",
					  "password": "%s"
					}
					""".formatted(TEST_PASSWORD)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.token").isNotEmpty())
			.andExpect(jsonPath("$.user.username").value("bootstrap-admin"))
			.andExpect(jsonPath("$.user.role").value("ADMIN"));
	}
}
