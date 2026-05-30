package com.smartlane.dispatch.device.led;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.smartlane.dispatch.controller.LedTestController.LedTestRequest.Segment;
import com.smartlane.dispatch.entity.DispatchTicket;
import com.smartlane.dispatch.service.OperationsService;

class LedGuideDisplayServiceTests {

	private static final String PROMPT_TEXT = "请按照车道指示进行停车等待！";

	private LedGuideDisplayProperties properties;
	private OperationsService operationsService;
	private LedGuideDisplayWriter displayWriter;
	private LedGuideDisplayService service;

	@BeforeEach
	void setUp() {
		properties = new LedGuideDisplayProperties();
		properties.setScreenWidth(192);
		properties.setScreenHeight(96);
		properties.setColor("RED");
		properties.setPromptText(PROMPT_TEXT);
		properties.setHighlightDurationMs(10000);
		properties.setFullRefreshMs(60000);
		operationsService = mock(OperationsService.class);
		displayWriter = mock(LedGuideDisplayWriter.class);
		service = new LedGuideDisplayService(properties, operationsService, displayWriter);
	}

	@AfterEach
	void tearDown() {
		service.shutdown();
	}

	@Test
	void buildsLatestThreeAssignmentsAndFixedPromptInListMode() {
		when(operationsService.getRecentGuideAssignments(3)).thenReturn(List.of(
				ticket("DSP-1", "苏B11111", "1号车道"),
				ticket("DSP-2", "苏B22222", "11号车道"),
				ticket("DSP-3", "苏B33333", "3号车道")));

		List<Segment> segments = service.buildGuideSegments();

		assertThat(segments).extracting(Segment::text).containsExactly(
				"苏B11111 请驶入 1车道",
				"苏B22222 请驶入 11车道",
				"苏B33333 请驶入 3车道",
				PROMPT_TEXT);
		assertThat(segments).extracting(Segment::fontSize).containsExactly(14, 14, 14, 11);
	}

	@Test
	void highlightsLatestAssignedYardEntryAcrossTwoGuideRowsWithPrompt() {
		when(operationsService.getRecentYardEntries(1)).thenReturn(List.of(
				ticket("DSP-1", "苏B11111", "11号车道")));

		service.highlightLatestGuideAssignment();
		List<Segment> segments = service.buildGuideSegments();

		assertThat(segments).extracting(Segment::text).containsExactly(
				"苏B11111",
				"请驶入11车道",
				PROMPT_TEXT);
		assertThat(segments).extracting(Segment::fontSize).containsExactly(28, 22, 11);
	}

	@Test
	void doesNotHighlightPreviousAssignmentWhenLatestYardEntryHasNoLane() {
		when(operationsService.getRecentYardEntries(1)).thenReturn(List.of(
				ticket("DSP-1", "苏B99999", null)));
		when(operationsService.getRecentGuideAssignments(3)).thenReturn(List.of(
				ticket("DSP-2", "苏B11111", "1号车道")));

		service.highlightLatestGuideAssignment();
		List<Segment> segments = service.buildGuideSegments();

		assertThat(segments).extracting(Segment::text).containsExactly(
				"苏B11111 请驶入 1车道",
				"",
				"",
				PROMPT_TEXT);
	}

	@Test
	void sendsGuideDisplayFrameWithForceRefreshOnFirstWrite() {
		when(operationsService.getRecentGuideAssignments(3)).thenReturn(List.of(
				ticket("DSP-1", "苏B11111", "1号车道")));
		when(displayWriter.write(org.mockito.ArgumentMatchers.any(), eq(true)))
				.thenReturn(LedGuideDisplayWriteResult.success("动态区发送成功"));

		service.sendCurrentGuideDisplay();

		ArgumentCaptor<LedGuideDisplayFrame> frameCaptor = ArgumentCaptor.captor();
		verify(displayWriter).write(frameCaptor.capture(), eq(true));
		assertThat(frameCaptor.getValue().mode()).isEqualTo(LedGuideDisplayFrame.Mode.LIST);
		assertThat(frameCaptor.getValue().rows()).hasSize(4);
	}

	private DispatchTicket ticket(String id, String plate, String laneName) {
		return DispatchTicket.builder()
				.id(id)
				.plate(plate)
				.yardEntryTime(OffsetDateTime.parse("2026-05-25T10:00:00+08:00"))
				.assignedLaneId(laneName)
				.assignedLaneName(laneName)
				.assignedAt(OffsetDateTime.parse("2026-05-25T10:00:01+08:00"))
				.vehicleType("出租车")
				.status(laneName == null ? "NO_LANE_AVAILABLE" : "ASSIGNED")
				.source("ALPR_YARD")
				.operator("总入口抓拍")
				.build();
	}
}
