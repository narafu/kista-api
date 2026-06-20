package com.kista.application.service.strategy;

import com.kista.common.TimeZones;
import com.kista.application.service.trading.BrokerMarginRouter;
import com.kista.application.service.trading.BrokerPriceRouter;
import com.kista.common.CycleLookups;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.RegisterStrategyCommand;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.strategy.StrategyDetail;
import com.kista.domain.model.strategy.UpdateStrategyCommand;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.in.StrategyUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.LoadUserSettingsPort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.UserPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class StrategyService implements StrategyUseCase {

    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final AccountPort accountPort;
    private final UserPort userPort;
    private final BrokerPriceRouter brokerPriceRouter;           // 등록 시점 현재가(종가) 조회 — 브로커 무관
    private final BrokerMarginRouter brokerMarginRouter;         // 등록 시점 가용 시드 검증 — 브로커 무관
    private final LoadUserSettingsPort loadUserSettingsPort;     // 잔고 검증 설정 조회 (user_settings)

    @Override
    public StrategyDetail register(UUID userId, UUID accountId, RegisterStrategyCommand cmd) {
        Account account = accountPort.requireOwnedAccount(accountId, userId);

        // PRIVACY는 SOXL 강제, INFINITE는 요청값 우선 → fallback
        Strategy.CycleSeedType seedType = cmd.cycleSeedType() != null
                ? cmd.cycleSeedType()
                : Strategy.CycleSeedType.NONE;
        Strategy.Ticker resolvedTicker = cmd.type().resolveTicker(cmd.ticker(), Strategy.Ticker.SOXL);

        // 같은 계좌 내 종목 중복 방지 — 체결 귀속(KIS 종목별 합산 잔고 ↔ 전략) 일대일 보장
        if (strategyPort.existsByAccountIdAndTicker(accountId, resolvedTicker)) {
            throw new IllegalStateException("이미 해당 종목으로 등록된 전략이 있습니다: " + resolvedTicker);
        }

        // 잔고 검증 활성 시: 새 시드는 KIS 가용금액에서 기존 전략 점유 시드를 뺀 예수금 한도 내
        userPort.findByIdOrThrow(userId); // 사용자 존재 확인
        UserSettings settings = loadUserSettingsPort.loadByUserId(userId).orElse(UserSettings.defaultFor(userId));
        if (settings.balanceCheckEnabled() && cmd.initialUsdDeposit() != null) {
            BigDecimal freeCash = calcFreeCash(account, accountId);
            if (cmd.initialUsdDeposit().compareTo(freeCash) > 0) {
                throw new IllegalArgumentException(
                        "다른 전략이 사용 중인 시드를 제외한 예수금(" + freeCash + ")을 초과했습니다");
            }
        }

        int divisionCount = cmd.divisionCount() > 0 ? cmd.divisionCount() : 20;
        Strategy strategy = new Strategy(null, accountId, cmd.type(), Strategy.Status.ACTIVE, resolvedTicker, seedType, divisionCount);
        Strategy saved = strategyPort.save(strategy);

        // 첫 번째 StrategyCycle 생성 — 사용자 직접 입력 시드
        StrategyCycle cycle = strategyCyclePort.save(StrategyCycle.startFromUserInput(saved.id(), cmd.initialUsdDeposit()));

        // 초기 스냅샷 저장: 입금액 기준, 보유 없음, 종가는 등록 시점 현재가
        BigDecimal currentPrice = brokerPriceRouter.getPrice(resolvedTicker, account);
        cyclePositionPort.save(CyclePosition.startSnapshot(cycle.id(), cmd.initialUsdDeposit(), currentPrice));

        log.info("전략 등록: accountId={}, strategyId={}, type={}", accountId, saved.id(), saved.type());
        return new StrategyDetail(saved, cycle.startAmount(), false);
    }

    @Override
    public void delete(UUID strategyId, UUID requesterId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        // StrategyCycle + CyclePosition 소프트 삭제 → Strategy 삭제 순
        cyclePositionPort.deleteByStrategyId(strategyId);
        strategyCyclePort.deleteByStrategyId(strategyId);
        strategyPort.delete(strategyId);
        log.info("전략 삭제: strategyId={}, requesterId={}", strategyId, requesterId);
    }

    @Override
    public void pause(UUID strategyId, UUID requesterId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        // 중복 상태 guard — 이미 중지된 전략은 재중지 불가
        if (strategy.isPaused()) {
            throw new IllegalStateException("이미 중지된 전략입니다: " + strategyId);
        }
        accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        strategyPort.save(strategy.withStatus(Strategy.Status.PAUSED));
        log.info("전략 중지: strategyId={}", strategyId);
    }

    @Override
    public void resume(UUID strategyId, UUID requesterId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        // 중복 상태 guard — 이미 활성화된 전략은 재활성화 불가
        if (strategy.isActive()) {
            throw new IllegalStateException("이미 활성화된 전략입니다: " + strategyId);
        }
        accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        strategyPort.save(strategy.withStatus(Strategy.Status.ACTIVE));
        log.info("전략 재개: strategyId={}", strategyId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StrategyDetail> listByUserId(UUID userId) {
        return accountPort.findByUserId(userId).stream()
                .flatMap(acc -> strategyPort.findByAccountId(acc.id()).stream())
                .map(this::toDetail)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StrategyDetail> listByAccountId(UUID accountId, UUID requesterId) {
        accountPort.requireOwnedAccount(accountId, requesterId);
        return strategyPort.findByAccountId(accountId).stream()
                .map(this::toDetail)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public StrategyDetail getById(UUID strategyId, UUID requesterId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        return toDetail(strategy);
    }

    @Override
    public StrategyDetail update(UUID strategyId, UUID requesterId, UpdateStrategyCommand cmd) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        accountPort.requireOwnedAccount(strategy.accountId(), requesterId);

        Strategy.CycleSeedType seedType = cmd.cycleSeedType() != null
                ? cmd.cycleSeedType()
                : strategy.cycleSeedType();
        Strategy updated = strategy.withCycleSeedType(seedType);
        Strategy saved = strategyPort.save(updated);

        if (cmd.newSeed() != null) {
            updateSeed(strategyId, cmd.newSeed());
        }

        log.info("전략 수정: strategyId={}, cycleSeedType={}", strategyId, seedType);
        return toDetail(saved);
    }

    // 예수금 = 브로커 USD 매수가능금액 - 기존 전략들이 보유한 미투자 현금(usdDeposit) 합
    private BigDecimal calcFreeCash(Account account, UUID accountId) {
        BigDecimal kisUsdAmount = brokerMarginRouter.getUsdBuyableAmount(account);

        BigDecimal reserved = strategyPort.findByAccountId(accountId).stream()
                .map(s -> cyclePositionPort.findLatestByStrategyId(s.id(), 1).stream()
                        .findFirst()
                        .map(CyclePosition::usdDeposit)
                        .orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return kisUsdAmount.subtract(reserved);
    }

    // 시드 수정: 새 시드를 총자산 B로 교체 — usdDeposit = newSeed - M (M = avgPrice * holdings)
    private void updateSeed(UUID strategyId, BigDecimal newSeed) {
        if (newSeed.signum() <= 0) {
            throw new IllegalArgumentException("시드는 0보다 커야 합니다");
        }
        StrategyCycle cycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategyId);
        CyclePosition latest = cyclePositionPort.findLatestByStrategyId(strategyId, 1).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("포지션 이력 없음: " + strategyId));

        BigDecimal purchaseAmount = latest.holdings() == 0
                ? BigDecimal.ZERO
                : latest.avgPrice().multiply(BigDecimal.valueOf(latest.holdings()));
        // 새 시드는 이미 매수한 금액보다 작을 수 없음 (현금 음수 방지)
        if (newSeed.compareTo(purchaseAmount) < 0) {
            throw new IllegalArgumentException("시드는 이미 매수한 금액보다 적을 수 없습니다");
        }
        BigDecimal newDeposit = newSeed.subtract(purchaseAmount);

        // 당일(KST) 기존 스냅샷 소프트 삭제 후 새 스냅샷 저장 — 같은 날 중복 방지
        cyclePositionPort.softDeleteTodayByStrategyId(strategyId, LocalDate.now(TimeZones.KST));
        strategyCyclePort.updateStartAmount(cycle.id(), newSeed);
        cyclePositionPort.save(new CyclePosition(null, cycle.id(), newDeposit,
                latest.closingPrice(), latest.avgPrice(), latest.holdings(), latest.isReverseMode(), null, null));
        log.info("시드 수정: strategyId={}, newSeed={}, newDeposit={}", strategyId, newSeed, newDeposit);
    }

    // 현재 StrategyCycle의 startAmount를 묶고, 리버스모드는 cycle_position 최신 행에서 판단
    private StrategyDetail toDetail(Strategy strategy) {
        var latestCycle = strategyCyclePort.findLatestByStrategyId(strategy.id());
        BigDecimal initialUsdDeposit = latestCycle.map(StrategyCycle::startAmount).orElse(null);
        // 리버스모드 SSOT = cycle_position.is_reverse_mode (strategy_cycle 아님)
        boolean isReverseMode = cyclePositionPort.findLatestByStrategyId(strategy.id(), 1)
                .stream().findFirst().map(CyclePosition::isReverseMode).orElse(false);
        return new StrategyDetail(strategy, initialUsdDeposit, isReverseMode);
    }
}
