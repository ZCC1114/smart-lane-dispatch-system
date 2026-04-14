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
@Table(name = "blacklist_records")
public class BlacklistRecord {
    @Id
    private String id;

    @Column(nullable = false)
    private String plate;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private String level;

    @Column(nullable = false)
    private OffsetDateTime effectiveDate;

    @Column(nullable = false)
    private String operator;

    @Column(nullable = false)
    private boolean active;
}
