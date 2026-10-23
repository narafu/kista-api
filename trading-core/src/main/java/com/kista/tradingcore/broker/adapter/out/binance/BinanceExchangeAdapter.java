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
import com.kista.tradingcore.broker.domain.model.CryptoOrderResult.State;
import com.kista.tradingcore.broker.domain.model.binance.BinanceApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

// 바이낸스 현물 CryptoExchangePort — 조회(잔고·시세·완성 일봉·거래 규칙)와 시장가 주문·주문 조회
@Component
class BinanceExchangeAdapter implements CryptoExchangePort {

    static final BigDecimal STANDARD_FEE_RATE = new BigDecimal("0.001"); // 현물 표준 taker 0.1% — RECEIVED_ASSET이라 커널 판정에 쓰이지 않음, 실제 수수료는 체결 내역

    private static final int ORDER_NOT_FOUND = -2013; // 주문 없음
    private static final int ORDER_ARCHIVED = -2026;  // 90일 지난 무체결 종료 주문
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
        if (quoteAmount.signum() <= 0) throw new IllegalArgumentException("매수 금액은 0보다 커야 합니다: " + quoteAmount);
        LinkedHashMap<String, String> query = orderParams(ticker, "BUY", clientOrderId);
        query.put("quoteOrderQty", quoteAmount.stripTrailingZeros().toPlainString()); // 견적 자산 금액 — 지수 표기 금지
        return placeOrder(account, ticker, query);
    }

    @Override
    public CryptoOrderResult sellMarketByVolume(BrokerAccountRef account, StrategyTicker ticker, BigDecimal volume, String clientOrderId) {
        // LOT_SIZE step 내림 — 보유 초과·-1013 방지
        BigDecimal qty = volume.setScale(getTradingRules(ticker).quantityScale(), RoundingMode.DOWN);
        if (qty.signum() <= 0) throw new IllegalArgumentException("매도 수량이 최소 단위 미만입니다: " + volume);
        LinkedHashMap<String, String> query = orderParams(ticker, "SELL", clientOrderId);
        query.put("quantity", qty.toPlainString());
        return placeOrder(account, ticker, query);
    }

    private LinkedHashMap<String, String> orderParams(StrategyTicker ticker, String side, String clientOrderId) {
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("symbol", registry.symbolOf(ticker));
        query.put("side", side);
        query.put("type", "MARKET"); // 시장가
        query.put("newClientOrderId", BinanceClientOrderIds.toWire(clientOrderId)); // 조회와 같은 전송 형태 — 불일치 시 -2013 → 이중 매수
        query.put("newOrderRespType", "FULL"); // 체결 내역(fills) 포함 응답
        return query;
    }

    // 전송 — 예외는 http 클라이언트가 확정 거절/결과 불명으로 분류. 재전송 금지(호출부 책임)
    private CryptoOrderResult placeOrder(BrokerAccountRef account, StrategyTicker ticker, LinkedHashMap<String, String> query) {
        OrderRow o = http.postSigned("/api/v3/order", query, account, new ParameterizedTypeReference<>() {});
        return toResult(o, o.fills() == null ? List.of() : o.fills(), registry.baseAssetOf(ticker));
    }

    @Override
    public Optional<CryptoOrderResult> findOrderByExchangeOrderId(BrokerAccountRef account, StrategyTicker ticker, String exchangeOrderId) {
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("symbol", registry.symbolOf(ticker));
        query.put("orderId", exchangeOrderId);
        try {
            return Optional.of(lookup(account, ticker, query));
        } catch (BinanceApiException e) {
            if (Integer.valueOf(ORDER_NOT_FOUND).equals(e.code())) return Optional.empty();
            // 90일 지난 무체결 종료 주문 — empty면 PLACED가 영구 정체하므로 취소로 확정
            if (Integer.valueOf(ORDER_ARCHIVED).equals(e.code())) {
                return Optional.of(new CryptoOrderResult(exchangeOrderId, null, State.CANCEL, BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO, null));
            }
            throw e;
        }
    }

    @Override
    public Optional<CryptoOrderResult> findOrderByClientOrderId(BrokerAccountRef account, StrategyTicker ticker, String clientOrderId) {
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("symbol", registry.symbolOf(ticker));
        query.put("origClientOrderId", BinanceClientOrderIds.toWire(clientOrderId));
        try {
            return Optional.of(lookup(account, ticker, query));
        } catch (BinanceApiException e) {
            // 오직 -2013/-2026만 "없음"(-2026은 무체결 종료 확정 — 동기화기가 FAILED 처리, 재전송 없음). 5xx·-1006/-1007·I/O는 그대로 던진다
            if (Integer.valueOf(ORDER_NOT_FOUND).equals(e.code()) || Integer.valueOf(ORDER_ARCHIVED).equals(e.code())) return Optional.empty();
            throw e;
        }
    }

    // 주문 조회 + (닫힌 주문이고 체결이 있을 때만) myTrades로 수수료 확정
    private CryptoOrderResult lookup(BrokerAccountRef account, StrategyTicker ticker, LinkedHashMap<String, String> query) {
        OrderRow o = http.getSigned("/api/v3/order", query, account, new ParameterizedTypeReference<>() {});
        String base = registry.baseAssetOf(ticker);
        CryptoOrderResult open = toResult(o, List.of(), base);
        boolean closed = open.state() == State.DONE || open.state() == State.CANCEL;
        if (!closed || o.executedQty().signum() <= 0) return open; // 열린 주문은 weight를 쓰지 않는다
        LinkedHashMap<String, String> tradeQuery = new LinkedHashMap<>();
        tradeQuery.put("symbol", registry.symbolOf(ticker));
        tradeQuery.put("orderId", String.valueOf(o.orderId()));
        List<FillRow> trades = http.getSigned("/api/v3/myTrades", tradeQuery, account, new ParameterizedTypeReference<>() {});
        BigDecimal tradedQty = trades.stream().map(FillRow::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (tradedQty.compareTo(o.executedQty()) != 0) {
            // 접수 직후 일부만 보이는 경우 — 순수량을 잘못 확정하지 않고 다음 틱에 재시도
            throw new BinanceApiException(200, null, "Binance 체결 내역 불일치: orderId=" + o.orderId(), null, false);
        }
        return toResult(o, trades, base);
    }

    static CryptoOrderResult toResult(OrderRow o, List<FillRow> fills, String baseAsset) {
        State state = switch (o.status()) {
            case "NEW", "PENDING_NEW", "PARTIALLY_FILLED", "PENDING_CANCEL" -> State.WAIT;
            case "FILLED" -> State.DONE;
            case "CANCELED", "EXPIRED", "EXPIRED_IN_MATCH", "REJECTED" -> State.CANCEL;
            default -> throw new BinanceApiException(200, null, "Binance 알 수 없는 주문 상태: " + o.status(), null, false);
        };
        BigDecimal executed = o.executedQty();
        BigDecimal avgPrice = executed.signum() > 0
                ? o.cummulativeQuoteQty().divide(executed, 8, RoundingMode.HALF_UP).stripTrailingZeros() : null;
        BigDecimal paidFee = fills.stream().map(FillRow::commission).reduce(BigDecimal.ZERO, BigDecimal::add);
        // ponytail: 자산 혼합 수수료(BNB 부족 시 BNB+BTC)는 첫 자산만 표기 — 원장은 아래 base 차감으로 정확
        String feeAsset = fills.isEmpty() ? null : fills.getFirst().commissionAsset();
        // 받는 자산(base)으로 낸 수수료만 순수량에서 차감 — SELL(USDT)·BNB 수수료는 net == executed
        BigDecimal baseFee = fills.stream().filter(f -> baseAsset.equals(f.commissionAsset()))
                .map(FillRow::commission).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CryptoOrderResult(String.valueOf(o.orderId()), BinanceClientOrderIds.fromWire(o.clientOrderId()), state,
                executed, executed.subtract(baseFee), avgPrice, paidFee, feeAsset);
    }

    record AccountRow(List<BalanceRow> balances) {}                                  // GET /api/v3/account
    record BalanceRow(String asset, BigDecimal free, BigDecimal locked) {}           // 자산별 가용·잠금
    record PriceRow(String symbol, BigDecimal price) {}                              // GET /api/v3/ticker/price
    record ExchangeInfoRow(List<SymbolRow> symbols) {}                               // GET /api/v3/exchangeInfo
    record SymbolRow(String symbol, String status, List<FilterRow> filters) {}       // 심볼 상태·필터
    record OrderRow(String symbol, long orderId, String clientOrderId, String status, BigDecimal executedQty, BigDecimal cummulativeQuoteQty, List<FillRow> fills) {} // GET/POST /api/v3/order
    record FillRow(BigDecimal price, BigDecimal qty, BigDecimal commission, String commissionAsset) {} // FULL fills·myTrades 공통
    record FilterRow(String filterType, BigDecimal stepSize, BigDecimal minNotional) {} // LOT_SIZE.stepSize / NOTIONAL.minNotional
}
