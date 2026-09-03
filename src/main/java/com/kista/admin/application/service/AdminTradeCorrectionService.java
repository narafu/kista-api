package com.kista.admin.application.service;

import com.kista.trading.application.event.CycleEndedEvent;
import com.kista.common.CycleLookups;
import com.kista.account.domain.model.Account;
import com.kista.admin.domain.model.AdminManualTradeCorrectionCommand;
import com.kista.admin.domain.model.AdminTradeCorrectionResult;
import com.kista.broker.domain.model.Execution;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.AccountBalance;
import com.kista.trading.domain.model.CyclePosition;
import com.kista.strategyconfig.domain.model.Strategy;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.trading.domain.model.StrategyRef;
import com.kista.user.domain.model.User;
import com.kista.admin.application.usecase.AdminTradeCorrectionUseCase;
import com.kista.account.application.port.output.AccountPort;
import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.trading.application.port.output.CyclePositionPort;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.strategyconfig.application.port.output.StrategyPort;
import com.kista.user.application.port.output.UserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 관리자 수동 체결 보정 — fills 순서대로 orders/cycle_position/cycle 종료를 원자적으로 반영
@Service
@RequiredArgsConstructor
@Transactional
class AdminTradeCorrectionService implements AdminTradeCorrectionUseCase {

    private static final String AUDIT_ACTION = "TRADE_MANUAL_CORRECTION"; // 감사 로그 액션 코드

    private final UserPort userPort;
    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final OrderPort orderPort;
    private final AuditLogPort auditLogPort;
    private final ApplicationEventPublisher eventPublisher; // 사이클 종료 시 사용자 알림 — 커밋 후 이벤트로 위임

    @Override
    public AdminTradeCorrectionResult correctManualFills(UUID adminId, AdminManualTradeCorrectionCommand command) {
        AdminSelectionChain.Selection sel = AdminSelectionChain.resolveAndValidate(
                userPort, accountPort, strategyPort, command.userId(), command.accountId(), command.strategyId());
        User user = sel.user();
        Account account = sel.account();
        Strategy strategy = sel.strategy();
        StrategyCycle currentCycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategy.id());
        CyclePosition latest = cyclePositionPort.findLatestOne(currentCycle.id())
                .orElseThrow(() -> new IllegalStateException("최신 cycle_position이 없습니다: cycleId=" + currentCycle.id()));
        if (currentCycle.endDate() != null) {
            throw new IllegalStateException("이미 종료된 사이클은 수동 체결 보정을 지원하지 않습니다");
        }

        AccountBalance balance = latest.toBalance();
        Strategy updatedStrategy = strategy;
        boolean cycleEnded = false;
        List<Order> manualOrders = new ArrayList<>();

        for (int i = 0; i < command.fills().size(); i++) {
            AdminManualTradeCorrectionCommand.Fill fill = command.fills().get(i);
            boolean isLastFill = i == command.fills().size() - 1;

            // fill 1건 반영: 검증 → FILLED 주문 이력 → 잔고 재계산 → 포지션 스냅샷
            validateSellQuantity(fill, balance);
            manualOrders.add(toManualOrder(fill, account, currentCycle, strategy));
            balance = applyFillAndSnapshot(fill, strategy, balance, currentCycle);

            // 청산이 발생하면 즉시 사이클 종료 + 안전하게 PAUSED 고정
            if (balance.holdings() == 0) {
                if (!isLastFill) {
                    throw new IllegalArgumentException("청산 이후 추가 체결은 같은 요청에서 처리할 수 없습니다");
                }
                updatedStrategy = AdminCycleCloser.closeIfExhausted(strategyCyclePort, strategyPort,
                        updatedStrategy, currentCycle, balance, fill.tradeDate()).strategy();
                cycleEnded = true;
            }
        }

        if (cycleEnded) {
            eventPublisher.publishEvent(new CycleEndedEvent(user.id(), account.id(), toStrategyRef(updatedStrategy)));
        }
        orderPort.saveAll(manualOrders);
        auditLogPort.log(adminId, AUDIT_ACTION, "STRATEGY", strategy.id(),
                Map.of(
                        "userId", user.id().toString(),
                        "accountId", account.id().toString(),
                        "fills", command.fills().size(),
                        "cycleEnded", cycleEnded
                ));

        return buildResult(user, account, strategy, command, balance, updatedStrategy, cycleEnded);
    }

    // SELL 수량이 현재 holdings를 초과하는지 검증
    private static void validateSellQuantity(AdminManualTradeCorrectionCommand.Fill fill, AccountBalance balance) {
        if (fill.direction() == Order.OrderDirection.SELL && fill.quantity() > balance.holdings()) {
            throw new IllegalArgumentException("SELL quantity가 현재 holdings를 초과합니다");
        }
    }

    // 수동 체결 1건을 FILLED 주문 이력으로 변환
    private static Order toManualOrder(AdminManualTradeCorrectionCommand.Fill fill, Account account,
                                       StrategyCycle currentCycle, Strategy strategy) {
        return Order.filledManual(account.id(), currentCycle.id(), fill.tradeDate(),
                strategy.ticker(), Order.OrderTiming.AT_CLOSE, fill.direction(),
                fill.quantity(), fill.price(), fill.externalOrderId());
    }

    // 체결 반영 후 잔고 재계산 + cycle_position 스냅샷 append
    private AccountBalance applyFillAndSnapshot(AdminManualTradeCorrectionCommand.Fill fill, Strategy strategy,
                                                AccountBalance balance, StrategyCycle currentCycle) {
        Execution execution = Execution.ofManualFill(fill.tradeDate(), strategy.ticker(),
                toDirection(fill.direction()), fill.quantity(), fill.price(), fill.externalOrderId());
        AccountBalance updated = balance.applyExecutions(List.of(AccountBalance.Fill.of(execution)));
        cyclePositionPort.save(CyclePosition.tradeSnapshot(currentCycle.id(), updated, fill.price()));
        return updated;
    }

    // trading Order.OrderDirection → broker Direction (값 1:1 대응, enum 이름 동일)
    private static com.kista.broker.domain.model.Direction toDirection(Order.OrderDirection direction) {
        return switch (direction) {
            case BUY -> com.kista.broker.domain.model.Direction.BUY;
            case SELL -> com.kista.broker.domain.model.Direction.SELL;
        };
    }

    private AdminTradeCorrectionResult buildResult(User user, Account account, Strategy strategy,
                                                   AdminManualTradeCorrectionCommand command, AccountBalance balance,
                                                   Strategy updatedStrategy, boolean cycleEnded) {
        return new AdminTradeCorrectionResult(
                user.id(),
                account.id(),
                strategy.id(),
                command.fills().size(),
                balance.holdings(),
                balance.avgPrice(),
                balance.usdDeposit(),
                updatedStrategy.status(),
                cycleEnded,
                cycleEnded ? command.fills().getLast().tradeDate() : null
        );
    }

    // trading own-type 변환 — CycleEndedEvent(trading "event")는 StrategyRef만 받는다(Task 7 순환 해소)
    private static StrategyRef toStrategyRef(Strategy strategy) {
        return new StrategyRef(strategy.id(), strategy.accountId(), strategy.type(),
                strategy.status(), strategy.ticker(), strategy.cycleSeedType());
    }
}
