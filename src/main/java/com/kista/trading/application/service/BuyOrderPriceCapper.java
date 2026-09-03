package com.kista.trading.application.service;

import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.InfinitePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.trading.domain.model.VrPosition;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.trading.domain.strategy.CycleOrderStrategy;
import com.kista.trading.domain.strategy.InfiniteStrategy;
import com.kista.trading.domain.strategy.PriceCapPolicy;
import com.kista.trading.domain.strategy.VrStrategy;
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

import static com.kista.trading.domain.model.Order.OrderDirection.BUY;

// BUY PLANNED 가격이 캡(PriceCapPolicy) 초과 시 — InfiniteStrategy에 위임해 가격 캡 적용 후 재저장
// 가격 캡 재산정 공식(unitAmount/2/averagePrice, (unitAmount-averagePrice·q1)·(1+r)/referencePrice, 보정 주문 등)은 InfiniteStrategy.buildCappedBuyOrders 참고
// capIfNeeded/capPrivacyIfNeeded는 브로커 호출 없이 DB만 다루므로 @Transactional 허용 (constraints.md 외부 호출 금지 규칙과 무충돌)
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
    List<Order> prepareForAllocation(List<Order> orders, BigDecimal currentPrice, InfinitePosition position,
                                     VrPosition vrPosition, Strategy.Ticker ticker,
                                     CycleOrderStrategy.PriceCapMode mode, LocalDate tradeDate) {
        if (mode == null || mode == CycleOrderStrategy.PriceCapMode.NONE || currentPrice == null) return orders;

        BigDecimal cap = PriceCapPolicy.capFor(currentPrice);
        List<Order> buyOrders = orders.stream().filter(order -> order.direction() == BUY).toList();
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
            List<Order> cappedBuys = vrStrategy.buildCappedBuyOrders(vrPosition, ticker, tradeDate, cap);
            return replaceBuysPreservingOrder(orders, cappedBuys);
        }
        if (position == null) return orders;

        List<Order> cappedBuys = infiniteStrategy.buildCappedBuyOrders(position, tradeDate, buyOrders, cap);
        return replaceBuysPreservingOrder(orders, cappedBuys);
    }

    // 재산정 BUY는 원래 BUY 슬롯을 채우고, 추가 correction BUY는 기존 상대 순서 뒤에 붙인다
    private List<Order> replaceBuysPreservingOrder(List<Order> orders, List<Order> cappedBuys) {
        List<Order> prepared = new ArrayList<>(orders.size() + cappedBuys.size());
        int cappedBuyIndex = 0;
        for (Order order : orders) {
            if (order.direction() != BUY) {
                prepared.add(order);
            } else if (cappedBuyIndex < cappedBuys.size()) {
                prepared.add(cappedBuys.get(cappedBuyIndex++));
            }
        }
        prepared.addAll(cappedBuys.subList(cappedBuyIndex, cappedBuys.size()));
        return List.copyOf(prepared);
    }

    // PRIVACY 전용: cap 초과 주문만 CANCELLED → cap 가격으로 재저장 (cap 이하 주문은 그대로 유지)
    @Transactional
    void capPrivacyIfNeeded(LocalDate today, Account account, UUID strategyCycleId, BigDecimal currentPrice) {
        strategyCyclePort.lockForUpdate(strategyCycleId); // 동일 사이클 동시 보정 직렬화
        applyPrivacyCap(account, strategyCycleId, currentPrice, loadBuyOrders(strategyCycleId, today, false));
    }

    // PRIVACY 전용 AT_OPEN 스코프: PRIVACY BUY는 항상 AT_CLOSE라 실제로는 no-op이지만
    // priceCapMode() 분기 대칭성(INFINITE_POSITION/VR_POSITION과 동일한 AT_OPEN 스코프 계약) 유지를 위해 제공한다
    @Transactional
    void capPrivacyIfNeededAtOpen(LocalDate tradeDate, Account account, UUID strategyCycleId, BigDecimal currentPrice) {
        strategyCyclePort.lockForUpdate(strategyCycleId); // 동일 사이클 동시 보정 직렬화
        applyPrivacyCap(account, strategyCycleId, currentPrice, loadBuyOrders(strategyCycleId, tradeDate, true));
    }

    private void applyPrivacyCap(Account account, UUID strategyCycleId, BigDecimal currentPrice, List<Order> buyOrders) {
        if (buyOrders.isEmpty()) return;

        BigDecimal cap = PriceCapPolicy.capFor(currentPrice);
        List<Order> exceeding = buyOrders.stream().filter(o -> o.price().compareTo(cap) > 0).toList();
        if (exceeding.isEmpty()) return;

        log.info("[{}] PRIVACY BUY 가격 보정 필요 — cap={}, 초과 주문: {}", account.nickname(), cap, describeOrders(exceeding));
        // cap 초과 주문만 CANCELLED 처리 → cap 가격으로 재저장
        exceeding.forEach(o -> orderPort.markCancelled(o.id()));
        List<Order> corrected = exceeding.stream().map(o -> o.withPrice(cap)).toList();
        orderPlanner.savePlannedOrders(corrected, account, strategyCycleId);
        log.info("[{}] PRIVACY BUY 가격 보정 완료 — 보정 주문: {}", account.nickname(), describeOrders(corrected));
    }

    // INFINITE 전용: position 기반 수량 재산정 + 보정 주문 포함
    @Transactional
    void capIfNeeded(LocalDate today, Account account, UUID strategyCycleId,
                     BigDecimal currentPrice, InfinitePosition position) {
        strategyCyclePort.lockForUpdate(strategyCycleId); // 동일 사이클 동시 보정 직렬화
        List<Order> buyOrders = loadBuyOrders(strategyCycleId, today, false);
        applyCapIfNeeded(account, strategyCycleId, buyOrders, currentPrice,
                (orders, cap) -> infiniteStrategy.buildCappedBuyOrders(position, today, orders, cap));
    }

    // INFINITE 전용 AT_OPEN 스코프: 개장 스케쥴러 선접수·개장 후 수동실행 경로에서 사용
    // findAtOpenPlannedByCycleAndDate로 AT_OPEN PLANNED만 조회해 동일 사이클의 AT_CLOSE PLANNED(미도래)를 건드리지 않는다
    // (INFINITE는 BUY가 항상 AT_CLOSE라 실제로는 no-op이지만 priceCapMode() 분기 대칭성을 위해 유지)
    @Transactional
    void capIfNeededAtOpen(LocalDate tradeDate, Account account, UUID strategyCycleId,
                          BigDecimal currentPrice, InfinitePosition position) {
        strategyCyclePort.lockForUpdate(strategyCycleId); // 동일 사이클 동시 보정 직렬화
        List<Order> buyOrders = loadBuyOrders(strategyCycleId, tradeDate, true);
        applyCapIfNeeded(account, strategyCycleId, buyOrders, currentPrice,
                (orders, cap) -> infiniteStrategy.buildCappedBuyOrders(position, tradeDate, orders, cap));
    }

    // VR 전용: vrPosition 기반 매수 사다리 전체 재산정 — 기존 buyOrders 인자는 사용하지 않는다
    // (VR 사다리는 position+cap만으로 자기완결적으로 재생성되며, poolLimit·pool 한도 내로 자연스럽게 재수렴한다)
    // bootstrap(LOC+AT_CLOSE) 주문은 사다리 재산정 대상이 아니므로 skip한다 (isVrBootstrapShaped 참고)
    @Transactional
    void capVrIfNeeded(LocalDate today, Account account, UUID strategyCycleId,
                       BigDecimal currentPrice, VrPosition vrPosition, Strategy.Ticker ticker) {
        strategyCyclePort.lockForUpdate(strategyCycleId); // 동일 사이클 동시 보정 직렬화
        List<Order> buyOrders = loadBuyOrders(strategyCycleId, today, false);
        applyCapIfNeeded(account, strategyCycleId, buyOrders, currentPrice,
                (orders, cap) -> vrStrategy.buildCappedBuyOrders(vrPosition, ticker, today, cap),
                BuyOrderPriceCapper::isVrBootstrapShaped);
    }

    // VR 전용 AT_OPEN 스코프: 사다리(LIMIT+AT_OPEN) BUY만 조회해 보정한다 — 개장 스케쥴러 선접수·개장 후 수동실행 경로 전용
    // AT_CLOSE PLANNED(bootstrap 또는 아직 접수 전인 마감 주문)는 findAtOpenPlannedByCycleAndDate 스코프 밖이라 자연히 제외된다
    @Transactional
    void capVrIfNeededAtOpen(LocalDate tradeDate, Account account, UUID strategyCycleId,
                            BigDecimal currentPrice, VrPosition vrPosition, Strategy.Ticker ticker) {
        strategyCyclePort.lockForUpdate(strategyCycleId); // 동일 사이클 동시 보정 직렬화
        List<Order> buyOrders = loadBuyOrders(strategyCycleId, tradeDate, true);
        applyCapIfNeeded(account, strategyCycleId, buyOrders, currentPrice,
                (orders, cap) -> vrStrategy.buildCappedBuyOrders(vrPosition, ticker, tradeDate, cap),
                BuyOrderPriceCapper::isVrBootstrapShaped);
    }

    // VR bootstrap 주문(LOC+AT_CLOSE)인지 판별 — 이 함수는 buyOrders(BUY만 필터링된 목록)만 검사한다.
    // VrStrategy.buildOrders()는 holdings=0에서 첫 포지션을 못 만든 상태(needsBootstrap)면 bootstrap
    // 주문만 단독 반환하지만, holdings>0인데 사다리 첫 유효 단조차 예산 초과인 드리프트 상태에서는
    // bootstrap BUY(LOC+AT_CLOSE)와 정상 매도 사다리(LIMIT+AT_OPEN)가 같은 배치에 섞여 반환될 수 있다.
    // 사다리 매수는 항상 LIMIT+AT_OPEN이므로, BUY 중 하나라도 LOC이면 이번 배치의 매수가 bootstrap이라는
    // 뜻이다(SELL이 섞여 있어도 무관 — 이 함수는 BUY만 본다). bootstrap 가격
    // (PriceCapPolicy.capFor(referencePrice) = referencePrice×1.05)은 사다리의 buyPrice(m) 공식과 무관한
    // 별도 산정식이라 buildCappedBuyOrders(사다리 전용)로 재계산하면 안 된다.
    private static boolean isVrBootstrapShaped(List<Order> buyOrders) {
        return buyOrders.stream().anyMatch(o -> o.orderType() == Order.OrderType.LOC);
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
                                  BiFunction<List<Order>, BigDecimal, List<Order>> correctFn) {
        applyCapIfNeeded(account, strategyCycleId, buyOrders, currentPrice, correctFn, orders -> false);
    }

    // 공통 cap 적용 골격: skip 대상 여부 확인 → cap 초과 확인 → 기존 주문 CANCELLED → 보정 주문 저장
    // buyOrders: 호출측이 스코프(전체 PLANNED vs AT_OPEN 전용)를 결정해 미리 조회한 목록
    // skipIf: 조회된 buyOrders가 이 correctFn의 재산정 대상이 아니면 true (예: VR bootstrap 주문)
    private void applyCapIfNeeded(Account account, UUID strategyCycleId, List<Order> buyOrders,
                                  BigDecimal currentPrice,
                                  BiFunction<List<Order>, BigDecimal, List<Order>> correctFn,
                                  Predicate<List<Order>> skipIf) {
        if (buyOrders.isEmpty()) return;
        if (skipIf.test(buyOrders)) {
            log.info("[{}] BUY 보정 대상 아님(예: VR bootstrap) — post-hoc 캡 제외", account.nickname());
            return;
        }

        BigDecimal cap = PriceCapPolicy.capFor(currentPrice);
        if (buyOrders.stream().noneMatch(o -> o.price().compareTo(cap) > 0)) return;

        log.info("[{}] BUY 가격 보정 필요 — cap={}, 원래 주문: {}", account.nickname(), cap, describeOrders(buyOrders));

        List<Order> newBuys = correctFn.apply(buyOrders, cap);

        // 기존 BUY PLANNED CANCELLED 처리 → 보정된 BUY 재저장
        buyOrders.forEach(o -> orderPort.markCancelled(o.id()));
        if (newBuys.isEmpty()) {
            log.warn("[{}] 보정 후 BUY 주문 없음 — 매수 제외", account.nickname());
            return;
        }
        orderPlanner.savePlannedOrders(newBuys, account, strategyCycleId);
        log.info("[{}] BUY 가격 보정 완료 — 보정 주문: {}", account.nickname(), describeOrders(newBuys));
    }

    // 주문 목록을 "가격×수량" 형식으로 표현 — 가격 보정 전후 로그용
    private static String describeOrders(List<Order> orders) {
        return orders.stream().map(o -> o.price() + "×" + o.quantity()).toList().toString();
    }
}
