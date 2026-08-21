package com.smartlane.dispatch.dto;

import java.time.OffsetDateTime;

import com.smartlane.dispatch.entity.Lane;

/** Public-screen lane projection containing only fields rendered by the board. */
public record ScreenLaneView(
		String id,
		String code,
		String name,
		String status,
		String mode,
		int capacity,
		int vehicleCount,
		String currentPlate,
		OffsetDateTime lastActionAt,
		String lastEntryPlate,
		String entrySignal,
		String exitSignal,
		int reservedCount,
		int availableSlots) {

	public static ScreenLaneView from(Lane lane) {
		return new ScreenLaneView(
				lane.getId(),
				lane.getCode(),
				lane.getName(),
				lane.getStatus(),
				lane.getMode(),
				lane.getCapacity(),
				lane.getVehicleCount(),
				lane.getCurrentPlate(),
				lane.getLastActionAt(),
				lane.getLastEntryPlate(),
				lane.getEntrySignal(),
				lane.getExitSignal(),
				lane.getReservedCount(),
				lane.getAvailableSlots());
	}
}
