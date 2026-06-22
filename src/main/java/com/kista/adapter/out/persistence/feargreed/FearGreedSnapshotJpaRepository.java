package com.kista.adapter.out.persistence.feargreed;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface FearGreedSnapshotJpaRepository extends JpaRepository<FearGreedSnapshotEntity, UUID> {
    boolean existsBySourceAndSnapshotDate(String source, LocalDate snapshotDate);
    // source + snapshot_date >= since, 날짜 오름차순
    List<FearGreedSnapshotEntity> findBySourceAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(
            String source, LocalDate since);
}
