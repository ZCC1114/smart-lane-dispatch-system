package com.smartlane.dispatch.dto;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
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
		List<ScreenDispatchTicketView> waitingAssignments,
		List<ScreenDispatchTicketView> guideAssignments,
		List<ScreenDispatchTicketView> recentDispatches,
		List<ScreenEntryLogView> recentEntryLogs,
		Map<String, List<ScreenDispatchTicketView>> laneVehicles,
		List<ScreenEventView> pendingEvents,
		List<ScreenEventView> events,
		List<ScreenLaneView> lanes,
		OffsetDateTime lastDailyResetAt) {

	public static ScreenBoardView from(
			DispatchBoardView board,
			List<EntryLog> recentEntryLogs,
			List<DispatchTicket> recentDispatches,
			List<DispatchTicket> guideAssignments,
			Map<String, List<DispatchTicket>> laneVehicles,
			List<ScreenEventView> pendingEvents,
			List<ScreenEventView> events,
			List<Lane> lanes,
			OffsetDateTime lastDailyResetAt) {
		return new ScreenBoardView(
				board.generatedAt(),
				board.activeEntryLaneId(),
				board.activeEntryLaneName(),
				board.activeExitLaneId(),
				board.activeExitLaneName(),
				board.entryDispatchEnabled(),
				board.exitDispatchEnabled(),
				toTicketViews(board.waitingAssignments()),
				toTicketViews(guideAssignments),
				toTicketViews(recentDispatches),
				recentEntryLogs.stream().map(ScreenEntryLogView::from).toList(),
				toLaneVehicleViews(laneVehicles),
				pendingEvents,
				events,
				lanes.stream().map(ScreenLaneView::from).toList(),
				lastDailyResetAt);
	}

	private static List<ScreenDispatchTicketView> toTicketViews(List<DispatchTicket> tickets) {
		return tickets.stream().map(ScreenDispatchTicketView::from).toList();
	}

	private static Map<String, List<ScreenDispatchTicketView>> toLaneVehicleViews(
			Map<String, List<DispatchTicket>> laneVehicles) {
		Map<String, List<ScreenDispatchTicketView>> projected = new LinkedHashMap<>();
		laneVehicles.forEach((laneId, tickets) -> projected.put(laneId, toTicketViews(tickets)));
		return Map.copyOf(projected);
	}
}
