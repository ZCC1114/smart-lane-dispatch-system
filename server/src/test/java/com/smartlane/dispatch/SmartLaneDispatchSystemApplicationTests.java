package com.smartlane.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SmartLaneDispatchSystemApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void contextLoads() {
		assertThat(mockMvc).isNotNull();
	}

	@Test
	void loginShouldRejectUnknownUserWhenDatabaseIsEmpty() throws Exception {
		mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "username": "admin",
					  "password": "Admin@123"
					}
					"""))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void dashboardShouldRequireAuthentication() throws Exception {
		mockMvc.perform(get("/api/dashboard"))
			.andExpect(status().isUnauthorized());
	}

}
