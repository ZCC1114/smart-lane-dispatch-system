package com.smartlane.dispatch.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.smartlane.dispatch.entity.UserAccount;
import com.smartlane.dispatch.repository.UserAccountRepository;

@Component
@ConditionalOnProperty(value = "app.bootstrap-admin.enabled", havingValue = "true")
public class BootstrapAdminInitializer implements ApplicationRunner {

	private final UserAccountRepository userAccountRepository;
	private final PasswordEncoder passwordEncoder;
	private final String username;
	private final String password;
	private final String displayName;
	private final String station;

	public BootstrapAdminInitializer(
			UserAccountRepository userAccountRepository,
			PasswordEncoder passwordEncoder,
			@Value("${app.bootstrap-admin.username}") String username,
			@Value("${app.bootstrap-admin.password}") String password,
			@Value("${app.bootstrap-admin.display-name}") String displayName,
			@Value("${app.bootstrap-admin.station}") String station) {
		this.userAccountRepository = userAccountRepository;
		this.passwordEncoder = passwordEncoder;
		this.username = username;
		this.password = password;
		this.displayName = displayName;
		this.station = station;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (username == null || username.isBlank() || password == null || password.isBlank()) {
			throw new IllegalStateException("Bootstrap admin enabled but username/password is blank");
		}

		UserAccount account = userAccountRepository.findById(username)
				.map(existing -> {
					existing.setDisplayName(displayName);
					existing.setRole("ADMIN");
					existing.setStation(station);
					existing.setPasswordHash(passwordEncoder.encode(password));
					existing.setSystemProtected(true);
					return existing;
				})
				.orElseGet(() -> UserAccount.builder()
						.username(username)
						.displayName(displayName)
						.role("ADMIN")
						.station(station)
						.passwordHash(passwordEncoder.encode(password))
						.systemProtected(true)
						.build());

		userAccountRepository.save(account);
	}
}
