package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.CryptoBalance;
import com.kista.tradingcore.broker.domain.model.CryptoCandle;
import com.kista.tradingcore.broker.domain.model.CryptoOrderResult;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.matching.domain.strategy.DcaConditions;
import com.kista.tradingcore.matching.domain.strategy.DcaCycleOrderStrategy.DcaInputs.Holding;
import com.kista.tradingcore.matching.domain.strategy.DcaSchedule;
import com.kista.tradingcore.trading.application.port.output.CryptoFearGreedIndexPort;
import com.kista.tradingcore.trading.application.port.output.DcaExecutionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// 코인 DCA 회차 실행 — 한 계좌의 이번 틱 대상 전략을 조건 평가·금액 산정·시장가 주문·이력 기록까지 처리
// HTTP를 포함하므로 @Transactional 없음 — 상태 변경은 OrderPort·recorder의 개별 트랜잭션. 계좌 단위 예외는 호출부(Task 8)가 격리
@Slf4j
@Component
@RequiredArgsConstructor
class CryptoDcaRoundExecutor {

    private static final int CANDLE_COUNT = 250;                  // 일봉 조회 봉 수 (주식 DcaRoundPlanner와 동일)
    // ponytail: 업비트 KRW 수수료율 고정 — 거래소별 수수료 조회가 필요해지면 확장
    private static final BigDecimal FEE_FACTOR = new BigDecimal("1.0005"); // 1 + 수수료율 0.05%
    static final int LAST_TICK_MINUTE = 50;               // 이 분 이후 틱은 마지막 시도 — 일시 장애도 미주문 기록
    // 접수 확정 상태 — 신규 주문을 막고 이력만 복구 (FAILED·CANCELLED는 재시도 허용)
    private static final Set<OrderStatus> CONFIRMED = Set.of(OrderStatus.PLACED, OrderStatus.FILLED, OrderStatus.PARTIALLY_FILLED);
    // 접수 미확정 상태 — 동기화기(CryptoOrderSynchronizer) 소관이라 이력 없이 신규 주문만 막는다
    private static final Set<OrderStatus> UNSETTLED = Set.of(OrderStatus.PLANNED, OrderStatus.SUBMITTING);

    // 한 틱 안의 로컬 차감 지갑 — 같은 계좌 후속 전략이 이미 쓴 원화·보유 수량을 보게 한다
    private static final class Wallet {
        BigDecimal krw;                         // 원화 가용
        final Map<StrategyTicker, BigDecimal> held = new EnumMap<>(StrategyTicker.class); // 코인별 매도 가능 수량
    }

    private final CryptoFearGreedIndexPort fearGreedPort;
    private final OrderPort orderPort;
    private final DcaExecutionPort dcaExecutionPort;
    private final DcaExecutionRecorder recorder;

