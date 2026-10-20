package com.kista.api.market.application.service;

import com.kista.api.market.domain.model.FearGreedSnapshot;
import com.kista.api.market.application.event.FearGreedFetchFailedEvent;
import com.kista.api.market.application.usecase.FetchFearGreedUseCase;
import com.kista.api.market.application.port.output.CnnFearGreedPort;
import com.kista.api.market.application.port.output.FearGreedSnapshotPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;

// CNN 공포탐욕지수 수집·저장 — 비공식 엔드포인트라 장애 버퍼로 DB에 쌓는다(CRYPTO는 조회 시 alternative.me 직접 호출)
@Slf4j
@Service
@RequiredArgsConstructor
class FearGreedService implements FetchFearGreedUseCase {

    private final CnnFearGreedPort cnnFearGreedPort;
    private final FearGreedSnapshotPort fearGreedSnapshotPort;
    private final ApplicationEventPublisher eventPublisher;

    private static final String SOURCE_CNN = "CNN";

    @Override
    public void fetchAndSave(Instant snapshotDate) {
        try {
            var data = cnnFearGreedPort.fetch();
            FearGreedSnapshot snapshot = FearGreedSnapshot.of(SOURCE_CNN, snapshotDate, data.value(), data.rating());
            fearGreedSnapshotPort.save(snapshot);
            log.info("CNN 공포탐욕지수 저장 (snapshotDate={}, value={}, rating={})",
                    snapshotDate, snapshot.value(), snapshot.rating());
        } catch (Exception e) {
            log.error("CNN 공포탐욕지수 수집 실패: {}", e.getMessage(), e);
            eventPublisher.publishEvent(new FearGreedFetchFailedEvent(e.getMessage()));
        }
    }
}
