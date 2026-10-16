package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.OrderStatus;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.SubmittingOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Supplier;

// 취소 경로가 접수 중(SUBMITTING) 주문을 만나면 접수 결과가 정해질 때까지 유한 대기 — 결과를 확인한 뒤에 취소·실패를 판정한다
// 60초: KIS 호출 간격 게이트(최대 20초)+읽기 7초+401 재시도(게이트 재통과) ≈ 54초, Toss 10초×4회+백오프 ≈ 42초를 덮는다
@Component
class SubmittingOrderAwaiter {

    private final OrderPort orderPort;
    private final Duration timeout;      // 최대 대기
    private final Duration pollInterval; // 재조회 간격

    @Autowired
    SubmittingOrderAwaiter(OrderPort orderPort) {
        this(orderPort, Duration.ofSeconds(60), Duration.ofSeconds(1));
    }

    SubmittingOrderAwaiter(OrderPort orderPort, Duration timeout, Duration pollInterval) {
        this.orderPort = orderPort;
        this.timeout = timeout;
        this.pollInterval = pollInterval;
    }

    // 사이클·거래일의 접수 중 주문이 사라질 때까지 대기 — 타임아웃 시 남은 주문 반환(없으면 빈 목록)
    List<Order> awaitCycle(UUID strategyCycleId, LocalDate tradeDate) {
        return poll(() -> orderPort.findSubmittingByCycleAndDate(strategyCycleId, tradeDate).stream()
                .map(SubmittingOrder::order).toList(), List::isEmpty);
    }

    // 단건 주문이 접수 중을 벗어날 때까지 대기 — 최신 주문 반환(타임아웃이면 SUBMITTING 그대로)
    Order awaitOrder(UUID orderId) {
        return poll(() -> orderPort.findById(orderId)
                        .orElseThrow(() -> new NoSuchElementException("주문을 찾을 수 없습니다.")),
                o -> o.status() != OrderStatus.SUBMITTING);
    }

    private <T> T poll(Supplier<T> read, java.util.function.Predicate<T> settled) {
        long deadline = System.nanoTime() + timeout.toNanos();
        T current = read.get();
        while (!settled.test(current) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(pollInterval);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt(); // 종료 요청 — 현재 상태로 즉시 판정
                return current;
            }
            current = read.get();
        }
        return current;
    }
}
