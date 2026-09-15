package com.kista.trading.application.service;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.*;
import com.kista.matching.domain.model.*;
import com.kista.trading.application.usecase.StrategyUseCase;
import com.kista.trading.application.port.output.*;
import com.kista.trading.application.usecase.VrStrategyDetailUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyCycleSeedType;
import com.kista.sharedkernel.StrategyDefaults;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class StrategyService implements StrategyUseCase {

    private final StrategyPort strategyPort;
    private final StrategyVersionPort strategyVersionPort;
    private final StrategyInfiniteDetailPort strategyInfiniteDetailPort;
    private final VrStrategyDetailUseCase vrStrategyLifecycle;      // VR 전략 전용 상세 저장·조회
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final CyclePositionInfiniteDetailPort cyclePositionInfiniteDetailPort;
    private final AccountPort accountPort;
    private final StrategyCreationService creationService;         // 신규 전략 등록 전용
    private final StrategyHistoryQueryService historyQueryService; // 시드 미리보기 + 조회 2종

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public StrategyDetail register(UUID userId, UUID accountId, RegisterStrategyCommand cmd) {
        return creationService.register(userId, accountId, cmd);
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
        strategyPort.save(strategy.withStatus(StrategyStatus.PAUSED));
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
        strategyPort.save(strategy.withStatus(StrategyStatus.ACTIVE));
        log.info("전략 재개: strategyId={}", strategyId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StrategyDetail> listByUserId(UUID userId) {
        List<UUID> accountIds = accountPort.findByUserId(userId).stream().map(Account::id).toList();
        Map<UUID, List<Strategy>> strategiesByAccount = strategyPort.findByAccountIds(accountIds);
        List<Strategy> strategies = accountIds.stream()
                .flatMap(id -> strategiesByAccount.getOrDefault(id, List.of()).stream())
                .toList();
        return toDetails(strategies);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StrategyDetail> listByAccountId(UUID accountId, UUID requesterId) {
        accountPort.requireOwnedAccount(accountId, requesterId);
        return toDetails(strategyPort.findByAccountId(accountId));
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
        if (strategy.isVr() && cmd.newSeed() != null) {
            throw new IllegalArgumentException("VR 전략의 시드/시작금액은 일반 수정으로 변경할 수 없습니다. VR 재설정을 사용하세요");
        }

        StrategyCycleSeedType seedType = cmd.cycleSeedType() != null
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

    // 시드 수정: holdings=0 시작점에서만 허용 — strategy_cycle + 최신 cycle_position 함께 보정
    private void updateSeed(UUID strategyId, BigDecimal newSeed) {
        if (newSeed.signum() <= 0) {
            throw new IllegalArgumentException("시드는 0보다 커야 합니다");
        }
        StrategyCycle cycle = strategyCyclePort.requireLatestByStrategyId(strategyId);
        CyclePosition latest = cyclePositionPort.findLatestOneByStrategyId(strategyId)
                .orElseThrow(() -> new IllegalStateException("포지션 이력 없음: " + strategyId));

        if (latest.holdings() != 0) {
            throw new IllegalArgumentException("보유 수량이 있는 사이클은 시드를 수정할 수 없습니다");
        }

        strategyCyclePort.updateStartAmount(cycle.id(), newSeed);
        cyclePositionPort.updateCycleStartSnapshot(strategyId, newSeed);
        log.info("시드 수정: strategyId={}, newSeed={}, holdings={}", strategyId, newSeed, latest.holdings());
    }

    // 최신 사이클 개장금액을 조립하고, VR pool은 개장 포지션, 리버스모드는 최신 포지션에서 판단한다. (단건 경로 — 단건 port 호출로 입력 구성 후 assemble 위임)
    private StrategyDetail toDetail(Strategy strategy) {
        var latestCycle = strategyCyclePort.findLatestByStrategyId(strategy.id());
        Optional<CyclePosition> openingPosition = strategy.isVr()
                ? latestCycle.map(cycle -> cyclePositionPort.findFirstOne(cycle.id())
                        .orElseThrow(() -> new IllegalStateException(
                                "VR 시작 포지션 없음: cycleId=" + cycle.id())))
                : Optional.empty();

        Integer divisionCount = strategy.isInfinite()
                ? strategyInfiniteDetailPort.findActiveByStrategyId(strategy.id())
                        .map(StrategyInfiniteDetail::divisionCount)
                        .orElse(StrategyDefaults.DEFAULT_DIVISION_COUNT)
                : null;

        Optional<CyclePosition> latestPos = cyclePositionPort.findLatestOneByStrategyId(strategy.id());

        boolean isReverseMode = latestPos
                .flatMap(pos -> cyclePositionInfiniteDetailPort.findByCyclePositionId(pos.id()))
                .map(CyclePositionInfiniteDetail::isReverseMode)
                .orElse(false);

        // VR 전략: 최신 활성 버전 + 최신 사이클 상세를 helper가 합산 — openingPosition/latestPos는 위에서 이미 조회한 값 재사용
        VrSummary vrSummary = strategy.isVr()
                ? vrStrategyLifecycle.findSummary(strategy.id(), latestCycle, openingPosition, latestPos).orElse(null)
                : null;

        return assemble(strategy, latestCycle, openingPosition, latestPos, divisionCount, isReverseMode, vrSummary);
    }

    // 목록 경로 — 배치 메서드로 Map을 구성한 뒤 전략별 assemble 호출 (전략 수와 무관한 상수 쿼리 수)
    private List<StrategyDetail> toDetails(List<Strategy> strategies) {
        if (strategies.isEmpty()) return List.of();
        List<UUID> strategyIds = strategies.stream().map(Strategy::id).toList();

        Map<UUID, StrategyCycle> latestCycles = strategyCyclePort.findLatestByStrategyIds(strategyIds);
        List<UUID> cycleIds = latestCycles.values().stream().map(StrategyCycle::id).toList();

        // VR만 개장 포지션 필요 — 해당 전략들의 최신 사이클 ID로만 조회
        List<UUID> vrCycleIds = strategies.stream()
                .filter(Strategy::isVr)
                .map(s -> latestCycles.get(s.id()))
                .filter(Objects::nonNull)
                .map(StrategyCycle::id)
                .toList();
        Map<UUID, CyclePosition> openingPositions = cyclePositionPort.findFirstByCycleIds(vrCycleIds);
        Map<UUID, CyclePosition> latestPositions = cyclePositionPort.findLatestByCycleIds(cycleIds);

        Map<UUID, StrategyVersion> activeVersions = strategyVersionPort.findActiveByStrategyIds(strategyIds);
        List<UUID> versionIds = activeVersions.values().stream().map(StrategyVersion::id).toList();
        Map<UUID, StrategyInfiniteDetail> infiniteDetails = strategyInfiniteDetailPort.findByStrategyVersionIds(versionIds);
        Map<UUID, StrategyVrDetail> vrDetails = vrStrategyLifecycle.findVrDetailsByVersionIds(versionIds);
        Map<UUID, StrategyCycleVrDetail> cycleVrDetails = vrStrategyLifecycle.findCycleVrDetailsByCycleIds(cycleIds);

        List<UUID> positionIds = latestPositions.values().stream().map(CyclePosition::id).toList();
        Map<UUID, CyclePositionInfiniteDetail> infinitePositionDetails =
                cyclePositionInfiniteDetailPort.findByCyclePositionIds(positionIds);

        return strategies.stream().map(strategy -> {
            Optional<StrategyCycle> latestCycle = Optional.ofNullable(latestCycles.get(strategy.id()));
            Optional<CyclePosition> openingPosition = strategy.isVr()
                    ? latestCycle.map(cycle -> Optional.ofNullable(openingPositions.get(cycle.id()))
                            .orElseThrow(() -> new IllegalStateException(
                                    "VR 시작 포지션 없음: cycleId=" + cycle.id())))
                    : Optional.empty();
            Optional<StrategyVersion> activeVersion = Optional.ofNullable(activeVersions.get(strategy.id()));

            Integer divisionCount = strategy.isInfinite()
                    ? activeVersion.map(StrategyVersion::id)
                            .flatMap(versionId -> Optional.ofNullable(infiniteDetails.get(versionId)))
                            .map(StrategyInfiniteDetail::divisionCount)
                            .orElse(StrategyDefaults.DEFAULT_DIVISION_COUNT)
                    : null;

            Optional<CyclePosition> latestPos = latestCycle.flatMap(cycle -> Optional.ofNullable(latestPositions.get(cycle.id())));

            boolean isReverseMode = latestPos
                    .flatMap(pos -> Optional.ofNullable(infinitePositionDetails.get(pos.id())))
                    .map(CyclePositionInfiniteDetail::isReverseMode)
                    .orElse(false);

            VrSummary vrSummary = strategy.isVr()
                    ? activeVersion.map(StrategyVersion::id)
                            .flatMap(versionId -> Optional.ofNullable(vrDetails.get(versionId)))
                            .flatMap(vrDetail -> latestCycle.flatMap(cycle -> Optional.ofNullable(cycleVrDetails.get(cycle.id())))
                                    .map(cycleVr -> vrStrategyLifecycle.buildSummary(
                                            vrDetail, cycleVr, openingPosition.map(CyclePosition::usdDeposit).orElse(null),
                                            latestPos.map(CyclePosition::usdDeposit).orElse(null))))
                            .orElse(null)
                    : null;

            return assemble(strategy, latestCycle, openingPosition, latestPos, divisionCount, isReverseMode, vrSummary);
        }).toList();
    }

    // 이미 조회된 입력들을 조합만 하는 순수 조립 메서드 — toDetail/toDetails 공용
    private StrategyDetail assemble(Strategy strategy, Optional<StrategyCycle> latestCycle,
                                     Optional<CyclePosition> openingPosition, Optional<CyclePosition> latestPosition,
                                     Integer divisionCount, boolean isReverseMode, VrSummary vrSummary) {
        BigDecimal initialUsdDeposit = strategy.isVr()
                ? openingPosition.map(CyclePosition::usdDeposit).orElse(null)
                : latestCycle.map(StrategyCycle::startAmount).orElse(null);
        LocalDate startDate = latestCycle.map(StrategyCycle::startDate).orElse(null);

        // VR은 currentRound 없음 — INFINITE만 계산
        Double currentRound = strategy.isVr() ? null :
                latestPosition.map(pos -> InfinitePosition.calcCurrentRound(
                        pos.avgPrice(), pos.holdings(), pos.usdDeposit(),
                        divisionCount == null ? 0 : divisionCount)).orElse(null);

        Integer currentHoldings = latestPosition.map(CyclePosition::holdings).orElse(null);

        return new StrategyDetail(strategy, initialUsdDeposit, startDate, divisionCount, isReverseMode, currentRound, currentHoldings, vrSummary);
    }

    // ── 조회 전용 (stats에서 이관 — 전략 소유 read) ─────────────────────────────

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public StrategySeedPreview strategySeedPreview(
            UUID accountId, UUID requesterId,
            StrategyType type, StrategyTicker ticker, int divisionCount) {
        return historyQueryService.strategySeedPreview(accountId, requesterId, type, ticker, divisionCount);
    }

    @Override
    @Transactional(readOnly = true)
    public CycleHistoryPage getByStrategy(UUID strategyId, UUID requesterId,
                                          LocalDate from, LocalDate to,
                                          Instant cursor, int size) {
        return historyQueryService.getByStrategy(strategyId, requesterId, from, to, cursor, size);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> getOrdersByStrategy(UUID strategyId, UUID requesterId, LocalDate from, LocalDate to) {
        return historyQueryService.getOrdersByStrategy(strategyId, requesterId, from, to);
    }

}
