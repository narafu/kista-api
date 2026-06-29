package com.kista.adapter.out.persistence.feargreed;

import com.kista.adapter.out.persistence.BaseCreatedAtEntity;
import com.kista.domain.model.market.FearGreedRating;
import com.kista.domain.model.market.FearGreedSnapshot;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "fear_greed_snapshots",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_fear_greed_source_date",
        columnNames = {"source", "snapshot_date"}
    )
)
@Getter
@NoArgsConstructor
class FearGreedSnapshotEntity extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source", nullable = false, length = 20)
    private String source; // "CRYPTO" | "CNN"

    @Column(name = "snapshot_date", nullable = false)
    private Instant snapshotDate;

    @Column(name = "value", nullable = false)
    private int value; // 0-100

    @Enumerated(EnumType.STRING)
    @Column(name = "rating", nullable = false, length = 20)
    private FearGreedRating rating;

    static FearGreedSnapshotEntity from(FearGreedSnapshot snapshot) {
        FearGreedSnapshotEntity entity = new FearGreedSnapshotEntity();
        entity.source       = snapshot.source();
        entity.snapshotDate = snapshot.snapshotDate();
        entity.value        = snapshot.value();
        entity.rating       = snapshot.rating();
        return entity;
    }

    FearGreedSnapshot toDomain() {
        return new FearGreedSnapshot(id, source, snapshotDate, value, rating, createdAt);
    }
}
