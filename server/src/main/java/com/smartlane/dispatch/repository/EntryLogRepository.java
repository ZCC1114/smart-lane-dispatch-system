package com.smartlane.dispatch.repository;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.smartlane.dispatch.entity.EntryLog;

public interface EntryLogRepository extends JpaRepository<EntryLog, String> {

    List<EntryLog> findAllByOrderByEntryTimeDesc();

    List<EntryLog> findByExitTimeIsNullOrderByEntryTimeAsc();

    List<EntryLog> findByLaneIdAndExitTimeIsNullOrderByEntryTimeAsc(String laneId);

    List<EntryLog> findByPlateIgnoreCaseAndExitTimeIsNullOrderByEntryTimeAsc(String plate);

    @Query("""
            select log from EntryLog log
            where (:query is null or lower(log.plate) like concat('%', concat(lower(:query), '%')))
              and (:status is null or lower(log.status) = lower(:status))
              and (:laneId is null or lower(log.laneId) = lower(:laneId))
              and (:entryTimeFrom is null or log.entryTime >= :entryTimeFrom)
              and (:entryTimeTo is null or log.entryTime <= :entryTimeTo)
            order by log.entryTime desc
            """)
    Page<EntryLog> searchLogs(
            @Param("query") String query,
            @Param("status") String status,
            @Param("laneId") String laneId,
            @Param("entryTimeFrom") OffsetDateTime entryTimeFrom,
            @Param("entryTimeTo") OffsetDateTime entryTimeTo,
            Pageable pageable);
}
