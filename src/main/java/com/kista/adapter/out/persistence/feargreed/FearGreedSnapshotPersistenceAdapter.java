package com.kista.adapter.out.persistence.feargreed;

import com.kista.domain.model.market.FearGreedSnapshot;
import com.kista.domain.port.out.FearGreedSnapshotPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor
public class FearGreedSnapshotPersistenceAdapter implements FearGreedSnapshotPort {

    private final FearGreedSnapshotJpaRepository repository;

    @Override
    public void save(FearGreedSnapshot snapshot) {
        repository.save(FearGreedSnapshotEntity.from(snapshot));
    }

    @Override
    public boolean existsBySourceAndDate(String source, LocalDate date) {
        return repository.existsBySourceAndSnapshotDate(source, date);
    }

    @Override
    public List<FearGreedSnapshot> findBySourceSince(String source, LocalDate since) {
        return repository
                .findBySourceAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(source, since)
                .stream()
                .map(FearGreedSnapshotEntity::toDomain)
                .toList();
    }
}
