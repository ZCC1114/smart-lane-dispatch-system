package com.smartlane.dispatch.device.led;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
	private static final int DISPLAY_ROWS = 4;
	private static final int LIST_LIMIT = 3;
	private static final String ACTION_TEXT = "驶入";

	private final LedGuideDisplayProperties properties;
	private final OperationsService operationsService;
	private final LedGuideDisplayWriter displayWriter;
	private final AtomicBoolean sending = new AtomicBoolean(false);
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "led-guide-display");
		thread.setDaemon(true);
		return thread;
	});

	private volatile String lastPayloadKey = "";
	private volatile long lastSuccessfulWriteMillis = 0;
	private volatile HighlightState highlightState;

	public LedGuideDisplayService(
			LedGuideDisplayProperties properties,
			OperationsService operationsService,
			LedGuideDisplayWriter displayWriter) {
		this.properties = properties;
		this.operationsService = operationsService;
		this.displayWriter = displayWriter;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void handleOperationsChanged(OperationsChangedEvent event) {
		if (!properties.isEnabled() || !shouldRefresh(event.action())) {
			return;
		}
		executor.execute(() -> {
			if (isYardEntryAction(event.action())) {
				highlightLatestGuideAssignment();
			}
			sendCurrentGuideDisplay();
		});
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
			LedGuideDisplayFrame frame = buildGuideFrame();
			String payloadKey = frame.payloadKey();
			long now = System.currentTimeMillis();
			boolean forceRefresh = shouldForceRefresh(now);
			if (Objects.equals(payloadKey, lastPayloadKey) && !forceRefresh) {
				return;
			}
			LedGuideDisplayWriteResult result = displayWriter.write(frame, forceRefresh);
			if (result.success()) {
				lastPayloadKey = payloadKey;
				lastSuccessfulWriteMillis = now;
				log.info("LED guide display refreshed: {}", result.message());
			} else {
				log.warn("LED guide display refresh failed: {}", result.message());
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

	List<Segment> buildGuideSegments() {
		return buildGuideFrame().toSegments();
	}

	LedGuideDisplayFrame buildGuideFrame() {
		HighlightState activeHighlight = activeHighlight();
		if (activeHighlight != null) {
			return buildHighlightFrame(activeHighlight);
		}
		return buildListFrame();
	}

	void highlightLatestGuideAssignment() {
		List<DispatchTicket> tickets = operationsService.getRecentYardEntries(1);
		if (tickets.isEmpty()) {
			return;
		}
		DispatchTicket ticket = tickets.getFirst();
		String laneText = laneText(ticket);
		if (isBlank(ticket.getPlate()) || isBlank(laneText) || "待分配".equals(laneText)) {
			return;
		}

		long durationMs = Math.max(1000, properties.getHighlightDurationMs());
		highlightState = new HighlightState(ticket.getPlate(), laneText, System.currentTimeMillis() + durationMs);
		executor.schedule(this::sendCurrentGuideDisplay, durationMs + 100, TimeUnit.MILLISECONDS);
	}

	private LedGuideDisplayFrame buildHighlightFrame(HighlightState state) {
		return new LedGuideDisplayFrame(
				LedGuideDisplayFrame.Mode.HIGHLIGHT,
				List.of(
						new LedGuideDisplayFrame.Line(state.plate(), highlightPlateFontSize(), properties.getColor()),
						new LedGuideDisplayFrame.Line(ACTION_TEXT, highlightActionFontSize(), properties.getColor()),
						new LedGuideDisplayFrame.Line(state.laneText(), highlightLaneFontSize(), properties.getColor()),
						new LedGuideDisplayFrame.Line(promptText(), promptFontSize(), properties.getColor())));
	}

	private LedGuideDisplayFrame buildListFrame() {
		List<DispatchTicket> tickets = operationsService.getRecentGuideAssignments(LIST_LIMIT);
		List<LedGuideDisplayFrame.Line> rows = new ArrayList<>(DISPLAY_ROWS);
		for (int index = 0; index < LIST_LIMIT; index++) {
			String text = index < tickets.size() ? guideText(tickets.get(index)) : "";
			rows.add(new LedGuideDisplayFrame.Line(text, listFontSize(), properties.getColor()));
		}
		rows.add(new LedGuideDisplayFrame.Line(promptText(), promptFontSize(), properties.getColor()));
		return new LedGuideDisplayFrame(LedGuideDisplayFrame.Mode.LIST, rows);
	}

	private String guideText(DispatchTicket ticket) {
		return ticket.getPlate() + " " + ACTION_TEXT + " " + laneText(ticket);
	}

	private String laneText(DispatchTicket ticket) {
		String source = firstNonBlank(ticket.getAssignedLaneName(), ticket.getAssignedLaneId());
		Matcher matcher = FIRST_NUMBER.matcher(source);
		if (!matcher.find()) {
			return "待分配";
		}
		return Integer.parseInt(matcher.group()) + "车道";
	}

	private HighlightState activeHighlight() {
		HighlightState state = highlightState;
		if (state == null) {
			return null;
		}
		if (state.expiresAtMillis() > System.currentTimeMillis()) {
			return state;
		}
		highlightState = null;
		return null;
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

	private boolean isYardEntryAction(String action) {
		return action != null && action.toLowerCase(Locale.ROOT).contains("yard_entry");
	}

	private String promptText() {
		return firstNonBlank(properties.getPromptText(), "请按照车道指示进行停车等待！");
	}

	private int listFontSize() {
		return scaledFontSize(14, 0.85);
	}

	private int highlightPlateFontSize() {
		return scaledFontSize(22, 0.95);
	}

	private int highlightActionFontSize() {
		return scaledFontSize(12, 0.65);
	}

	private int highlightLaneFontSize() {
		return scaledFontSize(22, 0.95);
	}

	private int promptFontSize() {
		return scaledFontSize(11, 0.7);
	}

	private int scaledFontSize(int baseSizeAt192x96, double maxRowRatio) {
		int screenWidth = properties.getScreenWidth() > 0 ? properties.getScreenWidth() : 192;
		int screenHeight = properties.getScreenHeight() > 0 ? properties.getScreenHeight() : 96;
		double scale = Math.max(1.0, Math.min(screenWidth / 192.0, screenHeight / 96.0));
		int rowHeight = Math.max(1, screenHeight / DISPLAY_ROWS);
		int size = (int) Math.round(baseSizeAt192x96 * scale);
		int maxSize = Math.max(8, (int) Math.floor(rowHeight * maxRowRatio));
		return Math.max(8, Math.min(size, maxSize));
	}

	private boolean shouldForceRefresh(long nowMillis) {
		long fullRefreshMs = Math.max(0, properties.getFullRefreshMs());
		return fullRefreshMs > 0
				&& (lastSuccessfulWriteMillis == 0 || nowMillis - lastSuccessfulWriteMillis >= fullRefreshMs);
	}

	private String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return "";
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private record HighlightState(String plate, String laneText, long expiresAtMillis) {}
}
