package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderStatus;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.application.usecase.ReorderBuyBudgetQuery;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.ReorderBuyBudget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 트랜잭션 없음 — 포트 호출마다 자체 트랜잭션(읽기 전용 계산)
@Service
@RequiredArgsConstructor
class ReorderBuyBudgetService implements ReorderBuyBudgetQuery {

    private final OrderPort orderPort;
    private final StrategyCyclePort strategyCyclePort;
    private final StrategyLedgerBudget ledgerBudget;

    static final int MAX_ORDERS = 50; // 일괄 조회 상한 — 일괄 재주문 폼 한 화면 분량

    @Override
    public ReorderBuyBudget query(List<UUID> orderIds, LocalDate tradeDate) {
        if (orderIds.isEmpty() || orderIds.size() > MAX_ORDERS) {
            throw new IllegalArgumentException("orderIds는 1~" + MAX_ORDERS + "건이어야 합니다");
        }
        List<Order> sources = orderIds.stream().distinct()
                .map(id -> orderPort.findById(id)
                        .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다.")))
                .toList();
        // 같은 계좌 제한 유지 — 일괄 재주문 폼은 한 계좌 단위
        UUID accountId = sources.getFirst().accountId();
        if (sources.stream().anyMatch(o -> !o.accountId().equals(accountId))) {
            throw new IllegalArgumentException("같은 계좌의 주문만 함께 조회할 수 있습니다");
        }
        LocalDate date = tradeDate != null ? tradeDate : sources.getFirst().tradeDate();

        // PLANNED·PLACED BUY 원본은 remaining의 저장된 BUY 차감에 이미 들어 있어 재주문 취소 시 그만큼 풀려난다
        Map<UUID, BigDecimal> sourceRefunds = new LinkedHashMap<>();
        sources.forEach(o -> sourceRefunds.put(o.id(), refundOf(o, date)));

        // 사이클별 원장 한도 — live 조회 없음
        Map<UUID, ReorderBuyBudget.CycleBudget> cycles = new LinkedHashMap<>();
        sources.stream().map(Order::strategyCycleId).distinct().forEach(cycleId -> {
            StrategyCycle cycle = strategyCyclePort.findById(cycleId)
                    .orElseThrow(() -> new IllegalArgumentException("전략 사이클을 찾을 수 없습니다."));
            cycles.put(cycleId, new ReorderBuyBudget.CycleBudget(ledgerBudget.remaining(cycle, date)));
        });
        return new ReorderBuyBudget(cycles, sourceRefunds);
    }

    // ReorderService도 재사용 — 같은 거래일 PLANNED·PLACED BUY 원본만 환급
    static BigDecimal refundOf(Order source, LocalDate date) {
        boolean cancellable = source.direction() == OrderDirection.BUY
                && (source.status() == OrderStatus.PLANNED || source.status() == OrderStatus.PLACED)
                && source.tradeDate().equals(date);
        return cancellable ? source.price().multiply(source.quantity()) : BigDecimal.ZERO;
    }
}
