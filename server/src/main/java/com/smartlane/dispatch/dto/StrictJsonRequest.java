package com.smartlane.dispatch.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * Rejects properties that are not part of an API request contract without
 * changing the ObjectMapper used for tolerant third-party device messages.
 */
public interface StrictJsonRequest {

	@JsonAnySetter
	default void rejectUnknownProperty(String propertyName, Object ignoredValue) {
		throw new IllegalArgumentException("请求包含未定义字段");
	}
}
