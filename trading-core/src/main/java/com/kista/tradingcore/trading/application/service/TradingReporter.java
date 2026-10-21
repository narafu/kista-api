package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.trading.domain.model.TradingReport;
import com.kista.tradingcore.trading.application.event.TradingReportReadyEvent;
import com.kista.shared.kernel.TradeLegSummary;
import com.kista.tradingcore.trading.application.event.TradingErrorEvent;
import com.kista.tradingcore.broker.domain.model.Execution;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.shared.kernel.OrderDirection;
import com.kista.tradingcore.privacy.domain.model.PrivacyTradeBase;
import com.kista.tradingcore.trading.domain.model.*;
import com.kista.tradingcore.broker.domain.model.BrokerApiException;
import com.kista.shared.kernel.NotificationType;
import com.kista.tradingcore.trading.application.port.output.*;
import com.kista.tradingcore.broker.domain.model.CancelInstruction;
import com.kista.tradingcore.broker.application.port.output.BrokerOrderCorrectionPort;
import com.kista.tradingcore.broker.application.port.output.ExecutionPort;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyTicker;

// 체결 조회 + 알림 발송 (포지션 저장은 CyclePositionPersistor에 위임)
@Component
@RequiredArgsConstructor
@Slf4j
class TradingReporter {

    private final ExecutionPort executionPort;

    private final BrokerOrderCorrectionPort brokerOrderCorrectionPort;
    private final OrderPort orderPort;                              // 주문 체결 상태 갱신
    private final CyclePositionPersistor cyclePositionPersistor;   // 포지션 스냅샷 저장 위임
    private final ApplicationEventPublisher eventPublisher;        // 리포트/SSE 알림 이벤트 발행
    private final CycleOrderStrategies cycleStrategies;            // 전략별 시드 점유 여부(occupiesSeed) 조회

    void recordAndNotify(LocalDate today, BatchContext ctx, AccountBalance balance,
                         BigDecimal closingPrice, List<Order> mainOrders, PrivacyTradeBase privacyBase) {
        Strategy strategy = ctx.strategy();
        TradingAccount account = ctx.account();
        TradingUserProfile userProfile = ctx.userProfile();
        warnLeftoverSubmitting(today, ctx);
        // 장마감 후에도 체결 가능한 잔여 PLACED 주문을 취소 — 애프터마켓 체결이 CANCELLED로 오기록되는 것을 방지
        cancelUnresolvedOrders(mainOrders, account);

        // today는 KST — KIS는 어댑터에서 toUtc 변환, Toss는 KST 날짜 그대로 전달
        List<Execution> executions = executionPort.getExecutions(today, today, strategy.ticker(), account.brokerRef());
        // 원장 = 우리 매매 원칙의 기록, 외부 매매는 수동 보정으로만 반영 (spec 3장 원장 원칙) — 자기 주문(externalOrderId) 체결만 사용
        // 당일 취소·재주문된 주문(CANCELLED)도 부분 체결분이 있을 수 있어 자기 주문에 포함 — 수동 체결 보정 FILLED 행(UNKNOWN_LEG)은 포지션을 직접 기록했으므로 제외
        // 외부 주문번호가 없는 체결은 우리 것이 아닌 것으로 본다 (ownIds에 null이 없어 걸러짐)
        Set<String> ownIds = Stream.concat(mainOrders.stream(),
                        orderPort.findCancelledByCycleAndDate(ctx.currentCycle().id(), today).stream())
                .map(Order::externalOrderId).filter(Objects::nonNull).collect(Collectors.toSet());
        executions = executions.stream().filter(e -> ownIds.contains(e.externalOrderId())).toList();
        log.info("[{}] 체결 내역 {}건 조회", account.nickname(), executions.size());

        // 체결 결과로 매매 후 잔고 계산 (체결 없으면 pre-trade 그대로)
        List<AccountBalance.Fill> fills = executions.stream()
                .map(ExecutionFillMapper::toFill)
                .toList();
        AccountBalance applied = balance.applyExecutions(fills);
        // 시드를 점유하지 않는 전략(DCA)은 usd_deposit 0 — calcFreeCash가 점유 시드로 합산하지 않게 한다
        AccountBalance postBalance = cycleStrategies.of(strategy.type()).occupiesSeed()
                ? applied : applied.withUsdDeposit(BigDecimal.ZERO);
        // 접수된 주문별 체결 현황 기록 (FILLED / PARTIALLY_FILLED) — 포지션 저장(rotation 포함)보다 먼저:
        // rotation 실패가 예외로 끝나도 주문이 PLACED로 남지 않게 한다 (리포트 마커가 이미 찍혀 재개로도 복구되지 않음)
        markFilledOrders(mainOrders, executions);

        cyclePositionPersistor.saveCyclePosition(today, postBalance, ctx, closingPrice, privacyBase);

        // TRADING_ALERT 알림 활성 여부 확인(기본값 true) 후 리포트/SSE 알림 이벤트 발행 — 실제 발송은 TradingReportNotifier에 위임
        TradingReport report = buildReport(today, strategy.type(), strategy.ticker(), executions);
        boolean reportEnabled = userProfile.isNotificationEnabled(NotificationType.TRADING_ALERT);
        List<TradeLegSummary> legs = executions.stream()
                .map(e -> new TradeLegSummary(e.direction(), e.ticker(), e.quantity(), e.price(), e.amountUsd()))
                .toList();
        eventPublisher.publishEvent(new TradingReportReadyEvent(userProfile.userId(), account.id(), strategy.id(), account.nickname(), report, legs, reportEnabled));
        log.info("[{}] 매매 리포트 이벤트 발행 완료 (executions={}건)", account.nickname(), executions.size());
    }

