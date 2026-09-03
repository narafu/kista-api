package com.kista.trading.application.service;

import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.broker.domain.model.Direction;
import com.kista.trading.domain.model.Order;
import com.kista.broker.domain.model.OrderInstruction;
import com.kista.broker.domain.model.OrderResult;
import com.kista.broker.domain.model.OrderType;
import com.kista.trading.domain.model.InfinitePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.trading.domain.model.VrPosition;
import com.kista.trading.application.event.TradingErrorEvent;
import com.kista.trading.domain.port.out.OrderPort;
import com.kista.broker.domain.port.out.BrokerOrderCorrectionPort;
import com.kista.trading.domain.strategy.CycleOrderStrategies;
import com.kista.trading.domain.strategy.CycleOrderStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// 증권사 접수: BUY 가격 보정 → PLANNED 개별 접수 → PLACED 마킹 (접수 실패 주문은 로그 후 skip)
@Component
@RequiredArgsConstructor
@Slf4j
class TradingOrderExecutor {

    private final OrderPort orderPort;
    private final BrokerAdapterRegistry registry;
    private final BuyOrderPriceCapper buyOrderPriceCapper;
    private final ApplicationEventPublisher eventPublisher;
    private final CycleOrderStrategies cycleOrderStrategies;

    // AT_OPEN PLANNED 주문 접수 — 개장 스케쥴러 선접수 + 개장 후 수동실행 공용
    // BUY cap 보정을 AT_OPEN 스코프(capIfNeededAtOpen/capPrivacyIfNeededAtOpen/capVrIfNeededAtOpen)로 적용한 뒤
    // AT_OPEN PLANNED만 재조회해 접수한다 — 동일 사이클에 공존 가능한 AT_CLOSE PLANNED(미도래)는 건드리지 않는다
    // (findPlannedByCycleAndDate를 그대로 쓰면 접수 전 AT_CLOSE 주문까지 캡 재산정 대상이 되는 버그가 발생함)
    List<Order> placeAtOpenOrders(LocalDate tradeDate, Account account, UUID strategyCycleId,
                                  BigDecimal currentPrice, InfinitePosition position, VrPosition vrPosition, Strategy strategy) {
        if (currentPrice != null) {
            CycleOrderStrategy.PriceCapMode mode = cycleOrderStrategies.of(strategy.type()).priceCapMode();
            if (mode == CycleOrderStrategy.PriceCapMode.INFINITE_POSITION && position != null) {
                buyOrderPriceCapper.capIfNeededAtOpen(tradeDate, account, strategyCycleId, currentPrice, position);
            } else if (mode == CycleOrderStrategy.PriceCapMode.PRIVACY_SIMPLE) {
                buyOrderPriceCapper.capPrivacyIfNeededAtOpen(tradeDate, account, strategyCycleId, currentPrice);
            } else if (mode == CycleOrderStrategy.PriceCapMode.VR_POSITION && vrPosition != null) {
                buyOrderPriceCapper.capVrIfNeededAtOpen(tradeDate, account, strategyCycleId, currentPrice, vrPosition, strategy.ticker());
            }
        }
        List<Order> atOpenOrders = orderPort.findAtOpenPlannedByCycleAndDate(strategyCycleId, tradeDate);
        if (atOpenOrders.isEmpty()) {
            log.info("[{}] 개장 선접수할 주문 없음", account.nickname());
            return List.of();
        }
        List<Order> placed = placeEach(atOpenOrders, account);
        log.info("[{}] 개장 주문 {}건 선접수 (성공/{} 시도)", account.nickname(), placed.size(), atOpenOrders.size());
        return placed;
    }

    // capIfNeeded/capPrivacyIfNeeded/capVrIfNeeded 적용 여부는 전략의 priceCapMode()로 결정
    // INFINITE_POSITION이어도 position이 null(재계산 skip 케이스)이면 캡 미적용 — 기존 동작 그대로
    // VR_POSITION이어도 vrPosition이 null(재계산 skip 케이스)이면 캡 미적용 — 동일 원칙
    List<Order> placeOrders(LocalDate today, Account account, UUID strategyCycleId,
                            BigDecimal currentPrice, InfinitePosition position, VrPosition vrPosition, Strategy strategy) {
        if (currentPrice != null) {
            CycleOrderStrategy.PriceCapMode mode = cycleOrderStrategies.of(strategy.type()).priceCapMode();
            if (mode == CycleOrderStrategy.PriceCapMode.INFINITE_POSITION && position != null) {
                buyOrderPriceCapper.capIfNeeded(today, account, strategyCycleId, currentPrice, position);
            } else if (mode == CycleOrderStrategy.PriceCapMode.PRIVACY_SIMPLE) {
                buyOrderPriceCapper.capPrivacyIfNeeded(today, account, strategyCycleId, currentPrice);
            } else if (mode == CycleOrderStrategy.PriceCapMode.VR_POSITION && vrPosition != null) {
                buyOrderPriceCapper.capVrIfNeeded(today, account, strategyCycleId, currentPrice, vrPosition, strategy.ticker());
            }
        }
        List<Order> planned = orderPort.findPlannedByCycleAndDate(strategyCycleId, today);
        List<Order> placed = placeEach(planned, account);
        log.info("[{}] 주문 {}건 접수 (성공/{} 시도)", account.nickname(), placed.size(), planned.size());
        return placed;
    }

