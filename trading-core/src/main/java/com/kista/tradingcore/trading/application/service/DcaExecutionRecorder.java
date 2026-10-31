package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.tradingcore.trading.application.event.DcaRoundSkippedEvent;
import com.kista.tradingcore.trading.application.event.TradingErrorEvent;
import com.kista.tradingcore.trading.application.port.output.DcaExecutionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyDcaDetailPort;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.DcaExecution;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

// DCA 규칙 회차 이력 기록 — UNIQUE(strategy, ruleKey, tradeDate) 삽입 성공 시에만 알림 이벤트를 발행해 재개·중복 진입 알림 중복을 차단
@Component
@RequiredArgsConstructor
class DcaExecutionRecorder {

    private final DcaExecutionPort dcaExecutionPort;
    private final ApplicationEventPublisher eventPublisher;
    private final StrategyDcaDetailPort dcaDetailPort;

    @Transactional
    public void recordOrdered(BatchContext ctx, LocalDate tradeDate, String ruleKey, Order order, BigDecimal referencePrice) {
        dcaExecutionPort.insertIfAbsent(
                DcaExecution.ordered(ctx.strategy().id(), ruleKey, order.direction(), tradeDate, order.id(), order.quantity(), referencePrice));
    }

    @Transactional
    // direction: 호출부가 규칙(판정 경로) 또는 주문(복구·배정 경로)에서 넘긴다 — 이력·알림 방향의 SSOT
    public void recordSkipped(BatchContext ctx, LocalDate tradeDate, String ruleKey, OrderDirection direction, DcaSkipReason reason,
                              String detail, BigDecimal referencePrice) {
        recordSkipped(ctx.strategy(), ctx.account(), tradeDate, ruleKey, direction, reason, detail, referencePrice);
    }

    // BatchContext 없이 호출하는 접수 단계(TradingOrderExecutor)용 — strategy·account만 필요
    @Transactional
    public void recordSkipped(Strategy strategy, TradingAccount account, LocalDate tradeDate, String ruleKey, OrderDirection direction,
                              DcaSkipReason reason, String detail, BigDecimal referencePrice) {
        boolean inserted = dcaExecutionPort.insertIfAbsent(
                DcaExecution.skipped(strategy.id(), ruleKey, direction, tradeDate, reason, detail, referencePrice));
        if (!inserted) return;
        publishSkipped(strategy, account, tradeDate, ruleKey, direction, reason, detail);
        adminAlertIfNeeded(strategy, tradeDate, ruleKey, reason, detail);
    }

    private void adminAlertIfNeeded(Strategy strategy, LocalDate tradeDate, String ruleKey, DcaSkipReason reason, String detail) {
        if (reason == DcaSkipReason.DATA_UNAVAILABLE || reason == DcaSkipReason.EXCHANGE_API_ERROR) {
            // 시세·수량 조회·거래소 API 실패는 사용자 미주문 알림과 별개로 관리자에게도 알린다 (spec 5.7)
            String label = reason == DcaSkipReason.DATA_UNAVAILABLE ? "[DCA 데이터 실패]" : "[DCA 거래소 오류]";
            eventPublisher.publishEvent(new TradingErrorEvent(null,
                    label + " strategyId=" + strategy.id() + ", " + strategy.ticker() + ", rule=" + ruleKey + ", " + tradeDate + " — " + detail));
        }
    }

    // 증권사가 접수 거절한 주문 — 이미 기록된 ORDERED 이력을 SKIPPED로 전이하고 사용자 알림. 전이할 ORDERED 행이 없으면
    // (이력 미기록·다른 주문 연결) 새 SKIPPED 삽입을 시도하고, 그것도 불가하면 조용히 묻히지 않게 관리자 알림
    @Transactional
    public void recordOrderRejected(Strategy strategy, TradingAccount account, LocalDate tradeDate, String ruleKey, Order order,
                                    DcaSkipReason reason, String detail) {
        if (dcaExecutionPort.markOrderedSkipped(strategy.id(), ruleKey, tradeDate, order.id(), reason, detail)) {
            publishSkipped(strategy, account, tradeDate, ruleKey, order.direction(), reason, detail);
            return;
        }
        if (dcaExecutionPort.insertIfAbsent(DcaExecution.skipped(strategy.id(), ruleKey, order.direction(), tradeDate, reason, detail, order.price()))) {
            publishSkipped(strategy, account, tradeDate, ruleKey, order.direction(), reason, detail);
            return;
        }
        eventPublisher.publishEvent(new TradingErrorEvent(null, "[DCA 접수 거절 이력 불일치] strategyId=" + strategy.id() + ", " + strategy.ticker()
                + ", rule=" + ruleKey + ", " + tradeDate + ", orderId=" + order.id() + " — " + detail + " (ORDERED 이력 전이 실패, 사용자 알림 없음)"));
    }

    // 알림 표기용 규칙 순번만 활성 설정에서 조회 — 편집으로 삭제된 규칙이면 null(문구에서 생략)
    private void publishSkipped(Strategy strategy, TradingAccount account, LocalDate tradeDate, String ruleKey, OrderDirection direction,
                                DcaSkipReason reason, String detail) {
        // 조건 미충족은 실패가 아닌 정상 대기라 알림하지 않는다 — 이력(dca_execution)은 남는다
        if (reason == DcaSkipReason.CONDITION_NOT_MET) return;
        Integer ruleNumber = dcaDetailPort.findActiveByStrategyId(strategy.id()).map(c -> c.ruleNumber(ruleKey)).orElse(null);
        eventPublisher.publishEvent(new DcaRoundSkippedEvent(account.userId(), strategy.id(), account.nickname(),
                strategy.ticker(), ruleKey, ruleNumber, direction, tradeDate, reason, detail));
    }
}
