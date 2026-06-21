package com.kista.adapter.out.persistence.feargreed;

import com.kista.domain.model.market.FearGreedSnapshot;
import com.kista.domain.port.out.FearGreedSnapshotPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@RequiredArgsConstructor
public class FearGreedSnapshotPersistenceAdapter implements FearGreedSnapshotPort {

    private final FearGreedSnapshotJpaRepository repository;

    @Override
    public void save(FearGreedSnapshot snapshot) {
        repository.save(FearGreedSnapshotEntity.from(snapshot));
    }

    @Override
    public boolean existsByDate(LocalDate date) {
        return repository.existsBySnapshotDate(date);
    }
}
