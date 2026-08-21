package com.smartlane.dispatch.dto;

/** Shared allow-list patterns for external API request values. */
public final class RequestValidationPatterns {

	public static final String IDENTIFIER = "^[A-Za-z0-9][A-Za-z0-9._:-]*$";
	public static final String LICENSE_PLATE = "^[\\p{IsHan}A-Za-z0-9· -]+$";
	public static final String SAFE_TEXT = "^[^<>\\p{Cc}]*$";
	public static final String SCREEN_EVENT_ID = "^[A-Za-z0-9_-]+$";

	private RequestValidationPatterns() {
	}
}
