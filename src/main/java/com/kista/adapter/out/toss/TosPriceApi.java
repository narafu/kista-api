package com.kista.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.TosPricePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class TosPriceApi implements TosPricePort {

    // Toss 가격 API: GET /api/v1/prices?symbols=SOXL,TQQQ (콤마 구분, 최대 200개)
    private static final String PRICES_PATH = "/api/v1/prices";

    private final TossHttpClient tossHttpClient;

    @Override
    public Map<Ticker, BigDecimal> getPrices(List<Ticker> tickers, Account account) {
        if (tickers.isEmpty()) return Map.of();

        // symbols 쿼리 파라미터: 콤마 구분 종목 코드 목록
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("symbols", tickers.stream().map(Ticker::name).collect(Collectors.joining(",")));

        // Toss API 응답: {"result": [{symbol, lastPrice, currency, timestamp}]} 래퍼 구조
        PricesResponse response = tossHttpClient.get(
                PRICES_PATH, tossHttpClient.buildHeadersNoAccount(account), params, PricesResponse.class);

        List<PriceItem> items = response != null ? response.result() : null;

        if (items == null) return Map.of();

        return items.stream()
                .flatMap(item -> Ticker.tryParse(item.symbol())  // Ticker 외 종목(예: AAPL) silent drop
                        .map(t -> Map.entry(t, new BigDecimal(item.lastPrice())))
                        .stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public BigDecimal getPrice(Ticker ticker, Account account) {
        // 단건도 getPrices 재사용 — HTTP 호출 횟수 동일
        return getPrices(List.of(ticker), account).getOrDefault(ticker, BigDecimal.ZERO);
    }

    @Override
    public PriceSnapshot getPriceSnapshot(Ticker ticker, Account account) {
        BigDecimal price = getPrice(ticker, account);
        // Toss 전일종가 전용 API 없음 — prevClose = current (0회차 진입 방향 보수적 처리)
        return new PriceSnapshot(price, price);
    }

    @Override
    public Map<Ticker, PriceSnapshot> getPriceSnapshots(List<Ticker> tickers, Account account) {
        return getPrices(tickers, account).entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new PriceSnapshot(e.getValue(), e.getValue())));  // prevClose = current
    }

    // package-private — TosPriceApiTest에서 직접 생성하여 stub에 사용
    record PriceItem(
        @JsonProperty("symbol") String symbol,        // 종목 코드 (예: SOXL)
        @JsonProperty("lastPrice") String lastPrice,  // 현재가 (문자열 소수 형식)
        @JsonProperty("currency") String currency     // 통화 (예: USD)
    ) {}

    // Toss API 공통 래퍼: {"result": [...]} — TossAuthApi.AccountsResponse 와 동일 패턴
    record PricesResponse(
        @JsonProperty("result") List<PriceItem> result
    ) {}
}
