package com.kista.tradingcore.stats.adapter.out.upbit;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.stats.application.port.output.UpbitMarketDataPort;
import com.kista.tradingcore.stats.domain.model.DatedValue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

// 업비트 공개(무인증) 시세 — 김프·USDT 프리미엄 전용. 업비트 거래(broker UpbitExchangeAdapter)를 제거해도 이 어댑터는 남는다
@Slf4j
@Component
class UpbitMarketDataAdapter implements UpbitMarketDataPort {

    private static final String BASE = "https://api.upbit.com";
    private static final String USDT_MARKET = "KRW-USDT"; // 테더 원화 마켓
    private static final int MAX_CANDLES = 200; // 업비트 일봉 1회 최대 개수
    private static final int MAX_COUNT = 400;   // 페이징으로 지원하는 최대 일봉 개수
    private static final int MAX_PAGES = 3;     // 400봉 + 진행 중 봉을 덮는 최대 요청 수

    private final RestClient restClient; // 무인증 공개 시세용
    private final Clock clock;           // 진행 중 일봉 판정 — 테스트에서 고정

    @Autowired // 생성자 2개 — 누락 시 빈 생성 실패
    UpbitMarketDataAdapter(RestClient statsUpbitRestClient) {
        this(statsUpbitRestClient, Clock.system(TimeZones.KST));
    }

    UpbitMarketDataAdapter(RestClient restClient, Clock clock) {
        this.restClient = restClient;
        this.clock = clock;
    }

    @Override
    public BigDecimal getUsdtKrwPrice() {
        List<TickerRow> rows = tickers(USDT_MARKET);
        if (rows == null || rows.isEmpty()) {
            throw new IllegalStateException("업비트 USDT 시세 응답이 비어 있습니다: market=" + USDT_MARKET);
        }
        return rows.getFirst().tradePrice();
    }

    @Override
    public List<DatedValue> getCompletedUsdtKrwDailyCloses(int count) {
        return completedDailyCloses(USDT_MARKET, count);
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getKrwPrices(Collection<StrategyTicker> tickers) {
        if (tickers.isEmpty()) return Map.of();
        List<TickerRow> rows = tickers(tickers.stream().map(UpbitMarketDataAdapter::marketOf).collect(Collectors.joining(",")));
        Map<StrategyTicker, BigDecimal> prices = new EnumMap<>(StrategyTicker.class);
        if (rows == null) return prices;
        for (StrategyTicker t : tickers) {
            rows.stream().filter(r -> marketOf(t).equals(r.market())).findFirst().ifPresent(r -> prices.put(t, r.tradePrice()));
        }
        return prices;
    }

    @Override
    public List<DatedValue> getCompletedKrwDailyCloses(StrategyTicker ticker, int count) {
        return completedDailyCloses(marketOf(ticker), count);
    }

    // 종목 마켓 코드 — KRW-BTC·KRW-ETH
    private static String marketOf(StrategyTicker ticker) {
        return "KRW-" + ticker.name();
    }

    private List<TickerRow> tickers(String markets) {
        // 쉼표 구분 마켓 목록을 템플릿 변수로 넘기면 %2C로 인코딩돼 직접 이어 붙인다 — 내부 상수 조합이라 인젝션 여지 없음
        return restClient.get().uri(BASE + "/v1/ticker?markets=" + markets)
                .retrieve().body(new ParameterizedTypeReference<>() {});
    }

    // ponytail: broker UpbitExchangeAdapter와 일봉 페이징 중복 — 모듈 경계(stats↛broker adapter) 때문, 업비트 거래 제거 시 broker 쪽이 사라져 해소
    private List<DatedValue> completedDailyCloses(String market, int count) {
        if (count < 1 || count > MAX_COUNT) throw new IllegalArgumentException("일봉 개수는 1~" + MAX_COUNT + "입니다: " + count);
        LocalDateTime now = LocalDateTime.now(clock.withZone(TimeZones.KST));
        // 시작일 기준 중복 제거 + 오래된 순 정렬 — 페이지 경계 중복·정렬 미보장 방어
        TreeMap<LocalDate, DatedValue> completed = new TreeMap<>();
        String to = null; // 직전 응답 중 가장 오래된 봉의 UTC 시작 — 업비트 to는 exclusive
        for (int page = 0; page < MAX_PAGES && completed.size() < count; page++) {
            // 첫 페이지는 진행 중 봉 1개를 버릴 여유(count+1), 이후 페이지는 최대치
            int pageCount = page == 0 ? Math.min(count + 1, MAX_CANDLES) : MAX_CANDLES;
            List<CandleRow> rows = to == null
                    ? restClient.get().uri(BASE + "/v1/candles/days?market={m}&count={c}", market, pageCount)
                            .retrieve().body(new ParameterizedTypeReference<>() {})
                    : restClient.get().uri(BASE + "/v1/candles/days?market={m}&count={c}&to={t}", market, pageCount, to)
                            .retrieve().body(new ParameterizedTypeReference<>() {});
            if (rows == null || rows.isEmpty()) break; // 상장 이력 소진
            // 진행 중 봉 포함 여부가 문서에 없어 직접 보장 — 시작+1일(다음날 09:00)이 지나야 완성
            rows.stream().filter(r -> !r.startKst().plusDays(1).isAfter(now))
                    .forEach(r -> completed.putIfAbsent(r.startKst().toLocalDate(), new DatedValue(r.startKst().toLocalDate(), r.tradePrice())));
            to = rows.stream().map(CandleRow::startUtc).filter(Objects::nonNull).min(Comparator.naturalOrder())
                    .map(DateTimeFormatter.ISO_LOCAL_DATE_TIME::format).orElse(null);
            if (to == null) {
                log.warn("업비트 일봉 페이징 중단 — 가장 오래된 봉에 UTC 시각 없음: market={}, collected={}", market, completed.size());
                break;
            }
        }
        List<DatedValue> list = List.copyOf(completed.values());
        return list.subList(Math.max(0, list.size() - count), list.size());
    }

    // 업비트 응답 (숫자는 문자열 decimal이어도 Jackson이 BigDecimal로 변환)
    record TickerRow(String market, @JsonProperty("trade_price") BigDecimal tradePrice) {}

    record CandleRow(@JsonProperty("candle_date_time_kst") LocalDateTime startKst,
                     @JsonProperty("candle_date_time_utc") LocalDateTime startUtc,
                     @JsonProperty("trade_price") BigDecimal tradePrice) {}
}
