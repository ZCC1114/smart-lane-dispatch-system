package com.smartlane.dispatch.dto;

public record DispatchConfigView(
	        String entryLaneOrder,
	        boolean entryDispatchEnabled,
	        boolean exitDispatchEnabled,
	        String activeEntryLaneId,
	        String activeExitLaneId,
	        long assignmentReserveMinutes) {
}
