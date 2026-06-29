package com.kista.application.service.market;

import com.kista.domain.model.market.FearGreedSnapshot;
import com.kista.domain.port.in.FetchFearGreedUseCase;
import com.kista.domain.port.out.CnnFearGreedPort;
import com.kista.domain.port.out.CryptoFearGreedPort;
import com.kista.domain.port.out.FearGreedSnapshotPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
class FearGreedService implements FetchFearGreedUseCase {

    private final CryptoFearGreedPort cryptoFearGreedPort;
    private final CnnFearGreedPort cnnFearGreedPort;
    private final FearGreedSnapshotPort fearGreedSnapshotPort;

    private static final String SOURCE_CRYPTO = "CRYPTO";
    private static final String SOURCE_CNN    = "CNN";

    @Override
    public void fetchAndSave(Instant snapshotDate) {
        // CRYPTO와 CNN을 독립 처리 — 한쪽 실패가 다른쪽 저장을 롤백하지 않도록
        try {
            CryptoFearGreedPort.CryptoFearGreedData crypto = cryptoFearGreedPort.fetch();
            fearGreedSnapshotPort.save(FearGreedSnapshot.of(SOURCE_CRYPTO, snapshotDate, crypto.value(), crypto.rating()));
            log.info("CRYPTO 공포탐욕지수 저장 (snapshotDate={}, value={}, rating={})", snapshotDate, crypto.value(), crypto.rating());
        } catch (Exception e) {
            log.error("CRYPTO 공포탐욕지수 수집 실패: {}", e.getMessage(), e);
        }

        try {
            CnnFearGreedPort.CnnFearGreedData cnn = cnnFearGreedPort.fetch();
            fearGreedSnapshotPort.save(FearGreedSnapshot.of(SOURCE_CNN, snapshotDate, cnn.value(), cnn.rating()));
            log.info("CNN 공포탐욕지수 저장 (snapshotDate={}, value={}, rating={})", snapshotDate, cnn.value(), cnn.rating());
        } catch (Exception e) {
            log.error("CNN 공포탐욕지수 수집 실패: {}", e.getMessage(), e);
        }
    }
}
