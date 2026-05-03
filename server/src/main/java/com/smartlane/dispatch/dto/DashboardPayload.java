package com.smartlane.dispatch.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.smartlane.dispatch.dto.DispatchBoardView;
import com.smartlane.dispatch.entity.Lane;

public record DashboardPayload(
		OffsetDateTime generatedAt,
		List<ThroughputPoint> throughput,
		List<Lane> lanes,
		DispatchBoardView dispatchBoard) {
}
