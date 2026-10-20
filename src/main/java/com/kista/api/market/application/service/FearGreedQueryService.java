package com.kista.api.market.application.service;

import com.kista.shared.kernel.TimeZones;
import com.kista.shared.platform.cache.TtlResultCache;
import com.kista.api.market.domain.model.FearGreedSnapshot;
import com.kista.api.market.application.usecase.GetFearGreedUseCase;
import com.kista.api.market.application.port.output.CryptoFearGreedPort;
import com.kista.api.market.application.port.output.FearGreedSnapshotPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

// CNN은 스케쥴러가 쌓은 DB 스냅샷, CRYPTO는 alternative.me 이력 API 직접 조회(인메모리 캐시).
// 클래스 레벨 @Transactional 없음 — CRYPTO 외부 HTTP 동안 DB 커넥션을 잡지 않도록(CNN 조회는 리포지토리 기본 트랜잭션)
@Slf4j
@Service
class FearGreedQueryService implements GetFearGreedUseCase {

    static final int MAX_DAYS = 365; // 조회 기간 상한 — kista-ui 최대 옵션, CRYPTO 외부 limit 상한
    static final Duration CRYPTO_TTL = Duration.ofHours(1); // 정상 이력 캐시 — 갱신이 하루 1회(KST 09:00)라 최대 1시간 지연 허용
    static final Duration CRYPTO_FAILURE_TTL = Duration.ofMinutes(1); // 실패 캐시 — 비인증 공개라 장애 중 요청마다 외부 재호출되는 것을 막는다
    private static final String SOURCE_CRYPTO = "CRYPTO";
    private static final String CRYPTO_CACHE_KEY = "fear-greed:crypto"; // days와 무관하게 MAX_DAYS+1건 단일 결과를 잘라 쓴다
    private static final String CRYPTO_FAILED_CACHE_KEY = CRYPTO_CACHE_KEY + ":failed"; // 실패 표식 — 정상 결과와 TTL이 달라 키 분리

    private final FearGreedSnapshotPort fearGreedSnapshotPort;
    private final CryptoFearGreedPort cryptoFearGreedPort;
    private final Clock clock; // since 기준일·캐시 만료 판정 — 테스트에서 고정
    private final TtlResultCache cache; // CRYPTO 이력 캐시 (single-flight)

    @Autowired // 생성자 2개 — 누락 시 빈 생성 실패
    FearGreedQueryService(FearGreedSnapshotPort fearGreedSnapshotPort, CryptoFearGreedPort cryptoFearGreedPort) {
        this(fearGreedSnapshotPort, cryptoFearGreedPort, Clock.systemUTC());
    }

    FearGreedQueryService(FearGreedSnapshotPort fearGreedSnapshotPort, CryptoFearGreedPort cryptoFearGreedPort, Clock clock) {
        this.fearGreedSnapshotPort = fearGreedSnapshotPort;
        this.cryptoFearGreedPort = cryptoFearGreedPort;
        this.clock = clock;
        this.cache = new TtlResultCache(clock);
    }

    @Override
    public List<FearGreedSnapshot> getRecent(String source, int days) {
        if (days < 1 || days > MAX_DAYS) throw new IllegalArgumentException("조회 기간은 1~" + MAX_DAYS + "일입니다: " + days);
        // days일 전 KST 00:00부터 현재까지 시각 범위로 조회
        Instant since = LocalDate.ofInstant(clock.instant(), TimeZones.KST).minusDays(days).atStartOfDay(TimeZones.KST).toInstant();
        if (SOURCE_CRYPTO.equals(source)) {
            return cryptoHistory().stream().filter(s -> !s.snapshotDate().isBefore(since)).toList();
        }
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

    // 실패는 빈 이력으로 흡수 — CRYPTO 장애가 CNN까지 묶어 500이 되지 않도록. 실패 표식이 살아있는 동안은 외부 재호출 없음
    private List<FearGreedSnapshot> cryptoHistory() {
        List<FearGreedSnapshot> history = cache.getOrCompute(CRYPTO_CACHE_KEY, CRYPTO_TTL, () -> {
            if (cache.peek(CRYPTO_FAILED_CACHE_KEY) != null) return null;
            try {
                return cryptoFearGreedPort.fetchHistory(MAX_DAYS + 1); // since가 days일 전 자정이라 최대 days+1건
            } catch (RuntimeException e) {
                log.warn("크립토 공포탐욕지수 이력 조회 실패: {}", e.getMessage());
                cache.getOrCompute(CRYPTO_FAILED_CACHE_KEY, CRYPTO_FAILURE_TTL, () -> Boolean.TRUE);
                return null; // null은 정상 키에 캐시되지 않는다
            }
        });
        return history != null ? history : List.of();
    }
}
