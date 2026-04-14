package com.smartlane.dispatch.dto;

public record DashboardMetric(
		int totalPassages,
		double averagePassMinutes,
		int blacklistAlerts,
		double systemHealth,
		double activeLaneRate) {
}
