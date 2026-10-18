package com.kista.tradingcore.broker.adapter.out.upbit;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.CryptoBalance;
import com.kista.tradingcore.broker.domain.model.CryptoCandle;
import com.kista.tradingcore.broker.domain.model.CryptoOrderResult;
import com.kista.tradingcore.broker.domain.model.upbit.UpbitApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

// 업비트 CryptoExchangePort — 잔고·시세·일봉·시장가 주문·주문 조회
@Component
class UpbitExchangeAdapter implements CryptoExchangePort {

    private static final int MAX_CANDLES = 200; // 업비트 일봉 1회 최대 개수
    private static final int MAX_COUNT = 400;   // 페이징으로 지원하는 최대 일봉 개수
    private static final int MAX_PAGES = 3;     // 400봉 + 진행 중 봉을 덮는 최대 요청 수

    private final UpbitHttpClient http;          // JWT 서명·오류 매핑 공통
    private final UpbitMarketRegistry registry;  // StrategyTicker ↔ KRW-XXX
    private final Clock clock;                   // 진행 중 일봉 판정 — 테스트에서 고정

    @Autowired // 생성자 2개 — 누락 시 빈 생성 실패
    UpbitExchangeAdapter(UpbitHttpClient http, UpbitMarketRegistry registry) {
        this(http, registry, Clock.system(TimeZones.KST));
    }

    UpbitExchangeAdapter(UpbitHttpClient http, UpbitMarketRegistry registry, Clock clock) {
        this.http = http;
        this.registry = registry;
        this.clock = clock;
    }

    @Override
    public Broker supports() {
        return Broker.UPBIT;
    }

