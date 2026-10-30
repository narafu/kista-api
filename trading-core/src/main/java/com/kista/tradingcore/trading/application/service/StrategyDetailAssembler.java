package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.StrategyDefaults;
import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.matching.domain.model.*;
import com.kista.tradingcore.trading.application.port.output.*;
import com.kista.tradingcore.trading.application.usecase.VrStrategyDetailUseCase;
import com.kista.tradingcore.trading.domain.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

// StrategyDetail 조립 SSOT — 조회(StrategyService)와 등록(StrategyCreationService)이 공용해 POST 응답과 GET 값이 어긋나지 않는다
// 트랜잭션은 호출측을 그대로 탄다(@Transactional 없음)
@Component
@RequiredArgsConstructor
class StrategyDetailAssembler {

    private final StrategyVersionPort strategyVersionPort;
    private final StrategyInfiniteDetailPort strategyInfiniteDetailPort;
    private final VrStrategyDetailUseCase vrStrategyLifecycle;      // VR 전략 전용 상세 저장·조회
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final CyclePositionInfiniteDetailPort cyclePositionInfiniteDetailPort;
    private final StrategyDcaDetailPort dcaDetailPort;             // DCA 활성 설정 조회
    private final DcaExecutionPort dcaExecutionPort;               // DCA 최근 회차 조회
    private final DcaSummaryAssembler dcaSummaryAssembler;         // DCA 조회 요약 조립

    // 최신 사이클 개장금액을 조립하고, VR pool은 개장 포지션, 리버스모드는 최신 포지션에서 판단한다. (단건 경로 — 단건 port 호출로 입력 구성 후 assemble 위임)
    StrategyDetail toDetail(Strategy strategy, Market market) {
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

        DcaSummary dcaSummary = null;
        if (strategy.isDca()) {
            Map<String, DcaExecution> last = dcaExecutionPort.findLatestByStrategyIds(List.of(strategy.id()))
                    .getOrDefault(strategy.id(), Map.of());
            dcaSummary = latestCycle.flatMap(c -> dcaDetailPort.findActiveByStrategyId(strategy.id())
                    .map(config -> buildDcaSummary(config, last, c, market, latestPos))).orElse(null);
        }

        return assemble(strategy, latestCycle, openingPosition, latestPos, divisionCount, isReverseMode, vrSummary, dcaSummary);
    }

    // DCA 요약 — 규칙별 배치가 판정 가능한 첫 거래일부터 다음 실행 예정일 계산 + 원장 보유·평단
    private DcaSummary buildDcaSummary(DcaStrategyConfig config, Map<String, DcaExecution> lastByRule, StrategyCycle cycle,
                                       Market market, Optional<CyclePosition> ledger) {
        return dcaSummaryAssembler.assemble(config, lastByRule, cycle.startDate(), market, LocalDateTime.now(TimeZones.KST),
                ledger.map(CyclePosition::holdings).orElse(null), ledger.map(CyclePosition::avgPrice).orElse(null));
    }

    // 목록 경로 — 배치 메서드로 Map을 구성한 뒤 전략별 assemble 호출 (전략 수와 무관한 상수 쿼리 수)
    List<StrategyDetail> toDetails(List<Strategy> strategies, Map<UUID, Market> marketByAccountId) {
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

        // DCA만 설정·최근 회차 배치 조회 (각 1회)
        List<UUID> dcaIds = strategies.stream().filter(Strategy::isDca).map(Strategy::id).toList();
        Map<UUID, DcaStrategyConfig> dcaConfigs = dcaIds.isEmpty() ? Map.of() : dcaDetailPort.findActiveByStrategyIds(dcaIds);
        Map<UUID, Map<String, DcaExecution>> dcaLast = dcaIds.isEmpty() ? Map.of() : dcaExecutionPort.findLatestByStrategyIds(dcaIds);

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

            DcaSummary dcaSummary = null;
            if (strategy.isDca() && dcaConfigs.containsKey(strategy.id()) && latestCycle.isPresent()) {
                dcaSummary = buildDcaSummary(dcaConfigs.get(strategy.id()), dcaLast.getOrDefault(strategy.id(), Map.of()),
                        latestCycle.get(), marketByAccountId.get(strategy.accountId()), latestPos);
            }

            return assemble(strategy, latestCycle, openingPosition, latestPos, divisionCount, isReverseMode, vrSummary, dcaSummary);
        }).toList();
    }

    // 이미 조회된 입력들을 조합만 하는 순수 조립 메서드 — toDetail/toDetails 공용
    private StrategyDetail assemble(Strategy strategy, Optional<StrategyCycle> latestCycle,
                                     Optional<CyclePosition> openingPosition, Optional<CyclePosition> latestPosition,
                                     Integer divisionCount, boolean isReverseMode, VrSummary vrSummary,
                                     DcaSummary dcaSummary) {
        BigDecimal initialUsdDeposit = strategy.isVr()
                ? openingPosition.map(CyclePosition::usdDeposit).orElse(null)
                : latestCycle.map(StrategyCycle::startAmount).orElse(null);
        LocalDate startDate = latestCycle.map(StrategyCycle::startDate).orElse(null);

        // VR·DCA는 currentRound 없음 — INFINITE·PRIVACY만 계산
        Double currentRound = strategy.isVr() || strategy.isDca() ? null :
                latestPosition.map(pos -> InfinitePosition.calcCurrentRound(
                        pos.avgPrice(), pos.toBalance().wholeShares(), pos.usdDeposit(),
                        divisionCount == null ? 0 : divisionCount)).orElse(null);

        // 코인은 소수 원장이라 wholeShares()를 부르지 않는다(StrategyDetail도 코인 보유 수량은 싣지 않음)
        Integer currentHoldings = strategy.ticker().isCrypto() ? null
                : latestPosition.map(pos -> pos.toBalance().wholeShares()).orElse(null);

        return new StrategyDetail(strategy, initialUsdDeposit, startDate, divisionCount, isReverseMode, currentRound, currentHoldings, vrSummary, dcaSummary);
    }
}
