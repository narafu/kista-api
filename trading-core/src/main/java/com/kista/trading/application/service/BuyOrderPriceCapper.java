package com.kista.trading.application.service;
import com.kista.trading.application.service.support.TradingOrderPlanner;

import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.Order;
import com.kista.sharedkernel.OrderType;
import com.kista.matching.domain.model.PlannedOrder;
import com.kista.matching.domain.model.InfinitePosition;
import com.kista.matching.domain.model.VrPosition;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.matching.domain.strategy.CycleOrderStrategy;
import com.kista.matching.domain.strategy.InfiniteStrategy;
import com.kista.matching.domain.strategy.PriceCapPolicy;
import com.kista.matching.domain.strategy.VrStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import static com.kista.sharedkernel.OrderDirection.BUY;
import com.kista.sharedkernel.StrategyTicker;

// BUY PLANNED 가격이 캡(PriceCapPolicy) 초과 시 — InfiniteStrategy에 위임해 가격 캡 적용 후 재저장
// 가격 캡 재산정 공식(unitAmount/2/averagePrice, (unitAmount-averagePrice·q1)·(1+r)/referencePrice, 보정 주문 등)은 InfiniteStrategy.buildCappedBuyOrders 참고
// capIfNeeded는 브로커 호출 없이 DB만 다루므로 @Transactional 허용 (constraints.md 외부 호출 금지 규칙과 무충돌)
// — 사이클 row lock(StrategyCyclePort.lockForUpdate)으로 동시 호출을 직렬화해 read-cancel-write 경합에 의한 부분 저장을 방지한다
@Component
@RequiredArgsConstructor
@Slf4j
class BuyOrderPriceCapper {

    private final OrderPort orderPort;
    private final TradingOrderPlanner orderPlanner;
    private final InfiniteStrategy infiniteStrategy;
    private final VrStrategy vrStrategy;
    private final StrategyCyclePort strategyCyclePort;

    // 신규 후보의 최종 BUY를 allocator 입력 전에 계산하며 영속화는 수행하지 않는다
    // ticker: VR 전용 — VrPosition은 ticker를 보유하지 않아 별도 전달 필요 (INFINITE_POSITION/PRIVACY_SIMPLE은 무시)
    List<PlannedOrder> prepareForAllocation(List<PlannedOrder> orders, BigDecimal currentPrice, InfinitePosition position,
                                     VrPosition vrPosition, StrategyTicker ticker,
                                     CycleOrderStrategy.PriceCapMode mode, LocalDate tradeDate) {
        if (mode == null || mode == CycleOrderStrategy.PriceCapMode.NONE || currentPrice == null) return orders;

        BigDecimal cap = PriceCapPolicy.capFor(currentPrice);
        List<PlannedOrder> buyOrders = orders.stream().filter(order -> order.direction() == BUY).toList();
        if (buyOrders.stream().noneMatch(order -> order.price().compareTo(cap) > 0)) return orders;

        if (mode == CycleOrderStrategy.PriceCapMode.PRIVACY_SIMPLE) {
            return orders.stream()
                    .map(order -> order.direction() == BUY && order.price().compareTo(cap) > 0
                            ? order.withPrice(cap)
                            : order)
                    .toList();
        }
        if (mode == CycleOrderStrategy.PriceCapMode.VR_POSITION) {
            if (vrPosition == null) return orders;
            // bootstrap 주문(LOC+AT_CLOSE)은 사다리 재산정(buildCappedBuyOrders) 대상이 아니다 — 아래 isVrBootstrapShaped() 참고
            if (isVrBootstrapShaped(buyOrders)) return orders;
            List<PlannedOrder> cappedBuys = vrStrategy.buildCappedBuyOrders(vrPosition, ticker, tradeDate, cap);
            return replaceBuysPreservingOrder(orders, cappedBuys);
        }
        if (position == null) return orders;

        List<PlannedOrder> cappedBuys = infiniteStrategy.buildCappedBuyOrders(position, tradeDate, buyOrders, cap);
        return replaceBuysPreservingOrder(orders, cappedBuys);
    }

    // 재산정 BUY는 원래 BUY 슬롯을 채우고, 추가 correction BUY는 기존 상대 순서 뒤에 붙인다
    private List<PlannedOrder> replaceBuysPreservingOrder(List<PlannedOrder> orders, List<PlannedOrder> cappedBuys) {
        List<PlannedOrder> prepared = new ArrayList<>(orders.size() + cappedBuys.size());
        int cappedBuyIndex = 0;
        for (PlannedOrder order : orders) {
            if (order.direction() != BUY) {
                prepared.add(order);
            } else if (cappedBuyIndex < cappedBuys.size()) {
                prepared.add(cappedBuys.get(cappedBuyIndex++));
            }
        }
        prepared.addAll(cappedBuys.subList(cappedBuyIndex, cappedBuys.size()));
        return List.copyOf(prepared);
    }

