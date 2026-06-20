package com.kista.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossCandle;
import com.kista.domain.port.out.TosCandlePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TosCandleApi implements TosCandlePort {

    // Toss 캔들 API 경로
    private static final String CANDLES_PATH = "/api/v1/candles";
    // Toss API 날짜 파라미터 포맷: YYYYMMDD
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TossHttpClient tossHttpClient;

    @Override
    public List<TossCandle> getCandles(Ticker ticker, String interval, LocalDate from, LocalDate to, Account account) {
        // symbol, interval, startDate, endDate 파라미터로 캔들 조회
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("symbol",    ticker.name());
        params.add("interval",  interval);
        params.add("startDate", from.format(DATE_FMT));
        params.add("endDate",   to.format(DATE_FMT));

        // 캔들은 계좌 컨텍스트 불필요 — 시세 조회 경로 사용
        CandlesResponse response = tossHttpClient.getNoAccountHeader(
                CANDLES_PATH, account, params, CandlesResponse.class);

        if (response == null || response.result() == null) {
            log.warn("Toss 캔들 응답 없음: ticker={}, interval={}", ticker, interval);
            return List.of();
        }
        return response.result().stream()
                .map(c -> new TossCandle(
                        LocalDate.parse(c.date(), DATE_FMT),
                        new BigDecimal(c.openPrice()),
                        new BigDecimal(c.highPrice()),
                        new BigDecimal(c.lowPrice()),
                        new BigDecimal(c.closePrice()),
                        Long.parseLong(c.volume())
                ))
                .toList();
    }

    // ── 내부 응답 record ──────────────────────────────────────────────────────

    record CandlesResponse(
        @JsonProperty("result") List<CandleItem> result
    ) {}

    record CandleItem(
        @JsonProperty("date")       String date,        // YYYYMMDD
        @JsonProperty("openPrice")  String openPrice,   // 시가
        @JsonProperty("highPrice")  String highPrice,   // 고가
        @JsonProperty("lowPrice")   String lowPrice,    // 저가
        @JsonProperty("closePrice") String closePrice,  // 종가
        @JsonProperty("volume")     String volume       // 거래량
    ) {}
}
