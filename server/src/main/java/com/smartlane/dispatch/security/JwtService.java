package com.smartlane.dispatch.security;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.smartlane.dispatch.entity.UserAccount;

@Service
public class JwtService {

	private final String secret;
	private final int expireHours;

	public JwtService(
			@Value("${app.jwt.secret}") String secret,
			@Value("${app.jwt.expire-hours}") int expireHours) {
		if (secret == null || secret.length() < 32) {
			throw new IllegalStateException("JWT secret must contain at least 32 characters");
		}
		if (expireHours < 1 || expireHours > 24) {
			throw new IllegalStateException("JWT expiry must be between 1 and 24 hours");
		}
		this.secret = secret;
		this.expireHours = expireHours;
	}

	public OffsetDateTime expiryTime() {
		return OffsetDateTime.now(ZoneOffset.ofHours(8)).plusHours(expireHours);
	}

	public String generateToken(UserAccount user, OffsetDateTime expiresAt) {
		String payload = String.join("|",
				user.getUsername(),
				user.getDisplayName(),
				user.getRole(),
				user.getStation(),
				String.valueOf(expiresAt.toEpochSecond()));
		String signature = sign(payload);
		String token = payload + "|" + signature;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(token.getBytes(StandardCharsets.UTF_8));
	}

	public Optional<AuthenticatedUser> parse(String token) {
		if (token == null || token.isBlank() || token.length() > 8192) {
			return Optional.empty();
		}
		try {
			String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
			String[] parts = decoded.split("\\|", 6);
			if (parts.length != 6) {
				return Optional.empty();
			}

			String payload = String.join("|", parts[0], parts[1], parts[2], parts[3], parts[4]);
			String signature = parts[5];
			if (!MessageDigest.isEqual(
					sign(payload).getBytes(StandardCharsets.US_ASCII),
					signature.getBytes(StandardCharsets.US_ASCII))) {
				return Optional.empty();
			}

			long expiry = Long.parseLong(parts[4]);
			if (OffsetDateTime.now(ZoneOffset.ofHours(8)).toEpochSecond() > expiry) {
				return Optional.empty();
			}

			return Optional.of(new AuthenticatedUser(parts[0], parts[1], parts[2], parts[3]));
		}
		catch (IllegalArgumentException ignored) {
			return Optional.empty();
		}
	}

	private String sign(String payload) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (NoSuchAlgorithmException | InvalidKeyException exception) {
			throw new IllegalStateException("Unable to sign token", exception);
		}
	}
}
