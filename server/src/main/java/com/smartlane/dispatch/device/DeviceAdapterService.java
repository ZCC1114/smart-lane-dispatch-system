package com.smartlane.dispatch.device;

import com.smartlane.dispatch.entity.Lane;
import org.springframework.stereotype.Service;

@Service
public class DeviceAdapterService implements LaneDeviceGateway {

    @Override
    public void syncLane(Lane lane) {
        if ("OFFLINE".equals(lane.getMode()) || "OFFLINE".equals(lane.getSensorStatus())) {
            lane.setLedStatus("FAILED");
            lane.setLedMessage("设备离线，等待现场复位");
            return;
        }

        lane.setLedStatus("SYNCED");
        if ("GREEN".equals(lane.getExitSignal())) {
            lane.setLedMessage("出口放行，请按序通行");
        } else if ("FULL".equals(lane.getStatus())) {
            lane.setLedMessage("车道已满，入口禁入");
        } else if ("BUSY".equals(lane.getStatus())) {
            lane.setLedMessage("车道繁忙，请减速慢行");
        } else {
            lane.setLedMessage("请有序通行");
        }

        if (lane.isPriority()) {
            lane.setLedMessage(lane.getLedMessage() + " | 当前优先放行");
        }
    }
}
