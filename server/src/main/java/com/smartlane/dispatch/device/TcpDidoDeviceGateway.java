package com.smartlane.dispatch.device;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.smartlane.dispatch.dto.TcpDidoRelayResponse;
import com.smartlane.dispatch.entity.Lane;
import com.smartlane.dispatch.service.LaneRuntimeStateService;

import jakarta.annotation.PostConstruct;

@Service
@ConditionalOnProperty(value = "app.device.gateway", havingValue = "tcp-dido")
public class TcpDidoDeviceGateway implements LaneDeviceGateway {

	private static final Logger log = LoggerFactory.getLogger(TcpDidoDeviceGateway.class);
	private static final ZoneOffset DEVICE_ZONE = ZoneOffset.ofHours(8);

	private final DeviceGatewayProperties properties;
	private final TcpDidoCommandService tcpDidoCommandService;
	private final LaneRuntimeStateService laneRuntimeStateService;
	private final Map<String, DeviceGatewayProperties.LaneBinding> bindingsByLaneId = new ConcurrentHashMap<>();
	private final Map<String, String> lastLaneSyncStates = new ConcurrentHashMap<>();

	public TcpDidoDeviceGateway(
			DeviceGatewayProperties properties,
			TcpDidoCommandService tcpDidoCommandService,
			LaneRuntimeStateService laneRuntimeStateService) {
		this.properties = properties;
		this.tcpDidoCommandService = tcpDidoCommandService;
		this.laneRuntimeStateService = laneRuntimeStateService;
	}

	@PostConstruct
	void indexLaneBindings() {
		for (DeviceGatewayProperties.LaneBinding binding : properties.getLanes()) {
			if (binding.getLaneId() != null && !binding.getLaneId().isBlank()) {
				bindingsByLaneId.put(binding.getLaneId(), binding);
			}
		}
		log.info("TCP DIDO device gateway indexed {} lane bindings", bindingsByLaneId.size());
	}

	@Override
	public void syncLane(Lane lane) {
		DeviceGatewayProperties.LaneBinding binding = bindingsByLaneId.get(lane.getId());
		if (binding == null) {
			laneRuntimeStateService.markCommandPending(lane.getId(), "未配置 TCP DIDO 车道设备绑定", now());
			return;
		}

		String nextSyncState = lane.getEntrySignal() + "|" + lane.getExitSignal();
		if (nextSyncState.equals(lastLaneSyncStates.get(lane.getId()))) {
			return;
		}

		String host = resolveHost(binding);
		int port = resolvePort(binding);
		try {
			controlRelayIfPresent(host, port, binding.getEntryRedRelay(), !"GREEN".equals(lane.getEntrySignal()));
			controlRelayIfPresent(host, port, binding.getEntryGreenRelay(), "GREEN".equals(lane.getEntrySignal()));
			controlRelayIfPresent(host, port, binding.getExitRedRelay(), !"GREEN".equals(lane.getExitSignal()));
			controlRelayIfPresent(host, port, binding.getExitGreenRelay(), "GREEN".equals(lane.getExitSignal()));
			lastLaneSyncStates.put(lane.getId(), nextSyncState);
			laneRuntimeStateService.markCommandPublished(lane.getId(), "TCP DIDO 指令已下发", now());
			laneRuntimeStateService.recordDeviceFeedback(
					lane.getId(),
					lane.getEntrySignal(),
					lane.getExitSignal(),
					now(),
					"TCP DIDO 已按目标灯态执行");
		} catch (Exception ex) {
			laneRuntimeStateService.markCommandFailed(lane.getId(), "TCP DIDO 指令下发失败", now());
			log.warn("Failed to sync lane {} to TCP DIDO {}:{}", lane.getId(), host, port, ex);
		}
	}

	private void controlRelayIfPresent(String host, int port, String relay, boolean on) {
		if (relay == null || relay.isBlank()) {
			return;
		}
		TcpDidoRelayResponse response = tcpDidoCommandService.controlRelay(
				host,
				port,
				relay,
				on,
				properties.getDidoTcp().getProtocol());
		log.debug("TCP DIDO relay command {} {} -> {}", relay, on ? "ON" : "OFF", response.responseHex());
	}

	private String resolveHost(DeviceGatewayProperties.LaneBinding binding) {
		if (binding.getDidoHost() != null && !binding.getDidoHost().isBlank()) {
			return binding.getDidoHost();
		}
		return properties.getDidoTcp().getHost();
	}

	private int resolvePort(DeviceGatewayProperties.LaneBinding binding) {
		if (binding.getDidoPort() != null && binding.getDidoPort() > 0) {
			return binding.getDidoPort();
		}
		return properties.getDidoTcp().getPort();
	}

	private OffsetDateTime now() {
		return OffsetDateTime.now(DEVICE_ZONE);
	}
}
