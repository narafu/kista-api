package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.application.port.output.ExecutionPort;
import com.kista.tradingcore.broker.application.service.BrokerCallGuard;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.trading.domain.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 증권사 취소 성공(markCancelled 커밋) 직후 취소분의 체결량을 기록한다 — 마감 리포트 전에도 장부·예산이 부분 체결분을 알게 한다
// 비-트랜잭션: 체결 조회 HTTP는 트랜잭션 밖, DB 쓰기만 stateWriter의 짧은 트랜잭션. 실패해도 취소 결과를 바꾸지 않는다(fail-open)
@Slf4j
@Component
@RequiredArgsConstructor
class CancelledOrderFillRecorder {

    private record GroupKey(LocalDate tradeDate, StrategyTicker ticker) {}

    private final ExecutionPort executionPort;
    private final OrderCancelStateWriter stateWriter;

    void record(List<Order> cancelled, BrokerAccountRef ref) {
        // (거래일, 종목)당 1회만 조회
        Map<GroupKey, List<Order>> groups = cancelled.stream()
                .filter(o -> o.externalOrderId() != null)
                .collect(Collectors.groupingBy(o -> new GroupKey(o.tradeDate(), o.ticker())));
        groups.forEach((key, orders) -> {
            try {
                BrokerCallGuard.tryGet("취소 주문 체결 조회 " + key.ticker() + " " + key.tradeDate(),
                        () -> executionPort.getExecutions(key.tradeDate(), key.tradeDate(), key.ticker(), ref))
                        .ifPresent(executions -> write(orders, OrderFill.byExternalOrderId(executions)));
            } catch (RuntimeException e) {
                log.warn("[accountId={}] 취소 주문 체결 기록 실패 — {} {}: {}", ref.id(), key.ticker(), key.tradeDate(), e.getMessage());
            }
        });
    }

    private void write(List<Order> orders, Map<String, OrderFill> fills) {
        for (Order order : orders) {
            try {
                OrderFill fill = fills.get(order.externalOrderId());
                // 체결 목록에 없으면 0·null(미체결) — 조회가 성공했으므로 "모름"이 아니라 "0"
                BigDecimal quantity = fill == null ? BigDecimal.ZERO : fill.quantity();
                BigDecimal avg = fill == null || fill.quantity().signum() == 0 ? null : fill.avgPrice();
                stateWriter.recordCancelledFill(order.id(), quantity, avg);
            } catch (RuntimeException e) {
                log.warn("[orderId={}] 취소 체결 기록 실패: {}", order.id(), e.getMessage());
            }
        }
    }
}
