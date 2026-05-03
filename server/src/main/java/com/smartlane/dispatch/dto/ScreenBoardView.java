package com.smartlane.dispatch.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.smartlane.dispatch.entity.DispatchTicket;
import com.smartlane.dispatch.entity.EntryLog;
import com.smartlane.dispatch.entity.Lane;

public record ScreenBoardView(
		OffsetDateTime generatedAt,
		String activeEntryLaneId,
		String activeEntryLaneName,
		String activeExitLaneId,
		String activeExitLaneName,
		boolean entryDispatchEnabled,
		boolean exitDispatchEnabled,
		List<DispatchTicket> waitingAssignments,
		List<DispatchTicket> recentDispatches,
		List<EntryLog> recentEntryLogs,
		Map<String, List<DispatchTicket>> laneVehicles,
		List<ScreenEventView> events,
		List<Lane> lanes) {

	public static ScreenBoardView from(DispatchBoardView board, List<EntryLog> recentEntryLogs, Map<String, List<DispatchTicket>> laneVehicles, List<ScreenEventView> events, List<Lane> lanes) {
		return new ScreenBoardView(
				board.generatedAt(),
				board.activeEntryLaneId(),
				board.activeEntryLaneName(),
				board.activeExitLaneId(),
				board.activeExitLaneName(),
				board.entryDispatchEnabled(),
				board.exitDispatchEnabled(),
				board.waitingAssignments(),
				board.recentDispatches(),
				recentEntryLogs,
				laneVehicles,
				events,
				lanes);
	}
}
