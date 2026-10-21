package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.OrderStatus;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// 코인 체결 확정 → 원장(cycle_position) 반영 + 주문 FILLED 기록을 한 트랜잭션으로 — DB만 다룬다(HTTP 없음)
// 멱등: 사이클 잠금 아래 주문을 다시 읽어 이미 기록된 체결 수량과의 증분만 반영한다(재처리·부분→전량 체결 모두 안전)
@Component
@RequiredArgsConstructor
class CryptoLedgerRecorder {

    private final StrategyCyclePort strategyCyclePort;
    private final OrderPort orderPort;
    private final CyclePositionPort cyclePositionPort;

    // volume·avgPrice: 거래소가 보고한 누적 체결 수량·평균가. 반환: 새 증분을 반영했으면 true(호출부가 알림 이벤트 발행)
    @Transactional
    public boolean recordFill(BatchContext ctx, Order order, BigDecimal volume, BigDecimal avgPrice) {
        UUID cycleId = ctx.currentCycle().id();
        strategyCyclePort.lockForUpdate(cycleId); // 수동 체결 보정과 같은 잠금 — 원장 갱신 직렬화
        Order current = orderPort.findById(order.id())
                .orElseThrow(() -> new IllegalStateException("주문이 없습니다: orderId=" + order.id()));
        BigDecimal prevQty = current.filledQuantity() == null ? BigDecimal.ZERO : current.filledQuantity();
        BigDecimal delta = volume.subtract(prevQty);
        if (delta.signum() <= 0) return false;

        // 체결 금액 증분 = 누적 금액 − 이미 반영한 금액
        BigDecimal prevAmount = current.filledPrice() == null ? BigDecimal.ZERO : prevQty.multiply(current.filledPrice());
        AccountBalance.Fill fill = ExecutionFillMapper.of(current.direction(), delta, volume.multiply(avgPrice).subtract(prevAmount));
        // 최신 원장 — 행이 없으면(초기 보유 없는 첫 체결) 0 원장에서 시작. 동시각 tie는 id desc로 고정되는 배치 조회 사용
        CyclePosition latest = cyclePositionPort.findLatestByCycleIds(List.of(cycleId)).get(cycleId);
        AccountBalance base = latest == null ? new AccountBalance(BigDecimal.ZERO, null, BigDecimal.ZERO) : latest.toBalance();
        AccountBalance updated = base.applyExecutions(List.of(fill)).withUsdDeposit(BigDecimal.ZERO); // DCA는 시드 미점유
        cyclePositionPort.save(CyclePosition.tradeSnapshot(cycleId, updated, avgPrice)); // closing_price = 체결 평균가

        orderPort.markFilled(order.id(), volume, avgPrice, OrderStatus.FILLED);
        return true;
    }
}
