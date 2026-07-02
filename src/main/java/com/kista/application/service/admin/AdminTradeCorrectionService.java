package com.kista.application.service.admin;

import com.kista.common.CycleLookups;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminManualTradeCorrectionCommand;
import com.kista.domain.model.admin.AdminTradeCorrectionResult;
import com.kista.domain.model.broker.Execution;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.AdminTradeCorrectionUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.AuditLogPort;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.UserPort;
import lombok.RequiredArgsConstructor;
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

    @Override
    public AdminTradeCorrectionResult correctManualFills(UUID adminId, AdminManualTradeCorrectionCommand command) {
        User user = userPort.findByIdOrThrow(command.userId());
        Account account = accountPort.findByIdOrThrow(command.accountId());
        Strategy strategy = strategyPort.findByIdOrThrow(command.strategyId());
        StrategyCycle currentCycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategy.id());
        CyclePosition latest = cyclePositionPort.findLatestByCycleId(currentCycle.id(), 1).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("최신 cycle_position이 없습니다: cycleId=" + currentCycle.id()));

        // 선택 체인 무결성 검증 — 관리자 UI에서 user -> account -> strategy 순 선택을 강제하는 백엔드 가드
        AdminSelectionChain.validate(user, account, strategy);
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
                        updatedStrategy, currentCycle, balance, fill.tradeDateKst()).strategy();
                cycleEnded = true;
            }
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
        return Order.filledManual(account.id(), currentCycle.id(), fill.tradeDateKst(),
                strategy.ticker(), Order.OrderTiming.AT_CLOSE, fill.direction(),
                fill.quantity(), fill.price(), fill.externalOrderId());
    }

    // 체결 반영 후 잔고 재계산 + cycle_position 스냅샷 append
    private AccountBalance applyFillAndSnapshot(AdminManualTradeCorrectionCommand.Fill fill, Strategy strategy,
                                                AccountBalance balance, StrategyCycle currentCycle) {
        Execution execution = Execution.ofManualFill(fill.tradeDateKst(), strategy.ticker(),
                fill.direction(), fill.quantity(), fill.price(), fill.externalOrderId());
        AccountBalance updated = balance.applyExecutions(List.of(execution));
        cyclePositionPort.save(CyclePosition.tradeSnapshot(currentCycle.id(), updated, fill.price()));
        return updated;
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
                cycleEnded ? command.fills().getLast().tradeDateKst() : null
        );
    }
}
