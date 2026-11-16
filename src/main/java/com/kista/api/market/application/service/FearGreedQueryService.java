package com.kista.api.market.application.service;

import com.kista.shared.kernel.TimeZones;
import com.kista.api.market.domain.model.FearGreedSnapshot;
import com.kista.api.market.application.port.output.FearGreedSnapshotPort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

// CNN은 스케쥴러가 쌓은 DB 스냅샷을 조회한다(리포지토리 기본 트랜잭션).
@Service
public class FearGreedQueryService {

    static final int MAX_DAYS = 365; // 조회 기간 상한 — kista-ui 최대 옵션

    private final FearGreedSnapshotPort fearGreedSnapshotPort;
    private final Clock clock; // since 기준일 — 테스트에서 고정

    FearGreedQueryService(FearGreedSnapshotPort fearGreedSnapshotPort, Clock clock) {
        this.fearGreedSnapshotPort = fearGreedSnapshotPort;
        this.clock = clock;
    }

    // 지정 source의 최근 days일(1~365) 일별 값을 날짜 오름차순으로 조회
    public List<FearGreedSnapshot> getRecent(String source, int days) {
        if (days < 1 || days > MAX_DAYS) throw new IllegalArgumentException("조회 기간은 1~" + MAX_DAYS + "일이어야 합니다.");
        // days일 전 KST 00:00부터 현재까지 시각 범위로 조회
        Instant since = LocalDate.ofInstant(clock.instant(), TimeZones.KST).minusDays(days).atStartOfDay(TimeZones.KST).toInstant();
        List<FearGreedSnapshot> snapshots = fearGreedSnapshotPort.findBySourceSince(source, since);

        // KST 일자별 최신 1건만 남겨 일봉 기준으로 응답
        return List.copyOf(snapshots.stream()
                .collect(Collectors.toMap(
                        s -> s.snapshotDate().atZone(TimeZones.KST).toLocalDate(),
                        s -> s,
                        (existing, latest) -> latest,
                        LinkedHashMap::new))
                .values());
    }
}
