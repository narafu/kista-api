package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.tradingcore.matching.domain.model.DcaRule;
import com.kista.tradingcore.matching.domain.model.DcaStrategyConfig;
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
import java.util.Optional;

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
                DcaExecution.ordered(ctx.strategy().id(), ruleKey, tradeDate, order.id(), order.quantity(), referencePrice));
    }

    @Transactional
    public void recordSkipped(BatchContext ctx, LocalDate tradeDate, String ruleKey, DcaSkipReason reason, String detail,
                              BigDecimal referencePrice) {
        boolean inserted = dcaExecutionPort.insertIfAbsent(
                DcaExecution.skipped(ctx.strategy().id(), ruleKey, tradeDate, reason, detail, referencePrice));
        if (!inserted) return;
        // 알림 표기용 방향·순번 — 활성 규칙 기준, 편집으로 삭제된 규칙이면 null
        Optional<DcaStrategyConfig> config = dcaDetailPort.findActiveByStrategyId(ctx.strategy().id());
        OrderDirection direction = config.flatMap(c -> c.rule(ruleKey)).map(DcaRule::direction).orElse(null);
        Integer ruleNumber = config.map(c -> c.ruleNumber(ruleKey)).orElse(null);
        eventPublisher.publishEvent(new DcaRoundSkippedEvent(ctx.account().userId(), ctx.strategy().id(), ctx.account().nickname(),
                ctx.strategy().ticker(), ruleKey, ruleNumber, direction, tradeDate, reason, detail));
        if (reason == DcaSkipReason.DATA_UNAVAILABLE || reason == DcaSkipReason.EXCHANGE_API_ERROR) {
            // 시세·수량 조회·거래소 API 실패는 사용자 미주문 알림과 별개로 관리자에게도 알린다 (spec 5.7)
            String label = reason == DcaSkipReason.DATA_UNAVAILABLE ? "[DCA 데이터 실패]" : "[DCA 거래소 오류]";
            eventPublisher.publishEvent(new TradingErrorEvent(null,
                    label + " strategyId=" + ctx.strategy().id() + ", " + ctx.strategy().ticker() + ", rule=" + ruleKey + ", " + tradeDate + " — " + detail));
        }
    }
}
