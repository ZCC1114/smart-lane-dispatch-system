package com.smartlane.dispatch.config;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import com.smartlane.dispatch.entity.EntryLog;
import com.smartlane.dispatch.entity.Lane;
import com.smartlane.dispatch.repository.EntryLogRepository;
import com.smartlane.dispatch.repository.LaneRepository;

@Component
public class DataInitializer implements CommandLineRunner {

	private final LaneRepository laneRepository;
	private final EntryLogRepository entryLogRepository;
	private final boolean seedDemoEntryLogs;

	public DataInitializer(LaneRepository laneRepository,
			EntryLogRepository entryLogRepository,
			@Value("${app.seed-demo-entry-logs:false}") boolean seedDemoEntryLogs) {
		this.laneRepository = laneRepository;
		this.entryLogRepository = entryLogRepository;
		this.seedDemoEntryLogs = seedDemoEntryLogs;
	}

	@Override
	public void run(String... args) {
		initLanes();
		initEntryLogs();
	}

	private void initLanes() {
		if (laneRepository.count() > 0) {
			return;
		}

		List<Lane> lanes = List.of(
			createLane("L01", "L01", "1号车道"),
			createLane("L02", "L02", "2号车道"),
			createLane("L03", "L03", "3号车道"),
			createLane("L04", "L04", "4号车道"),
			createLane("L05", "L05", "5号车道"),
			createLane("L06", "L06", "6号车道"),
			createLane("L07", "L07", "7号车道"),
			createLane("L08", "L08", "8号车道"),
			createLane("L09", "L09", "9号车道"),
			createLane("L10", "L10", "10号车道"),
			createLane("L11", "L11", "11号车道")
		);

		laneRepository.saveAll(lanes);
	}

	private void initEntryLogs() {
		if (!seedDemoEntryLogs) {
			return;
		}
		if (entryLogRepository.count() > 0) {
			return;
		}

		OffsetDateTime now = OffsetDateTime.now();
		List<EntryLog> logs = List.of(
			// 仍在场的车辆
			createEntryLog("苏B·E54G1", "L01", "1号车道", now.minusHours(3), null, "PASSED"),
			createEntryLog("苏B·A12594", "L02", "2号车道", now.minusHours(2), null, "PASSED"),
			createEntryLog("苏B·88888", "L03", "3号车道", now.minusHours(5), null, "PASSED"),
			createEntryLog("苏B·66666", "L01", "1号车道", now.minusHours(1), null, "PASSED"),
			createEntryLog("苏B·77777", "L04", "4号车道", now.minusHours(4), null, "PASSED"),
			createEntryLog("苏B·99999", "L05", "5号车道", now.minusHours(2), null, "MANUAL"),
			createEntryLog("苏B·11111", "L06", "6号车道", now.minusMinutes(30), null, "PASSED"),
			createEntryLog("苏B·22222", "L02", "2号车道", now.minusHours(6), null, "PASSED"),
			// 已离场的车辆
			createEntryLog("苏B·33333", "L01", "1号车道", now.minusHours(8), now.minusHours(5), "PASSED"),
			createEntryLog("苏B·44444", "L03", "3号车道", now.minusHours(7), now.minusHours(4), "PASSED"),
			createEntryLog("苏B·55555", "L05", "5号车道", now.minusHours(6), now.minusHours(3), "REJECTED"),
			createEntryLog("苏B·12345", "L07", "7号车道", now.minusHours(10), now.minusHours(7), "PASSED"),
			createEntryLog("苏B·67890", "L08", "8号车道", now.minusHours(9), now.minusHours(6), "PASSED"),
			createEntryLog("苏B·13579", "L09", "9号车道", now.minusHours(5), now.minusHours(2), "MANUAL"),
			createEntryLog("苏B·24680", "L10", "10号车道", now.minusHours(12), now.minusHours(8), "PASSED")
		);

		entryLogRepository.saveAll(logs);
	}

	private Lane createLane(String id, String code, String name) {
		Lane lane = new Lane();
		lane.setId(id);
		lane.setCode(code);
		lane.setName(name);
		lane.setZone("出租车蓄车区");
		lane.setType("MIXED");
		lane.setStatus("OPEN");
		lane.setMode("AUTO");
		lane.setCapacity(6 + Math.abs(id.hashCode() % 3));
		lane.setVehicleCount(0);
		lane.setPriority(false);
		lane.setLastActionAt(OffsetDateTime.now());
		lane.setSensorStatus("ONLINE");
		lane.setLastSensorAt(OffsetDateTime.now());
		return lane;
	}

	private EntryLog createEntryLog(String plate, String laneId, String laneName, OffsetDateTime entryTime, OffsetDateTime exitTime, String status) {
		EntryLog log = new EntryLog();
		log.setId(UUID.randomUUID().toString());
		log.setPlate(plate);
		log.setLaneId(laneId);
		log.setLaneName(laneName);
		log.setEntryTime(entryTime);
		log.setExitTime(exitTime);
		log.setVehicleType("出租车");
		log.setStatus(status);
		log.setSource("ALPR");
		log.setOperator("系统自动识别");
		return log;
	}
}
