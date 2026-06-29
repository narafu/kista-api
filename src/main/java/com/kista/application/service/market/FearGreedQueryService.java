package com.kista.application.service.market;

import com.kista.common.TimeZones;
import com.kista.domain.model.market.FearGreedSnapshot;
import com.kista.domain.port.in.GetFearGreedUseCase;
import com.kista.domain.port.out.FearGreedSnapshotPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class FearGreedQueryService implements GetFearGreedUseCase {

    private final FearGreedSnapshotPort fearGreedSnapshotPort;

    @Override
    public List<FearGreedSnapshot> getRecent(String source, int days) {
        // days일 전 KST 00:00부터 현재까지 시각 범위로 조회
        Instant since = LocalDate.now(TimeZones.KST).minusDays(days).atStartOfDay(TimeZones.KST).toInstant();
        List<FearGreedSnapshot> snapshots = fearGreedSnapshotPort.findBySourceSince(source, since);

        // KST 일자별 최신 1건만 남겨 일봉 기준으로 응답
        Map<LocalDate, FearGreedSnapshot> latestByDate = new LinkedHashMap<>();
        for (FearGreedSnapshot snapshot : snapshots) {
            LocalDate kstDate = snapshot.snapshotDate().atZone(TimeZones.KST).toLocalDate();
            latestByDate.put(kstDate, snapshot);
        }
        return List.copyOf(latestByDate.values());
    }
}
