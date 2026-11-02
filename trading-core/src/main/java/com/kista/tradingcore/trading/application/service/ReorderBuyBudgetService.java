package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderStatus;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.application.usecase.ReorderBuyBudgetQuery;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.ReorderBuyBudget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

// 트랜잭션 없음 — 포트 호출마다 자체 트랜잭션(읽기 전용 계산)
@Slf4j
@Service
@RequiredArgsConstructor
class ReorderBuyBudgetService implements ReorderBuyBudgetQuery {

    private final OrderPort orderPort;
    private final StrategyCyclePort strategyCyclePort;
    private final StrategyPort strategyPort;
    private final StrategyLedgerBudget ledgerBudget;
    private final AccountPort accountPort;
    private final CancelledOrderFillRecorder fillRecorder; // PLACED 원본의 현재 체결량 — 환급에서 뺀다

    static final int MAX_ORDERS = 50; // 일괄 조회 상한 — 일괄 재주문 폼 한 화면 분량

    @Override
    public ReorderBuyBudget query(List<UUID> orderIds, LocalDate tradeDate) {
        if (orderIds.isEmpty() || orderIds.size() > MAX_ORDERS) {
            throw new IllegalArgumentException("주문은 1~" + MAX_ORDERS + "건까지 선택할 수 있습니다.");
        }
        List<Order> sources = orderIds.stream().distinct()
                .map(id -> orderPort.findById(id)
                        .orElseThrow(() -> new NoSuchElementException("주문을 찾을 수 없습니다.")))
                .toList();
        // 같은 계좌 제한 유지 — 일괄 재주문 폼은 한 계좌 단위
        UUID accountId = sources.getFirst().accountId();
        if (sources.stream().anyMatch(o -> !o.accountId().equals(accountId))) {
            throw new IllegalArgumentException("같은 계좌의 주문만 함께 조회할 수 있습니다.");
        }
        LocalDate date = tradeDate != null ? tradeDate : sources.getFirst().tradeDate();

        // PLANNED·PLACED BUY 원본은 remaining의 저장된 BUY 차감에 이미 들어 있어 재주문 취소 시 그만큼 풀려난다
        Map<UUID, BigDecimal> sourceRefunds = new LinkedHashMap<>();
        // PLACED 원본은 부분 체결분을 뺀다 — 취소해도 체결분은 풀리지 않는다
        Map<UUID, BigDecimal> filled = filledQuantities(sources, date);
        sources.forEach(o -> sourceRefunds.put(o.id(), refundOf(o, date, filled.getOrDefault(o.id(), BigDecimal.ZERO))));

        // 사이클별 원장 한도 — live 조회 없음
        Map<UUID, ReorderBuyBudget.CycleBudget> cycles = new LinkedHashMap<>();
        sources.stream().map(Order::strategyCycleId).distinct().forEach(cycleId -> {
            StrategyCycle cycle = strategyCyclePort.findById(cycleId)
                    .orElseThrow(() -> new NoSuchElementException("전략 사이클을 찾을 수 없습니다."));
            // DCA(시드 비점유) 사이클은 상한 없음 — 키를 생략해 UI가 무제한으로 본다
            if (!ledgerBudget.applies(strategyPort.findByIdOrThrow(cycle.strategyId()).type())) return;
            cycles.put(cycleId, new ReorderBuyBudget.CycleBudget(ledgerBudget.remaining(cycle, date)));
        });
        return new ReorderBuyBudget(cycles, sourceRefunds);
    }

    // 같은 거래일 PLACED BUY 원본의 체결량 — 종목별 1회 조회, 실패 시 해당 종목은 0(전액 환급, fail-open: 조회용 미리보기라 막지 않는다)
    private Map<UUID, BigDecimal> filledQuantities(List<Order> sources, LocalDate date) {
        List<Order> placed = sources.stream()
                .filter(o -> o.direction() == OrderDirection.BUY && o.status() == OrderStatus.PLACED
                        && o.tradeDate().equals(date) && o.externalOrderId() != null)
                .toList();
        Map<UUID, BigDecimal> filled = new LinkedHashMap<>();
        if (placed.isEmpty()) return filled;
        BrokerAccountRef ref;
        try {
            ref = accountPort.findByIdOrThrow(placed.getFirst().accountId()).toBrokerRef();
        } catch (RuntimeException e) {
            // 삭제된 계좌 등 — 조회용 미리보기라 막지 않고 전액 환급으로 계산
            log.warn("재주문 환급 계좌 조회 실패 — 전액 환급으로 계산: {}", e.toString());
            return filled;
        }
        placed.stream().collect(Collectors.groupingBy(Order::ticker)).forEach((ticker, orders) -> {
            try {
                Map<String, OrderFill> fills = fillRecorder.currentFills(date, ticker, ref);
                orders.forEach(o -> {
                    OrderFill f = fills.get(o.externalOrderId());
                    if (f != null) filled.put(o.id(), f.quantity());
                });
            } catch (RuntimeException e) {
                log.warn("재주문 환급 체결 조회 실패 — 전액 환급으로 계산: {} {}: {}", ticker, date, e.toString());
            }
        });
        return filled;
    }

    // ReorderService도 재사용 — 같은 거래일 PLANNED·PLACED BUY 원본의 미체결 금액만 환급(취소해도 체결분은 풀리지 않음)
    static BigDecimal refundOf(Order source, LocalDate date, BigDecimal filledQuantity) {
        boolean cancellable = source.direction() == OrderDirection.BUY
                && (source.status() == OrderStatus.PLANNED || source.status() == OrderStatus.PLACED)
                && source.tradeDate().equals(date);
        if (!cancellable) return BigDecimal.ZERO;
        return source.price().multiply(source.quantity().subtract(filledQuantity).max(BigDecimal.ZERO));
    }
}
