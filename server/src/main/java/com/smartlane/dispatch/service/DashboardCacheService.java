package com.smartlane.dispatch.service;

import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.BeansException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartlane.dispatch.dto.DashboardPayload;

@Service
public class DashboardCacheService {

	private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
	private final ObjectMapper objectMapper;
	private final boolean redisEnabled;
	private final Duration ttl;
	private final String dashboardCacheNamespace;

	public DashboardCacheService(
			ObjectProvider<StringRedisTemplate> redisTemplateProvider,
			ObjectMapper objectMapper,
			@Value("${app.redis.enabled:false}") boolean redisEnabled,
			@Value("${app.redis.dashboard-ttl-seconds:30}") long ttlSeconds,
			@Value("${app.redis.dashboard-cache-name:smartlane:dashboard}") String dashboardCacheNamespace) {
		this.redisTemplateProvider = redisTemplateProvider;
		this.objectMapper = objectMapper;
		this.redisEnabled = redisEnabled;
		this.ttl = Duration.ofSeconds(ttlSeconds);
		this.dashboardCacheNamespace = dashboardCacheNamespace;
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
			String payload = template.opsForValue().get(dashboardCacheNamespace);
			if (payload == null || payload.isBlank()) {
				return Optional.empty();
			}
			return Optional.of(objectMapper.readValue(payload, DashboardPayload.class));
		}
		catch (BeansException | DataAccessException | JsonProcessingException exception) {
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
			template.opsForValue().set(dashboardCacheNamespace, objectMapper.writeValueAsString(payload), ttl);
		}
		catch (BeansException | DataAccessException | JsonProcessingException ignored) {
		}
	}

	public void evictDashboard() {
		if (!redisEnabled) {
			return;
		}

		try {
			StringRedisTemplate template = redisTemplateProvider.getIfAvailable();
			if (template != null) {
				template.delete(dashboardCacheNamespace);
			}
		}
		catch (BeansException | DataAccessException ignored) {
		}
	}
}
