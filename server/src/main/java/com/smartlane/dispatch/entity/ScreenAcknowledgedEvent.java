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
@Table(name = "screen_acknowledged_events")
public class ScreenAcknowledgedEvent {
    @Id
    private String id;

    @Column(nullable = false)
    private OffsetDateTime acknowledgedAt;

    @Column(nullable = false)
    private String operator;
}
