package com.kista.application.service.market;

import com.kista.domain.model.market.FearGreedSnapshot;
import com.kista.domain.port.in.FetchFearGreedUseCase;
import com.kista.domain.port.out.CnnFearGreedPort;
import com.kista.domain.port.out.CryptoFearGreedPort;
import com.kista.domain.port.out.FearGreedSnapshotPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
class FearGreedService implements FetchFearGreedUseCase {

    private final CryptoFearGreedPort cryptoFearGreedPort;
    private final CnnFearGreedPort cnnFearGreedPort;
    private final FearGreedSnapshotPort fearGreedSnapshotPort;

    @Override
    @Transactional
    public void fetchAndSave(LocalDate date) {
        // 당일 데이터가 이미 있으면 중복 저장 skip
        if (fearGreedSnapshotPort.existsByDate(date)) {
            log.info("공포탐욕지수 이미 저장됨 — skip (date={})", date);
            return;
        }

        // 두 API에서 데이터 수집
        CryptoFearGreedPort.CryptoFearGreedData crypto = cryptoFearGreedPort.fetch();
        CnnFearGreedPort.CnnFearGreedData cnn = cnnFearGreedPort.fetch();

        FearGreedSnapshot snapshot = FearGreedSnapshot.of(date, crypto.rating(), crypto.value(), cnn.rating(), cnn.score());
        fearGreedSnapshotPort.save(snapshot);

        log.info("공포탐욕지수 저장 완료 (date={}, crypto={}/{}, cnn={}/{})",
                date, crypto.rating(), crypto.value(), cnn.rating(), cnn.score());
    }
}
