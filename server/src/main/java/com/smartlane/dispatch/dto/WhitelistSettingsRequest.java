package com.smartlane.dispatch.dto;

public record WhitelistSettingsRequest(boolean filterEnabled) implements StrictJsonRequest {
}
