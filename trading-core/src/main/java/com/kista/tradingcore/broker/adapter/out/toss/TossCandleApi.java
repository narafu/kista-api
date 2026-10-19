package com.kista.tradingcore.broker.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.tradingcore.broker.domain.model.toss.TossCandle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

// Toss 캔들 API 스펙 (openapi.json 검증):
// - params: symbol(required), interval(1m|1d), count(1~200, default 100), before(ISO8601), adjusted(boolean)
// - response item: timestamp(ISO8601), openPrice, highPrice, lowPrice, closePrice, volume, currency
// from/to 범위 조회: before=to+1일(UTC)부터 200봉 단위 페이징(다음 before=직전 페이지 가장 오래된 봉일) 후 from 미만 필터링
// getDailyCandlesBefore: 200봉 단위 페이징 + adjusted=true(분할·배당 조정, 이 경로만)
@Slf4j
@Component
@RequiredArgsConstructor
class TossCandleApi {

    private static final String CANDLES_PATH = "/api/v1/candles";
    // 일봉 기준 최대 요청 수 (주말 포함 여유분)
    private static final int MAX_COUNT = 200;
    // 최신 일봉 조회 최대 개수 (페이징 3회)
    private static final int MAX_LATEST_DAILY_COUNT = 600;

    private final TossHttpClient tossHttpClient;

    public List<TossCandle> getCandles(String symbol, String interval, LocalDate from, LocalDate to) {
        List<TossCandle> all = new ArrayList<>(); // 오름차순 유지 — 더 오래된 페이지는 앞에 붙인다
        // 첫 before = to 다음날 00:00 UTC (to 당일 봉 포함)
        LocalDate before = to.plusDays(1);
        while (true) {
            // count = 남은 달력 일수 × 1.5 (주말·공휴일 여유분), 페이지당 최대 200
            long calendarDays = ChronoUnit.DAYS.between(from, before);
            int count = (int) Math.min(calendarDays * 3 / 2 + 5, MAX_COUNT);
            LocalDate pageBefore = before;
            // before 방식이므로 from 이전 봉이 포함될 수 있음 — 필터링
            List<TossCandle> fetched = fetchCandles(symbol, interval, count,
                    pageBefore.atStartOfDay(ZoneOffset.UTC).toString(), false).stream()
                    .filter(c -> !c.date().isBefore(from) && c.date().isBefore(pageBefore))
                    .toList();
            all.addAll(0, fetched);
            // 200 미만 요청이면 남은 범위를 1회에 덮었다 — 200이면 가장 오래된 봉 이전을 이어서 조회
            if (count < MAX_COUNT || fetched.isEmpty()) break;
            before = fetched.getFirst().date();
        }
        return List.copyOf(all);
    }

    // 일봉은 최대 600봉(MA200을 400봉 전체에 잇는 차트용)까지 페이징 — before가 날짜 단위라 분봉은 1페이지(200)만
    public List<TossCandle> getLatestCandles(String symbol, String interval, int count) {
        if (!"1d".equals(interval)) return fetchCandles(symbol, interval, Math.clamp(count, 1, MAX_COUNT), null, false);
        // before 미지정 첫 페이지는 Toss가 최신 봉부터 반환
        return pageDailyBefore(symbol, null, Math.clamp(count, 1, MAX_LATEST_DAILY_COUNT), false);
    }

    // 200봉 단위 페이징 — 다음 페이지 before = 직전 페이지 가장 오래된 봉일 00:00 UTC (isBefore 필터가 엄격해 중복 없음).
    // 봉 date()는 US 세션일, adjusted=true(분할·배당 조정 — DCA 지표용, 이 메서드만)
    public List<TossCandle> getDailyCandlesBefore(String symbol, LocalDate beforeUsSession, int count) {
        return pageDailyBefore(symbol, beforeUsSession, count, true);
    }

