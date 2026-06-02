package com.smartlane.dispatch.dto;

public record WhitelistImportResult(
		int totalRows,
		int validRows,
		int importedPlates,
		int createdCount,
		int updatedCount,
		int duplicateRows,
		int invalidRows) {
}