    // 주문 목록을 개별 접수 — 실패한 주문은 로그 후 건너뜀 (다음 주문 계속 진행)
    // 계좌(앱키) 단위 호출 간격 게이트는 KisHttpClient.executeWithRetry가 전담(경로: place() → KisOrderApi → KisHttpClient.post()) — 여기서 별도 페이싱 불필요
    private List<Order> placeEach(List<Order> orders, Account account) {
        List<Order> placed = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            // 주문 간격이 이미 충분히 벌어져(정상적인 왕복 지연) 게이트가 대기 없이 즉시 반환하는 경우
            // catch 블록의 인터럽트 체크만으로는 놓친다 — 매 주문 시도 전에 별도로 확인한다
            if (Thread.currentThread().isInterrupted()) {
                log.warn("[{}] 인터럽트 감지 — 남은 주문 {}건 접수 중단", account.nickname(), orders.size() - i);
                break;
            }
            Order p = orders.get(i);
            OrderInstruction instruction = new OrderInstruction(p.ticker(), toDirection(p.direction()),
                    toOrderType(p.orderType()), p.quantity(), p.price());
            OrderResult result;
            try {
                result = registry.require(account, BrokerOrderCorrectionPort.class).place(instruction, account);
            } catch (Exception e) {
                // BUY 실패 시 SELL 포함 나머지 주문 계속 진행 — 잔고 부족은 브로커가 판단
                log.warn("[{}] {} {} 주문 접수 실패: {}", account.nickname(), p.direction(), p.ticker(), e.getMessage());
                eventPublisher.publishEvent(new TradingErrorEvent(null, e));
                orderPort.markFailed(p.id()); // 접수 실패 → FAILED
                // KisHttpClient의 호출 간격 게이트가 대기 중 인터럽트를 감지하면 예외로 전파한다 — 이 경우 이 주문만
                // FAILED로 남기고 나머지 주문은 다음 반복 상단의 체크에서 중단된다
                if (Thread.currentThread().isInterrupted()) {
                    log.warn("[{}] 인터럽트 감지 — 남은 주문 접수 중단", account.nickname());
                    break;
                }
                continue;
            }
            // 증권사 접수 성공 후 DB 동기화 실패 — 브로커에 주문이 남아있는 불일치 상태 (1회 재시도로 창 축소)
            try {
                markPlacedWithRetry(p.id(), result.externalOrderId());
                placed.add(p.withPlaced(result.externalOrderId()));
            } catch (Exception e) {
                log.error("[{}] {} {} 증권사 접수 완료됐으나 DB PLACED 기록 실패 — 수동 확인 필요 (externalOrderId={}): {}",
                        account.nickname(), p.direction(), p.ticker(), result.externalOrderId(), e.getMessage());
                eventPublisher.publishEvent(new TradingErrorEvent(null, new IllegalStateException(
                        "[DB 불일치] 증권사 접수 완료 후 PLACED 기록 실패 — externalOrderId=" + result.externalOrderId(), e)));
            }
        }
        return placed;
    }

    // trading Order.OrderDirection → broker Direction (값 1:1 대응, enum 이름 동일)
    private static Direction toDirection(Order.OrderDirection direction) {
        return switch (direction) {
            case BUY -> Direction.BUY;
            case SELL -> Direction.SELL;
        };
    }

    // trading Order.OrderType → broker OrderType (값 1:1 대응, enum 이름 동일)
    private static OrderType toOrderType(Order.OrderType orderType) {
        return switch (orderType) {
            case LOC -> OrderType.LOC;
            case MOC -> OrderType.MOC;
            case LIMIT -> OrderType.LIMIT;
        };
    }

    // 일시적 DB 오류 흡수 — 1초 후 1회 재시도, 2차 실패는 호출측으로 전파
    private void markPlacedWithRetry(UUID orderId, String externalOrderId) {
        try {
            orderPort.markPlaced(orderId, externalOrderId);
        } catch (Exception first) {
            log.warn("markPlaced 1차 실패 — 1초 후 재시도: {}", first.getMessage());
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            orderPort.markPlaced(orderId, externalOrderId);
        }
    }
}
