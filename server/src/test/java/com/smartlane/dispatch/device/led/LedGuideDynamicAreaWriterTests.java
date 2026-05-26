package com.smartlane.dispatch.device.led;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LedGuideDynamicAreaWriterTests {

	private LedGuideDisplayProperties properties;
	private LedGuideDynamicAreaClient client;
	private LedGuideDynamicAreaWriter writer;

	@BeforeEach
	void setUp() {
		properties = new LedGuideDisplayProperties();
		properties.setIp("172.17.2.70");
		properties.setPort(5005);
		properties.setGeneration("6");
		properties.setModel("Bx6E");
		properties.setScreenWidth(192);
		properties.setScreenHeight(96);
		properties.setDynamicAreaStartId(2);
		client = mock(LedGuideDynamicAreaClient.class);
		writer = new LedGuideDynamicAreaWriter(properties, client);
	}

	@Test
	void writesFourRowDynamicAreasWithStableCoordinates() throws Exception {
		LedGuideDisplayFrame frame = frame();

		LedGuideDisplayWriteResult result = writer.write(frame, false);

		assertThat(result.success()).isTrue();
		ArgumentCaptor<LedGuideDynamicAreaRequest> requestCaptor = ArgumentCaptor.captor();
		verify(client, org.mockito.Mockito.times(4)).write(requestCaptor.capture());
		assertThat(requestCaptor.getAllValues())
				.extracting(LedGuideDynamicAreaRequest::areaId)
				.containsExactly(2, 3, 4, 5);
		assertThat(requestCaptor.getAllValues())
				.extracting(LedGuideDynamicAreaRequest::y)
				.containsExactly(0, 24, 48, 72);
		assertThat(requestCaptor.getAllValues())
				.extracting(LedGuideDynamicAreaRequest::height)
				.containsExactly(24, 24, 24, 24);
		assertThat(requestCaptor.getAllValues().get(1).text()).isEqualTo("驶入");
		assertThat(requestCaptor.getAllValues().get(2).fontSize()).isEqualTo(20);
	}

	@Test
	void skipsUnchangedRowsUntilForceRefresh() throws Exception {
		LedGuideDisplayFrame frame = frame();
		writer.write(frame, false);
		clearInvocations(client);

		LedGuideDisplayWriteResult skipped = writer.write(frame, false);
		LedGuideDisplayWriteResult forced = writer.write(frame, true);

		assertThat(skipped.success()).isTrue();
		assertThat(forced.success()).isTrue();
		verify(client, org.mockito.Mockito.times(4)).write(any());
	}

	@Test
	void failsFastWhenGenerationIsNotSix() throws Exception {
		properties.setGeneration("5");

		LedGuideDisplayWriteResult result = writer.write(frame(), false);

		assertThat(result.success()).isFalse();
		verify(client, never()).write(any());
	}

	@Test
	void clearsCacheAfterWriteFailure() throws Exception {
		LedGuideDisplayFrame frame = frame();
		when(client.connectionVersion()).thenReturn(1L);
		org.mockito.Mockito.doThrow(new RuntimeException("offline")).when(client).write(any());

		LedGuideDisplayWriteResult result = writer.write(frame, false);

		assertThat(result.success()).isFalse();
		clearInvocations(client);
		org.mockito.Mockito.doNothing().when(client).write(any());
		LedGuideDisplayWriteResult retry = writer.write(frame, false);

		assertThat(retry.success()).isTrue();
		verify(client, org.mockito.Mockito.times(4)).write(any());
	}

	private LedGuideDisplayFrame frame() {
		return new LedGuideDisplayFrame(
				LedGuideDisplayFrame.Mode.HIGHLIGHT,
				List.of(
						new LedGuideDisplayFrame.Line("苏B11111", 22, "RED"),
						new LedGuideDisplayFrame.Line("驶入", 16, "RED"),
						new LedGuideDisplayFrame.Line("11车道", 20, "RED"),
						new LedGuideDisplayFrame.Line("请按照车道指示进行停车等待！", 10, "RED")));
	}
}
