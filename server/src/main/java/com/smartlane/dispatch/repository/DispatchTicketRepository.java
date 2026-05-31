package com.smartlane.dispatch.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.smartlane.dispatch.entity.DispatchTicket;

public interface DispatchTicketRepository extends JpaRepository<DispatchTicket, String> {

	List<DispatchTicket> findAllByOrderByYardEntryTimeDesc();

	List<DispatchTicket> findByClosedAtIsNullOrderByYardEntryTimeAsc();

	List<DispatchTicket> findByPlateIgnoreCaseAndClosedAtIsNullOrderByYardEntryTimeDesc(String plate);

	List<DispatchTicket> findByPlateInOrderByYardEntryTimeDesc(Collection<String> plates);

	List<DispatchTicket> findByYardEntryTimeBetweenOrderByYardEntryTimeDesc(OffsetDateTime start, OffsetDateTime end);

	List<DispatchTicket> findByYardEntryTimeGreaterThanEqualOrderByYardEntryTimeDesc(OffsetDateTime start);

	List<DispatchTicket> findByYardEntryTimeLessThanEqualOrderByYardEntryTimeDesc(OffsetDateTime end);

	List<DispatchTicket> findByAssignedLaneIdAndLaneEntryTimeIsNullAndClosedAtIsNullOrderByAssignedAtAsc(String assignedLaneId);

	List<DispatchTicket> findByActualLaneIdAndExitTimeIsNullAndClosedAtIsNullOrderByLaneEntryTimeAsc(String actualLaneId);
}
