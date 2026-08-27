package com.testpilot.repository;

import com.testpilot.model.entity.RunStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

// Normalde adimlar RunEntity.steps (@OneToMany) uzerinden erisilecek, bu
// repository ayri sorgu gerekirse diye var -- su an kullanilmiyor.
public interface RunStepRepository extends JpaRepository<RunStepEntity, Long> {
}
