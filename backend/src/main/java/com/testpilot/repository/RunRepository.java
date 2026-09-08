package com.testpilot.repository;

import com.testpilot.model.entity.RunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RunRepository extends JpaRepository<RunEntity, String> {

    // Bir Suite silinince (SuiteController.deleteSuite) o suite'e ait testlerin
    // suite bağlantısını topluca temizler -- testler SİLİNMEZ, sadece
    // mobile_run_suites bağlantı tablosundaki ilgili satırlar silinir (Run<->Suite
    // çok-çok ilişki olduğu için JPQL ile değil, doğrudan native sorguyla
    // yapılıyor -- JPQL join tablosunu doğrudan hedefleyemiyor).
    @Modifying
    @Query(value = "DELETE FROM mobile_run_suites WHERE suite_id = :suiteId", nativeQuery = true)
    void clearSuiteReferences(@Param("suiteId") Long suiteId);

    // steps @OneToMany LAZY olduğu için, RunStore dışına (transaction kapandıktan
    // sonra) taşınacaksa steps'in de aynı sorguda (JOIN FETCH ile) gelmesi lazım --
    // yoksa LazyInitializationException alırız.

    @Query("SELECT DISTINCT r FROM RunEntity r LEFT JOIN FETCH r.steps")
    List<RunEntity> findAllWithSteps();

    @Query("SELECT r FROM RunEntity r LEFT JOIN FETCH r.steps WHERE r.id = :id")
    Optional<RunEntity> findByIdWithSteps(@Param("id") String id);
}
