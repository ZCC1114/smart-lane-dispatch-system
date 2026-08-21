package com.smartlane.dispatch.device;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.smartlane.dispatch.entity.Lane;
import com.smartlane.dispatch.service.LaneRuntimeStateService;

class TcpDidoDeviceGatewayTests {

	private TcpDidoCommandService commandService;
	private LaneRuntimeStateService runtimeStateService;
	private TcpDidoDeviceGateway gateway;
	private Lane lane;

	@BeforeEach
	void setUp() {
		DeviceGatewayProperties properties = new DeviceGatewayProperties();
		properties.getDidoTcp().setHost("dido.test.internal");
		DeviceGatewayProperties.LaneBinding binding = new DeviceGatewayProperties.LaneBinding();
		binding.setLaneId("L01");
		binding.setEntryRedRelay("A01");
		properties.setLanes(List.of(binding));

		commandService = mock(TcpDidoCommandService.class);
		runtimeStateService = mock(LaneRuntimeStateService.class);
		gateway = new TcpDidoDeviceGateway(properties, commandService, runtimeStateService);
		gateway.indexLaneBindings();

		lane = mock(Lane.class);
		when(lane.getId()).thenReturn("L01");
		when(lane.getName()).thenReturn("1号车道");
		when(lane.getEntrySignal()).thenReturn("RED");
		when(lane.getExitSignal()).thenReturn("RED");
	}

	@Test
	void recordsExpectedDeviceFailureWithoutAbortingLaneSync() {
		when(commandService.controlRelay(anyString(), anyInt(), anyString(), anyBoolean(), anyString()))
				.thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "offline"));

		assertThatCode(() -> gateway.syncLane(lane)).doesNotThrowAnyException();

		verify(runtimeStateService).markCommandFailed(
				eq("L01"),
				contains("TCP DIDO"),
				any(OffsetDateTime.class));
	}

	@Test
	void recordsAndRethrowsExpectedManualRelayFailure() {
		ResponseStatusException failure = new ResponseStatusException(HttpStatus.BAD_GATEWAY, "offline");
		when(commandService.controlRelay(anyString(), anyInt(), anyString(), anyBoolean(), anyString()))
				.thenThrow(failure);

		assertThatThrownBy(() -> gateway.controlRelay(lane, "ENTRY_RED", true, "test"))
				.isSameAs(failure);
		verify(runtimeStateService).markCommandFailed(
				eq("L01"),
				contains("TCP DIDO"),
				any(OffsetDateTime.class));
	}

	@Test
	void doesNotHideUnexpectedProgrammingFailure() {
		when(commandService.controlRelay(anyString(), anyInt(), anyString(), anyBoolean(), anyString()))
				.thenThrow(new IllegalStateException("unexpected"));

		assertThatThrownBy(() -> gateway.syncLane(lane))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("unexpected");
	}
}
