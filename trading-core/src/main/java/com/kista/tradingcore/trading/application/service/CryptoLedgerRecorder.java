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

import com.kista.shared.kernel.OrderDirection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.NoSuchElementException;
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

    // volume·avgPrice: 거래소가 보고한 누적 순 체결 수량·평균가, quoteAmount: 누적 견적 통화 실지출(BUY, 수수료 포함)·실수령(SELL, 수수료 차감)
    // 반환: 새 증분을 반영했으면 true(호출부가 알림 이벤트 발행)
    @Transactional
    public boolean recordFill(BatchContext ctx, Order order, BigDecimal volume, BigDecimal avgPrice, BigDecimal quoteAmount) {
        UUID cycleId = ctx.currentCycle().id();
        strategyCyclePort.lockForUpdate(cycleId); // 수동 체결 보정과 같은 잠금 — 원장 갱신 직렬화
        Order current = orderPort.findById(order.id())
                .orElseThrow(() -> new NoSuchElementException("주문이 없습니다: " + order.id()));
        BigDecimal prevQty = current.filledQuantity() == null ? BigDecimal.ZERO : current.filledQuantity();
        BigDecimal delta = volume.subtract(prevQty);
        if (delta.signum() <= 0) return false;

        // 체결 금액 증분 = 누적 금액 − 이미 반영한 금액
        BigDecimal prevAmount = current.filledPrice() == null ? BigDecimal.ZERO : prevQty.multiply(current.filledPrice());
        AccountBalance.Fill fill = ExecutionFillMapper.of(current.direction(), delta, volume.multiply(avgPrice).subtract(prevAmount));
        // 최신 원장 — 행이 없으면(초기 보유 없는 첫 체결) 0 원장에서 시작. 동시각 tie는 id desc로 고정되는 배치 조회 사용
        CyclePosition latest = cyclePositionPort.findLatestByCycleIds(List.of(cycleId)).get(cycleId);
        AccountBalance base = latest == null ? AccountBalance.empty() : latest.toBalance();
        // 원장 예수금은 견적 통화 실지출·실수령으로 증감 — 순수량 × 평균가와 수수료만큼 다르다, 누적 금액을 이번 증분 비율로 배분
        // ponytail: 비율 배분 — 동기화기는 종결(DONE·CANCEL) 주문만 넘겨 누적값이 최종값 1회라 정확, 부분 체결을 여러 번 반영하면 체결가 차이만큼 어긋난다(그때는 반영한 견적 금액을 주문에 저장해 차액 반영)
        // 증분은 scale 8 중간값 — 최종 예수금은 DB numeric(20,2)가 한 번만 반올림한다(증분을 먼저 2자리로 자르면 경계에서 0.01 어긋남)
        BigDecimal cashDelta = quoteAmount.multiply(delta).divide(volume, 8, RoundingMode.HALF_UP);
        BigDecimal deposit = current.direction() == OrderDirection.BUY
                ? base.usdDeposit().subtract(cashDelta) : base.usdDeposit().add(cashDelta);
        AccountBalance updated = base.applyExecutions(List.of(fill)).withUsdDeposit(deposit);
        cyclePositionPort.save(CyclePosition.tradeSnapshot(cycleId, updated, avgPrice)); // closing_price = 체결 평균가

        orderPort.markFilled(order.id(), volume, avgPrice, OrderStatus.FILLED);
        return true;
    }
}
