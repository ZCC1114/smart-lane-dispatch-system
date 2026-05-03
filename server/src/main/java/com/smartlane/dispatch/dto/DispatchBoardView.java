package com.smartlane.dispatch.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.smartlane.dispatch.entity.DispatchTicket;

public record DispatchBoardView(
		OffsetDateTime generatedAt,
		String activeEntryLaneId,
		String activeExitLaneId,
		String activeEntryLaneName,
		String activeExitLaneName,
		boolean entryDispatchEnabled,
		boolean exitDispatchEnabled,
		List<DispatchTicket> waitingAssignments,
		List<DispatchTicket> recentDispatches) {
}