    // 일봉 200봉 단위 페이징 — before가 null이면 최신 봉부터. 오름차순 마지막 count개
    private List<TossCandle> pageDailyBefore(String symbol, LocalDate firstBefore, int count, boolean adjusted) {
        List<TossCandle> all = new ArrayList<>(); // 오름차순 유지 — 더 오래된 페이지는 앞에 붙인다
        LocalDate before = firstBefore;
        int maxPages = count / MAX_COUNT + 2; // 첫 페이지 진행 중 봉 필터 여유
        for (int page = 0; page < maxPages && all.size() < count; page++) {
            LocalDate pageBefore = before;
            List<TossCandle> fetched = fetchCandles(symbol, "1d", Math.clamp(count - all.size(), 1, MAX_COUNT),
                    pageBefore == null ? null : pageBefore.atStartOfDay(ZoneOffset.UTC).toString(), adjusted).stream()
                    .filter(c -> pageBefore == null || c.date().isBefore(pageBefore))
                    .toList();
            if (fetched.isEmpty()) break;
            all.addAll(0, fetched);
            before = fetched.getFirst().date(); // fetchCandles는 오름차순
        }
        return List.copyOf(all.subList(Math.max(0, all.size() - count), all.size()));
    }

    // before(exclusive 경계로 호출측에서 미리 보정된 시각) 이하 가장 최신 봉 1개만 조회 — 전일종가 확정 조회 전용
    public Optional<TossCandle> getCandleBefore(String symbol, String interval, Instant before) {
        List<TossCandle> candles = fetchCandles(symbol, interval, 1, before.toString(), false);
        return candles.isEmpty() ? Optional.empty() : Optional.of(candles.getFirst());
    }

    private List<TossCandle> fetchCandles(String symbol, String interval, int count, String beforeParam, boolean adjusted) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("symbol",   symbol);
        params.add("interval", interval);
        params.add("count",    String.valueOf(count));
        if (beforeParam != null) {
            params.add("before", beforeParam);
        }
        if (adjusted) {
            params.add("adjusted", "true");
        }

        // 공통 API — 관리자 토큰 사용
        TossResult<CandlesResult> wrapper = tossHttpClient.getCommon(CANDLES_PATH, params,
                new ParameterizedTypeReference<TossResult<CandlesResult>>() {});
        CandlesResult candlesResult = wrapper != null ? wrapper.result() : null;
        if (candlesResult == null || candlesResult.candles() == null) {
            log.warn("Toss 캔들 응답 없음: symbol={}, interval={}", symbol, interval);
            return List.of();
        }

        return candlesResult.candles().stream()
                .filter(c -> c.timestamp() != null)
                .map(c -> {
                    // timestamp(ISO8601 UTC) → LocalDate
                    LocalDate date = OffsetDateTime.parse(c.timestamp()).toLocalDate();
                    return new TossCandle(
                            date,
                            TossResponseParser.parseBdOrZero(c.openPrice()),
                            TossResponseParser.parseBdOrZero(c.highPrice()),
                            TossResponseParser.parseBdOrZero(c.lowPrice()),
                            TossResponseParser.parseBdOrZero(c.closePrice()),
                            c.volume() != null ? Long.parseLong(c.volume()) : 0L
                    );
                })
                // Toss 응답 순서가 최신순(내림차순)일 수 있음 — 캔들차트 라이브러리는 오름차순 필수
                .sorted(Comparator.comparing(TossCandle::date))
                .toList();
    }

    // ── 내부 응답 record ──────────────────────────────────────────────────────

    // package-private — 테스트에서 직접 생성
    record CandlesResult(
        @JsonProperty("candles") List<CandleItem> candles
    ) {}

    record CandleItem(
        @JsonProperty("timestamp")  String timestamp,   // ISO 8601 UTC
        @JsonProperty("openPrice")  String openPrice,   // 시가
        @JsonProperty("highPrice")  String highPrice,   // 고가
        @JsonProperty("lowPrice")   String lowPrice,    // 저가
        @JsonProperty("closePrice") String closePrice,  // 종가
        @JsonProperty("volume")     String volume       // 거래량
    ) {}
}
