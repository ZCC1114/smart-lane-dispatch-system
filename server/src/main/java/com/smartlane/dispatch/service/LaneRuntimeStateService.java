package com.smartlane.dispatch.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.smartlane.dispatch.entity.Lane;

@Service
public class LaneRuntimeStateService {

    private final Map<String, LaneRuntimeState> states = new ConcurrentHashMap<>();

    public void recordTarget(String laneId, String entrySignal, String exitSignal, String message, OffsetDateTime observedAt) {
        LaneRuntimeState state = state(laneId);
        state.targetEntrySignal = normalizeSignal(entrySignal);
        state.targetExitSignal = normalizeSignal(exitSignal);
        state.commandStatus = signalsMatch(state) ? "SYNCED" : "PENDING";
        state.message = firstNonBlank(message, state.message, "已生成灯控目标，等待设备反馈");
        state.lastCommandAt = resolveTime(observedAt);
        state.entryGreenAt = "GREEN".equals(state.targetEntrySignal) ? state.lastCommandAt : null;
    }

    public void recordRenderedState(String laneId, String entrySignal, String exitSignal, String message, OffsetDateTime observedAt) {
        LaneRuntimeState state = state(laneId);
        state.targetEntrySignal = normalizeSignal(entrySignal);
        state.targetExitSignal = normalizeSignal(exitSignal);
        state.commandStatus = signalsMatch(state) ? "SYNCED" : "PENDING";
        state.message = firstNonBlank(message, state.message, "已生成灯控目标，等待设备反馈");
    }

    public void markCommandPending(String laneId, String message, OffsetDateTime observedAt) {
        LaneRuntimeState state = state(laneId);
        state.commandStatus = "PENDING";
        state.message = firstNonBlank(message, state.message, "灯控指令等待下发");
        state.lastCommandAt = resolveTime(observedAt);
    }

    public void markCommandPublished(String laneId, String message, OffsetDateTime observedAt) {
        LaneRuntimeState state = state(laneId);
        state.commandStatus = signalsMatch(state) ? "SYNCED" : "PENDING";
        state.message = firstNonBlank(message, state.message, "灯控指令已下发，等待设备反馈");
        state.lastCommandAt = resolveTime(observedAt);
    }

    public void markCommandFailed(String laneId, String message, OffsetDateTime observedAt) {
        LaneRuntimeState state = state(laneId);
        state.commandStatus = "FAILED";
        state.message = firstNonBlank(message, "灯控指令下发失败");
        state.lastCommandAt = resolveTime(observedAt);
    }

    public void recordDeviceFeedback(
            String laneId,
            String entrySignal,
            String exitSignal,
            OffsetDateTime observedAt,
            String message) {
        LaneRuntimeState state = state(laneId);
        if (!isBlank(entrySignal)) {
            state.actualEntrySignal = normalizeSignal(entrySignal);
        }
        if (!isBlank(exitSignal)) {
            state.actualExitSignal = normalizeSignal(exitSignal);
        }
        state.lastFeedbackAt = resolveTime(observedAt);
        state.message = firstNonBlank(message, state.message, "设备状态已反馈");
        if (signalsMatch(state)) {
            state.commandStatus = "SYNCED";
        } else if (state.commandStatus == null || "SYNCED".equals(state.commandStatus)) {
            state.commandStatus = "PENDING";
        }
    }

    public void recordDeviceMessage(String laneId, String message, OffsetDateTime observedAt) {
        LaneRuntimeState state = state(laneId);
        state.message = firstNonBlank(message, state.message, "设备状态已更新");
        state.lastFeedbackAt = resolveTime(observedAt);
    }

    public void clearManualTarget(String laneId) {
        LaneRuntimeState state = state(laneId);
        state.targetEntrySignal = null;
        state.targetExitSignal = null;
        state.commandStatus = null;
        state.lastCommandAt = resolveTime(null);
        state.entryGreenAt = null;
    }

    public String targetEntrySignal(String laneId, String fallback) {
        return firstNonBlank(state(laneId).targetEntrySignal, fallback, state(laneId).actualEntrySignal, "RED");
    }

    public String targetExitSignal(String laneId, String fallback) {
        return firstNonBlank(state(laneId).targetExitSignal, fallback, state(laneId).actualExitSignal, "RED");
    }

    public OffsetDateTime entryGreenAt(String laneId) {
        return state(laneId).entryGreenAt;
    }

    public Lane applyRuntimeState(Lane lane) {
        LaneRuntimeState state = state(lane.getId());
        lane.setEntrySignal(firstNonBlank(state.actualEntrySignal, "OFFLINE"));
        lane.setExitSignal(firstNonBlank(state.actualExitSignal, "OFFLINE"));
        lane.setLedStatus(firstNonBlank(state.commandStatus, "PENDING"));
        lane.setLedMessage(firstNonBlank(state.message, "等待设备反馈"));
        return lane;
    }

    public void clearAll() {
        states.clear();
    }

    private LaneRuntimeState state(String laneId) {
        return states.computeIfAbsent(laneId, ignored -> new LaneRuntimeState());
    }

    private boolean signalsMatch(LaneRuntimeState state) {
        return !isBlank(state.targetEntrySignal)
                && !isBlank(state.targetExitSignal)
                && state.targetEntrySignal.equals(state.actualEntrySignal)
                && state.targetExitSignal.equals(state.actualExitSignal);
    }

    private String normalizeSignal(String signal) {
        if ("GREEN".equalsIgnoreCase(signal)) {
            return "GREEN";
        }
        if ("OFFLINE".equalsIgnoreCase(signal)) {
            return "OFFLINE";
        }
        return "RED";
    }

    private OffsetDateTime resolveTime(OffsetDateTime value) {
        return value == null ? OffsetDateTime.now(ZoneOffset.ofHours(8)) : value;
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

    private static class LaneRuntimeState {
        private String targetEntrySignal;
        private String targetExitSignal;
        private String actualEntrySignal;
        private String actualExitSignal;
        private String commandStatus;
        private String message;
        private OffsetDateTime lastCommandAt;
        private OffsetDateTime lastFeedbackAt;
        private OffsetDateTime entryGreenAt;
    }
}