    void executeAccount(CryptoExchangePort exchange, List<BatchContext> accountContexts,
                        Map<UUID, DcaConfig> configsByStrategyId, LocalDateTime nowKst) {
        LocalDate today = nowKst.toLocalDate();
        List<BatchContext> targets = targets(accountContexts, configsByStrategyId, nowKst, today);
        if (targets.isEmpty()) return;

        // ponytail: 캔들·공포탐욕 메모는 호출(계좌) 단위 — 계좌 수만큼 재조회, 계좌가 늘면 틱 단위 메모로 승격
        Map<StrategyTicker, List<CryptoCandle>> candleMemo = new HashMap<>();
        List<Optional<BigDecimal>> fearGreedMemo = new ArrayList<>(1); // 비어 있으면 미조회
        int i = 0;
        try {
            BrokerAccountRef ref = targets.get(0).account().brokerRef();
            CryptoBalance balance = exchange.getBalance(ref);
            Map<StrategyTicker, BigDecimal> prices = exchange.getCurrentPrices(
                    targets.stream().map(c -> c.strategy().ticker()).distinct().toList());
            Wallet wallet = new Wallet();
            wallet.krw = balance.krwAvailable();
            balance.holdings().forEach((t, h) -> wallet.held.put(t, h.available()));
            for (; i < targets.size(); i++) {
                BatchContext ctx = targets.get(i);
                DcaConfig config = configsByStrategyId.get(ctx.strategy().id());
                StrategyTicker ticker = ctx.strategy().ticker();
                BigDecimal price = prices.get(ticker);
                if (price == null || price.signum() <= 0) {
                    recorder.recordSkipped(ctx, today, DcaSkipReason.DATA_UNAVAILABLE, "현재가 조회 실패", null);
                    continue;
                }
                List<BigDecimal> closes = closes(exchange, ticker, candleMemo, nowKst, today);
                Holding holding = null;
                if (config.hasIndicator(DcaIndicator.AVG_PRICE)) {
                    var h = balance.holdings().get(ticker);
                    holding = h == null ? new Holding(BigDecimal.ZERO, null) : new Holding(h.total(), h.avgBuyPrice());
                }
                BigDecimal fearGreed = null;
                if (config.hasIndicator(DcaIndicator.FEAR_GREED)) {
                    if (fearGreedMemo.isEmpty()) fearGreedMemo.add(fearGreedPort.latest());
                    fearGreed = fearGreedMemo.get(0).orElse(null);
                }
                var skipped = DcaConditions.check(config, closes, holding, fearGreed, price);
                if (skipped.isPresent()) {
                    var s = skipped.get();
                    recorder.recordSkipped(ctx, today, s.reason(), s.detail(), s.referencePrice());
                    continue;
                }
                RuntimeException keyExpired = place(exchange, ctx, config, today, price, wallet);
                if (keyExpired != null) {
                    // 키 만료는 계좌 단위 문제 — 남은 전략은 주문·HTTP 없이 일괄 미주문 기록
                    handleFailure(targets.subList(i + 1, targets.size()), keyExpired, nowKst, today);
                    return;
                }
            }
        } catch (RuntimeException e) {
            // 조회 단계 실패 — 이 전략부터 남은 대상에 오류 규칙 적용
            handleFailure(targets.subList(i, targets.size()), e, nowKst, today);
        }
    }

    // 이번 틱 대상 — 실행 시각·실행일 일치, 당일 이력 없음. 당일 주문이 이미 있으면 이력만 복구하고 제외
    private List<BatchContext> targets(List<BatchContext> contexts, Map<UUID, DcaConfig> configs, LocalDateTime now, LocalDate today) {
        List<BatchContext> result = new ArrayList<>();
        for (BatchContext ctx : contexts) {
            DcaConfig config = configs.get(ctx.strategy().id());
            if (config == null || config.executionHour() != now.getHour()) continue;
            if (!DcaSchedule.isExecutionDay(today, config, d -> true)) continue;
            UUID strategyId = ctx.strategy().id();
            if (dcaExecutionPort.find(strategyId, today).isPresent()) continue;
            List<Order> sameLeg = orderPort.findByStrategyId(strategyId, today, today).stream()
                    .filter(o -> config.orderLeg().equals(o.orderLeg())).toList();
            if (sameLeg.stream().anyMatch(o -> UNSETTLED.contains(o.status()))) continue;
            Optional<Order> confirmed = sameLeg.stream().filter(o -> CONFIRMED.contains(o.status())).findFirst();
            if (confirmed.isPresent()) {
                recorder.recordOrdered(ctx, today, confirmed.get(), confirmed.get().price());
                continue;
            }
            result.add(ctx);
        }
        return result;
    }

    // 전일 종가까지의 일봉 종가 — 마지막 봉이 기대 일자가 아니면 null(DATA_UNAVAILABLE). 조회 실패는 던진다
    private List<BigDecimal> closes(CryptoExchangePort exchange, StrategyTicker ticker,
                                    Map<StrategyTicker, List<CryptoCandle>> memo, LocalDateTime now, LocalDate today) {
        List<CryptoCandle> candles = memo.computeIfAbsent(ticker, t -> exchange.getCompletedDailyCandles(t, CANDLE_COUNT));
        if (candles.isEmpty()) return null;
        // 업비트 일봉은 KST 09:00 마감 — 09시 전에는 아직 어제 봉이 완성되지 않아 그제가 마지막
        LocalDate expected = now.getHour() >= 9 ? today.minusDays(1) : today.minusDays(2);
        if (!candles.getLast().kstDate().equals(expected)) return null;
        return candles.stream().map(CryptoCandle::close).toList();
    }

