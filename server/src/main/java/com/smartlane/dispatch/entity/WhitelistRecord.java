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
@Table(name = "whitelist_records")
public class WhitelistRecord {
	@Id
	private String id;

	@Column(nullable = false, unique = true)
	private String plate;

	@Column(nullable = false)
	private OffsetDateTime createdAt;

	@Column(nullable = false)
	private OffsetDateTime updatedAt;

	@Column(nullable = false)
	private String createdBy;

	@Column(nullable = false)
	private String updatedBy;
}
