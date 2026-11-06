package com.recon.reconciliation.repository;

import com.recon.reconciliation.domain.Reconciliation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRepository extends JpaRepository<Reconciliation, Long> {}
