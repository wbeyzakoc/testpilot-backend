package com.testpilot.repository;

import com.testpilot.model.AppUser;
import com.testpilot.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    boolean existsByNameIgnoreCase(String name);
    List<Project> findByMembersContaining(AppUser member);
}
