package com.kista.application.service.market;

import com.kista.domain.model.market.FearGreedSnapshot;
import com.kista.domain.port.in.FetchFearGreedUseCase;
import com.kista.domain.port.out.CnnFearGreedPort;
import com.kista.domain.port.out.CryptoFearGreedPort;
import com.kista.domain.port.out.FearGreedSnapshotPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

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
    public void fetchAndSave(LocalDate date) {
        boolean cryptoExists = fearGreedSnapshotPort.existsBySourceAndDate(SOURCE_CRYPTO, date);
        boolean cnnExists    = fearGreedSnapshotPort.existsBySourceAndDate(SOURCE_CNN, date);

        if (cryptoExists && cnnExists) {
            log.info("공포탐욕지수 이미 저장됨 — skip (date={})", date);
            return;
        }

        // CRYPTO와 CNN을 독립 처리 — 한쪽 실패가 다른쪽 저장을 롤백하지 않도록
        if (!cryptoExists) {
            try {
                CryptoFearGreedPort.CryptoFearGreedData crypto = cryptoFearGreedPort.fetch();
                fearGreedSnapshotPort.save(FearGreedSnapshot.of(SOURCE_CRYPTO, date, crypto.value(), crypto.rating()));
                log.info("CRYPTO 공포탐욕지수 저장 (date={}, value={}, rating={})", date, crypto.value(), crypto.rating());
            } catch (Exception e) {
                log.error("CRYPTO 공포탐욕지수 수집 실패: {}", e.getMessage(), e);
            }
        }

        if (!cnnExists) {
            // 예외는 스케쥴러로 전파 — notifyError 알림 처리
            CnnFearGreedPort.CnnFearGreedData cnn = cnnFearGreedPort.fetch();
            fearGreedSnapshotPort.save(FearGreedSnapshot.of(SOURCE_CNN, date, cnn.value(), cnn.rating()));
            log.info("CNN 공포탐욕지수 저장 (date={}, value={}, rating={})", date, cnn.value(), cnn.rating());
        }
    }
}
