package com.testpilot.repository;

import com.testpilot.model.AppUser;
import com.testpilot.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    boolean existsByNameIgnoreCase(String name);
    List<Project> findByMembersContaining(AppUser member);

    // UserController.deleteUser icin -- Project.members @ManyToMany join tablosu
    // (mobile_project_members) uzerinde JPA'nin bilmedigi bir FK oldugu icin,
    // kullaniciyi silmeden once tum uyelik kayitlarini elle temizlemek gerekiyor
    // (SuiteController.deleteSuite'teki clearSuiteReferences ile ayni sebep).
    @Modifying
    @Query(value = "DELETE FROM mobile_project_members WHERE user_id = :userId", nativeQuery = true)
    void removeUserFromAllProjects(@Param("userId") Long userId);
}
