package com.smartlane.dispatch.device;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import lombok.Data;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.device")
public class DeviceGatewayProperties {

	private String gateway = "mock";
	private MqttProperties mqtt = new MqttProperties();
	private ParkingMfProperties parkingMf = new ParkingMfProperties();
	private SmartCameraProperties smartCamera = new SmartCameraProperties();
	private DidoProperties dido = new DidoProperties();
	private DidoTcpProperties didoTcp = new DidoTcpProperties();
	private List<LaneBinding> lanes = new ArrayList<>();

	@Data
	public static class MqttProperties {
		private boolean enabled = false;
		private String host = "localhost";
		private int port = 1883;
		private String clientId = "smart-lane-dispatch-system";
		private String username;
		private String password;
		private int keepAliveSeconds = 30;
		private long reconnectDelayMs = 5000;
		private int maxPacketBytes = SimpleMqttClient.DEFAULT_MAX_PACKET_BYTES;
	}

	@Data
	public static class ParkingMfProperties {
		private boolean enabled = true;
		private String upTopicFilter = "/+/mf/up";
		private String downTopicTemplate = "/{mfSn}/mf/down";
		private String yardEntrySn;
		private String yardEntryGroupId;
		private String yardEntryDeviceNo;
	}

	@Data
	public static class SmartCameraProperties {
		private boolean enabled = true;
		private String upTopicFilter = "/device/+/update";
		private String willTopicFilter = "/device/+/will";
		private String downTopicTemplate = "/device/{cameraDevId}/get";
		private String yardEntryCameraDevId;
		private String activeEntryCameraDevId;
		private boolean requestVersionOnConnect = true;
		private boolean haveCarPollEnabled = false;
		private long haveCarPollMs = 30000;
	}

	@Data
	public static class DidoProperties {
		private boolean enabled = true;
		private String upTopicFilter = "/device/+/update";
		private String downTopicTemplate = "/device/{didoDeviceId}/get";
		private String payloadMode = "json";
		private String relayMode = "ordinary";
		private int pulseMilliseconds = 500;
		private boolean enableRemoteConfigOnConnect = false;
		private boolean enableRelayUploadOnConnect = false;
		private boolean exitTriggerEnabled = true;
	}

	@Data
	public static class DidoTcpProperties {
		private String host;
		private int port = 8080;
		private int timeoutMs = 3000;
		private String protocol = "A1";
	}

	@Data
	public static class LaneBinding {
		private String laneId;
		private String mfSn;
		private String mfGroupId;
		private String mfDeviceNo;
		private String cameraDevId;
		private String didoDeviceId;
		private String didoHost;
		private Integer didoPort;
		private String entryDidoDeviceId;
		private String entryDidoHost;
		private Integer entryDidoPort;
		private String exitDidoDeviceId;
		private String exitDidoHost;
		private Integer exitDidoPort;
		private String entryRedRelay;
		private String entryGreenRelay;
		private String exitRedRelay;
		private String exitGreenRelay;
		private String presenceInputKey;
		private String exitTriggerInputKey;
	}

	@AssertTrue(message = "MQTT 网关启用时必须显式配置主机、用户名和密码")
	public boolean isMqttConnectionConfigured() {
		return !"mqtt".equalsIgnoreCase(gateway)
				|| !mqtt.enabled
				|| (hasText(mqtt.host) && hasText(mqtt.username) && hasText(mqtt.password));
	}

	@AssertTrue(message = "MQTT 单包上限必须大于 0 且不超过 8 MiB")
	public boolean isMqttPacketLimitValid() {
		return mqtt.maxPacketBytes > 0 && mqtt.maxPacketBytes <= SimpleMqttClient.HARD_MAX_PACKET_BYTES;
	}

	@AssertTrue(message = "TCP DIDO 网关启用时必须显式配置默认设备主机，或为每条车道配置入口和出口主机")
	public boolean isDidoTcpConnectionConfigured() {
		if (!"tcp-dido".equalsIgnoreCase(gateway) || hasText(didoTcp.host)) {
			return true;
		}
		return !lanes.isEmpty() && lanes.stream().allMatch(binding ->
				hasText(binding.entryDidoHost) && hasText(binding.exitDidoHost));
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
