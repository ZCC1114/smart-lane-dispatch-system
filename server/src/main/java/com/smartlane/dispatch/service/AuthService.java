package com.smartlane.dispatch.service;

import java.time.OffsetDateTime;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.smartlane.dispatch.dto.AuthResponse;
import com.smartlane.dispatch.dto.LoginRequest;
import com.smartlane.dispatch.dto.UserView;
import com.smartlane.dispatch.entity.UserAccount;
import com.smartlane.dispatch.repository.UserAccountRepository;
import com.smartlane.dispatch.security.JwtService;

@Service
public class AuthService {

	private final UserAccountRepository userAccountRepository;
	private final JwtService jwtService;
	private final PasswordEncoder passwordEncoder;

	public AuthService(UserAccountRepository userAccountRepository, JwtService jwtService, PasswordEncoder passwordEncoder) {
		this.userAccountRepository = userAccountRepository;
		this.jwtService = jwtService;
		this.passwordEncoder = passwordEncoder;
	}

	public AuthResponse login(LoginRequest request) {
		UserAccount user = userAccountRepository.findById(request.username())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));

		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
		}

		OffsetDateTime expiresAt = jwtService.expiryTime();
		String token = jwtService.generateToken(user, expiresAt);
		return new AuthResponse(token, expiresAt,
				new UserView(user.getUsername(), user.getDisplayName(), user.getRole(), user.getStation()));
	}
}
