package com.smartlane.dispatch.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.smartlane.dispatch.entity.UserAccount;

public interface UserAccountRepository extends JpaRepository<UserAccount, String> {
}
