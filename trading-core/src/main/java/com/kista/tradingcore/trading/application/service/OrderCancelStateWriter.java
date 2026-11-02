package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.OrderStatus;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// OrderCancelService의 브로커 취소 HTTP를 트랜잭션 밖으로 빼기 위한 DB 상태변경 전용 헬퍼
// 같은 클래스 내 @Transactional self-invocation은 프록시를 타지 않으므로 별도 빈으로 분리
// 메서드는 반드시 public — Spring 기본 proxy 모드는 non-public @Transactional을 무시(no-op)함
@Service
@RequiredArgsConstructor
class OrderCancelStateWriter {

    private final OrderPort orderPort;

    @Transactional
    public int deletePlanned(UUID strategyCycleId, LocalDate tradeDate) {
        // PLANNED 주문 삭제 — 증권사 미접수분이므로 DB만 짧은 트랜잭션으로 처리, 실제 삭제 건수 반환
        return orderPort.deletePlannedByCycleAndDate(strategyCycleId, tradeDate);
    }

    @Transactional
    public void cancelPlanned(UUID orderId) {
        // 증권사 미접수 주문 취소 — PLANNED일 때만, 아니면 OrderStateConflictException(호출자가 최신 상태로 재판정)
        orderPort.cancelPlanned(orderId);
    }

    @Transactional
    public void markCancelled(UUID orderId) {
        // 브로커 취소 성공 직후에만 호출 — 성공분만 건별 짧은 트랜잭션으로 커밋
        orderPort.markCancelled(orderId);
    }

    @Transactional
    public void recordCancelledFill(UUID orderId, BigDecimal filledQuantity, @Nullable BigDecimal filledPrice) {
        // 취소 직후 조회한 체결량 기록 — 상태는 CANCELLED 유지(마감 리포트가 같은 호출로 덮어써도 값이 같다)
        orderPort.markFilled(orderId, filledQuantity, filledPrice, OrderStatus.CANCELLED);
    }
}
