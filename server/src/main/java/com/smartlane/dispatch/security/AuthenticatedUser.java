package com.smartlane.dispatch.security;

public record AuthenticatedUser(String username, String displayName, String role, String station) {
}