    // 체결 조회 전 잔여 PLACED 주문을 증권사에 취소 요청 — 이미 체결된 주문의 취소는 브로커가 거부(무시)한다.
    // 실패해도 흐름은 계속되며(다음 getExecutions로 실제 상태를 확정), 취소 자체 실패만 관리자에게 알린다.
    // Toss 전용 — 정규장 지정가 주문이 애프터장까지 이어져 다음날 09:00 KST에야 자동 취소되므로 명시적 취소가 필요.
    // KIS는 정규장 종료 시 자동 취소되어 이 호출이 불필요 — 스킵해 마감 시점 KIS API 호출량(rate-limit 위험)도 함께 줄인다.
    private void cancelUnresolvedOrders(List<Order> mainOrders, TradingAccount account) {
        if (account.broker() != Broker.TOSS) return;
        for (Order order : mainOrders) {
            if (order.status() != OrderStatus.PLACED || order.externalOrderId() == null) continue;
            try {
                brokerOrderCorrectionPort
                        .cancel(new CancelInstruction(order.ticker(), order.externalOrderId()), account.brokerRef());
            } catch (Exception e) {
                if (isAlreadyFilled(e)) {
                    // 취소 요청 직전/직후 체결 확정 — 브로커 주석대로 예상된 경합, 관리자 알림 불필요
                    log.info("[orderId={}] 취소 시점 이미 체결 완료 — 취소 생략", order.id());
                } else {
                    log.warn("[orderId={}] 마감 후 잔여 주문 취소 실패: {}", order.id(), e.getMessage());
                    eventPublisher.publishEvent(new TradingErrorEvent(null, "[%s %s 잔여 주문 취소 실패] orderId=%s — %s"
                            .formatted(account.nickname(), order.ticker(), order.id(), e.getMessage())));
                }
            }
        }
    }

    // 취소 시점 이미 체결 확정 여부 — 어댑터(TossHttpClient)가 409 CONFLICT(already-filled) 응답을 판정해 BrokerApiException에 실어 보낸다
    private boolean isAlreadyFilled(Exception e) {
        return e instanceof BrokerApiException bae && bae.isAlreadyFilledConflict();
    }

    // 접수 주문과 실체결 내역을 externalOrderId 기준으로 매칭하여 FILLED / PARTIALLY_FILLED 기록
    private void markFilledOrders(List<Order> mainOrders, List<Execution> executions) {
        // externalOrderId → 체결 목록 그룹핑 (1:N 체결 허용)
        Map<String, List<Execution>> byOrderId = executions.stream()
                .filter(e -> e.externalOrderId() != null && !e.externalOrderId().isBlank())
                .collect(Collectors.groupingBy(Execution::externalOrderId));

        for (Order order : mainOrders) {
            if (order.externalOrderId() == null) continue;
            List<Execution> matched = byOrderId.get(order.externalOrderId());
            if (matched == null || matched.isEmpty()) {
                // 체결 내역 없음 → 미체결(LOC/MOC 당일 자동 취소) → CANCELLED
                orderPort.markCancelled(order.id());
                log.info("[orderId={}] 미체결 → CANCELLED", order.id());
                continue;
            }

            int filledQuantity = matched.stream().mapToInt(Execution::quantity).sum();
            // 가중평균 체결가: Σ(체결금액) ÷ Σ(체결수량)
            BigDecimal totalAmt = matched.stream().map(Execution::amountUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avgFilledPrice = filledQuantity > 0
                    ? totalAmt.divide(BigDecimal.valueOf(filledQuantity), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            int orderedQuantity = order.quantity() != null ? order.quantity().intValueExact() : 0;
            OrderStatus newStatus = filledQuantity >= orderedQuantity
                    ? OrderStatus.FILLED
                    : OrderStatus.PARTIALLY_FILLED;

            orderPort.markFilled(order.id(), BigDecimal.valueOf(filledQuantity), avgFilledPrice, newStatus);
            log.info("[orderId={}] {} → {}, 체결수량={}/{}", order.id(), order.status(), newStatus, filledQuantity, orderedQuantity);
        }
    }

    private TradingReport buildReport(LocalDate today, StrategyType strategyType, StrategyTicker ticker, List<Execution> executions) {
        BigDecimal totalBought = executions.stream()
                .filter(e -> e.direction() == OrderDirection.BUY)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSold = executions.stream()
                .filter(e -> e.direction() == OrderDirection.SELL)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TradingReport(today, strategyType, ticker, totalBought, totalSold);
    }

    // 접수 결과 불명(SUBMITTING) 잔류 — 체결 매칭(PLACED만)에서 빠져 실제 체결 시 포지션이 어긋나므로 런북 정정·수동 체결 보정을 알린다
    private void warnLeftoverSubmitting(LocalDate today, BatchContext ctx) {
        List<UUID> leftoverIds = orderPort.findSubmittingByCycleAndDate(ctx.currentCycle().id(), today).stream()
                .map(s -> s.order().id()).toList();
        if (leftoverIds.isEmpty()) return;
        log.error("[{}] 리포트 시점 SUBMITTING 잔류 — 리포트 제외: {}", ctx.account().nickname(), leftoverIds);
        eventPublisher.publishEvent(new TradingErrorEvent(null,
                "[접수 결과 불명 — 리포트 제외] %s %s 거래일 %s orderId=%s — 증권사 확인 후 런북 정정, 체결분은 수동 체결 보정"
                        .formatted(ctx.account().nickname(), ctx.strategy().ticker(), today, leftoverIds)));
    }
}

