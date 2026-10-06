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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 트랜잭션 없음 — live 조회(외부 HTTP)가 트랜잭션 안에 들어가지 않도록 포트 호출마다 자체 트랜잭션
@Slf4j
@Service
@RequiredArgsConstructor
class ReorderBuyBudgetService implements ReorderBuyBudgetQuery {

    private final OrderPort orderPort;
    private final AccountPort accountPort;
    private final LiveBalancePort liveBalancePort;

    static final int MAX_ORDERS = 50; // 일괄 조회 상한 — 일괄 재주문 폼 한 화면 분량

    @Override
    public ReorderBuyBudget query(List<UUID> orderIds, LocalDate tradeDate) {
        if (orderIds.isEmpty() || orderIds.size() > MAX_ORDERS) {
            throw new IllegalArgumentException("orderIds는 1~" + MAX_ORDERS + "건이어야 합니다");
        }
        List<Order> sources = orderIds.stream().distinct()
                .map(id -> orderPort.findById(id)
                        .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + id)))
                .toList();
        // 계좌 공통 값(합계·live)을 한 번만 조회하므로 원본은 모두 같은 계좌여야 한다
        UUID accountId = sources.getFirst().accountId();
        if (sources.stream().anyMatch(o -> !o.accountId().equals(accountId))) {
            throw new IllegalArgumentException("같은 계좌의 주문만 함께 조회할 수 있습니다");
        }
        TradingAccount account = TradingAccount.from(accountPort.findByIdOrThrow(accountId));
        LocalDate date = tradeDate != null ? tradeDate : sources.getFirst().tradeDate();

        // 예약 합계를 live보다 먼저 읽는다 — 동시 접수분이 이중 차감(보수적)될 뿐 과대 계상되지 않음 (workflow.md 접수 직전 재캡)
        BigDecimal plannedBuy = orderPort.sumPlannedBuyByAccountAndDate(accountId, date);

        // PLANNED는 합계에 포함, PLACED는 live에서 이미 차감 — 재주문이 원본을 취소하면 둘 다 풀려난다
        Map<UUID, BigDecimal> sourceRefunds = new LinkedHashMap<>();
        sources.forEach(o -> sourceRefunds.put(o.id(), refundOf(o, date)));

        // usdDeposit은 계좌 단위 값이라 첫 원본 ticker로 1회만 조회 (allocator fetchQuote와 동일)
        BigDecimal live = null;
        try {
            live = liveBalancePort.getLiveBalance(account.brokerRef(), sources.getFirst().ticker()).usdDeposit();
        } catch (RuntimeException e) {
            log.warn("재주문 예산 live 조회 실패 — accountId={}, error={}", accountId, e.getMessage());
        }
        return new ReorderBuyBudget(plannedBuy, live, sourceRefunds);
    }

    private static BigDecimal refundOf(Order source, LocalDate date) {
        boolean cancellable = source.direction() == OrderDirection.BUY
                && (source.status() == OrderStatus.PLANNED || source.status() == OrderStatus.PLACED)
                && source.tradeDate().equals(date);
        return cancellable ? source.price().multiply(BigDecimal.valueOf(source.quantity())) : BigDecimal.ZERO;
    }
}
