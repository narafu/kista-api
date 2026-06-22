package com.kista.domain.port.out;

import com.kista.domain.model.market.FearGreedSnapshot;

import java.time.LocalDate;
import java.util.List;

public interface FearGreedSnapshotPort {
    void save(FearGreedSnapshot snapshot);
    boolean existsBySourceAndDate(String source, LocalDate date);
    // source 기준 since(포함) 이후 스냅샷을 날짜 오름차순으로 조회
    List<FearGreedSnapshot> findBySourceSince(String source, LocalDate since);
}
