package com.smartlane.dispatch.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.smartlane.dispatch.entity.AlertEvent;
import com.smartlane.dispatch.entity.Lane;

public record DashboardPayload(
		OffsetDateTime generatedAt,
		DashboardMetric metrics,
		List<ThroughputPoint> throughput,
		List<Lane> lanes,
		List<AlertEvent> alerts) {
}
