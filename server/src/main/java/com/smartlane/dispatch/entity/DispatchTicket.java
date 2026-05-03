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
@Table(name = "dispatch_tickets")
public class DispatchTicket {

	@Id
	private String id;

	@Column(nullable = false)
	private String plate;

	@Column(nullable = false)
	private OffsetDateTime yardEntryTime;

	private String assignedLaneId;

	private String assignedLaneName;

	private OffsetDateTime assignedAt;

	private String actualLaneId;

	private String actualLaneName;

	private OffsetDateTime laneEntryTime;

	private OffsetDateTime exitTime;

	private OffsetDateTime closedAt;

	@Column(nullable = false)
	private String vehicleType;

	@Column(nullable = false)
	private String status;

	@Column(nullable = false)
	private String source;

	@Column(nullable = false)
	private String operator;

	private String notes;
}
