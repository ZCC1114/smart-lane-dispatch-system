package com.smartlane.dispatch.service;

import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartlane.dispatch.dto.DashboardPayload;

@Service
public class DashboardCacheService {

	private static final String DASHBOARD_KEY = "smartlane:dashboard";

	private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
	private final ObjectMapper objectMapper;
	private final boolean redisEnabled;
	private final Duration ttl;

	public DashboardCacheService(
			ObjectProvider<StringRedisTemplate> redisTemplateProvider,
			ObjectMapper objectMapper,
			@Value("${app.redis.enabled:false}") boolean redisEnabled,
			@Value("${app.redis.dashboard-ttl-seconds:30}") long ttlSeconds) {
		this.redisTemplateProvider = redisTemplateProvider;
		this.objectMapper = objectMapper;
		this.redisEnabled = redisEnabled;
		this.ttl = Duration.ofSeconds(ttlSeconds);
	}

	public Optional<DashboardPayload> getDashboard() {
		if (!redisEnabled) {
			return Optional.empty();
		}

		try {
			StringRedisTemplate template = redisTemplateProvider.getIfAvailable();
			if (template == null) {
				return Optional.empty();
			}
			String payload = template.opsForValue().get(DASHBOARD_KEY);
			if (payload == null || payload.isBlank()) {
				return Optional.empty();
			}
			return Optional.of(objectMapper.readValue(payload, DashboardPayload.class));
		}
		catch (RedisConnectionFailureException connectionFailureException) {
			return Optional.empty();
		}
		catch (Exception exception) {
			return Optional.empty();
		}
	}

	public void cacheDashboard(DashboardPayload payload) {
		if (!redisEnabled) {
			return;
		}

		try {
			StringRedisTemplate template = redisTemplateProvider.getIfAvailable();
			if (template == null) {
				return;
			}
			template.opsForValue().set(DASHBOARD_KEY, objectMapper.writeValueAsString(payload), ttl);
		}
		catch (Exception ignored) {
		}
	}

	public void evictDashboard() {
		if (!redisEnabled) {
			return;
		}

		try {
			StringRedisTemplate template = redisTemplateProvider.getIfAvailable();
			if (template != null) {
				template.delete(DASHBOARD_KEY);
			}
		}
		catch (Exception ignored) {
		}
	}
}
