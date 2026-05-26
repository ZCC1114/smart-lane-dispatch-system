package com.smartlane.dispatch.device;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

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

		try {
			String entryHost = resolveEntryHost(binding);
			int entryPort = resolveEntryPort(binding);
			String exitHost = resolveExitHost(binding);
			int exitPort = resolveExitPort(binding);
			controlSignalRelaysIfPresent(entryHost, entryPort, binding.getEntryGreenRelay(), binding.getEntryRedRelay(), lane.getEntrySignal());
			controlSignalRelaysIfPresent(exitHost, exitPort, binding.getExitGreenRelay(), binding.getExitRedRelay(), lane.getExitSignal());
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
			log.warn("Failed to sync lane {} to TCP DIDO", lane.getId(), ex);
		}
	}

	@Override
	public void controlRelay(Lane lane, String relayTarget, boolean on, String reason) {
		DeviceGatewayProperties.LaneBinding binding = bindingsByLaneId.get(lane.getId());
		if (binding == null) {
			laneRuntimeStateService.markCommandPending(lane.getId(), "未配置 TCP DIDO 车道设备绑定", now());
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前车道未配置 TCP DIDO 设备绑定");
		}

		String relayKey = resolveRelayKey(binding, relayTarget);
		if (relayKey == null || relayKey.isBlank()) {
			laneRuntimeStateService.markCommandFailed(lane.getId(), "继电器映射缺失", now());
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前车道未配置该业务继电器");
		}

		String host = resolveRelayHost(binding, relayTarget);
		int port = resolveRelayPort(binding, relayTarget);
		try {
			tcpDidoCommandService.controlRelay(host, port, relayKey, on, properties.getDidoTcp().getProtocol());
			String message = relayDisplayName(relayTarget) + (on ? "已吸合" : "已关闭");
			if (reason != null && !reason.isBlank()) {
				message += " · " + reason;
			}
			laneRuntimeStateService.markCommandPublished(lane.getId(), "TCP DIDO 继电器指令已下发", now());
			laneRuntimeStateService.recordDeviceMessage(lane.getId(), message, now());
		} catch (RuntimeException ex) {
			laneRuntimeStateService.markCommandFailed(lane.getId(), "TCP DIDO 继电器指令下发失败", now());
			throw ex;
		}
	}

	@Override
	public void clearSyncState() {
		lastLaneSyncStates.clear();
	}

	private void controlSignalRelaysIfPresent(String host, int port, String greenRelayKey, String redRelayKey, String signal) {
		for (RelayState relayState : signalRelayStates(greenRelayKey, redRelayKey, signal)) {
			controlRelayIfPresent(host, port, relayState.relayKey(), relayState.on());
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

	private List<RelayState> signalRelayStates(String greenRelayKey, String redRelayKey, String signal) {
		boolean green = "GREEN".equals(signal);
		if (!isBlank(greenRelayKey) && !isBlank(redRelayKey)) {
			return List.of(new RelayState(redRelayKey, !green), new RelayState(greenRelayKey, green));
		}
		String relayKey = firstNonBlank(greenRelayKey, redRelayKey);
		if (isBlank(relayKey)) {
			return List.of();
		}
		return List.of(new RelayState(relayKey, !green));
	}

	private String firstNonBlank(String... values) {
		for (String value : values) {
			if (!isBlank(value)) {
				return value;
			}
		}
		return null;
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private record RelayState(String relayKey, boolean on) {
	}

	private String resolveEntryHost(DeviceGatewayProperties.LaneBinding binding) {
		if (binding.getEntryDidoHost() != null && !binding.getEntryDidoHost().isBlank()) {
			return binding.getEntryDidoHost();
		}
		if (binding.getDidoHost() != null && !binding.getDidoHost().isBlank()) {
			return binding.getDidoHost();
		}
		return properties.getDidoTcp().getHost();
	}

	private int resolveEntryPort(DeviceGatewayProperties.LaneBinding binding) {
		if (binding.getEntryDidoPort() != null && binding.getEntryDidoPort() > 0) {
			return binding.getEntryDidoPort();
		}
		if (binding.getDidoPort() != null && binding.getDidoPort() > 0) {
			return binding.getDidoPort();
		}
		return properties.getDidoTcp().getPort();
	}

	private String resolveExitHost(DeviceGatewayProperties.LaneBinding binding) {
		if (binding.getExitDidoHost() != null && !binding.getExitDidoHost().isBlank()) {
			return binding.getExitDidoHost();
		}
		if (binding.getDidoHost() != null && !binding.getDidoHost().isBlank()) {
			return binding.getDidoHost();
		}
		return properties.getDidoTcp().getHost();
	}

	private int resolveExitPort(DeviceGatewayProperties.LaneBinding binding) {
		if (binding.getExitDidoPort() != null && binding.getExitDidoPort() > 0) {
			return binding.getExitDidoPort();
		}
		if (binding.getDidoPort() != null && binding.getDidoPort() > 0) {
			return binding.getDidoPort();
		}
		return properties.getDidoTcp().getPort();
	}

	private String resolveRelayHost(DeviceGatewayProperties.LaneBinding binding, String relayTarget) {
		String normalizedTarget = relayTarget == null ? "" : relayTarget.trim().toUpperCase().replace('-', '_');
		return switch (normalizedTarget) {
			case "ENTRY_RED", "ENTRY_GREEN" -> resolveEntryHost(binding);
			case "EXIT_RED", "EXIT_GREEN" -> resolveExitHost(binding);
			default -> resolveEntryHost(binding);
		};
	}

	private int resolveRelayPort(DeviceGatewayProperties.LaneBinding binding, String relayTarget) {
		String normalizedTarget = relayTarget == null ? "" : relayTarget.trim().toUpperCase().replace('-', '_');
		return switch (normalizedTarget) {
			case "ENTRY_RED", "ENTRY_GREEN" -> resolveEntryPort(binding);
			case "EXIT_RED", "EXIT_GREEN" -> resolveExitPort(binding);
			default -> resolveEntryPort(binding);
		};
	}

	private String resolveRelayKey(DeviceGatewayProperties.LaneBinding binding, String relayTarget) {
		String normalizedTarget = relayTarget == null ? "" : relayTarget.trim().toUpperCase().replace('-', '_');
		return switch (normalizedTarget) {
			case "ENTRY_RED" -> binding.getEntryRedRelay();
			case "ENTRY_GREEN" -> binding.getEntryGreenRelay();
			case "EXIT_RED" -> binding.getExitRedRelay();
			case "EXIT_GREEN" -> binding.getExitGreenRelay();
			default -> relayTarget;
		};
	}

	private String relayDisplayName(String relayTarget) {
		String normalizedTarget = relayTarget == null ? "" : relayTarget.trim().toUpperCase().replace('-', '_');
		return switch (normalizedTarget) {
			case "ENTRY_RED" -> "入口红灯";
			case "ENTRY_GREEN" -> "入口绿灯";
			case "EXIT_RED" -> "出口红灯";
			case "EXIT_GREEN" -> "出口绿灯";
			default -> relayTarget;
		};
	}

	private OffsetDateTime now() {
		return OffsetDateTime.now(DEVICE_ZONE);
	}
}
