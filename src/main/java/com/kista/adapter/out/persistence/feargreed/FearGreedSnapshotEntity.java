package com.kista.adapter.out.persistence.feargreed;

import com.kista.adapter.out.persistence.BaseCreatedAtEntity;
import com.kista.domain.model.market.FearGreedRating;
import com.kista.domain.model.market.FearGreedSnapshot;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "fear_greed_snapshots")
@Getter
@NoArgsConstructor
class FearGreedSnapshotEntity extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "snapshot_date", nullable = false, unique = true)
    private LocalDate snapshotDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "crypto_rating", nullable = false, length = 20)
    private FearGreedRating cryptoRating;

    @Column(name = "crypto_value", nullable = false)
    private int cryptoValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "cnn_rating", nullable = false, length = 20)
    private FearGreedRating cnnRating;

    @Column(name = "cnn_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal cnnScore;

    static FearGreedSnapshotEntity from(FearGreedSnapshot snapshot) {
        FearGreedSnapshotEntity entity = new FearGreedSnapshotEntity();
        entity.snapshotDate = snapshot.snapshotDate();
        entity.cryptoRating = snapshot.cryptoRating();
        entity.cryptoValue  = snapshot.cryptoValue();
        entity.cnnRating    = snapshot.cnnRating();
        entity.cnnScore     = snapshot.cnnScore();
        return entity;
    }

    FearGreedSnapshot toDomain() {
        return new FearGreedSnapshot(id, snapshotDate, cryptoRating, cryptoValue, cnnRating, cnnScore, createdAt);
    }
}
