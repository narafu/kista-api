package com.kista.market.adapter.out.persistence.feargreed;

import com.kista.market.domain.model.FearGreedSnapshot;
import com.kista.market.application.port.output.FearGreedSnapshotPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
    public List<FearGreedSnapshot> findBySourceSince(String source, Instant since) {
        return repository
                .findBySourceAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(source, since)
                .stream()
                .map(FearGreedSnapshotEntity::toDomain)
                .toList();
    }
}
