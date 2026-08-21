package com.smartlane.dispatch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class DeviceTaskConfiguration {

	@Bean("mqttReaderTaskExecutor")
	ThreadPoolTaskExecutor mqttReaderTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(0);
		executor.setThreadNamePrefix("mqtt-reader-");
		executor.setWaitForTasksToCompleteOnShutdown(false);
		return executor;
	}

	@Bean("ledGuideTaskExecutor")
	ThreadPoolTaskExecutor ledGuideTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(32);
		executor.setThreadNamePrefix("led-guide-");
		executor.setWaitForTasksToCompleteOnShutdown(false);
		return executor;
	}

	@Bean("taskScheduler")
	ThreadPoolTaskScheduler taskScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("smart-lane-scheduler-");
		scheduler.setRemoveOnCancelPolicy(true);
		scheduler.setWaitForTasksToCompleteOnShutdown(false);
		return scheduler;
	}

	@Bean("ledGuideTaskScheduler")
	ThreadPoolTaskScheduler ledGuideTaskScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("led-guide-scheduler-");
		scheduler.setRemoveOnCancelPolicy(true);
		scheduler.setWaitForTasksToCompleteOnShutdown(false);
		return scheduler;
	}
}
