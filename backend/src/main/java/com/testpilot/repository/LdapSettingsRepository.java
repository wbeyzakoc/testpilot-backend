package com.testpilot.repository;

import com.testpilot.model.LdapSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LdapSettingsRepository extends JpaRepository<LdapSettings, Long> {
}
