package com.smartlane.dispatch.dto;

import java.time.OffsetDateTime;

import com.smartlane.dispatch.entity.DispatchTicket;

/** Public-screen projection that excludes internal source, operator and notes fields. */
public record ScreenDispatchTicketView(
		String id,
		String plate,
		OffsetDateTime yardEntryTime,
		String assignedLaneId,
		String assignedLaneName,
		OffsetDateTime assignedAt,
		String actualLaneId,
		String actualLaneName,
		OffsetDateTime laneEntryTime,
		OffsetDateTime exitTime,
		OffsetDateTime closedAt,
		String status) {

	public static ScreenDispatchTicketView from(DispatchTicket ticket) {
		return new ScreenDispatchTicketView(
				ticket.getId(),
				ticket.getPlate(),
				ticket.getYardEntryTime(),
				ticket.getAssignedLaneId(),
				ticket.getAssignedLaneName(),
				ticket.getAssignedAt(),
				ticket.getActualLaneId(),
				ticket.getActualLaneName(),
				ticket.getLaneEntryTime(),
				ticket.getExitTime(),
				ticket.getClosedAt(),
				ticket.getStatus());
	}
}
