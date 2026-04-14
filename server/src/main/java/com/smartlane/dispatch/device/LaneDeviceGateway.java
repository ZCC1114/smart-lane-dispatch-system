package com.smartlane.dispatch.device;

import java.util.List;

import com.smartlane.dispatch.entity.Lane;

public interface LaneDeviceGateway {

    void syncLane(Lane lane);

    default void syncBatch(List<Lane> lanes) {
        for (Lane lane : lanes) {
            syncLane(lane);
        }
    }
}
