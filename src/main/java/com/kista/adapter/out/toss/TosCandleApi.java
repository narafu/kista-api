package com.kista.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.toss.TossCandle;
import com.kista.domain.port.out.TosCandlePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

// Toss 캔들 API 스펙 (openapi.json 검증):
// - params: symbol(required), interval(1m|1d), count(1~200, default 100), before(ISO8601), adjusted(boolean)
// - response item: timestamp(ISO8601), openPrice, highPrice, lowPrice, closePrice, volume, currency
// from/to 범위 조회: before=to+1일(UTC) + count=범위내 최대 봉 수(여유있게)로 1회 호출 후 from 미만 필터링
@Slf4j
@Component
@RequiredArgsConstructor
public class TosCandleApi implements TosCandlePort {

    private static final String CANDLES_PATH = "/api/v1/candles";
    // 일봉 기준 최대 요청 수 (주말 포함 여유분)
    private static final int MAX_COUNT = 200;

    private final TossHttpClient tossHttpClient;

    @Override
    public List<TossCandle> getCandles(String symbol, String interval, LocalDate from, LocalDate to) {
        // before = to 다음날 00:00 UTC (to 당일 봉 포함)
        String beforeParam = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toString();
        // count = from~to 달력 일수 × 1.5 (주말·공휴일 여유분), 최대 200
        long calendarDays = from.until(to).getDays() + 1;
        int count = (int) Math.min(calendarDays * 3 / 2 + 5, MAX_COUNT);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("symbol",   symbol);
        params.add("interval", interval);
        params.add("count",    String.valueOf(count));
        params.add("before",   beforeParam);

        // 공통 API — 관리자 토큰 사용
        CandlesResponse response = tossHttpClient.getCommon(CANDLES_PATH, params, CandlesResponse.class);

        if (response == null || response.result() == null || response.result().candles() == null) {
            log.warn("Toss 캔들 응답 없음: symbol={}, interval={}", symbol, interval);
            return List.of();
        }

        return response.result().candles().stream()
                .filter(c -> c.timestamp() != null)
                .map(c -> {
                    // timestamp(ISO8601 UTC) → LocalDate
                    LocalDate date = OffsetDateTime.parse(c.timestamp()).toLocalDate();
                    return new TossCandle(
                            date,
                            parseBd(c.openPrice()),
                            parseBd(c.highPrice()),
                            parseBd(c.lowPrice()),
                            parseBd(c.closePrice()),
                            c.volume() != null ? Long.parseLong(c.volume()) : 0L
                    );
                })
                // before 방식이므로 from 이전 봉이 포함될 수 있음 — 필터링
                .filter(c -> !c.date().isBefore(from))
                .toList();
    }

    private BigDecimal parseBd(String value) {
        return value != null && !value.isBlank() ? new BigDecimal(value) : BigDecimal.ZERO;
    }

    // ── 내부 응답 record ──────────────────────────────────────────────────────

    // package-private — 테스트에서 직접 생성
    record CandlesResponse(
        @JsonProperty("result") CandlesResult result
    ) {}

    record CandlesResult(
        @JsonProperty("candles") List<CandleItem> candles,
        @JsonProperty("nextBefore") String nextBefore
    ) {}

    record CandleItem(
        @JsonProperty("timestamp")  String timestamp,   // ISO 8601 UTC
        @JsonProperty("openPrice")  String openPrice,   // 시가
        @JsonProperty("highPrice")  String highPrice,   // 고가
        @JsonProperty("lowPrice")   String lowPrice,    // 저가
        @JsonProperty("closePrice") String closePrice,  // 종가
        @JsonProperty("volume")     String volume,      // 거래량
        @JsonProperty("currency")   String currency     // 통화
    ) {}
}
