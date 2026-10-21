package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.Quantities;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.CryptoBalance;
import com.kista.tradingcore.broker.domain.model.CryptoCandle;
import com.kista.tradingcore.broker.domain.model.CryptoOrderResult;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.matching.domain.model.DcaRule;
import com.kista.tradingcore.matching.domain.model.DcaRuleDecision;
import com.kista.tradingcore.matching.domain.model.DcaStrategyConfig;
import com.kista.tradingcore.matching.domain.strategy.DcaCycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.DcaSchedule;
import com.kista.tradingcore.trading.application.port.output.CryptoFearGreedIndexPort;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.DcaExecutionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// 코인 DCA 회차 실행 — 한 계좌의 이번 틱 대상 전략×규칙을 decideRule 판정·시장가 주문·이력 기록까지 처리
// 원장 = 최신 cycle_position(체결 동기화가 갱신), 매도 가용 = min(원장 − 같은 틱 앞 규칙 매도분, 지갑 매도 가능 수량)
// HTTP를 포함하므로 @Transactional 없음 — 상태 변경은 OrderPort·recorder의 개별 트랜잭션. 계좌 단위 예외는 호출부(Task 8)가 격리
@Slf4j
@Component
@RequiredArgsConstructor
class CryptoDcaRoundExecutor {

    private static final int CANDLE_COUNT = 250;                  // 일봉 조회 봉 수 (주식 DcaPlanInputCollector와 동일)
    static final int LAST_TICK_MINUTE = 50;               // 이 분 이후 틱은 마지막 시도 — 일시 장애도 미주문 기록
    // 접수 확정 상태 — 신규 주문을 막고 이력만 복구 (FAILED·CANCELLED는 재시도 허용)
    private static final Set<OrderStatus> CONFIRMED = Set.of(OrderStatus.PLACED, OrderStatus.FILLED, OrderStatus.PARTIALLY_FILLED);
    // 접수 미확정 상태 — 동기화기(CryptoOrderSynchronizer) 소관이라 이력 없이 신규 주문만 막는다
    private static final Set<OrderStatus> UNSETTLED = Set.of(OrderStatus.PLANNED, OrderStatus.SUBMITTING);

    // 이번 틱 판정 단위 — 전략 컨텍스트 × 규칙
    private record Target(BatchContext ctx, DcaRule rule) {}

    // 한 틱 안의 로컬 차감 지갑 — 같은 계좌 후속 규칙이 이미 쓴 원화·보유 수량을 보게 한다(매도 대금은 더하지 않음 — 보수적)
    private static final class Wallet {
        BigDecimal krw;                         // 원화 가용
        final Map<StrategyTicker, BigDecimal> held = new EnumMap<>(StrategyTicker.class); // 코인별 매도 가능 수량
        final Map<UUID, BigDecimal> ledgerSold = new HashMap<>(); // 사이클별 같은 틱 앞 규칙 매도 수량 — 규칙 합계가 원장을 넘지 않게
    }

    private final CryptoFearGreedIndexPort fearGreedPort;
    private final OrderPort orderPort;
    private final DcaExecutionPort dcaExecutionPort;
    private final DcaExecutionRecorder recorder;
    private final DcaCycleOrderStrategy dcaStrategy; // 규칙 판정 커널
    private final CyclePositionPort cyclePositionPort; // 원장(최신 cycle_position)