    @Override
    public CryptoBalance getBalance(BrokerAccountRef account) {
        List<AccountRow> rows = http.get("/v1/accounts", new LinkedHashMap<>(), account, new ParameterizedTypeReference<>() {});
        BigDecimal krwAvailable = BigDecimal.ZERO;
        BigDecimal krwLocked = BigDecimal.ZERO;
        Map<StrategyTicker, CryptoBalance.Holding> holdings = new EnumMap<>(StrategyTicker.class);
        for (AccountRow row : rows) {
            if ("KRW".equals(row.currency())) {
                krwAvailable = row.balance();
                krwLocked = row.locked();
                continue;
            }
            // KRW 기준 평단인 지원 코인만 — 그 외 코인은 전략 대상이 아니라 제외
            if (!"KRW".equals(row.unitCurrency())) continue;
            registry.tickerOfCurrency(row.currency()).ifPresent(t ->
                    holdings.put(t, new CryptoBalance.Holding(row.balance(), row.locked(), row.avgBuyPrice())));
        }
        return new CryptoBalance(krwAvailable, krwLocked, holdings);
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getCurrentPrices(Collection<StrategyTicker> tickers) {
        if (tickers.isEmpty()) return Map.of();
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("markets", tickers.stream().map(registry::marketOf).collect(Collectors.joining(",")));
        List<TickerRow> rows = http.getPublic("/v1/ticker", query, new ParameterizedTypeReference<>() {});
        Map<StrategyTicker, BigDecimal> prices = new EnumMap<>(StrategyTicker.class);
        rows.forEach(r -> registry.tickerOfMarket(r.market()).ifPresent(t -> prices.put(t, r.tradePrice())));
        return prices;
    }

    @Override
    public List<CryptoCandle> getCompletedDailyCandles(StrategyTicker ticker, int count) {
        if (count < 1 || count > MAX_COUNT) throw new IllegalArgumentException("일봉 개수는 1~" + MAX_COUNT + "입니다: " + count);
        LocalDateTime now = LocalDateTime.now(clock.withZone(TimeZones.KST));
        // kstDate 기준 중복 제거 + 오래된 순 정렬 — 페이지 경계 중복·정렬 미보장 방어
        TreeMap<LocalDate, CryptoCandle> completed = new TreeMap<>();
        String to = null; // 직전 응답 중 가장 오래된 봉의 UTC 시작 — 업비트 to는 exclusive
        for (int page = 0; page < MAX_PAGES && completed.size() < count; page++) {
            LinkedHashMap<String, String> query = new LinkedHashMap<>();
            query.put("market", registry.marketOf(ticker));
            // 첫 페이지는 진행 중 봉 1개를 버릴 여유(count+1), 이후 페이지는 최대치
            query.put("count", String.valueOf(page == 0 ? Math.min(count + 1, MAX_CANDLES) : MAX_CANDLES));
            if (to != null) query.put("to", to);
            List<CandleRow> rows = http.getPublic("/v1/candles/days", query, new ParameterizedTypeReference<>() {});
            if (rows == null || rows.isEmpty()) break; // 상장 이력 소진
            // 진행 중 봉 포함 여부가 문서에 없어 직접 보장 — 시작+1일(다음날 09:00)이 지나야 완성
            rows.stream().filter(r -> !r.startKst().plusDays(1).isAfter(now))
                    .forEach(r -> completed.putIfAbsent(r.startKst().toLocalDate(), new CryptoCandle(r.startKst().toLocalDate(), r.tradePrice())));
            to = rows.stream().map(CandleRow::startUtc).filter(Objects::nonNull).min(Comparator.naturalOrder())
                    .map(DateTimeFormatter.ISO_LOCAL_DATE_TIME::format).orElse(null);
            if (to == null) break;
        }
        List<CryptoCandle> list = List.copyOf(completed.values());
        return list.subList(Math.max(0, list.size() - count), list.size());
    }

    @Override
    public CryptoOrderResult buyMarketByAmount(BrokerAccountRef account, StrategyTicker ticker, BigDecimal krwAmount, String identifier) {
        if (krwAmount.signum() <= 0) throw new IllegalArgumentException("매수 금액은 0보다 커야 합니다: " + krwAmount);
        LinkedHashMap<String, String> body = new LinkedHashMap<>();
        body.put("market", registry.marketOf(ticker));
        body.put("side", "bid");
        body.put("ord_type", "price"); // 시장가 매수 — price=총액, volume 생략
        body.put("price", krwAmount.stripTrailingZeros().toPlainString());
        body.put("identifier", identifier);
        return toResult(http.post("/v1/orders", body, account, new ParameterizedTypeReference<OrderRow>() {}));
    }

    @Override
    public CryptoOrderResult sellMarketByVolume(BrokerAccountRef account, StrategyTicker ticker, BigDecimal volume, String identifier) {
        // 소수 8자리 내림 — 보유 수량 초과 방지
        BigDecimal floored = volume.setScale(8, RoundingMode.DOWN);
        if (floored.signum() <= 0) throw new IllegalArgumentException("매도 수량은 0보다 커야 합니다: " + volume);
        LinkedHashMap<String, String> body = new LinkedHashMap<>();
        body.put("market", registry.marketOf(ticker));
        body.put("side", "ask");
        body.put("ord_type", "market"); // 시장가 매도 — volume 필수, price 생략
        body.put("volume", floored.stripTrailingZeros().toPlainString());
        body.put("identifier", identifier);
        return toResult(http.post("/v1/orders", body, account, new ParameterizedTypeReference<OrderRow>() {}));
    }

    @Override
    public Optional<CryptoOrderResult> findOrderByUuid(BrokerAccountRef account, String uuid) {
        return findOrder(account, "uuid", uuid);
    }

    @Override
    public Optional<CryptoOrderResult> findOrderByIdentifier(BrokerAccountRef account, String identifier) {
        return findOrder(account, "identifier", identifier);
    }

    private Optional<CryptoOrderResult> findOrder(BrokerAccountRef account, String key, String value) {
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put(key, value);
        try {
            return Optional.of(toResult(http.get("/v1/order", query, account, new ParameterizedTypeReference<OrderRow>() {})));
        } catch (UpbitApiException e) {
            if ("order_not_found".equals(e.errorName())) return Optional.empty(); // 미접수 — 재실행 복구 판단 근거
            throw e;
        }
    }

    // trades가 있으면 Σfunds/Σvolume로 평균가 산출(업비트 응답에 평균가 필드 없음)
    private static CryptoOrderResult toResult(OrderRow o) {
        List<TradeRow> trades = o.trades() == null ? List.of() : o.trades();
        BigDecimal volume = trades.stream().map(TradeRow::volume).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal funds = trades.stream().map(TradeRow::funds).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avg = volume.signum() > 0 ? funds.divide(volume, 8, RoundingMode.HALF_UP).stripTrailingZeros() : null;
        return new CryptoOrderResult(o.uuid(), o.identifier(), parseState(o),
                o.executedVolume() == null ? BigDecimal.ZERO : o.executedVolume(), avg,
                o.paidFee() == null ? BigDecimal.ZERO : o.paidFee());
    }

    // 알 수 없는 state는 접수 여부 불명이라 rejected=false 예외로 올린다
    private static CryptoOrderResult.State parseState(OrderRow o) {
        try {
            return CryptoOrderResult.State.valueOf(o.state().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new UpbitApiException(0, null, "Upbit 주문 state 해석 실패: uuid=" + o.uuid() + ", state=" + o.state(), e, false);
        }
    }

    // ── 업비트 응답 (숫자는 문자열 decimal — Jackson이 BigDecimal로 변환) ──
    record AccountRow(String currency, BigDecimal balance, BigDecimal locked,
                      @JsonProperty("avg_buy_price") BigDecimal avgBuyPrice,
                      @JsonProperty("unit_currency") String unitCurrency) {}

    record TickerRow(String market, @JsonProperty("trade_price") BigDecimal tradePrice) {}

    record CandleRow(@JsonProperty("candle_date_time_kst") LocalDateTime startKst,
                     @JsonProperty("candle_date_time_utc") LocalDateTime startUtc,
                     @JsonProperty("trade_price") BigDecimal tradePrice) {}

    record OrderRow(String uuid, String identifier, String state,
                    @JsonProperty("executed_volume") BigDecimal executedVolume,
                    @JsonProperty("paid_fee") BigDecimal paidFee,
                    List<TradeRow> trades) {}

    record TradeRow(BigDecimal price, BigDecimal volume, BigDecimal funds) {}
}
