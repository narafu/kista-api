package com.kista.adapter.out.persistence.feargreed;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.UUID;

interface FearGreedSnapshotJpaRepository extends JpaRepository<FearGreedSnapshotEntity, UUID> {
    boolean existsBySnapshotDate(LocalDate snapshotDate);
}
