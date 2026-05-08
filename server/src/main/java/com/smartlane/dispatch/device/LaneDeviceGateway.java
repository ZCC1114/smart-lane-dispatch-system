package com.smartlane.dispatch.device;

import java.util.List;

import com.smartlane.dispatch.entity.Lane;

public interface LaneDeviceGateway {

    void syncLane(Lane lane);

    default void controlRelay(Lane lane, String relayTarget, boolean on, String reason) {
        throw new UnsupportedOperationException("当前设备网关不支持继电器直控");
    }

    default void clearSyncState() {
    }

    default void syncBatch(List<Lane> lanes) {
        for (Lane lane : lanes) {
            syncLane(lane);
        }
    }
}
