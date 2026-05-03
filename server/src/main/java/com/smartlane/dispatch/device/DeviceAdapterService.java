package com.smartlane.dispatch.device;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.smartlane.dispatch.entity.Lane;
import com.smartlane.dispatch.service.LaneRuntimeStateService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(value = "app.device.gateway", havingValue = "mock", matchIfMissing = true)
public class DeviceAdapterService implements LaneDeviceGateway {

    private final LaneRuntimeStateService laneRuntimeStateService;

    public DeviceAdapterService(LaneRuntimeStateService laneRuntimeStateService) {
        this.laneRuntimeStateService = laneRuntimeStateService;
    }

    @Override
    public void syncLane(Lane lane) {
        if ("OFFLINE".equals(lane.getMode()) || "OFFLINE".equals(lane.getSensorStatus())) {
            laneRuntimeStateService.markCommandFailed(lane.getId(), "设备离线，等待现场复位", now());
            laneRuntimeStateService.recordDeviceFeedback(lane.getId(), "OFFLINE", "OFFLINE", now(), "设备离线，等待现场复位");
            return;
        }

        String ledMessage;
        if ("GREEN".equals(lane.getExitSignal())) {
            ledMessage = "出口放行，请按序通行";
        } else if ("FULL".equals(lane.getStatus())) {
            ledMessage = "车道已满，入口禁入";
        } else if ("GREEN".equals(lane.getEntrySignal())) {
            ledMessage = "入口开放，请驶入本车道";
        } else if ("RED".equals(lane.getEntrySignal())) {
            ledMessage = "入口待命，请按绿灯车道通行";
        } else if ("BUSY".equals(lane.getStatus())) {
            ledMessage = "车道繁忙，请减速慢行";
        } else {
            ledMessage = "请有序通行";
        }

        if (lane.isPriority()) {
            ledMessage += " | 当前优先放行";
        }
        laneRuntimeStateService.recordDeviceFeedback(lane.getId(), lane.getEntrySignal(), lane.getExitSignal(), now(), ledMessage);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.ofHours(8));
    }
}
