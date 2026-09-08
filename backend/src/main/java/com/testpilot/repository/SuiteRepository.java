package com.testpilot.repository;

import com.testpilot.model.Suite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SuiteRepository extends JpaRepository<Suite, Long> {
    List<Suite> findByProject_Id(Long projectId);
    List<Suite> findByProject_IdIn(List<Long> projectIds);
}
