package com.kista.market.application.service;

import com.kista.market.domain.model.FearGreedSnapshot;
import com.kista.market.application.event.FearGreedFetchFailedEvent;
import com.kista.market.application.usecase.FetchFearGreedUseCase;
import com.kista.market.application.port.output.CnnFearGreedPort;
import com.kista.market.application.port.output.CryptoFearGreedPort;
import com.kista.market.application.port.output.FearGreedSnapshotPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
class FearGreedService implements FetchFearGreedUseCase {

    private final CryptoFearGreedPort cryptoFearGreedPort;
    private final CnnFearGreedPort cnnFearGreedPort;
    private final FearGreedSnapshotPort fearGreedSnapshotPort;
    private final ApplicationEventPublisher eventPublisher;

    private static final String SOURCE_CRYPTO = "CRYPTO";
    private static final String SOURCE_CNN    = "CNN";

    @Override
    public void fetchAndSave(Instant snapshotDate) {
        // CRYPTO와 CNN을 독립 처리 — 한쪽 실패가 다른쪽 저장을 롤백하지 않도록
        fetchAndSaveSource(SOURCE_CRYPTO, snapshotDate,
                () -> { var d = cryptoFearGreedPort.fetch(); return FearGreedSnapshot.of(SOURCE_CRYPTO, snapshotDate, d.value(), d.rating()); });
        fetchAndSaveSource(SOURCE_CNN, snapshotDate,
                () -> { var d = cnnFearGreedPort.fetch(); return FearGreedSnapshot.of(SOURCE_CNN, snapshotDate, d.value(), d.rating()); });
    }

    private void fetchAndSaveSource(String source, Instant snapshotDate, Supplier<FearGreedSnapshot> fetcher) {
        try {
            FearGreedSnapshot snapshot = fetcher.get();
            fearGreedSnapshotPort.save(snapshot);
            log.info("{} 공포탐욕지수 저장 (snapshotDate={}, value={}, rating={})",
                    source, snapshotDate, snapshot.value(), snapshot.rating());
        } catch (Exception e) {
            log.error("{} 공포탐욕지수 수집 실패: {}", source, e.getMessage(), e);
            eventPublisher.publishEvent(new FearGreedFetchFailedEvent(e.getMessage()));
        }
    }
}
