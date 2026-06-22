package com.kista.application.service.market;

import com.kista.domain.model.market.FearGreedSnapshot;
import com.kista.domain.port.in.GetFearGreedUseCase;
import com.kista.domain.port.out.FearGreedSnapshotPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class FearGreedQueryService implements GetFearGreedUseCase {

    private final FearGreedSnapshotPort fearGreedSnapshotPort;

    @Override
    public List<FearGreedSnapshot> getRecent(String source, int days) {
        // days일 전부터(포함) 오늘까지 날짜 범위로 조회
        LocalDate since = LocalDate.now().minusDays(days);
        return fearGreedSnapshotPort.findBySourceSince(source, since);
    }
}
