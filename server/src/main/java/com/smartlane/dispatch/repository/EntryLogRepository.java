package com.smartlane.dispatch.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.smartlane.dispatch.entity.EntryLog;

public interface EntryLogRepository extends JpaRepository<EntryLog, String> {

    List<EntryLog> findAllByOrderByEntryTimeDesc();

    List<EntryLog> findByExitTimeIsNullOrderByEntryTimeAsc();

    List<EntryLog> findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc(String laneId);

    List<EntryLog> findByPlateIgnoreCaseAndExitTimeIsNullOrderByEntryTimeAsc(String plate);
}
