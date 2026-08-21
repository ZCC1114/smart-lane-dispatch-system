package com.smartlane.dispatch.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.smartlane.dispatch.entity.UserAccount;

class JwtServiceTests {

	@Test
	void generatedTokenCanBeParsed() {
		JwtService service = new JwtService(UUID.randomUUID().toString(), 8);
		UserAccount account = UserAccount.builder()
				.username("dispatcher")
				.displayName("调度员")
				.role("DISPATCHER")
				.station("总控中心")
				.passwordHash("unused")
				.build();

		String token = service.generateToken(account, OffsetDateTime.now(ZoneOffset.ofHours(8)).plusHours(1));

		assertThat(service.parse(token))
				.contains(new AuthenticatedUser("dispatcher", "调度员", "DISPATCHER", "总控中心"));
	}

	@Test
	void malformedAndOversizedTokensAreRejected() {
		JwtService service = new JwtService(UUID.randomUUID().toString(), 8);

		assertThat(service.parse("not-base64***")).isEmpty();
		assertThat(service.parse("x".repeat(8193))).isEmpty();
	}

	@Test
	void shortSecretIsRejected() {
		assertThatThrownBy(() -> new JwtService("too-short", 8))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("at least 32");
	}
}
