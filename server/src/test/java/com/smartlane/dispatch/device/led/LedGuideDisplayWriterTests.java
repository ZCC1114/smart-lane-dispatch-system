package com.smartlane.dispatch.device.led;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LedGuideDisplayWriterTests {

	private LedGuideDisplayProperties properties;
	private LedGuideDynamicAreaWriter dynamicAreaWriter;
	private LedGuideProgramDisplayWriter programDisplayWriter;
	private LedGuideDisplayWriter writer;

	@BeforeEach
	void setUp() {
		properties = new LedGuideDisplayProperties();
		dynamicAreaWriter = mock(LedGuideDynamicAreaWriter.class);
		programDisplayWriter = mock(LedGuideProgramDisplayWriter.class);
		writer = new LedGuideDisplayWriter(properties, dynamicAreaWriter, programDisplayWriter);
	}

	@Test
	void usesDynamicAreaWhenItSucceeds() {
		LedGuideDisplayFrame frame = frame();
		when(dynamicAreaWriter.write(frame, false))
				.thenReturn(LedGuideDisplayWriteResult.success("动态区发送成功"));

		LedGuideDisplayWriteResult result = writer.write(frame, false);

		assertThat(result.success()).isTrue();
		verify(programDisplayWriter, never()).write(frame);
	}

	@Test
	void fallsBackToFullProgramWhenDynamicAreaFails() {
		LedGuideDisplayFrame frame = frame();
		when(dynamicAreaWriter.write(frame, true))
				.thenReturn(LedGuideDisplayWriteResult.failure("动态区发送失败"));
		when(programDisplayWriter.write(frame))
				.thenReturn(LedGuideDisplayWriteResult.success("完整节目发送成功"));

		LedGuideDisplayWriteResult result = writer.write(frame, true);

		assertThat(result.success()).isTrue();
		assertThat(result.message()).contains("已回退完整节目");
		verify(programDisplayWriter).write(frame);
	}

	@Test
	void canForceProgramMode() {
		properties.setWriteMode(LedGuideDisplayProperties.WriteMode.PROGRAM);
		LedGuideDisplayFrame frame = frame();
		when(programDisplayWriter.write(frame))
				.thenReturn(LedGuideDisplayWriteResult.success("完整节目发送成功"));

		LedGuideDisplayWriteResult result = writer.write(frame, false);

		assertThat(result.success()).isTrue();
		verify(dynamicAreaWriter, never()).write(frame, false);
	}

	private LedGuideDisplayFrame frame() {
		return new LedGuideDisplayFrame(
				LedGuideDisplayFrame.Mode.LIST,
				List.of(
						new LedGuideDisplayFrame.Line("苏B11111 请驶入 1车道", 10, "RED"),
						new LedGuideDisplayFrame.Line("苏B22222 请驶入 11车道", 10, "RED"),
						new LedGuideDisplayFrame.Line("苏B33333 请驶入 3车道", 10, "RED"),
						new LedGuideDisplayFrame.Line("请按照车道指示进行停车等待！", 10, "RED")));
	}
}
