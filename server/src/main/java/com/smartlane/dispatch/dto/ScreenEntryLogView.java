package com.smartlane.dispatch.dto;

import java.time.OffsetDateTime;

import com.smartlane.dispatch.entity.EntryLog;

/** Public-screen entry projection without audit and device-source metadata. */
public record ScreenEntryLogView(
		String id,
		String plate,
		String laneId,
		String laneName,
		OffsetDateTime entryTime,
		OffsetDateTime exitTime,
		String vehicleType,
		String status) {

	public static ScreenEntryLogView from(EntryLog entryLog) {
		return new ScreenEntryLogView(
				entryLog.getId(),
				entryLog.getPlate(),
				entryLog.getLaneId(),
				entryLog.getLaneName(),
				entryLog.getEntryTime(),
				entryLog.getExitTime(),
				entryLog.getVehicleType(),
				entryLog.getStatus());
	}
}
