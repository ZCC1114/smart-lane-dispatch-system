package com.smartlane.dispatch.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "lanes")
public class Lane {
    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String zone;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private String mode;

    @Column(nullable = false)
    private int capacity;

    @Column(nullable = false)
    private int vehicleCount;
    private String currentPlate;

    @Column(nullable = false)
    private OffsetDateTime lastActionAt;

    @Column(nullable = false)
    private String entrySignal;

    @Column(nullable = false)
    private String exitSignal;

    @Column(nullable = false)
    private String ledMessage;

    @Column(nullable = false)
    private String ledStatus;

    @Column(nullable = false)
    private boolean priority;

    private String sensorStatus;

    private OffsetDateTime lastSensorAt;

    private String lastEntryPlate;

    private OffsetDateTime lastEntryAt;
}
