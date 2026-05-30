package com.smartlane.dispatch.dto;

import java.time.OffsetDateTime;

import com.smartlane.dispatch.entity.DispatchTicket;
import com.smartlane.dispatch.entity.EntryLog;

public record EntryLogView(
		String id,
		String plate,
		String laneId,
		String laneName,
		String assignedLaneId,
		String assignedLaneName,
		OffsetDateTime entryTime,
		OffsetDateTime exitTime,
		String vehicleType,
		String status,
		String source,
		String operator) {

	public static EntryLogView from(EntryLog log, DispatchTicket ticket) {
		String actualLaneId = actualLaneId(log, ticket);
		String actualLaneName = actualLaneName(log, ticket);
		return new EntryLogView(
				log.getId(),
				log.getPlate(),
				actualLaneId,
				actualLaneName,
				ticket == null ? null : ticket.getAssignedLaneId(),
				ticket == null ? null : ticket.getAssignedLaneName(),
				log.getEntryTime(),
				log.getExitTime(),
				log.getVehicleType(),
				log.getStatus(),
				log.getSource(),
				log.getOperator());
	}

	private static String actualLaneId(EntryLog log, DispatchTicket ticket) {
		if (ticket == null) {
			return log.getLaneId();
		}
		if (ticket.getLaneEntryTime() == null) {
			return null;
		}
		return firstNonBlank(ticket.getActualLaneId(), log.getLaneId());
	}

	private static String actualLaneName(EntryLog log, DispatchTicket ticket) {
		if (ticket == null) {
			return log.getLaneName();
		}
		if (ticket.getLaneEntryTime() == null) {
			return null;
		}
		return firstNonBlank(ticket.getActualLaneName(), log.getLaneName());
	}

	private static String firstNonBlank(String first, String second) {
		if (first != null && !first.isBlank()) {
			return first;
		}
		return second;
	}
}
