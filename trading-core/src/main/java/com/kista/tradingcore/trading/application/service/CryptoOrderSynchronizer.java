package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderStatus;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import com.kista.tradingcore.broker.domain.model.CryptoOrderResult;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.trading.application.event.CryptoOrderFilledEvent;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// 코인 주문 동기화 — 신규 실행 전에 PLANNED(접수 결과 불명)를 identifier로 복구하고 PLACED의 체결을 확정. 거래소 확인 전 재주문 금지(중복 매수 방지)
// HTTP를 포함하므로 @Transactional 없음 — 상태 변경은 OrderPort·recorder의 개별 트랜잭션
@Slf4j
@Component
@RequiredArgsConstructor
class CryptoOrderSynchronizer {

    private final OrderPort orderPort;
    private final DcaExecutionRecorder recorder;
    private final ApplicationEventPublisher eventPublisher;

    // accountContexts: 한 계좌의 전략 컨텍스트들
    void sync(CryptoExchangePort exchange, List<BatchContext> accountContexts,
              Map<UUID, DcaConfig> configsByStrategyId, LocalDateTime nowKst) {
        if (accountContexts.isEmpty()) return;
        Map<UUID, BatchContext> byCycle = accountContexts.stream()
                .collect(Collectors.toMap(c -> c.currentCycle().id(), Function.identity(), (a, b) -> a));
        for (Order order : orderPort.findOpenByAccountId(accountContexts.get(0).account().id())) {
            BatchContext ctx = byCycle.get(order.strategyCycleId());
            DcaConfig config = ctx == null ? null : configsByStrategyId.get(ctx.strategy().id());
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

    private void syncOne(CryptoExchangePort exchange, BatchContext ctx, DcaConfig config, Order order, LocalDateTime nowKst) {
        var ref = ctx.account().brokerRef();
        if (order.status() == OrderStatus.PLANNED) {
            var found = exchange.findOrderByIdentifier(ref, order.id().toString());
            if (found.isEmpty()) {
                // 거래소에 없음이 확인됐으므로 실패 처리 — 실행 시간대 안이면 같은 시간대 재시도(Task 7), 지났으면 미주문 기록
                // 이력(멱등 insertIfAbsent)을 먼저 남기고 상태를 전이 — 중간 실패 시 PLANNED로 남아 다음 틱이 무해하게 재처리
                boolean retryable = order.tradeDate().equals(nowKst.toLocalDate()) && nowKst.getHour() == config.executionHour();
                if (!retryable) {
                    recorder.recordSkipped(ctx, order.tradeDate(), DcaSkipReason.EXCHANGE_API_ERROR, "주문 접수 확인 실패", order.price());
                }
                orderPort.markFailed(order.id());
                return;
            }
            // 이력 먼저, 상태 전이 나중 — recordOrdered 실패 시 PLANNED 유지(PLACED 경로는 이력을 쓰지 않음)
            recorder.recordOrdered(ctx, order.tradeDate(), order, order.price());
            orderPort.markPlaced(order.id(), found.get().uuid());
            settle(ctx, order, found.get());
            return;
        }
        // PLACED
        exchange.findOrderByUuid(ref, order.externalOrderId()).ifPresent(r -> settle(ctx, order, r));
    }

    // 체결 수량이 있으면 FILLED 확정(+이벤트), 무체결 취소면 CANCELLED, 그 외(WAIT/WATCH)는 유지
    private void settle(BatchContext ctx, Order order, CryptoOrderResult r) {
        boolean closed = r.state() == CryptoOrderResult.State.DONE || r.state() == CryptoOrderResult.State.CANCEL;
        if (!closed) return;
        BigDecimal volume = r.executedVolume() == null ? BigDecimal.ZERO : r.executedVolume();
        if (volume.signum() > 0) {
            if (r.avgPrice() == null) {
                log.warn("체결 수량은 있으나 평균가 없음 — 건너뜀 orderId={}", order.id());
                return;
            }
            orderPort.markFilled(order.id(), volume, r.avgPrice(), OrderStatus.FILLED);
            eventPublisher.publishEvent(new CryptoOrderFilledEvent(ctx.account().userId(), ctx.account().id(), ctx.strategy().id(),
                    ctx.account().nickname(), order.ticker(), order.direction(), volume, r.avgPrice(),
                    volume.multiply(r.avgPrice()).setScale(0, RoundingMode.HALF_UP)));
        } else if (r.state() == CryptoOrderResult.State.CANCEL) {
            orderPort.markCancelled(order.id());
        }
    }
}
