package com.kista.domain.port.out;

import com.kista.domain.model.market.FearGreedSnapshot;

import java.time.LocalDate;

public interface FearGreedSnapshotPort {
    void save(FearGreedSnapshot snapshot);
    boolean existsByDate(LocalDate date);
}