    // 전략별 BUY 가격 캡 진입점 단일화 — 과거 capIfNeeded/capIfNeededAtOpen/capPrivacyIfNeeded/
    // capPrivacyIfNeededAtOpen/capVrIfNeeded/capVrIfNeededAtOpen 6개를 mode+atOpen 파라미터로 통합했다.
    // mode==null||NONE, INFINITE_POSITION&&position==null, VR_POSITION&&vrPosition==null 3개 skip 가드는
    // 이 메서드가 아닌 TradingOrderExecutor.applyCap에 있다 — 이 메서드는 @Transactional이라 가드를 메서드
    // 내부에 두면 skip 케이스마다 스프링 AOP 프록시가 빈 트랜잭션을 열고 커밋한다(스케쥴러 매 틱 DB 커넥션풀
    // 낭비). 호출측에서 조건을 걸러 이 메서드 자체를 아예 호출하지 않는 것만이 트랜잭션 오픈을 막는 방법이다.
    @Transactional
    void capIfNeeded(CycleOrderStrategy.PriceCapMode mode, boolean atOpen, LocalDate date, Account account, UUID strategyCycleId,
                     BigDecimal currentPrice, InfinitePosition position, VrPosition vrPosition, StrategyTicker ticker) {
        strategyCyclePort.lockForUpdate(strategyCycleId); // 동일 사이클 동시 보정 직렬화
        List<Order> buyOrders = loadBuyOrders(strategyCycleId, date, atOpen);

        if (mode == CycleOrderStrategy.PriceCapMode.PRIVACY_SIMPLE) {
            applyPrivacyCap(account, strategyCycleId, currentPrice, buyOrders);
        } else if (mode == CycleOrderStrategy.PriceCapMode.INFINITE_POSITION) {
            applyCapIfNeeded(account, strategyCycleId, buyOrders, currentPrice,
                    (orders, cap) -> infiniteStrategy.buildCappedBuyOrders(position, date, orders, cap));
        } else if (mode == CycleOrderStrategy.PriceCapMode.VR_POSITION) {
            // bootstrap(LOC+AT_CLOSE) 주문은 사다리 재산정 대상이 아니므로 skip한다 (isVrBootstrapShaped 참고)
            applyCapIfNeeded(account, strategyCycleId, buyOrders, currentPrice,
                    (orders, cap) -> vrStrategy.buildCappedBuyOrders(vrPosition, ticker, date, cap),
                    BuyOrderPriceCapper::isVrBootstrapShaped);
        }
    }

    // PRIVACY 전용: cap 초과 주문만 CANCELLED → cap 가격으로 재저장 (cap 이하 주문은 그대로 유지)
    private void applyPrivacyCap(Account account, UUID strategyCycleId, BigDecimal currentPrice, List<Order> buyOrders) {
        if (buyOrders.isEmpty()) return;

        BigDecimal cap = PriceCapPolicy.capFor(currentPrice);
        List<Order> exceeding = buyOrders.stream().filter(o -> o.price().compareTo(cap) > 0).toList();
        if (exceeding.isEmpty()) return;

        log.info("[{}] PRIVACY BUY 가격 보정 필요 — cap={}, 초과 주문: {}", account.nickname(), cap, describeOrders(exceeding));
        // cap 초과 주문만 CANCELLED 처리 → cap 가격으로 재저장 (강등 후 in-memory 가격 치환)
        exceeding.forEach(o -> orderPort.markCancelled(o.id()));
        List<PlannedOrder> corrected = exceeding.stream().map(o -> o.toPlanned().withPrice(cap)).toList();
        orderPlanner.savePlannedOrders(corrected, account, strategyCycleId);
        log.info("[{}] PRIVACY BUY 가격 보정 완료 — 보정 주문: {}", account.nickname(), describePlannedOrders(corrected));
    }

    // VR bootstrap 주문(LOC+AT_CLOSE)인지 판별 — 이 함수는 buyOrders(BUY만 필터링된 목록)만 검사한다.
    // VrStrategy.buildOrders()는 holdings=0에서 첫 포지션을 못 만든 상태(needsBootstrap)면 bootstrap
    // 주문만 단독 반환하지만, holdings>0인데 사다리 첫 유효 단조차 예산 초과인 드리프트 상태에서는
    // bootstrap BUY(LOC+AT_CLOSE)와 정상 매도 사다리(LIMIT+AT_OPEN)가 같은 배치에 섞여 반환될 수 있다.
    // 사다리 매수는 항상 LIMIT+AT_OPEN이므로, BUY 중 하나라도 LOC이면 이번 배치의 매수가 bootstrap이라는
    // 뜻이다(SELL이 섞여 있어도 무관 — 이 함수는 BUY만 본다). bootstrap 가격
    // (PriceCapPolicy.capFor(referencePrice) = referencePrice×1.05)은 사다리의 buyPrice(m) 공식과 무관한
    // 별도 산정식이라 buildCappedBuyOrders(사다리 전용)로 재계산하면 안 된다.
    private static boolean isVrBootstrapShaped(List<PlannedOrder> buyOrders) {
        return buyOrders.stream().anyMatch(o -> o.orderType() == OrderType.LOC);
    }

