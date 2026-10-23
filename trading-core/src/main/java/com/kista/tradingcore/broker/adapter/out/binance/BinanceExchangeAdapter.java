package com.kista.tradingcore.broker.adapter.out.binance;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.CryptoTradingRules;
import com.kista.shared.kernel.CryptoTradingRules.FeeSide;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.CryptoBalance;
import com.kista.tradingcore.broker.domain.model.CryptoCandle;
import com.kista.tradingcore.broker.domain.model.CryptoOrderResult;
import com.kista.tradingcore.broker.domain.model.binance.BinanceApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

// 바이낸스 현물 CryptoExchangePort — 조회(잔고·시세·완성 일봉·거래 규칙). 주문 메서드는 Task 4에서 채운다
@Component
class BinanceExchangeAdapter implements CryptoExchangePort {

    static final BigDecimal STANDARD_FEE_RATE = new BigDecimal("0.001"); // 현물 표준 taker 0.1% — RECEIVED_ASSET이라 커널 판정에 쓰이지 않음, 실제 수수료는 체결 내역

    private static final int MAX_COUNT = 999;               // klines limit ≤ 1000, 진행 중 봉 1개 여유
    private static final long DAY_MILLIS = 86_400_000L;     // 일봉 길이(ms)

    private final BinanceHttpClient http;         // 서명·오류 매핑 공통
    private final BinanceSymbolRegistry registry; // StrategyTicker ↔ BTCUSDT
    private final Clock clock;                    // 진행 중 일봉 판정 — 테스트에서 고정

    @Autowired // 생성자 2개 — 누락 시 빈 생성 실패
    BinanceExchangeAdapter(BinanceHttpClient http, BinanceSymbolRegistry registry) {
        this(http, registry, Clock.systemUTC());
    }

    BinanceExchangeAdapter(BinanceHttpClient http, BinanceSymbolRegistry registry, Clock clock) {
        this.http = http;
        this.registry = registry;
        this.clock = clock;
    }

    @Override
    public Broker supports() {
        return Broker.BINANCE;
    }

