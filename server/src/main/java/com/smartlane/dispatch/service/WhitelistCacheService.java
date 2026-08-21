package com.smartlane.dispatch.service;

import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.BeansException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.smartlane.dispatch.repository.WhitelistRecordRepository;

@Service
public class WhitelistCacheService {

	private static final String WHITELIST_KEY = "smartlane:whitelist:plates";
	private static final String WHITELIST_LOADED_KEY = "smartlane:whitelist:loaded";

	private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
	private final WhitelistRecordRepository whitelistRecordRepository;
	private final boolean redisEnabled;
	private final boolean whitelistRedisCacheEnabled;

	public WhitelistCacheService(
			ObjectProvider<StringRedisTemplate> redisTemplateProvider,
			WhitelistRecordRepository whitelistRecordRepository,
			@Value("${app.redis.enabled:false}") boolean redisEnabled,
			@Value("${app.whitelist.redis-cache-enabled:true}") boolean whitelistRedisCacheEnabled) {
		this.redisTemplateProvider = redisTemplateProvider;
		this.whitelistRecordRepository = whitelistRecordRepository;
		this.redisEnabled = redisEnabled;
		this.whitelistRedisCacheEnabled = whitelistRedisCacheEnabled;
	}

	public boolean contains(String plate) {
		String normalizedPlate = normalizePlate(plate);
		if (normalizedPlate == null || normalizedPlate.isBlank()) {
			return false;
		}
		StringRedisTemplate template = redisTemplate();
		if (template == null) {
			return whitelistRecordRepository.existsByPlate(normalizedPlate);
		}
		try {
			if (!Boolean.TRUE.equals(template.hasKey(WHITELIST_LOADED_KEY))) {
				reload(template);
			}
			return Boolean.TRUE.equals(template.opsForSet().isMember(WHITELIST_KEY, normalizedPlate));
		}
			catch (DataAccessException ignored) {
			return whitelistRecordRepository.existsByPlate(normalizedPlate);
		}
	}

	public void refresh() {
		StringRedisTemplate template = redisTemplate();
		if (template == null) {
			return;
		}
		try {
			reload(template);
		}
		catch (DataAccessException ignored) {
		}
	}

	private void reload(StringRedisTemplate template) {
		template.delete(WHITELIST_LOADED_KEY);
		template.delete(WHITELIST_KEY);
		List<String> plates = whitelistRecordRepository.findAllPlates().stream()
				.map(this::normalizePlate)
				.filter(value -> value != null && !value.isBlank())
				.distinct()
				.toList();
		if (!plates.isEmpty()) {
			template.opsForSet().add(WHITELIST_KEY, plates.toArray(String[]::new));
		}
		template.opsForValue().set(WHITELIST_LOADED_KEY, String.valueOf(plates.size()));
	}

	private StringRedisTemplate redisTemplate() {
		if (!redisEnabled || !whitelistRedisCacheEnabled) {
			return null;
		}
		try {
			return redisTemplateProvider.getIfAvailable();
		}
		catch (BeansException ignored) {
			return null;
		}
	}

	private String normalizePlate(String plate) {
		if (plate == null) {
			return null;
		}
		return plate.replace("·", "").replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
	}
}
