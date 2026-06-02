package com.smartlane.dispatch.dto;

import java.time.OffsetDateTime;

public record WhitelistImportProgress(
		String jobId,
		String status,
		int percent,
		int totalRows,
		int validRows,
		int importedPlates,
		int createdCount,
		int updatedCount,
		int duplicateRows,
		int invalidRows,
		String message,
		OffsetDateTime startedAt,
		OffsetDateTime finishedAt) {
}
