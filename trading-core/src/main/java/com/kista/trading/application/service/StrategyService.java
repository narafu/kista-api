package com.kista.trading.application.service;
import com.kista.trading.application.service.support.StrategyHistoryQueryService;

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
    private final CycleSnapshotCreator cycleSnapshotCreator;       // 재개 시 종료된 사이클 재오픈 전용
    private final OrderCancelService orderCancelService;           // 일시정지·삭제 전 미체결 주문 정리
    private final StrategyStateWriter stateWriter;                 // 일시정지·삭제 DB 쓰기 (취소 HTTP를 트랜잭션 밖에 두기 위해 분리)

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public StrategyDetail register(UUID userId, UUID accountId, RegisterStrategyCommand cmd) {
        return creationService.register(userId, accountId, cmd);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 증권사 주문 취소 HTTP 포함 — DB 쓰기는 StrategyStateWriter 짧은 트랜잭션
    public void delete(UUID strategyId, UUID requesterId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        Account account = accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        // 먼저 PAUSED로 기록해 이후 배치가 이 전략으로 새 주문을 만들지 않게 한 뒤 미체결 주문을 정리한다
        // 일시 장애로 증권사 취소에 실패한 주문이 있으면 삭제하지 않는다(삭제된 전략의 주문이 증권사에 남지 않도록) — 전략은 PAUSED로 남는다
        // 자격증명 오류(키 만료·철회)는 재시도로 풀리지 않아 삭제를 막지 않는다 — 그 주문은 PLACED로 남고 관리자에게 알린다
        stateWriter.pause(strategyId);
        CancelResult cancelled = orderCancelService.cancelOpenOrders(strategyId, account);
        if (cancelled.retryableFailedCount() > 0) {
            throw new IllegalStateException("증권사 주문 취소에 실패한 주문이 있어 전략을 삭제할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
        stateWriter.delete(strategyId);
        log.info("전략 삭제: strategyId={}, requesterId={}, cancelledOrders={}", strategyId, requesterId, cancelled.cancelledCount());
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 증권사 주문 취소 HTTP 포함 — DB 쓰기는 StrategyStateWriter 짧은 트랜잭션
    public void pause(UUID strategyId, UUID requesterId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        Account account = accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        // 중복 상태 guard — 이미 중지된 전략은 재중지 불가 (소유권 검증 이후 수행)
        if (strategy.isPaused()) {
            throw new IllegalStateException("이미 중지된 전략입니다: " + strategyId);
        }
        // 먼저 PAUSED로 기록해 이후 배치가 새 주문을 만들지 않게 하고, 배치가 정지 뒤 체결을 기록하지 않으므로 오늘 미체결 주문을 정리한다
        // 취소 실패분은 관리자 알림 후 PLACED로 남는다
        stateWriter.pause(strategyId);
        CancelResult cancelled = orderCancelService.cancelOpenOrders(strategyId, account);
        log.info("전략 중지: strategyId={}, cancelledOrders={}, failedOrders={}",
                strategyId, cancelled.cancelledCount(), cancelled.failedCount());
    }

    @Override
    public void resume(UUID strategyId, UUID requesterId) {
        Strategy strategy = requireOwnedStrategy(strategyId, requesterId);
        // 중복 상태 guard — 이미 활성화된 전략은 재활성화 불가 (소유권 검증 이후 수행)
        if (strategy.isActive()) {
            throw new IllegalStateException("이미 활성화된 전략입니다: " + strategyId);
        }
        reopenCycleIfEnded(strategy);
        strategyPort.save(strategy.withStatus(StrategyStatus.ACTIVE));
        log.info("전략 재개: strategyId={}", strategyId);
    }

    // 전략 조회 + 소유권 검증 — delete/pause/resume/getById/update 공용
    private Strategy requireOwnedStrategy(UUID strategyId, UUID requesterId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        return strategy;
    }

    // cycleSeedType=NONE 전략은 청산 시 자동 rotation 없이 사이클이 종료된 채로 PAUSED된다(CycleRotationService).
    // 그 상태에서 status만 ACTIVE로 되돌리면 종료된 사이클(endDate 있음)이 여전히 "최신 사이클"로 남아
    // BatchContextFactory가 매 배치마다 좀비 사이클로 판정해 skip — 재개가 실제로는 매매를 재개시키지 못한다.
    // 재개 시점에 종료 사이클의 원장 금액(endAmount)을 그대로 승계한 새 사이클을 열어 이를 방지한다.
    private void reopenCycleIfEnded(Strategy strategy) {
        // VR은 endsCycleOnLiquidation()=false라 이 경로로 종료되지 않는다(항상 rollover/재설정이 원자적으로
        // 대체 사이클을 만든다) — 그 불변식이 깨졌을 때 여기서 VR 상세(strategy_cycle_vr) 없는 사이클을
        // 만들어버리는 사고를 막기 위해 명시적으로 제외한다
        if (strategy.isVr()) return;
        StrategyCycle latestCycle = strategyCyclePort.findLatestByStrategyId(strategy.id()).orElse(null);
        if (latestCycle == null || latestCycle.endDate() == null) return;
        StrategyVersion activeVersion = strategyVersionPort.findActiveByStrategyId(strategy.id())
                .orElseThrow(() -> new IllegalStateException("활성 전략 버전이 없습니다: " + strategy.id()));
        BigDecimal closingPrice = cyclePositionPort.findLatestOneByStrategyId(strategy.id())
                .map(CyclePosition::closingPrice)
                .orElse(null);
        cycleSnapshotCreator.createCycleAndSnapshot(strategy.id(), activeVersion.id(), latestCycle.endAmount(), closingPrice);
        log.info("[strategyId={}] 재개 — 종료된 사이클 재오픈: seed={}", strategy.id(), latestCycle.endAmount());
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
        Strategy strategy = requireOwnedStrategy(strategyId, requesterId);
        return toDetail(strategy);
    }

    @Override
    public StrategyDetail update(UUID strategyId, UUID requesterId, UpdateStrategyCommand cmd) {
        Strategy strategy = requireOwnedStrategy(strategyId, requesterId);
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
