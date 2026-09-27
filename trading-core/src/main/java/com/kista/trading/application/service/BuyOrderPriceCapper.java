package com.kista.trading.application.service;
import com.kista.trading.application.service.support.TradingOrderPlanner;

import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.Order;
import com.kista.matching.domain.model.PlannedOrder;
import com.kista.matching.domain.model.InfinitePosition;
import com.kista.matching.domain.model.VrPosition;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.matching.domain.strategy.CycleOrderStrategies;
import com.kista.matching.domain.strategy.CycleOrderStrategy;
import com.kista.matching.domain.strategy.PriceCapPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.kista.sharedkernel.OrderDirection.BUY;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;

// BUY PLANNED 가격이 캡(PriceCapPolicy) 초과 시 — 전략별 CycleOrderStrategy.capBuyOrders()에 위임해 가격 캡 적용 후 재저장
// 가격 캡 재산정 공식(unitAmount/2/averagePrice, (unitAmount-averagePrice·q1)·(1+r)/referencePrice, 보정 주문 등)은 InfiniteStrategy.buildCappedBuyOrders 참고
// capIfNeeded는 브로커 호출 없이 DB만 다루므로 @Transactional 허용 (constraints.md 외부 호출 금지 규칙과 무충돌)
// — 사이클 row lock(StrategyCyclePort.lockForUpdate)으로 동시 호출을 직렬화해 read-cancel-write 경합에 의한 부분 저장을 방지한다
@Component
@RequiredArgsConstructor
@Slf4j
class BuyOrderPriceCapper {

    private final OrderPort orderPort;
    private final TradingOrderPlanner orderPlanner;
    private final CycleOrderStrategies cycleOrderStrategies;
    private final StrategyCyclePort strategyCyclePort;

    // 신규 후보의 최종 BUY를 allocator 입력 전에 계산하며 영속화는 수행하지 않는다
    // ticker: VR 전용 — VrPosition은 ticker를 보유하지 않아 별도 전달 필요
    List<PlannedOrder> prepareForAllocation(List<PlannedOrder> orders, BigDecimal currentPrice, InfinitePosition position,
                                     VrPosition vrPosition, StrategyTicker ticker,
                                     StrategyType type, LocalDate tradeDate) {
        if (currentPrice == null) return orders;
        BigDecimal cap = PriceCapPolicy.capFor(currentPrice);
        List<PlannedOrder> buyOrders = orders.stream().filter(order -> order.direction() == BUY).toList();
        if (buyOrders.stream().noneMatch(order -> order.price().compareTo(cap) > 0)) return orders;

        List<PlannedOrder> cappedBuys = cycleOrderStrategies.of(type).capBuyOrders(buyOrders, cap, position, vrPosition, ticker, tradeDate);
        return PriceCapPolicy.replaceBuysPreservingOrder(orders, cappedBuys);
    }

    // 전략별 BUY 가격 캡 진입점 단일화 — capsIndividualOrders()로 DB 행 단위 취소·재저장 방식을 결정한다.
    // capIfNeeded는 @Transactional이라 needsCapCheck() 가드는 TradingOrderExecutor.applyCap에서 미리 걸러
    // 스킵 케이스마다 빈 트랜잭션이 열리지 않도록 한다.
    @Transactional
    void capIfNeeded(StrategyType type, boolean atOpen, LocalDate date, Account account, UUID strategyCycleId,
                     BigDecimal currentPrice, InfinitePosition position, VrPosition vrPosition, StrategyTicker ticker) {
        strategyCyclePort.lockForUpdate(strategyCycleId); // 동일 사이클 동시 보정 직렬화
        List<Order> buyOrders = loadBuyOrders(strategyCycleId, date, atOpen);
        if (buyOrders.isEmpty()) return;

        BigDecimal cap = PriceCapPolicy.capFor(currentPrice);
        List<PlannedOrder> plannedBuyOrders = buyOrders.stream().map(Order::toPlanned).toList();
        if (plannedBuyOrders.stream().noneMatch(o -> o.price().compareTo(cap) > 0)) return;

        CycleOrderStrategy strategy = cycleOrderStrategies.of(type);
        List<PlannedOrder> corrected = strategy.capBuyOrders(plannedBuyOrders, cap, position, vrPosition, ticker, date);

        if (strategy.capsIndividualOrders()) {
            applyIndividualCap(account, strategyCycleId, buyOrders, plannedBuyOrders, corrected, cap);
        } else {
            applyBatchCap(account, strategyCycleId, buyOrders, plannedBuyOrders, corrected);
        }
    }

