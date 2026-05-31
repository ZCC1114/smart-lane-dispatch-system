package com.smartlane.dispatch.dto;

import java.util.List;

public record PageResult<T>(
		List<T> items,
		long total,
		int page,
		int pageSize,
		int totalPages) {

	public static <T> PageResult<T> of(List<T> items, long total, int page, int pageSize) {
		int normalizedPageSize = Math.max(1, pageSize);
		int totalPages = Math.max(1, (int) Math.ceil((double) total / normalizedPageSize));
		return new PageResult<>(items, total, Math.max(1, page), normalizedPageSize, totalPages);
	}
}
