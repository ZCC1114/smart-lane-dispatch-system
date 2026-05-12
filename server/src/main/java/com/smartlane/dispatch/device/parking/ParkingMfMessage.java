package com.smartlane.dispatch.device.parking;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 停车场总入口车牌抓拍设备（MF 系列）的 MQTT 消息模型。
 * <p>
 * 支持两类消息：
 * <ul>
 *   <li>{@code heartbeat} — 设备心跳，包含运行状态、网络、系统资源等</li>
 *   <li>{@code plateResult} — 车牌识别结果，包含车牌号、抓拍图片、置信度等</li>
 * </ul>
 */
public record ParkingMfMessage(
		String cmd,
		String sn,
		String msgId,
		Long timestamp,
		String timezone,
		HeartbeatData heartbeatData,
		PlateResultData plateResultData) {

	public boolean isHeartbeat() {
		return "heartbeat".equals(cmd);
	}

	public boolean isPlateResult() {
		return "plateResult".equals(cmd);
	}

	// ------------------------------------------------------------------
	// Heartbeat
	// ------------------------------------------------------------------

	public record HeartbeatData(
			List<DeviceStatus> deviceStatus,
			String ip,
			RuntimeInfo runtime,
			String version) {
	}

	public record DeviceStatus(
			String checkTime,
			String deviceNo,
			String groupId,
			String network) {
	}

	public record RuntimeInfo(
			CpuInfo cpu,
			DiskInfo disk,
			MemoryInfo memory,
			OsInfo os) {
	}

	public record CpuInfo(
			String load,
			Integer logicalProcessorCount,
			String name,
			Integer physicalProcessorCount) {
	}

	public record DiskInfo(
			Double free,
			Double total) {
	}

	public record MemoryInfo(
			Double free,
			Double total) {
	}

	public record OsInfo(
			String osName,
			String osVersion) {
	}

	// ------------------------------------------------------------------
	// PlateResult
	// ------------------------------------------------------------------

	public record PlateResultData(
			String carBrand,
			String carImg,
			Integer confidence,
			String deviceNo,
			String groupId,
			String parkingTime,
			String plateColor,
			String plateNo,
			Boolean realTime,
			Long uploadTime) {
	}
}
