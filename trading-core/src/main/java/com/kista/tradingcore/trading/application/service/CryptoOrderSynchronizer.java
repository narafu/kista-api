package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderStatus;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import com.kista.tradingcore.broker.domain.model.CryptoOrderResult;
import com.kista.tradingcore.matching.domain.model.DcaRule;
import com.kista.tradingcore.matching.domain.model.DcaStrategyConfig;
import com.kista.tradingcore.trading.application.event.CryptoOrderFilledEvent;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// 코인 주문 동기화 — 신규 실행 전에 SUBMITTING(접수 결과 불명)을 identifier로 복구하고 PLACED의 체결을 확정. 거래소 확인 전 재주문 금지(중복 매수 방지)
// HTTP를 포함하므로 @Transactional 없음 — 상태 변경은 OrderPort·recorder·ledgerRecorder의 개별 트랜잭션
@Slf4j
@Component
@RequiredArgsConstructor
class CryptoOrderSynchronizer {

    private final OrderPort orderPort;
    private final DcaExecutionRecorder recorder;
    private final CryptoLedgerRecorder ledgerRecorder; // 체결 확정 → 원장·주문 FILLED 기록(멱등)
    private final ApplicationEventPublisher eventPublisher;

    // accountContexts: 한 계좌의 전략 컨텍스트들
    void sync(CryptoExchangePort exchange, List<BatchContext> accountContexts,
              Map<UUID, DcaStrategyConfig> configsByStrategyId, LocalDateTime nowKst) {
        if (accountContexts.isEmpty()) return;
        Map<UUID, BatchContext> byCycle = accountContexts.stream()
                .collect(Collectors.toMap(c -> c.currentCycle().id(), Function.identity(), (a, b) -> a));
        for (Order order : orderPort.findOpenByAccountId(accountContexts.get(0).account().id())) {
            BatchContext ctx = byCycle.get(order.strategyCycleId());
            DcaStrategyConfig config = ctx == null ? null : configsByStrategyId.get(ctx.strategy().id());
            if (config == null) {
                log.warn("코인 주문 동기화 건너뜀 — 매칭되는 전략 없음 orderId={}", order.id());
                continue;
            }
            try {
                syncOne(exchange, ctx, config, order, nowKst);
            } catch (RuntimeException e) {
                // 조회 실패는 TRANSIENT 취급 — 상태를 그대로 두고 다음 틱에 재시도
                log.warn("코인 주문 동기화 실패 orderId={}", order.id(), e);
            }
        }
    }

    private void syncOne(CryptoExchangePort exchange, BatchContext ctx, DcaStrategyConfig config, Order order, LocalDateTime nowKst) {
        var ref = ctx.account().brokerRef();
        String ruleKey = ruleKeyOf(order);
        if (order.status() == OrderStatus.PLANNED) {
            // 저장만 되고 SUBMITTING 선점 전에 중단된 주문 — 전송된 적이 없어 조회 없이 취소
            skipIfNotRetryable(ctx, config, order, nowKst);
            orderPort.cancelPlanned(order.id());
            return;
        }
        if (order.status() == OrderStatus.SUBMITTING) {
            var found = exchange.findOrderByClientOrderId(ref, order.ticker(), order.id().toString());
            if (found.isEmpty()) {
                // 거래소에 없음이 확인됐으므로 실패 처리 — 이력(멱등 insertIfAbsent)을 먼저 남기고 상태를 전이해 중간 실패 시 다음 틱이 무해하게 재처리
                skipIfNotRetryable(ctx, config, order, nowKst);
                orderPort.markFailed(order.id());
                return;
            }
            // 이력 먼저, 상태 전이 나중 — recordOrdered 실패 시 SUBMITTING 유지(PLACED 경로는 이력을 쓰지 않음)
            recorder.recordOrdered(ctx, order.tradeDate(), ruleKey, order, order.price());
            orderPort.markPlaced(order.id(), found.get().exchangeOrderId());
            settle(ctx, order, found.get());
            return;
        }
        // PLACED
        exchange.findOrderByExchangeOrderId(ref, order.ticker(), order.externalOrderId()).ifPresent(r -> settle(ctx, order, r));
    }

    // 규칙 실행 시간대 안(오늘·같은 시각)이면 실행기가 재시도하므로 기록 없음, 지났거나 편집으로 규칙이 사라졌으면 미주문 기록
    private void skipIfNotRetryable(BatchContext ctx, DcaStrategyConfig config, Order order, LocalDateTime nowKst) {
        String ruleKey = ruleKeyOf(order);
        Integer hour = config.rule(ruleKey).map(DcaRule::executionHour).orElse(null);
        boolean retryable = order.tradeDate().equals(nowKst.toLocalDate()) && hour != null && nowKst.getHour() == hour;
        if (!retryable) {
            recorder.recordSkipped(ctx, order.tradeDate(), ruleKey, order.direction(), DcaSkipReason.EXCHANGE_API_ERROR, "주문 접수 확인 실패", order.price());
        }
    }

    // 주문 leg "DCA_" + ruleKey → ruleKey
    private static String ruleKeyOf(Order order) {
        return order.orderLeg().substring("DCA_".length());
    }

    // 체결 수량이 있으면 원장 반영·FILLED 확정(새 증분일 때만 이벤트), 무체결 취소면 CANCELLED, 그 외(WAIT/WATCH)는 유지
    private void settle(BatchContext ctx, Order order, CryptoOrderResult r) {
        boolean closed = r.state() == CryptoOrderResult.State.DONE || r.state() == CryptoOrderResult.State.CANCEL;
        if (!closed) return;
        BigDecimal volume = r.executedVolume() == null ? BigDecimal.ZERO : r.executedVolume();
        if (volume.signum() > 0) {
            if (r.avgPrice() == null) {
                log.warn("체결 수량은 있으나 평균가 없음 — 건너뜀 orderId={}", order.id());
                return;
            }
            if (!ledgerRecorder.recordFill(ctx, order, r.netExecutedVolume(), r.avgPrice())) return; // 이미 반영된 체결 — 알림 중복 방지
            // 체결 금액은 계좌 통화 자릿수로 반올림
            Currency currency = ctx.account().brokerRef().broker().getCurrency();
            eventPublisher.publishEvent(new CryptoOrderFilledEvent(ctx.account().userId(), ctx.account().id(), ctx.strategy().id(),
                    ctx.account().nickname(), order.ticker(), order.direction(), volume, r.avgPrice(),
                    currency.round(volume.multiply(r.avgPrice())), currency));
        } else if (r.state() == CryptoOrderResult.State.CANCEL) {
            orderPort.markCancelled(order.id());
        }
    }
}
