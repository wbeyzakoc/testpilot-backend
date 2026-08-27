package com.testpilot.repository;

import com.testpilot.model.entity.RunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RunRepository extends JpaRepository<RunEntity, String> {

    // steps @OneToMany LAZY olduğu için, RunStore dışına (transaction kapandıktan
    // sonra) taşınacaksa steps'in de aynı sorguda (JOIN FETCH ile) gelmesi lazım --
    // yoksa LazyInitializationException alırız.

    @Query("SELECT DISTINCT r FROM RunEntity r LEFT JOIN FETCH r.steps")
    List<RunEntity> findAllWithSteps();

    @Query("SELECT r FROM RunEntity r LEFT JOIN FETCH r.steps WHERE r.id = :id")
    Optional<RunEntity> findByIdWithSteps(@Param("id") String id);
}
