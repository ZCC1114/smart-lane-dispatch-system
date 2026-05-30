package com.smartlane.dispatch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

@Configuration
public class OpenApiConfig {

	@Bean
	OpenAPI smartLaneOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("无锡硕放机场出租车蓄车池排队管理系统 API")
						.description("提供登录鉴权、车道调度、信号灯控制、黑名单与运行总览接口。")
						.version("v1")
						.contact(new Contact().name("Smart Lane Dispatch"))
						.license(new License().name("Internal Use")));
	}
}
