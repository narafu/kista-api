package com.kista.tradingcore.trading.application.service;
import com.kista.tradingcore.trading.application.service.support.SelectionChain;

import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.OrderStateConflictException;
import com.kista.tradingcore.trading.application.event.TradingErrorEvent;
import com.kista.tradingcore.trading.domain.model.DstInfo;
import com.kista.tradingcore.trading.domain.model.ReorderCommand;
import com.kista.tradingcore.trading.domain.model.ReorderResult;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.application.usecase.ReorderUseCase;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.broker.domain.model.CancelInstruction;
import com.kista.shared.kernel.OrderDirection;
import com.kista.tradingcore.broker.domain.model.OrderInstruction;
import com.kista.tradingcore.broker.domain.model.OrderResult;
import com.kista.tradingcore.broker.application.port.output.BrokerOrderCorrectionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
// 비-트랜잭션 — 증권사 취소·접수 HTTP를 트랜잭션 밖에서 실행하고 DB 쓰기는 건별 짧은 트랜잭션(OrderCancelStateWriter·saveAll)으로 처리
class ReorderService implements ReorderUseCase {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final OrderPort orderPort;
    private final BrokerOrderCorrectionPort brokerOrderCorrectionPort;
    private final MarketCalendarPort marketCalendarPort;
    private final OrderCancelStateWriter stateWriter; // 원본 취소 DB 기록 — 짧은 트랜잭션
    private final ApplicationEventPublisher eventPublisher; // 접수 후 DB 저장 실패 관리자 알림

    @Override
    public ReorderResult reorder(ReorderCommand command) {
        return reorder(command, DstInfo.calculate(), Instant.now());
    }

