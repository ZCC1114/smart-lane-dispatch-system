package com.smartlane.dispatch.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.smartlane.dispatch.entity.Lane;

public interface LaneRepository extends JpaRepository<Lane, String> {

    List<Lane> findAllByOrderByCodeAsc();
}
