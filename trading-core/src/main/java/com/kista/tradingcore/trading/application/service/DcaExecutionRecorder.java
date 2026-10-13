package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.trading.application.event.DcaRoundSkippedEvent;
import com.kista.tradingcore.trading.application.event.TradingErrorEvent;
import com.kista.tradingcore.trading.application.port.output.DcaExecutionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyDcaDetailPort;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.DcaExecution;
import com.kista.tradingcore.trading.domain.model.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

// DCA 회차 이력 기록 — UNIQUE(strategy, tradeDate) 삽입 성공 시에만 알림 이벤트를 발행해 재개·중복 진입 알림 중복을 차단
@Component
@RequiredArgsConstructor
class DcaExecutionRecorder {

    private final DcaExecutionPort dcaExecutionPort;
    private final ApplicationEventPublisher eventPublisher;
    private final StrategyDcaDetailPort dcaDetailPort;

    @Transactional
    public void recordOrdered(BatchContext ctx, LocalDate tradeDate, Order order, BigDecimal referencePrice) {
        dcaExecutionPort.insertIfAbsent(
                DcaExecution.ordered(ctx.strategy().id(), tradeDate, order.id(), order.quantity(), referencePrice));
    }

    @Transactional
    public void recordSkipped(BatchContext ctx, LocalDate tradeDate, DcaSkipReason reason, String detail, BigDecimal referencePrice) {
        boolean inserted = dcaExecutionPort.insertIfAbsent(
                DcaExecution.skipped(ctx.strategy().id(), tradeDate, reason, detail, referencePrice));
        if (!inserted) return;
        OrderDirection direction = dcaDetailPort.findActiveByStrategyId(ctx.strategy().id()).map(DcaConfig::direction).orElse(null);
        eventPublisher.publishEvent(new DcaRoundSkippedEvent(ctx.account().userId(), ctx.strategy().id(), ctx.account().nickname(),
                ctx.strategy().ticker(), direction, tradeDate, reason, detail));
        if (reason == DcaSkipReason.DATA_UNAVAILABLE) {
            // 시세·수량 조회 실패는 사용자 미주문 알림과 별개로 관리자에게도 알린다 (spec 5.7)
            eventPublisher.publishEvent(new TradingErrorEvent(null,
                    "[DCA 데이터 실패] strategyId=" + ctx.strategy().id() + ", " + ctx.strategy().ticker() + ", " + tradeDate + " — " + detail));
        }
    }
}
