package com.smartlane.dispatch.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.smartlane.dispatch.entity.BlacklistRecord;

public interface BlacklistRecordRepository extends JpaRepository<BlacklistRecord, String> {

    List<BlacklistRecord> findAllByOrderByEffectiveDateDesc();

    Optional<BlacklistRecord> findFirstByPlateIgnoreCaseAndActiveTrue(String plate);
}