    // 테스트 주입용 — DstInfo + 판정 시각 직접 지정
    ReorderResult reorder(ReorderCommand command, DstInfo dst, Instant now) {
        SelectionChain.Selection sel = SelectionChain.resolveAndValidate(
                accountPort, strategyPort, command.accountId(), command.strategyId(), command.userId());
        Account account = sel.account();
        Strategy strategy = sel.strategy();
        StrategyCycle currentCycle = strategyCyclePort.requireLatestByStrategyId(strategy.id());
        Order sourceOrder = orderPort.findById(command.orderId())
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + command.orderId()));

        SelectionChain.validate(command.userId(), account, strategy, sourceOrder);
        if (!sourceOrder.strategyCycleId().equals(currentCycle.id())) {
            throw new IllegalArgumentException("현재 전략 사이클 주문만 재주문할 수 있습니다");
        }
        // 미체결(PLANNED·PLACED) 원본만 — 체결·취소·실패한 주문 위에 새 주문을 얹으면 같은 주문이 중복으로 나간다(체결분 보정은 수동 체결 보정)
        if (sourceOrder.status() != OrderStatus.PLANNED && sourceOrder.status() != OrderStatus.PLACED) {
            throw new IllegalArgumentException("미체결(PLANNED·PLACED) 주문만 재주문할 수 있습니다");
        }

        BigDecimal price = requirePrice(command);
        int quantity = requireQuantity(command);
        OrderDirection direction = command.direction() != null ? command.direction() : sourceOrder.direction();
        LocalDate tradeDate = command.tradeDate() != null ? command.tradeDate() : sourceOrder.tradeDate();

        // 1. 주문시점 가용성 서버 측 재검증 (UI disable 우회 방지) — 증권사 취소보다 먼저: 검증 실패 시 원본이 취소된 채 남지 않도록
        // 판정 시각 기준 거래일(04:30 KST 경계) — 오늘 KST 일자를 넘기면 포트가 직전 세션을 검사한다
        if (!marketCalendarPort.isMarketOpen(DstInfo.nextTradeDate(now.atZone(TimeZones.KST)))) {
            throw new IllegalArgumentException("휴장일에는 재주문할 수 없습니다");
        }
        DstInfo.ReorderTimingAvailability avail = dst.reorderTimingAvailabilityAt(now);
        boolean timingOk = switch (command.timing()) {
            case AT_OPEN -> avail.atOpen();
            case AT_CLOSE -> avail.atClose();
            case IMMEDIATE -> avail.immediate();
        };
        if (!timingOk) {
            throw new IllegalArgumentException("현재 시장 단계에서 " + command.timing() + " 접수가 불가합니다");
        }

        // 2. 원본 상태별 취소 처리
        cancelSource(sourceOrder, account);

        // 3. 재주문 생성 — timing에 따라 PLANNED 저장 또는 즉시 증권사 접수
        Order newOrder = Order.reorder(sourceOrder, tradeDate, direction, quantity, price, command.timing());
        PlacementResult placement = placeOrSave(newOrder, account, command.timing());

        // trading 쪽 독립 감사 기록 — admin 프록시(AdminReorderService.auditLogPort)와 별개로,
        // 내부 엔드포인트를 직접 호출한 경우에도 trading 로그에는 반드시 남긴다
        log.info("재주문 처리: userId={}, accountId={}, strategyId={}, sourceOrderId={}, originalStatus={}, resultingStatus={}, externalOrderId={}",
                command.userId(), command.accountId(), command.strategyId(), sourceOrder.id(),
                sourceOrder.status(), placement.status(), placement.externalOrderId());

        return new ReorderResult(command.userId(), command.accountId(), command.strategyId(),
                sourceOrder.id(), sourceOrder.status(), placement.status(), placement.externalOrderId(),
                sourceOrder.price(), sourceOrder.quantity(), direction);
    }

    // 원본 취소 — PLANNED: DB만 CANCELLED, PLACED: 증권사 취소 + DB CANCELLED (그 밖의 상태는 앞에서 거부됨)
    private void cancelSource(Order order, Account account) {
        if (order.status() == OrderStatus.PLACED) {
            brokerOrderCorrectionPort
                    .cancel(new CancelInstruction(order.ticker(), order.externalOrderId()), account.toBrokerRef());
            stateWriter.markCancelled(order.id());
            return;
        }
        // PLANNED일 때만 전이 — 읽은 뒤 배치가 접수했으면 새 주문을 얹지 않고 중단(원본이 그대로 남아 중복 없음)
        try {
            stateWriter.cancelPlanned(order.id());
        } catch (OrderStateConflictException e) {
            throw new IllegalArgumentException("원본 주문 상태가 바뀌었습니다. 다시 조회한 뒤 시도해 주세요");
        }
    }

    // AT_OPEN/AT_CLOSE: PLANNED 저장 / IMMEDIATE: 즉시 증권사 접수 (실패 시 FAILED 기록)
    private PlacementResult placeOrSave(Order newOrder, Account account, com.kista.shared.kernel.OrderTiming timing) {
        if (timing == com.kista.shared.kernel.OrderTiming.IMMEDIATE) {
            OrderInstruction instruction = new OrderInstruction(newOrder.ticker(), newOrder.direction(),
                    newOrder.orderType(), newOrder.quantity(), newOrder.price());
            OrderResult result;
            try {
                result = brokerOrderCorrectionPort.place(instruction, account.toBrokerRef());
            } catch (Exception e) {
                log.warn("재주문 즉시 접수 실패 — FAILED 기록: error={}", e.getMessage());
                orderPort.saveAll(List.of(newOrder.withFailed()));
                return new PlacementResult(OrderStatus.FAILED, null);
            }
            Order placed = newOrder.withPlaced(result.externalOrderId());
            try {
                orderPort.saveAll(List.of(placed));
            } catch (Exception e) {
                // 증권사엔 접수됐는데 DB 저장 실패 — FAILED로 쓰면 재시도가 중복 주문을 내므로 PLACED로 응답하고 관리자에게 알린다
                log.error("재주문 증권사 접수 완료 후 DB 저장 실패 — 수동 확인 필요 (externalOrderId={}): {}",
                        placed.externalOrderId(), e.getMessage());
                eventPublisher.publishEvent(new TradingErrorEvent(null,
                        "[DB 불일치] 재주문 %s %s 증권사 접수 완료 후 DB 저장 실패 — accountId=%s, externalOrderId=%s. 수동 정정 전 재시도 금지"
                                .formatted(placed.ticker(), placed.direction(), placed.accountId(), placed.externalOrderId())));
            }
            return new PlacementResult(OrderStatus.PLACED, placed.externalOrderId());
        }
        orderPort.saveAll(List.of(newOrder));
        return new PlacementResult(OrderStatus.PLANNED, null);
    }

    private record PlacementResult(OrderStatus status, String externalOrderId) {}

    private static BigDecimal requirePrice(ReorderCommand command) {
        if (command.price() == null || command.price().signum() <= 0) {
            throw new IllegalArgumentException("price는 양수여야 합니다");
        }
        return command.price();
    }

    private static int requireQuantity(ReorderCommand command) {
        if (command.quantity() == null || command.quantity() <= 0) {
            throw new IllegalArgumentException("quantity는 양수여야 합니다");
        }
        return command.quantity();
    }
}