    // 금액 산정 → 최소 금액 검사 → 주문 저장·선점·전송. 지갑은 같은 틱 후속 전략을 위한 로컬 차감 대상
    // 반환: 키 만료로 확정 거절되면 그 예외(호출부가 남은 전략을 일괄 처리), 그 외 null
    private RuntimeException place(CryptoExchangePort exchange, BatchContext ctx, DcaConfig config, LocalDate today, BigDecimal price,
                       Wallet wallet) {
        StrategyTicker ticker = ctx.strategy().ticker();
        boolean buy = config.direction() == OrderDirection.BUY;
        BigDecimal amount;   // 주문 금액(원)
        BigDecimal volume;   // 추정·실제 수량
        if (buy) {
            // 업비트 금액 주문은 정수 원 — 저장 스케일(30000.00)이 새지 않도록 scale 0 고정
            amount = config.amount().min(wallet.krw.divide(FEE_FACTOR, 0, RoundingMode.FLOOR)).setScale(0, RoundingMode.FLOOR);
            volume = amount.divide(price, 8, RoundingMode.FLOOR);
        } else {
            BigDecimal held = wallet.held.getOrDefault(ticker, BigDecimal.ZERO);
            if (held.signum() <= 0) {
                recorder.recordSkipped(ctx, today, DcaSkipReason.NO_HOLDINGS, "보유 수량 0", price);
                return null;
            }
            volume = config.amount().divide(price, 8, RoundingMode.FLOOR).min(held);
            amount = volume.multiply(price);
        }
        if (amount.compareTo(DcaConfig.CRYPTO_MIN_AMOUNT) < 0) {
            recorder.recordSkipped(ctx, today, DcaSkipReason.BELOW_MIN_ORDER_AMOUNT,
                    "주문 금액 " + amount.setScale(0, RoundingMode.FLOOR).toPlainString() + " < 최소 5000", price);
            return null;
        }
        Order order = orderPort.save(Order.cryptoMarket(ctx.account().id(), ctx.currentCycle().id(), today, ticker,
                config.direction(), config.orderLeg(), config.timing(), volume, price));
        orderPort.markSubmitting(order.id());
        String identifier = order.id().toString();
        var ref = ctx.account().brokerRef();
        try {
            CryptoOrderResult result = buy ? exchange.buyMarketByAmount(ref, ticker, amount, identifier)
                    : exchange.sellMarketByVolume(ref, ticker, volume, identifier);
            // 이력 먼저(멱등), 상태 전이 나중 — 사이 중단돼도 다음 틱이 무해하게 재처리
            recorder.recordOrdered(ctx, today, order, price);
            orderPort.markPlaced(order.id(), result.uuid());
        } catch (RuntimeException e) {
            CryptoFailure failure = CryptoFailure.classify(e);
            if (failure == CryptoFailure.TRANSIENT) {
                // 접수 결과 불명 — SUBMITTING 유지(Task 6이 identifier로 복구), 이중 지출 방지 위해 차감은 성공과 동일
                log.warn("코인 DCA 주문 결과 불명 orderId={}", order.id(), e);
            } else {
                recorder.recordSkipped(ctx, today, failure.reason(), failureDetail(failure), price);
                orderPort.markFailed(order.id());
                return failure == CryptoFailure.KEY_EXPIRED ? e : null;
            }
        }
        if (buy) wallet.krw = wallet.krw.subtract(amount.multiply(FEE_FACTOR));
        else wallet.held.merge(ticker, volume, BigDecimal::subtract);
        return null;
    }

    // 오류 규칙 — KEY_EXPIRED·REJECTED는 남은 대상 전부 미주문 기록, TRANSIENT는 마지막 틱(50분~)에만 기록하고 아니면 다음 틱 재시도
    private void handleFailure(List<BatchContext> remaining, RuntimeException e, LocalDateTime now, LocalDate today) {
        CryptoFailure failure = CryptoFailure.classify(e);
        if (failure == CryptoFailure.TRANSIENT && now.getMinute() < LAST_TICK_MINUTE) {
            log.warn("코인 DCA 조회 일시 실패 — 다음 틱 재시도", e);
            return;
        }
        for (BatchContext ctx : remaining) {
            recorder.recordSkipped(ctx, today, failure.reason(), failureDetail(failure), null);
        }
    }

    // 사용자에게 노출되는 문구 — 내부 예외 메시지는 싣지 않는다
    private static String failureDetail(CryptoFailure failure) {
        return failure == CryptoFailure.KEY_EXPIRED ? "API 키가 만료되었거나 폐기되었습니다" : "거래소 API 호출에 실패했습니다";
    }
}
