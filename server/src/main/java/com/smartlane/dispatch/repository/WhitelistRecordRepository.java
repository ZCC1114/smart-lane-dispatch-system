package com.smartlane.dispatch.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.smartlane.dispatch.entity.WhitelistRecord;

public interface WhitelistRecordRepository extends JpaRepository<WhitelistRecord, String> {

	boolean existsByPlate(String plate);

	List<WhitelistRecord> findByPlateIn(Collection<String> plates);

	@Query("select record.plate from WhitelistRecord record")
	List<String> findAllPlates();

	@Query("""
			select record from WhitelistRecord record
			where (:query is null
				or lower(record.plate) like concat('%', concat(lower(:query), '%'))
				or lower(record.createdBy) like concat('%', concat(lower(:query), '%'))
				or lower(record.updatedBy) like concat('%', concat(lower(:query), '%')))
			order by record.updatedAt desc
			""")
	Page<WhitelistRecord> search(@Param("query") String query, Pageable pageable);
}
