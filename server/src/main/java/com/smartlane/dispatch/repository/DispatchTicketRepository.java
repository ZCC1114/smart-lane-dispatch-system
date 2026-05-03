package com.smartlane.dispatch.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.smartlane.dispatch.entity.DispatchTicket;

public interface DispatchTicketRepository extends JpaRepository<DispatchTicket, String> {

	List<DispatchTicket> findAllByOrderByYardEntryTimeDesc();

	List<DispatchTicket> findByClosedAtIsNullOrderByYardEntryTimeAsc();

	List<DispatchTicket> findByPlateIgnoreCaseAndClosedAtIsNullOrderByYardEntryTimeDesc(String plate);

	List<DispatchTicket> findByAssignedLaneIdAndLaneEntryTimeIsNullAndClosedAtIsNullOrderByAssignedAtAsc(String assignedLaneId);

	List<DispatchTicket> findByActualLaneIdAndExitTimeIsNullAndClosedAtIsNullOrderByLaneEntryTimeAsc(String actualLaneId);
}
