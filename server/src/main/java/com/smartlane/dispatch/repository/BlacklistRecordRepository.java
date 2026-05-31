package com.smartlane.dispatch.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.smartlane.dispatch.entity.BlacklistRecord;

public interface BlacklistRecordRepository extends JpaRepository<BlacklistRecord, String> {

    List<BlacklistRecord> findAllByOrderByEffectiveDateDesc();

    Optional<BlacklistRecord> findFirstByPlateIgnoreCaseAndActiveTrue(String plate);

    List<BlacklistRecord> findByActiveTrueAndPlateIn(Collection<String> plates);

    @Query("""
            select record from BlacklistRecord record
            where (:query is null
                or lower(record.plate) like concat('%', concat(lower(:query), '%'))
                or lower(record.reason) like concat('%', concat(lower(:query), '%'))
                or lower(record.operator) like concat('%', concat(lower(:query), '%')))
            order by record.effectiveDate desc
            """)
    Page<BlacklistRecord> search(@Param("query") String query, Pageable pageable);
}
