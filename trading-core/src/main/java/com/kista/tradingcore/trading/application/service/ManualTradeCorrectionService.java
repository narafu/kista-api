package com.kista.tradingcore.trading.application.service;
import com.kista.tradingcore.trading.application.service.support.CycleCloser;
import com.kista.tradingcore.trading.application.service.support.SelectionChain;

import com.kista.tradingcore.trading.application.event.CycleEndedEvent;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.Quantities;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategy;
import com.kista.tradingcore.trading.domain.model.ManualTradeCorrectionCommand;
import com.kista.tradingcore.trading.domain.model.ManualTradeCorrectionResult;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderDirection;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.application.usecase.ManualTradeCorrectionUseCase;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

// 관리자 수동 체결 보정 — fills 순서대로 orders/cycle_position/cycle 종료를 원자적으로 반영
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class ManualTradeCorrectionService implements ManualTradeCorrectionUseCase {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final OrderPort orderPort;
    private final ApplicationEventPublisher eventPublisher; // 사이클 종료 시 사용자 알림 — 커밋 후 이벤트로 위임
    private static final int CRYPTO_SCALE = 8; // 코인 수량 소수 자릿수 상한 (업비트)
    private final CycleOrderStrategies cycleOrderStrategies; // 청산 시 사이클 종료 여부(endsCycleOnLiquidation) SSOT

    @Override
    public ManualTradeCorrectionResult correctManualFills(ManualTradeCorrectionCommand command) {
        SelectionChain.Selection sel = SelectionChain.resolveAndValidate(
                accountPort, strategyPort, command.accountId(), command.strategyId(), command.userId());
        Account account = sel.account();
        Strategy strategy = sel.strategy();
        StrategyCycle currentCycle = strategyCyclePort.requireLatestByStrategyId(strategy.id());
        // 사이클 행 잠금 — 동시에 들어온 같은 보정(원 요청 + 재시도)이 중복 검사를 함께 통과하지 않도록 직렬화
        strategyCyclePort.lockForUpdate(currentCycle.id());
        rejectDuplicateFills(command, currentCycle);
        CyclePosition latest = cyclePositionPort.findLatestOne(currentCycle.id())
                .orElseThrow(() -> new IllegalStateException("최신 cycle_position이 없습니다: cycleId=" + currentCycle.id()));
        if (currentCycle.endDate() != null) {
            throw new IllegalStateException("이미 종료된 사이클은 수동 체결 보정을 지원하지 않습니다");
        }

        AccountBalance balance = latest.toBalance();
        CycleOrderStrategy cycleStrategy = cycleOrderStrategies.of(strategy.type());
        boolean endsCycleOnLiquidation = cycleStrategy.endsCycleOnLiquidation();
        boolean occupiesSeed = cycleStrategy.occupiesSeed(); // 시드 비점유 전략(DCA)은 보정 후에도 usd_deposit 0
        Strategy updatedStrategy = strategy;
        boolean cycleEnded = false;
        List<Order> manualOrders = new ArrayList<>();

        for (int i = 0; i < command.fills().size(); i++) {
            ManualTradeCorrectionCommand.Fill fill = command.fills().get(i);
            boolean isLastFill = i == command.fills().size() - 1;

            // fill 1건 반영: 검증 → FILLED 주문 이력 → 잔고 재계산 → 포지션 스냅샷
            validateQuantity(fill, strategy);
            validateSellQuantity(fill, balance);
            manualOrders.add(toManualOrder(fill, account, currentCycle, strategy));
            balance = applyFillAndSnapshot(fill, balance, currentCycle, occupiesSeed);

            // 청산이 발생하면 즉시 사이클 종료 + 안전하게 PAUSED 고정 — VR처럼 청산에도 사이클을 유지하는 전략은 제외
            // (VR 사이클을 닫으면 재개가 재오픈하지 않아 좀비 사이클로 영구 정지된다)
            if (!balance.hasHoldings() && endsCycleOnLiquidation) {
                if (!isLastFill) {
                    throw new IllegalArgumentException("청산 이후 추가 체결은 같은 요청에서 처리할 수 없습니다");
                }
                updatedStrategy = CycleCloser.closeIfExhausted(strategyCyclePort, strategyPort,
                        updatedStrategy, currentCycle, balance, fill.tradeDate()).strategy();
                cycleEnded = true;
            }
        }

        if (cycleEnded) {
            eventPublisher.publishEvent(new CycleEndedEvent(command.userId(), account.id(), account.nickname(),
                    updatedStrategy.type(), updatedStrategy.ticker(), updatedStrategy.cycleSeedType()));
        }
        orderPort.saveAll(manualOrders);

        // trading 쪽 독립 감사 기록 — admin 프록시(AdminTradeCorrectionService.auditLogPort)와 별개로,
        // 내부 엔드포인트를 직접 호출한 경우에도 trading 로그에는 반드시 남긴다
        log.info("수동 체결 보정 처리: userId={}, accountId={}, strategyId={}, fillCount={}, holdings={}, cycleEnded={}",
                command.userId(), account.id(), strategy.id(), command.fills().size(), balance.holdings(), cycleEnded);

        return buildResult(account, strategy, command, balance, updatedStrategy, cycleEnded);
    }

    // 같은 체결 재반영 방지 — 타임아웃 후 재요청 등으로 cycle_position·orders가 이중 반영되지 않게 한다
    // 증권사 주문번호는 일자별 채번이라 (거래일, 주문번호)로 판정, externalOrderId가 없는 fill은 식별 수단이 없어 검사하지 않는다
    private void rejectDuplicateFills(ManualTradeCorrectionCommand command, StrategyCycle currentCycle) {
        List<ManualTradeCorrectionCommand.Fill> identified = command.fills().stream()
                .filter(f -> f.externalOrderId() != null && !f.externalOrderId().isBlank())
                .toList();
        long distinctKeys = identified.stream().map(f -> f.tradeDate() + "|" + f.externalOrderId()).distinct().count();
        if (distinctKeys != identified.size()) {
            throw new IllegalArgumentException("같은 요청에 중복된 증권사 주문번호가 있습니다");
        }
        if (identified.stream().anyMatch(f -> orderPort.existsByStrategyCycleIdAndTradeDateAndExternalOrderId(
                currentCycle.id(), f.tradeDate(), f.externalOrderId()))) {
            throw new IllegalArgumentException("이미 반영된 증권사 주문번호가 포함돼 있습니다");
        }
    }

    // 수량 단위 검증 — 주식은 정수, 코인은 소수 8자리까지 (원장 normalize 기준)
    private static void validateQuantity(ManualTradeCorrectionCommand.Fill fill, Strategy strategy) {
        boolean crypto = strategy.ticker().getMarket() == Market.CRYPTO_KRW;
        int scale = Quantities.normalize(fill.quantity()).scale();
        if (crypto ? scale > CRYPTO_SCALE : scale > 0) {
            throw new IllegalArgumentException(crypto ? "코인 체결 수량은 소수 8자리까지 입력할 수 있습니다"
                    : "주식 체결 수량은 정수여야 합니다");
        }
    }

    // SELL 수량이 현재 holdings를 초과하는지 검증
    private static void validateSellQuantity(ManualTradeCorrectionCommand.Fill fill, AccountBalance balance) {
        if (fill.direction() == OrderDirection.SELL && fill.quantity().compareTo(balance.holdings()) > 0) {
            throw new IllegalArgumentException("SELL quantity가 현재 holdings를 초과합니다");
        }
    }

    // 수동 체결 1건을 FILLED 주문 이력으로 변환
    private static Order toManualOrder(ManualTradeCorrectionCommand.Fill fill, Account account,
                                       StrategyCycle currentCycle, Strategy strategy) {
        return Order.filledManual(account.id(), currentCycle.id(), fill.tradeDate(),
                strategy.ticker(), OrderTiming.AT_CLOSE, fill.direction(),
                fill.quantity(), fill.price(), fill.externalOrderId());
    }

    // 체결 반영 후 잔고 재계산 + cycle_position 스냅샷 append
    private AccountBalance applyFillAndSnapshot(ManualTradeCorrectionCommand.Fill fill, AccountBalance balance,
                                                StrategyCycle currentCycle, boolean occupiesSeed) {
        // 소수 수량을 쓰므로 브로커 Execution(int) 대신 원장 Fill을 직접 만든다
        AccountBalance applied = balance.applyExecutions(List.of(ExecutionFillMapper.of(
                fill.direction(), fill.quantity(), fill.price().multiply(fill.quantity()))));
        AccountBalance updated = occupiesSeed ? applied : applied.withUsdDeposit(BigDecimal.ZERO);
        cyclePositionPort.save(CyclePosition.tradeSnapshot(currentCycle.id(), updated, fill.price()));
        return updated;
    }

    private ManualTradeCorrectionResult buildResult(Account account, Strategy strategy,
                                                     ManualTradeCorrectionCommand command, AccountBalance balance,
                                                     Strategy updatedStrategy, boolean cycleEnded) {
        return new ManualTradeCorrectionResult(
                command.userId(), account.id(), strategy.id(),
                command.fills().size(), balance.holdings(), balance.avgPrice(), balance.usdDeposit(),
                updatedStrategy.status(), cycleEnded,
                cycleEnded ? command.fills().getLast().tradeDate() : null);
    }
}
