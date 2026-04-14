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
@Table(name = "entry_logs")
public class EntryLog {
    @Id
    private String id;

    @Column(nullable = false)
    private String plate;

    @Column(nullable = false)
    private String laneId;

    @Column(nullable = false)
    private String laneName;

    @Column(nullable = false)
    private OffsetDateTime entryTime;

    private OffsetDateTime exitTime;

    @Column(nullable = false)
    private String vehicleType;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private String source;

    @Column(nullable = false)
    private String operator;
}