    // PRIVACY 전용 — 값이 바뀐 행만 취소·재저장(변하지 않은 행은 DB에 그대로 둔다)
    // 개별 취소는 원본·재산정 목록이 같은 개수·순서라는 전제 위에서만 안전하다(현재 capsIndividualOrders()=true는 PRIVACY 하나뿐이고,
    // PrivacyCycleOrderStrategy.capBuyOrders()는 단순 가격 치환이라 개수·순서를 바꾸지 않는다) — 위반 시 조용히 잘못된 행을 취소하는 대신 즉시 실패시킨다
    private void applyIndividualCap(Account account, UUID strategyCycleId, List<Order> buyOrders,
                                    List<PlannedOrder> plannedBuyOrders, List<PlannedOrder> corrected, BigDecimal cap) {
        if (corrected.size() != plannedBuyOrders.size()) {
            throw new IllegalStateException("개별 취소 대상 개수 불일치 — capsIndividualOrders() 구현체는 개수·순서를 바꾸면 안 됨: "
                    + "original=" + plannedBuyOrders.size() + ", corrected=" + corrected.size());
        }
        List<PlannedOrder> changed = new ArrayList<>();
        for (int i = 0; i < buyOrders.size(); i++) {
            if (!plannedBuyOrders.get(i).equals(corrected.get(i))) {
                orderPort.markCancelled(buyOrders.get(i).id());
                changed.add(corrected.get(i));
            }
        }
        if (changed.isEmpty()) return;
        log.info("[{}] BUY 가격 보정 필요 — cap={}, 개별 보정 주문: {}", account.nickname(), cap, describePlannedOrders(changed));
        orderPlanner.savePlannedOrders(changed, account, strategyCycleId);
        log.info("[{}] BUY 가격 보정 완료(개별)", account.nickname());
    }

    // INFINITE/VR 전용 — 사다리 전체를 취소하고 재산정 결과 전체를 재저장(개별 행 대응이 무의미)
    private void applyBatchCap(Account account, UUID strategyCycleId, List<Order> buyOrders,
                               List<PlannedOrder> plannedBuyOrders, List<PlannedOrder> corrected) {
        if (plannedBuyOrders.equals(corrected)) return; // 예: VR bootstrap — 캡 재산정 대상 아님
        log.info("[{}] BUY 가격 보정 필요 — 원래 주문: {}", account.nickname(), describePlannedOrders(plannedBuyOrders));
        buyOrders.forEach(o -> orderPort.markCancelled(o.id()));
        if (corrected.isEmpty()) {
            log.warn("[{}] 보정 후 BUY 주문 없음 — 매수 제외", account.nickname());
            return;
        }
        orderPlanner.savePlannedOrders(corrected, account, strategyCycleId);
        log.info("[{}] BUY 가격 보정 완료(전체) — 보정 주문: {}", account.nickname(), describePlannedOrders(corrected));
    }

    // 스코프별 PLANNED BUY 조회 — atOpenOnly=false면 사이클+거래일 전체 PLANNED(AT_CLOSE 접수 경로 기존 계약 유지),
    // true면 findAtOpenPlannedByCycleAndDate로 AT_OPEN PLANNED만 조회(개장 접수 경로가 동일 사이클의 AT_CLOSE PLANNED를 건드리지 않도록)
    private List<Order> loadBuyOrders(UUID strategyCycleId, LocalDate tradeDate, boolean atOpenOnly) {
        List<Order> planned = atOpenOnly
                ? orderPort.findAtOpenPlannedByCycleAndDate(strategyCycleId, tradeDate)
                : orderPort.findPlannedByCycleAndDate(strategyCycleId, tradeDate);
        return planned.stream().filter(o -> o.direction() == BUY).toList();
    }

    private static String describePlannedOrders(List<PlannedOrder> orders) {
        return orders.stream().map(o -> o.price() + "×" + o.quantity()).toList().toString();
    }
}
