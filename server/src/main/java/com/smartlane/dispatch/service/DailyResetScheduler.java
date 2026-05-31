package com.smartlane.dispatch.service;

import java.time.LocalDate;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DailyResetScheduler {

	private static final Logger log = LoggerFactory.getLogger(DailyResetScheduler.class);

	private final OperationsService operationsService;
	private final boolean enabled;
	private final ZoneId zoneId;

	public DailyResetScheduler(
			OperationsService operationsService,
			@Value("${app.dispatch.daily-reset-scheduled-enabled:true}") boolean enabled,
			@Value("${app.dispatch.daily-reset-zone:Asia/Shanghai}") String zone) {
		this.operationsService = operationsService;
		this.enabled = enabled;
		this.zoneId = ZoneId.of(zone);
	}

	@Scheduled(cron = "${app.dispatch.daily-reset-cron:0 30 4 * * *}", zone = "${app.dispatch.daily-reset-zone:Asia/Shanghai}")
	public void runDailyReset() {
		if (!enabled) {
			return;
		}
		LocalDate today = LocalDate.now(zoneId);
		if (operationsService.dailyResetCompletedOn(today, zoneId)) {
			log.info("Scheduled daily reset skipped because daily reset has already completed today date={}", today);
			return;
		}
		log.info("Scheduled daily reset started date={}", today);
		operationsService.dailyReset();
		log.info("Scheduled daily reset completed date={}", today);
	}
}