    void executeAccount(CryptoExchangePort exchange, List<BatchContext> accountContexts,
                        Map<UUID, DcaStrategyConfig> configsByStrategyId, LocalDateTime nowKst) {
        LocalDate today = nowKst.toLocalDate();
        List<Target> targets = targets(accountContexts, configsByStrategyId, nowKst, today);
        if (targets.isEmpty()) return;

        // ponytail: 캔들·공포탐욕 메모는 호출(계좌) 단위 — 계좌 수만큼 재조회, 계좌가 늘면 틱 단위 메모로 승격
        Map<StrategyTicker, List<CryptoCandle>> candleMemo = new HashMap<>();
        List<Optional<BigDecimal>> fearGreedMemo = new ArrayList<>(1); // 비어 있으면 미조회
        int i = 0;
        try {
            BrokerAccountRef ref = targets.get(0).ctx().account().brokerRef();
            CryptoBalance balance = exchange.getBalance(ref);
            // 원장 — 사이클별 최신 cycle_position 1회 배치 조회. 행이 없으면(초기 보유 없는 첫 체결 전) 0
            Map<UUID, CyclePosition> ledgers = cyclePositionPort.findLatestByCycleIds(
                    targets.stream().map(t -> t.ctx().currentCycle().id()).distinct().toList());
            Map<StrategyTicker, BigDecimal> prices = exchange.getCurrentPrices(
                    targets.stream().map(t -> t.ctx().strategy().ticker()).distinct().toList());
            Wallet wallet = new Wallet();
            wallet.krw = balance.krwAvailable();
            balance.holdings().forEach((t, h) -> wallet.held.put(t, h.available()));
            for (; i < targets.size(); i++) {
                BatchContext ctx = targets.get(i).ctx();
                DcaRule rule = targets.get(i).rule();
                String key = rule.ruleKey();
                StrategyTicker ticker = ctx.strategy().ticker();
                BigDecimal price = prices.get(ticker);
                if (price == null || price.signum() <= 0) {
                    recorder.recordSkipped(ctx, today, key, rule.direction(), DcaSkipReason.DATA_UNAVAILABLE, "현재가 조회 실패", null);
                    continue;
                }
                List<BigDecimal> closes = closes(exchange, ticker, candleMemo, nowKst, today);
                UUID cycleId = ctx.currentCycle().id();
                CyclePosition position = ledgers.get(cycleId);
                AccountBalance ledger = position == null ? new AccountBalance(BigDecimal.ZERO, null, BigDecimal.ZERO) : position.toBalance();
                // 매도 가용 — 원장에서 같은 틱 앞 규칙 매도분을 빼고 지갑 매도 가능 수량(로컬 차감)으로 상한
                BigDecimal sellable = ledger.holdings().subtract(wallet.ledgerSold.getOrDefault(cycleId, BigDecimal.ZERO))
                        .min(wallet.held.getOrDefault(ticker, BigDecimal.ZERO)).max(BigDecimal.ZERO);
                BigDecimal fearGreed = null;
                if (rule.hasIndicator(DcaIndicator.FEAR_GREED)) {
                    if (fearGreedMemo.isEmpty()) fearGreedMemo.add(fearGreedPort.latest());
                    fearGreed = fearGreedMemo.get(0).orElse(null);
                }
                DcaRuleDecision decision = dcaStrategy.decideRule(new DcaCycleOrderStrategy.DcaRuleInputs(rule, Market.CRYPTO_KRW,
                        closes, ledger, sellable, fearGreed, price, wallet.krw));
                if (decision instanceof DcaRuleDecision.Skipped s) {
                    recorder.recordSkipped(ctx, today, key, rule.direction(), s.reason(), s.detail(), s.referencePrice());
                    continue;
                }
                RuntimeException keyExpired = place(exchange, ctx, rule, today, (DcaRuleDecision.Planned) decision, wallet);
                if (keyExpired != null) {
                    // 키 만료는 계좌 단위 문제 — 남은 규칙은 주문·HTTP 없이 일괄 미주문 기록
                    handleFailure(targets.subList(i + 1, targets.size()), keyExpired, nowKst, today);
                    return;
                }
            }
        } catch (RuntimeException e) {
            // 조회 단계 실패 — 이 규칙부터 남은 대상에 오류 규칙 적용
            handleFailure(targets.subList(i, targets.size()), e, nowKst, today);
        }
    }

