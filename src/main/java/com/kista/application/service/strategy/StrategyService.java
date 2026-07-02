package com.kista.application.service.strategy;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.common.CycleLookups;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.in.StrategyUseCase;
import com.kista.domain.port.out.*;
import com.kista.domain.port.out.broker.MarginPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class StrategyService implements StrategyUseCase {

    private final StrategyPort strategyPort;
    private final StrategyVersionPort strategyVersionPort;
    private final StrategyInfiniteDetailPort strategyInfiniteDetailPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final CyclePositionInfiniteDetailPort cyclePositionInfiniteDetailPort;
    private final AccountPort accountPort;
    private final UserPort userPort;
    private final BrokerAdapterRegistry registry;                // 등록 시점 가용 시드 검증 — MarginPort 경유
    private final UserSettingsPort userSettingsPort; // 잔고 검증 설정 조회 (user_settings)

    @Override
    public StrategyDetail register(UUID userId, UUID accountId, RegisterStrategyCommand cmd) {
        Account account = accountPort.requireOwnedAccount(accountId, userId);

        // PRIVACY는 SOXL 강제, INFINITE는 요청값 우선 → fallback
        Strategy.CycleSeedType seedType = cmd.cycleSeedType() != null
                ? cmd.cycleSeedType()
                : Strategy.CycleSeedType.NONE;
        Strategy.Ticker resolvedTicker = cmd.type().resolveTicker(cmd.ticker(), Strategy.Ticker.SOXL);

        // 종목 중복 + 잔고 검증
        validateUniqueTicker(accountId, resolvedTicker);
        validateBalanceIfRequired(account, accountId, userId, cmd.initialUsdDeposit());

        int divisionCount = cmd.divisionCount() > 0 ? cmd.divisionCount() : Strategy.DEFAULT_DIVISION_COUNT;

        // 전략·버전·상세 저장 (strategy → strategy_versions → strategy_infinite_details)
        var persisted = saveStrategyWithVersion(accountId, cmd.type(), resolvedTicker, seedType, divisionCount);

        // 첫 번째 사이클·포지션 저장 (strategy_cycles → cycle_positions → cycle_position_infinite_details)
        StrategyCycle cycle = saveInitialCycleAndPosition(
                persisted.strategy(), persisted.version().id(), cmd.initialUsdDeposit());

        log.info("전략 등록: accountId={}, strategyId={}, type={}", accountId, persisted.strategy().id(), persisted.strategy().type());
        return new StrategyDetail(persisted.strategy(), cycle.startAmount(), divisionCount, false, 0.0, 0);
    }

    // 같은 계좌 내 종목 중복 방지 — 종목별 합산 잔고 ↔ 전략 일대일 보장
    private void validateUniqueTicker(UUID accountId, Strategy.Ticker ticker) {
        if (strategyPort.existsByAccountIdAndTicker(accountId, ticker)) {
            throw new IllegalStateException("이미 해당 종목으로 등록된 전략이 있습니다: " + ticker);
        }
    }

    // 잔고 검증 활성 시: 새 시드는 증권사 가용금액에서 기존 전략 점유 시드를 뺀 예수금 한도 내
    private void validateBalanceIfRequired(Account account, UUID accountId, UUID userId, BigDecimal initialUsdDeposit) {
        userPort.findByIdOrThrow(userId); // 사용자 존재 확인
        UserSettings settings = userSettingsPort.loadByUserId(userId).orElse(UserSettings.defaultFor(userId));
        if (settings.balanceCheckEnabled() && initialUsdDeposit != null) {
            BigDecimal freeCash = calcFreeCash(account, accountId);
            if (initialUsdDeposit.compareTo(freeCash) > 0) {
                throw new IllegalArgumentException(
                        "다른 전략이 사용 중인 시드를 제외한 예수금(" + freeCash + ")을 초과했습니다");
            }
        }
    }

    // strategy → strategy_versions → strategy_infinite_details 순 저장
    private SavedStrategyAndVersion saveStrategyWithVersion(
            UUID accountId, Strategy.Type type, Strategy.Ticker ticker,
            Strategy.CycleSeedType seedType, int divisionCount) {
        Strategy strategy = new Strategy(null, accountId, type, Strategy.Status.ACTIVE, ticker, seedType);
        Strategy saved = strategyPort.save(strategy);
        StrategyVersion version = strategyVersionPort.save(
                new StrategyVersion(null, saved.id(), strategyVersionPort.nextVersionNo(saved.id()), null, null)
        );
        if (saved.isInfinite()) {
            strategyInfiniteDetailPort.save(new StrategyInfiniteDetail(version.id(), divisionCount));
        }
        return new SavedStrategyAndVersion(saved, version);
    }

    // strategy_cycles → cycle_positions → cycle_position_infinite_details 순 저장
    private StrategyCycle saveInitialCycleAndPosition(
            Strategy saved, UUID versionId, BigDecimal initialUsdDeposit) {
        StrategyCycle cycle = strategyCyclePort.save(StrategyCycle.start(saved.id(), versionId, initialUsdDeposit));
        // 초기 스냅샷 저장: 입금액 기준, 보유 없음 — 실제 종가는 첫 매매 후 저장
        CyclePosition initialPosition = cyclePositionPort.save(CyclePosition.initialSnapshot(cycle.id(), initialUsdDeposit));
        if (saved.isInfinite()) {
            cyclePositionInfiniteDetailPort.save(new CyclePositionInfiniteDetail(initialPosition.id(), false));
        }
        return cycle;
    }

    // 전략 저장 후 버전 ID를 함께 전달하기 위한 내부 전달 객체
    private record SavedStrategyAndVersion(Strategy strategy, StrategyVersion version) {}

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

    // 예수금 = 증권사 USD 매수가능금액 - 기존 전략들이 보유한 미투자 현금(usdDeposit) 합
    private BigDecimal calcFreeCash(Account account, UUID accountId) {
        BigDecimal kisUsdAmount = registry.require(account, MarginPort.class).getUsdBuyableAmount(account);

        BigDecimal reserved = strategyPort.findByAccountId(accountId).stream()
                .map(s -> cyclePositionPort.findLatestByStrategyId(s.id(), 1).stream()
                        .findFirst()
                        .map(CyclePosition::usdDeposit)
                        .orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return kisUsdAmount.subtract(reserved);
    }

    // 시드 수정: holdings=0 시작점에서만 허용 — strategy_cycle + 최신 cycle_position 함께 보정
    private void updateSeed(UUID strategyId, BigDecimal newSeed) {
        if (newSeed.signum() <= 0) {
            throw new IllegalArgumentException("시드는 0보다 커야 합니다");
        }
        StrategyCycle cycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategyId);
        CyclePosition latest = cyclePositionPort.findLatestByStrategyId(strategyId, 1).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("포지션 이력 없음: " + strategyId));

        if (latest.holdings() != 0) {
            throw new IllegalArgumentException("보유 수량이 있는 사이클은 시드를 수정할 수 없습니다");
        }

        strategyCyclePort.updateStartAmount(cycle.id(), newSeed);
        cyclePositionPort.updateCycleStartSnapshot(strategyId, newSeed);
        log.info("시드 수정: strategyId={}, newSeed={}, holdings={}", strategyId, newSeed, latest.holdings());
    }

    // 현재 StrategyCycle의 startAmount를 묶고, 리버스모드는 cycle_position 최신 행에서 판단
    private StrategyDetail toDetail(Strategy strategy) {
        var latestCycle = strategyCyclePort.findLatestByStrategyId(strategy.id());
        BigDecimal initialUsdDeposit = latestCycle.map(StrategyCycle::startAmount).orElse(null);
        Integer divisionCount = strategy.isInfinite()
                ? strategyVersionPort.findActiveByStrategyId(strategy.id())
                        .flatMap(version -> strategyInfiniteDetailPort.findByStrategyVersionId(version.id()))
                        .map(StrategyInfiniteDetail::divisionCount)
                        .orElse(Strategy.DEFAULT_DIVISION_COUNT)
                : null;
        Optional<CyclePosition> latestPos = cyclePositionPort.findLatestByStrategyId(strategy.id(), 1)
                .stream().findFirst();
        boolean isReverseMode = latestPos
                .flatMap(pos -> cyclePositionInfiniteDetailPort.findByCyclePositionId(pos.id()))
                .map(CyclePositionInfiniteDetail::isReverseMode)
                .orElse(false);
        Double currentRound = latestPos.map(pos -> InfinitePosition.calcCurrentRound(
                pos.avgPrice(), pos.holdings(), pos.usdDeposit(), divisionCount == null ? 0 : divisionCount)).orElse(null);
        Integer currentHoldings = latestPos.map(CyclePosition::holdings).orElse(null);
        return new StrategyDetail(strategy, initialUsdDeposit, divisionCount, isReverseMode, currentRound, currentHoldings);
    }

}