    @Override
    public CryptoBalance getBalance(BrokerAccountRef account) {
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("omitZeroBalances", "true");
        AccountRow row = http.getSigned("/api/v3/account", query, account, new ParameterizedTypeReference<>() {});
        BigDecimal quoteAvailable = BigDecimal.ZERO;
        BigDecimal quoteLocked = BigDecimal.ZERO;
        Map<StrategyTicker, CryptoBalance.Holding> holdings = new EnumMap<>(StrategyTicker.class);
        for (BalanceRow b : row.balances()) {
            if (BinanceSymbolRegistry.QUOTE_ASSET.equals(b.asset())) {
                quoteAvailable = b.free();
                quoteLocked = b.locked();
                continue;
            }
            // 평단은 거래소 미제공(null) — 포트폴리오는 원장 평단 사용. 미지원 자산은 무시
            registry.tickerOfAsset(b.asset()).ifPresent(t -> holdings.put(t, new CryptoBalance.Holding(b.free(), b.locked(), null)));
        }
        return new CryptoBalance(quoteAvailable, quoteLocked, holdings);
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getCurrentPrices(Collection<StrategyTicker> tickers) {
        Map<StrategyTicker, BigDecimal> prices = new EnumMap<>(StrategyTicker.class);
        for (StrategyTicker t : tickers) {
            LinkedHashMap<String, String> query = new LinkedHashMap<>();
            query.put("symbol", registry.symbolOf(t));
            PriceRow p = http.getPublic("/api/v3/ticker/price", query, new ParameterizedTypeReference<>() {});
            prices.put(t, p.price());
        }
        return prices;
    }

    @Override
    public List<CryptoCandle> getCompletedDailyCandles(StrategyTicker ticker, int count) {
        if (count < 1 || count > MAX_COUNT) throw new IllegalArgumentException("일봉 개수는 1~" + MAX_COUNT + "입니다: " + count);
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("symbol", registry.symbolOf(ticker));
        query.put("interval", "1d"); // 일봉
        query.put("limit", String.valueOf(count + 1)); // 진행 중 봉 1개를 버릴 여유
        List<List<JsonNode>> rows = http.getPublic("/api/v3/klines", query, new ParameterizedTypeReference<>() {});
        long now = clock.millis();
        List<CryptoCandle> completed = new ArrayList<>();
        for (List<JsonNode> k : rows) {
            long openTime = k.get(0).asLong(); // [openTime, open, high, low, close, ...]
            if (openTime + DAY_MILLIS > now) continue; // 진행 중 봉 제외
            // UTC 00:00 = KST 09:00 이라 일자가 같다
            LocalDate kstDate = Instant.ofEpochMilli(openTime).atZone(TimeZones.KST).toLocalDate();
            completed.add(new CryptoCandle(kstDate, new BigDecimal(k.get(4).asString())));
        }
        return completed.subList(Math.max(0, completed.size() - count), completed.size());
    }

    // ponytail: 캐시 없음 — 성공 요청 weight 0·실행기 계좌 단위 메모, 계좌 수가 늘면 TTL 캐시
    @Override
    public CryptoTradingRules getTradingRules(StrategyTicker ticker) {
        String symbol = registry.symbolOf(ticker);
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("symbol", symbol);
        ExchangeInfoRow info = http.getPublic("/api/v3/exchangeInfo", query, new ParameterizedTypeReference<>() {});
        SymbolRow s = info.symbols().getFirst();
        if (!"TRADING".equals(s.status())) {
            // 일시 중단 — 확정 거절 아님(틱마다 재시도)
            throw new BinanceApiException(200, null, "Binance 거래 중단 상태: symbol=" + symbol + ", status=" + s.status(), null, false);
        }
        BigDecimal step = filter(s, "LOT_SIZE").map(FilterRow::stepSize).orElseThrow(() -> new IllegalStateException("LOT_SIZE 필터 없음: " + symbol));
        BigDecimal minNotional = filter(s, "NOTIONAL").or(() -> filter(s, "MIN_NOTIONAL")).map(FilterRow::minNotional)
                .orElseThrow(() -> new IllegalStateException("NOTIONAL 필터 없음: " + symbol));
        return new CryptoTradingRules(STANDARD_FEE_RATE, FeeSide.RECEIVED_ASSET, minNotional, step);
    }

    private static Optional<FilterRow> filter(SymbolRow s, String type) {
        return s.filters().stream().filter(f -> type.equals(f.filterType())).findFirst();
    }

    @Override
    public CryptoOrderResult buyMarketByAmount(BrokerAccountRef account, StrategyTicker ticker, BigDecimal quoteAmount, String clientOrderId) {
        throw new UnsupportedOperationException("Task 4");
    }

    @Override
    public CryptoOrderResult sellMarketByVolume(BrokerAccountRef account, StrategyTicker ticker, BigDecimal volume, String clientOrderId) {
        throw new UnsupportedOperationException("Task 4");
    }

    @Override
    public Optional<CryptoOrderResult> findOrderByExchangeOrderId(BrokerAccountRef account, StrategyTicker ticker, String exchangeOrderId) {
        throw new UnsupportedOperationException("Task 4");
    }

    @Override
    public Optional<CryptoOrderResult> findOrderByClientOrderId(BrokerAccountRef account, StrategyTicker ticker, String clientOrderId) {
        throw new UnsupportedOperationException("Task 4");
    }

    record AccountRow(List<BalanceRow> balances) {}                                  // GET /api/v3/account
    record BalanceRow(String asset, BigDecimal free, BigDecimal locked) {}           // 자산별 가용·잠금
    record PriceRow(String symbol, BigDecimal price) {}                              // GET /api/v3/ticker/price
    record ExchangeInfoRow(List<SymbolRow> symbols) {}                               // GET /api/v3/exchangeInfo
    record SymbolRow(String symbol, String status, List<FilterRow> filters) {}       // 심볼 상태·필터
    record FilterRow(String filterType, BigDecimal stepSize, BigDecimal minNotional) {} // LOT_SIZE.stepSize / NOTIONAL.minNotional
}
