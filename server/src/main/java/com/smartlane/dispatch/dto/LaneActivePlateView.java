package com.smartlane.dispatch.dto;

import java.time.OffsetDateTime;

import com.smartlane.dispatch.entity.EntryLog;

public record LaneActivePlateView(
		String id,
		String plate,
		OffsetDateTime entryTime,
		String vehicleType,
		String source,
		String operator) {

	public static LaneActivePlateView from(EntryLog log) {
		return new LaneActivePlateView(
				log.getId(),
				log.getPlate(),
				log.getEntryTime(),
				log.getVehicleType(),
				log.getSource(),
				log.getOperator());
	}
}
