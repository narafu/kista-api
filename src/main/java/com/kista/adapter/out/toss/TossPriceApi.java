package com.kista.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.strategy.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossCandle;
import com.kista.domain.model.toss.TossStockInfo;
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
public class TossPriceApi {

    // Toss 가격 API: GET /api/v1/prices?symbols=SOXL,TQQQ (콤마 구분, 최대 200개)
    private static final String PRICES_PATH = "/api/v1/prices";
    // Toss 종목 기본 정보 API: GET /api/v1/stocks?symbols=SOXL (복수형, 콤마 구분)
    // 주의: stocks API는 가격 정보 미제공 — 현재가는 /prices 별도 조회
    private static final String STOCKS_PATH = "/api/v1/stocks";

    private final TossHttpClient tossHttpClient;
    private final TossCandleApi tossCandleApi; // 전일종가 캔들 조회

    public Map<Ticker, BigDecimal> getPrices(List<Ticker> tickers) {
        if (tickers.isEmpty()) return Map.of();

        // symbols 쿼리 파라미터: 콤마 구분 종목 코드 목록
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("symbols", tickers.stream().map(Ticker::name).collect(Collectors.joining(",")));

        // 공통 API — 관리자 토큰 사용
        PricesResponse response = tossHttpClient.getCommon(PRICES_PATH, params, PricesResponse.class);

        List<PriceItem> items = response != null ? response.result() : null;

        if (items == null) return Map.of();

        return items.stream()
                .flatMap(item -> Ticker.tryParse(item.symbol())  // Ticker 외 종목(예: AAPL) silent drop
                        .map(t -> Map.entry(t, new BigDecimal(item.lastPrice())))
                        .stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public BigDecimal getPrice(Ticker ticker) {
        // 단건도 getPrices 재사용 — HTTP 호출 횟수 동일
        return getPrices(List.of(ticker)).getOrDefault(ticker, BigDecimal.ZERO);
    }

    public PriceSnapshot getPriceSnapshot(Ticker ticker) {
        BigDecimal price = getPrice(ticker);
        return new PriceSnapshot(price, fetchPrevClose(ticker.name(), price));
    }

    public Map<Ticker, PriceSnapshot> getPriceSnapshots(List<Ticker> tickers) {
        return getPrices(tickers).entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new PriceSnapshot(e.getValue(), fetchPrevClose(e.getKey().name(), e.getValue()))));
    }

    // 일봉 최신 2개 조회 → 오름차순 [0]이 확정 전일종가, 실패 시 current fallback
    private BigDecimal fetchPrevClose(String symbol, BigDecimal fallback) {
        try {
            List<TossCandle> candles = tossCandleApi.getLatestCandles(symbol, "1d", 2);
            if (candles.size() >= 2) {
                return candles.get(0).close();
            }
            log.warn("Toss 캔들 부족({}개), prevClose=current 사용: symbol={}", candles.size(), symbol);
        } catch (Exception e) {
            log.warn("Toss 전일종가 조회 실패, prevClose=current 사용: symbol={}, error={}", symbol, e.getMessage());
        }
        return fallback;
    }

    // ── TossStockInfoPort ──────────────────────────────────────────────────────

    public TossStockInfo getStockInfo(Ticker ticker) {
        // stocks API는 복수형 파라미터(symbols) — 단건이어도 동일
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("symbols", ticker.name());

        // 공통 API — 관리자 토큰 사용
        StocksResponse response = tossHttpClient.getCommon(STOCKS_PATH, params, StocksResponse.class);

        List<StockItem> items = response != null ? response.result() : null;
        if (items == null || items.isEmpty()) {
            log.warn("Toss 종목 정보 응답 없음: ticker={}", ticker);
            return new TossStockInfo(ticker.name(), ticker.name(), ticker.name(), "", "USD", "");
        }
        StockItem s = items.get(0);
        return new TossStockInfo(
                s.symbol(),
                s.name()         != null ? s.name()         : ticker.name(),
                s.englishName()  != null ? s.englishName()  : ticker.name(),
                s.market()       != null ? s.market()       : "",
                s.currency()     != null ? s.currency()     : "USD",
                s.status()       != null ? s.status()       : ""
        );
    }

    // ── 내부 응답 record ──────────────────────────────────────────────────────

    // package-private — TossPriceApiTest에서 직접 생성하여 stub에 사용
    record PriceItem(
        @JsonProperty("symbol")    String symbol,    // 종목 코드 (예: SOXL)
        @JsonProperty("lastPrice") String lastPrice, // 현재가 (문자열 소수 형식)
        @JsonProperty("currency")  String currency   // 통화 (예: USD)
    ) {}

    // Toss API 공통 래퍼: {"result": [...]}
    record PricesResponse(
        @JsonProperty("result") List<PriceItem> result
    ) {}

    // GET /api/v1/stocks 응답 래퍼: {"result": [...]} (복수 반환)
    record StocksResponse(
        @JsonProperty("result") List<StockItem> result
    ) {}

    // stocks API 응답 — 가격 정보 없음 (name/market/currency 등 기본 정보만)
    record StockItem(
        @JsonProperty("symbol")           String symbol,          // 종목 코드
        @JsonProperty("name")             String name,            // 한글 종목명
        @JsonProperty("englishName")      String englishName,     // 영문 종목명
        @JsonProperty("market")           String market,          // 거래소/시장
        @JsonProperty("currency")         String currency,        // 통화
        @JsonProperty("status")           String status,          // 종목 상태
        @JsonProperty("sharesOutstanding") String sharesOutstanding // 발행주식수
    ) {}
}
