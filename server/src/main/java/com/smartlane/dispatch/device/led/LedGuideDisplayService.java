package com.smartlane.dispatch.device.led;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.smartlane.dispatch.controller.LedTestController.LedTestRequest.Segment;
import com.smartlane.dispatch.entity.DispatchTicket;
import com.smartlane.dispatch.service.OperationsChangedEvent;
import com.smartlane.dispatch.service.OperationsService;

import jakarta.annotation.PreDestroy;

@Service
public class LedGuideDisplayService {

	private static final Logger log = LoggerFactory.getLogger(LedGuideDisplayService.class);
	private static final Pattern FIRST_NUMBER = Pattern.compile("\\d+");

	private final LedGuideDisplayProperties properties;
	private final OperationsService operationsService;
	private final LedScreenService ledScreenService;
	private final AtomicBoolean sending = new AtomicBoolean(false);
	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "led-guide-display");
		thread.setDaemon(true);
		return thread;
	});

	private volatile String lastPayloadKey = "";

	public LedGuideDisplayService(
			LedGuideDisplayProperties properties,
			OperationsService operationsService,
			LedScreenService ledScreenService) {
		this.properties = properties;
		this.operationsService = operationsService;
		this.ledScreenService = ledScreenService;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void handleOperationsChanged(OperationsChangedEvent event) {
		if (!properties.isEnabled() || !shouldRefresh(event.action())) {
			return;
		}
		executor.submit(this::sendCurrentGuideDisplay);
	}

	@Scheduled(
			initialDelayString = "${app.led.guide.initial-delay-ms:5000}",
			fixedDelayString = "${app.led.guide.refresh-ms:5000}")
	public void scheduledRefresh() {
		if (properties.isEnabled()) {
			sendCurrentGuideDisplay();
		}
	}

	public void sendCurrentGuideDisplay() {
		if (!sending.compareAndSet(false, true)) {
			return;
		}
		try {
			List<Segment> segments = buildGuideSegments();
			String payloadKey = payloadKey(segments);
			if (Objects.equals(payloadKey, lastPayloadKey)) {
				return;
			}
			String result = ledScreenService.sendText(
					properties.getIp(),
					properties.getPort(),
					properties.getGeneration(),
					properties.getModel(),
					segments,
					properties.getScreenWidth(),
					properties.getScreenHeight(),
					properties.getColumns(),
					properties.getRows());
			if (result.startsWith("发送成功")) {
				lastPayloadKey = payloadKey;
				log.info("LED guide display refreshed: {}", result);
			} else {
				log.warn("LED guide display refresh failed: {}", result);
			}
		} catch (Exception ex) {
			log.warn("LED guide display refresh failed", ex);
		} finally {
			sending.set(false);
		}
	}

	@PreDestroy
	public void shutdown() {
		executor.shutdownNow();
	}

	private List<Segment> buildGuideSegments() {
		int capacity = Math.max(1, properties.getColumns() * properties.getRows());
		List<DispatchTicket> tickets = operationsService.getRecentGuideAssignments(capacity);
		List<Segment> segments = new ArrayList<>(capacity);
		for (int index = 0; index < capacity; index++) {
			String text = index < tickets.size() ? guideText(tickets.get(index)) : "";
			segments.add(new Segment(text, properties.getFontSize(), properties.getColor()));
		}
		return segments;
	}

	private String guideText(DispatchTicket ticket) {
		return ticket.getPlate() + "-" + laneText(ticket);
	}

	private String laneText(DispatchTicket ticket) {
		String source = firstNonBlank(ticket.getAssignedLaneName(), ticket.getAssignedLaneId());
		Matcher matcher = FIRST_NUMBER.matcher(source);
		if (!matcher.find()) {
			return "待分配";
		}
		return Integer.parseInt(matcher.group()) + "车道";
	}

	private String payloadKey(List<Segment> segments) {
		return segments.stream()
				.map(segment -> segment.text() + "|" + segment.fontSize() + "|" + segment.color())
				.collect(Collectors.joining("\n"));
	}

	private boolean shouldRefresh(String action) {
		if (action == null) {
			return true;
		}
		String value = action.toLowerCase(Locale.ROOT);
		return value.contains("yard")
				|| value.contains("entry")
				|| value.contains("dispatch")
				|| value.contains("reset")
				|| value.contains("config");
	}

	private String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return "";
	}
}
