package com.smartlane.dispatch.dto;

import java.time.OffsetDateTime;

public record AuthResponse(String token, OffsetDateTime expiresAt, UserView user) {
}
