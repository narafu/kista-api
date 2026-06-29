package com.kista.adapter.out.persistence.feargreed;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface FearGreedSnapshotJpaRepository extends JpaRepository<FearGreedSnapshotEntity, UUID> {
    // source + snapshot_date >= since, 시각 오름차순
    List<FearGreedSnapshotEntity> findBySourceAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(
            String source, Instant since);
}