    // 스코프별 PLANNED BUY 조회 — atOpenOnly=false면 사이클+거래일 전체 PLANNED(AT_CLOSE 접수 경로 기존 계약 유지),
    // true면 findAtOpenPlannedByCycleAndDate로 AT_OPEN PLANNED만 조회(개장 접수 경로가 동일 사이클의 AT_CLOSE PLANNED를 건드리지 않도록)
    private List<Order> loadBuyOrders(UUID strategyCycleId, LocalDate tradeDate, boolean atOpenOnly) {
        List<Order> planned = atOpenOnly
                ? orderPort.findAtOpenPlannedByCycleAndDate(strategyCycleId, tradeDate)
                : orderPort.findPlannedByCycleAndDate(strategyCycleId, tradeDate);
        return planned.stream().filter(o -> o.direction() == BUY).toList();
    }

    // 공통 cap 적용 골격 (skip 조건 없음) — INFINITE_POSITION/PRIVACY_SIMPLE 등 기본 경로
    private void applyCapIfNeeded(Account account, UUID strategyCycleId, List<Order> buyOrders,
                                  BigDecimal currentPrice,
                                  BiFunction<List<PlannedOrder>, BigDecimal, List<PlannedOrder>> correctFn) {
        applyCapIfNeeded(account, strategyCycleId, buyOrders, currentPrice, correctFn, orders -> false);
    }

    // 공통 cap 적용 골격: skip 대상 여부 확인 → cap 초과 확인 → 기존 주문 CANCELLED → 보정 주문 저장
    // buyOrders: 호출측이 스코프(전체 PLANNED vs AT_OPEN 전용)를 결정해 미리 조회한 영속 목록
    // skipIf: 강등된 plannedBuyOrders가 이 correctFn의 재산정 대상이 아니면 true (예: VR bootstrap 주문) —
    // isVrBootstrapShaped(List<PlannedOrder>)와 타입을 맞추기 위해 toPlanned() 변환을 skip 판정보다 먼저 수행한다
    // correctFn은 커널(InfiniteStrategy/VrStrategy)에 위임하므로 강등한 List<PlannedOrder>를 받는다
    private void applyCapIfNeeded(Account account, UUID strategyCycleId, List<Order> buyOrders,
                                  BigDecimal currentPrice,
                                  BiFunction<List<PlannedOrder>, BigDecimal, List<PlannedOrder>> correctFn,
                                  Predicate<List<PlannedOrder>> skipIf) {
        if (buyOrders.isEmpty()) return;

        List<PlannedOrder> plannedBuyOrders = buyOrders.stream().map(Order::toPlanned).toList();
        if (skipIf.test(plannedBuyOrders)) {
            log.info("[{}] BUY 보정 대상 아님(예: VR bootstrap) — post-hoc 캡 제외", account.nickname());
            return;
        }

        BigDecimal cap = PriceCapPolicy.capFor(currentPrice);
        if (buyOrders.stream().noneMatch(o -> o.price().compareTo(cap) > 0)) return;

        log.info("[{}] BUY 가격 보정 필요 — cap={}, 원래 주문: {}", account.nickname(), cap, describePlannedOrders(plannedBuyOrders));

        List<PlannedOrder> newBuys = correctFn.apply(plannedBuyOrders, cap);

        // 기존 BUY PLANNED CANCELLED 처리(강등 전 원본 목록에서 id 읽음) → 보정된 BUY 재저장
        buyOrders.forEach(o -> orderPort.markCancelled(o.id()));
        if (newBuys.isEmpty()) {
            log.warn("[{}] 보정 후 BUY 주문 없음 — 매수 제외", account.nickname());
            return;
        }
        orderPlanner.savePlannedOrders(newBuys, account, strategyCycleId);
        log.info("[{}] BUY 가격 보정 완료 — 보정 주문: {}", account.nickname(), describePlannedOrders(newBuys));
    }

    // 주문 목록을 "가격×수량" 형식으로 표현 — 가격 보정 전후 로그용
    private static String describeOrders(List<Order> orders) {
        return orders.stream().map(o -> o.price() + "×" + o.quantity()).toList().toString();
    }

    private static String describePlannedOrders(List<PlannedOrder> orders) {
        return orders.stream().map(o -> o.price() + "×" + o.quantity()).toList().toString();
    }
}
