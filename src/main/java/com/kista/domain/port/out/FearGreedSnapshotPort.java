package com.kista.domain.port.out;

import com.kista.domain.model.market.FearGreedSnapshot;

import java.time.Instant;
import java.util.List;

public interface FearGreedSnapshotPort {
    void save(FearGreedSnapshot snapshot);
    // source 기준 since(포함) 이후 스냅샷을 시각 오름차순으로 조회
    List<FearGreedSnapshot> findBySourceSince(String source, Instant since);
}
