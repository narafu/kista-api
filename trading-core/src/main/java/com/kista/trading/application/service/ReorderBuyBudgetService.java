package com.kista.trading.application.service;

import com.kista.account.application.port.output.AccountPort;
import com.kista.broker.application.port.output.LiveBalancePort;
import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.OrderStatus;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.application.usecase.ReorderBuyBudgetQuery;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.ReorderBuyBudget;
import com.kista.trading.domain.model.TradingAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// 트랜잭션 없음 — live 조회(외부 HTTP)가 트랜잭션 안에 들어가지 않도록 포트 호출마다 자체 트랜잭션
@Slf4j
@Service
@RequiredArgsConstructor
class ReorderBuyBudgetService implements ReorderBuyBudgetQuery {

    private final OrderPort orderPort;
    private final AccountPort accountPort;
    private final LiveBalancePort liveBalancePort;

    @Override
    public ReorderBuyBudget query(UUID orderId, LocalDate tradeDate) {
        Order source = orderPort.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + orderId));
        TradingAccount account = TradingAccount.from(accountPort.findByIdOrThrow(source.accountId()));
        LocalDate date = tradeDate != null ? tradeDate : source.tradeDate();

        // 예약 합계를 live보다 먼저 읽는다 — 동시 접수분이 이중 차감(보수적)될 뿐 과대 계상되지 않음 (workflow.md 접수 직전 재캡)
        BigDecimal plannedBuy = orderPort.sumPlannedBuyByAccountAndDate(account.id(), date);

        // PLANNED는 합계에 포함, PLACED는 live에서 이미 차감 — 재주문이 원본을 취소하면 둘 다 풀려난다
        BigDecimal sourceRefund = refundOf(source, date);

        BigDecimal live = null;
        BigDecimal remaining = null;
        try {
            live = liveBalancePort.getLiveBalance(account.brokerRef(), source.ticker()).usdDeposit();
            remaining = live.subtract(plannedBuy).add(sourceRefund);
        } catch (RuntimeException e) {
            log.warn("재주문 예산 live 조회 실패 — orderId={}, error={}", orderId, e.getMessage());
        }
        return new ReorderBuyBudget(plannedBuy, sourceRefund, live, remaining);
    }

    private static BigDecimal refundOf(Order source, LocalDate date) {
        boolean cancellable = source.direction() == OrderDirection.BUY
                && (source.status() == OrderStatus.PLANNED || source.status() == OrderStatus.PLACED)
                && source.tradeDate().equals(date);
        return cancellable ? source.price().multiply(BigDecimal.valueOf(source.quantity())) : BigDecimal.ZERO;
    }
}
