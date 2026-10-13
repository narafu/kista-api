package com.kista.tradingcore.trading.adapter.out.feargreed;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.tradingcore.trading.application.port.output.FearGreedIndexPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

// CNN 공포탐욕지수 일별 점 조회 — 세션일 00:00 UTC로 찍힌 점만 사용, 장중 실시간 점 제외 (spec 6.2, 2026-10-14 실측)
@Slf4j
@Component
@RequiredArgsConstructor
class CnnFearGreedIndexAdapter implements FearGreedIndexPort {

    private static final String URL = "https://production.dataviz.cnn.io/index/fearandgreed/graphdata";
    private static final long DAY_MS = 86_400_000L; // 일별 점 판별 — x가 UTC 자정 정각

    private final RestClient fearGreedRestClient;

    @Override
    public Optional<BigDecimal> closeOn(LocalDate usSessionDate) {
        try {
            CnnResponse response = fearGreedRestClient.get().uri(URL).retrieve().body(CnnResponse.class);
            if (response == null || response.historical() == null || response.historical().data() == null) {
                log.warn("[DCA] CNN 공포탐욕지수 historical 없음");
                return Optional.empty();
            }
            // UTC 자정 정각 점 중 요청 세션일과 같은 날짜 — CNN 일별 점은 세션일 00:00 UTC로 찍힌다
            Optional<BigDecimal> value = response.historical().data().stream()
                    .filter(p -> (long) p.x() % DAY_MS == 0)
                    .filter(p -> Instant.ofEpochMilli((long) p.x()).atZone(ZoneOffset.UTC).toLocalDate().equals(usSessionDate))
                    .map(p -> BigDecimal.valueOf(p.y()).setScale(2, RoundingMode.HALF_UP))
                    .reduce((first, second) -> second); // 같은 날 점이 여럿이면 마지막(가장 늦은) 값
            if (value.isEmpty()) log.warn("[DCA] CNN 공포탐욕지수 {} 일별 점 없음(피드 지연)", usSessionDate);
            return value;
        } catch (RuntimeException e) {
            log.warn("[DCA] CNN 공포탐욕지수 조회 실패: usSessionDate={}", usSessionDate, e);
            return Optional.empty();
        }
    }

    // CNN 응답 중 fear_and_greed_historical 블록만 사용
    record CnnResponse(@JsonProperty("fear_and_greed_historical") Historical historical) {}

    record Historical(@JsonProperty("data") List<Point> data) {}

    // x: epoch ms(double), y: 지수
    record Point(@JsonProperty("x") double x, @JsonProperty("y") double y) {}
}