    // 이번 틱 대상 — 규칙 실행 시각·실행일 일치, 그 규칙의 당일 이력 없음. 당일 규칙 주문이 이미 있으면 이력만 복구하고 제외
    // 같은 틱은 SELL 규칙 먼저, BUY 나중(같은 방향은 입력 순서 — 안정 정렬)
    private List<Target> targets(List<BatchContext> contexts, Map<UUID, DcaStrategyConfig> configs, LocalDateTime now, LocalDate today) {
        List<Target> result = new ArrayList<>();
        for (BatchContext ctx : contexts) {
            DcaStrategyConfig config = configs.get(ctx.strategy().id());
            if (config == null) continue;
            UUID strategyId = ctx.strategy().id();
            List<Order> todayOrders = null; // 실행 시각 규칙이 있을 때만 1회 조회
            for (DcaRule rule : config.rules()) {
                if (rule.executionHour() != now.getHour()) continue;
                if (!DcaSchedule.isExecutionDay(today, rule, d -> true)) continue;
                if (dcaExecutionPort.find(strategyId, rule.ruleKey(), today).isPresent()) continue;
                if (todayOrders == null) todayOrders = orderPort.findByStrategyId(strategyId, today, today);
                List<Order> sameLeg = todayOrders.stream().filter(o -> rule.orderLeg().equals(o.orderLeg())).toList();
                if (sameLeg.stream().anyMatch(o -> UNSETTLED.contains(o.status()))) continue;
                Optional<Order> confirmed = sameLeg.stream().filter(o -> CONFIRMED.contains(o.status())).findFirst();
                if (confirmed.isPresent()) {
                    recorder.recordOrdered(ctx, today, rule.ruleKey(), confirmed.get(), confirmed.get().price());
                    continue;
                }
                result.add(new Target(ctx, rule));
            }
        }
        result.sort(Comparator.comparing(t -> t.rule().direction() != OrderDirection.SELL)); // SELL(false) 먼저
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

    // 판정된 계획 → 주문 저장·선점·전송. 지갑은 같은 틱 후속 규칙을 위한 로컬 차감 대상
    // 반환: 키 만료로 확정 거절되면 그 예외(호출부가 남은 규칙을 일괄 처리), 그 외 null
    private RuntimeException place(CryptoExchangePort exchange, BatchContext ctx, DcaRule rule, LocalDate today,
                                   DcaRuleDecision.Planned plan, Wallet wallet) {
        StrategyTicker ticker = ctx.strategy().ticker();
        boolean buy = rule.direction() == OrderDirection.BUY;
        BigDecimal price = plan.referencePrice();
        BigDecimal volume = Quantities.normalize(plan.quantity());             // 추정(BUY)·실제(SELL) 수량 — 꼬리 0 제거(거래소 전송 형태 유지)
        BigDecimal amount = buy ? plan.notional() : volume.multiply(price);   // 주문 금액(원) — BUY는 정수 원
        String key = rule.ruleKey();
        Order order = orderPort.save(Order.cryptoMarket(ctx.account().id(), ctx.currentCycle().id(), today, ticker,
                rule.direction(), rule.orderLeg(), rule.timing(), volume, price));
        orderPort.markSubmitting(order.id());
        String identifier = order.id().toString();
        var ref = ctx.account().brokerRef();
        try {
            CryptoOrderResult result = buy ? exchange.buyMarketByAmount(ref, ticker, amount, identifier)
                    : exchange.sellMarketByVolume(ref, ticker, volume, identifier);
            // 이력 먼저(멱등), 상태 전이 나중 — 사이 중단돼도 다음 틱이 무해하게 재처리
            recorder.recordOrdered(ctx, today, key, order, price);
            orderPort.markPlaced(order.id(), result.uuid());
        } catch (RuntimeException e) {
            CryptoFailure failure = CryptoFailure.classify(e);
            if (failure == CryptoFailure.TRANSIENT) {
                // 접수 결과 불명 — SUBMITTING 유지(동기화기가 identifier로 복구), 이중 지출 방지 위해 차감은 성공과 동일
                log.warn("코인 DCA 주문 결과 불명 orderId={}", order.id(), e);
            } else {
                recorder.recordSkipped(ctx, today, key, rule.direction(), failure.reason(), failureDetail(failure), price);
                orderPort.markFailed(order.id());
                return failure == CryptoFailure.KEY_EXPIRED ? e : null;
            }
        }
        if (buy) wallet.krw = wallet.krw.subtract(amount.multiply(DcaRule.CRYPTO_FEE_FACTOR));
        else {
            wallet.held.merge(ticker, volume, BigDecimal::subtract);
            wallet.ledgerSold.merge(ctx.currentCycle().id(), volume, BigDecimal::add);
        }
        return null;
    }

    // 오류 규칙 — KEY_EXPIRED·REJECTED는 남은 대상 전부 미주문 기록, TRANSIENT는 마지막 틱(50분~)에만 기록하고 아니면 다음 틱 재시도
    private void handleFailure(List<Target> remaining, RuntimeException e, LocalDateTime now, LocalDate today) {
        CryptoFailure failure = CryptoFailure.classify(e);
        if (failure == CryptoFailure.TRANSIENT && now.getMinute() < LAST_TICK_MINUTE) {
            log.warn("코인 DCA 조회 일시 실패 — 다음 틱 재시도", e);
            return;
        }
        for (Target t : remaining) {
            recorder.recordSkipped(t.ctx(), today, t.rule().ruleKey(), t.rule().direction(), failure.reason(), failureDetail(failure), null);
        }
    }

    // 사용자에게 노출되는 문구 — 내부 예외 메시지는 싣지 않는다
    private static String failureDetail(CryptoFailure failure) {
        return failure == CryptoFailure.KEY_EXPIRED ? "API 키가 만료되었거나 폐기되었습니다" : "거래소 API 호출에 실패했습니다";
    }
}
