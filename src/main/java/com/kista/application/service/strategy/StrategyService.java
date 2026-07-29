package com.kista.application.service.strategy;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.application.service.broker.BrokerCallGuard;
import com.kista.common.CycleLookups;
import com.kista.common.TimeZones;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.settings.StrategyCreationSettings;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.in.StrategyUseCase;
import com.kista.domain.port.out.*;
import com.kista.domain.port.out.broker.BrokerPricePort;
import com.kista.domain.port.out.broker.MarginPort;
import com.kista.domain.strategy.StrategyCreationResolver;
import com.kista.domain.strategy.StrategyCreationResolver.ResolvedCreation;
import com.kista.domain.strategy.StrategyCreationResolvers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final VrStrategyLifecycle vrStrategyLifecycle;          // VR 전략 전용 상세 저장·조회
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final CyclePositionInfiniteDetailPort cyclePositionInfiniteDetailPort;
    private final AccountPort accountPort;
    private final UserPort userPort;
    private final BrokerAdapterRegistry registry;                   // 등록 시점 가용 시드 검증 — MarginPort / LiveBalancePort 경유
    private final UserSettingsPort userSettingsPort;                // 잔고 검증 설정 조회 (user_settings)
    private final RuntimeSettingsPort runtimeSettingsPort;          // 신규 전략 생성 허용값·기본값 조회
    private final StrategyCreationResolvers creationResolvers;      // 전략 타입별 생성 필드 해석 라우터

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 잔고 검증 HTTP 호출 포함 — 트랜잭션 없이 실행 (각 DB 저장은 JPA auto-commit)
    public StrategyDetail register(UUID userId, UUID accountId, RegisterStrategyCommand cmd) {
        Account account = accountPort.requireOwnedAccount(accountId, userId);
        ResolvedCreation resolved = resolveCreationSettings(cmd);

        // 중간부터 시작 입력 검증 (세 전략 공통) — holdings>0이면 avgPrice>0 필수, 음수 거부
        int initialHoldings = validateBootstrapPosition(cmd);
        // 시작예정일 검증 — 기본값 오늘(KST), 과거 거부
        LocalDate scheduledStart = resolveScheduledStart(cmd);
        Strategy.Ticker resolvedTicker = resolved.ticker();

        // 종목 중복 + 잔고 검증
        validateUniqueTicker(accountId, resolvedTicker);
        validateBalanceIfRequired(account, accountId, userId, cmd.initialUsdDeposit());

        // 중간부터 시작 시 시장가(전일종가) 1회 조회 — holdings=0이면 조회 자체를 건너뛰어 기존 동작 보존
        BigDecimal marketPrice = initialHoldings > 0 ? fetchMarketPrice(account, resolvedTicker) : null;
        BigDecimal initialStockValue = initialHoldings > 0
                ? marketPrice.multiply(BigDecimal.valueOf(initialHoldings)).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // VR 전략 파라미터 검증 (서비스 계층 — DTO @NotNull 없이 여기서 처리) — V값은 시장가×보유수량 기준
        // 램프 파라미터(gradient/poolLimitRate 경과주수 함수)는 RuntimeSettings 정책 밖 — 요청값 정규화 후 여기서 직접 검증
        VrRampParams ramp = null;
        if (cmd.type() == Strategy.Type.VR) {
            int normalizedRecurringAmount = resolved.recurringAmount() != null ? resolved.recurringAmount() : 0;
            ramp = normalizeVrRampParams(cmd, normalizedRecurringAmount);
            validateVrCommand(cmd, resolved.intervalWeeks(), resolved.bandWidth(), resolved.recurringAmount(), initialStockValue, ramp);
        }

        // VR seed type은 NONE으로 고정하고 나머지는 기존 요청 기본 규칙을 유지한다.
        Strategy.CycleSeedType seedType = cmd.type() == Strategy.Type.VR
                ? Strategy.CycleSeedType.NONE  // VR은 NONE 강제 (순환 재등록 불가)
                : (cmd.cycleSeedType() != null ? cmd.cycleSeedType() : Strategy.CycleSeedType.NONE);

        int divisionCount = resolved.divisionCount();

        // 전략·버전·상세 저장 (strategy → strategy_versions → 전략별 detail)
        var persisted = saveStrategyWithVersion(accountId, cmd.type(), resolvedTicker, seedType, divisionCount,
                resolved.intervalWeeks(), resolved.bandWidth(), resolved.recurringAmount(), ramp);

        // 첫 번째 사이클·포지션 저장 (strategy_cycles → cycle_positions → 전략별 cycle_detail)
        InitialCycleResult initialResult = saveInitialCycleAndPosition(
                persisted.strategy(), persisted.version().id(), cmd.initialUsdDeposit(),
                initialHoldings, cmd.initialAvgPrice(), marketPrice, initialStockValue, persisted.vrDetail(),
                scheduledStart);

        log.info("전략 등록: accountId={}, strategyId={}, type={}", accountId, persisted.strategy().id(), persisted.strategy().type());

        // VR 응답은 개장 포지션의 USD pool을 기준으로 조립한다.
        if (persisted.strategy().isVr()) {
            StrategyDetail.VrSummary vrSummary = vrStrategyLifecycle.buildSummary(
                    persisted.vrDetail(), initialResult.cycleVr(), initialResult.initialPosition().usdDeposit());
            return new StrategyDetail(persisted.strategy(), initialResult.initialPosition().usdDeposit(), initialResult.cycle().startDate(), null, false, null, initialHoldings, vrSummary);
        }
        return new StrategyDetail(persisted.strategy(), initialResult.cycle().startAmount(), initialResult.cycle().startDate(), divisionCount, false, 0.0, initialHoldings, null);
    }

    // 중간부터 시작 입력 검증 — holdings>0이면 avgPrice>0 필수, 둘 다 음수 거부. null/0이면 빈 포지션(기존 동작)
    private int validateBootstrapPosition(RegisterStrategyCommand cmd) {
        Integer holdings = cmd.initialHoldings();
        BigDecimal avgPrice = cmd.initialAvgPrice();
        if (holdings != null && holdings < 0) {
            throw new IllegalArgumentException("보유 수량(initialHoldings)은 0 이상이어야 합니다");
        }
        if (avgPrice != null && avgPrice.signum() < 0) {
            throw new IllegalArgumentException("평단가(initialAvgPrice)는 0 이상이어야 합니다");
        }
        int normalizedHoldings = holdings != null ? holdings : 0;
        if (normalizedHoldings > 0 && (avgPrice == null || avgPrice.signum() <= 0)) {
            throw new IllegalArgumentException("보유 수량(initialHoldings)이 있으면 평단가(initialAvgPrice)는 0보다 커야 합니다");
        }
        return normalizedHoldings;
    }

    // 시작예정일 — 기본값 오늘(KST), 과거 거부. 상한 없음
    private LocalDate resolveScheduledStart(RegisterStrategyCommand cmd) {
        LocalDate today = LocalDate.now(TimeZones.KST);
        LocalDate scheduled = cmd.scheduledStartDate() != null ? cmd.scheduledStartDate() : today;
        if (scheduled.isBefore(today)) {
            throw new IllegalArgumentException("시작예정일(scheduledStartDate)은 오늘 이후여야 합니다");
        }
        return scheduled;
    }

    // 중간부터 시작 시 시장가(전일종가) 조회 — startAmount·초기 포지션·VR V값을 동일 기준으로 정합
    // 조회 실패 시 등록 자체가 실패한다 — BrokerCallGuard가 IllegalStateException으로 래핑해 GlobalExceptionHandler 400 매핑
    private BigDecimal fetchMarketPrice(Account account, Strategy.Ticker ticker) {
        return BrokerCallGuard.wrap("전일종가 조회",
                () -> registry.require(account, BrokerPricePort.class).getPrevClose(ticker, account));
    }

    // 등록 시점에만 런타임 생성 정책을 적용해 기존 전략 흐름과 설정 조회를 분리한다.
    // 전략 타입별 필드 해석은 CycleOrderStrategy와 동일한 capability 패턴(StrategyCreationResolvers)에 위임한다.
    private ResolvedCreation resolveCreationSettings(RegisterStrategyCommand cmd) {
        StrategyCreationSettings settings = runtimeSettingsPort.load().strategies().get(cmd.type());
        if (!settings.enabled()) {
            throw new IllegalArgumentException("비활성화된 전략 유형은 새로 등록할 수 없습니다: " + cmd.type());
        }
        return creationResolvers.of(cmd.type()).resolve(cmd, settings);
    }

    // VR 전용 파라미터 검증 — 각 항목이 null이거나 범위 위반이면 IllegalArgumentException
    // initialValue: 시장가×보유수량으로 계산된 V값 (register()에서 전달, null 아님)
    private void validateVrCommand(RegisterStrategyCommand cmd, Integer intervalWeeks,
                                   BigDecimal bandWidth, Integer recurringAmount, BigDecimal initialValue,
                                   VrRampParams ramp) {
        if (intervalWeeks == null || intervalWeeks <= 0) {
            throw new IllegalArgumentException("VR 전략의 리밸런싱 주기(intervalWeeks)는 1 이상이어야 합니다");
        }
        if (bandWidth == null || bandWidth.signum() <= 0) {
            throw new IllegalArgumentException("VR 전략의 밴드 폭(bandWidth)은 0보다 커야 합니다");
        }
        BigDecimal initialUsdDeposit = normalizeMoney(cmd.initialUsdDeposit());
        int normalizedRecurringAmount = recurringAmount != null ? recurringAmount : 0;
        BigDecimal initialAssets = initialValue.add(initialUsdDeposit);

        if (normalizedRecurringAmount <= 0 && initialAssets.signum() <= 0) {
            throw new IllegalArgumentException("VR 거치식/인출식은 초기 V값과 초기 예수금 중 하나는 0보다 커야 합니다");
        }
        if (normalizedRecurringAmount < 0) {
            BigDecimal required = BigDecimal.valueOf(Math.abs((long) normalizedRecurringAmount))
                    .multiply(BigDecimal.valueOf(100))
                    .multiply(BigDecimal.valueOf(4))
                    .divide(BigDecimal.valueOf(intervalWeeks), 2, RoundingMode.HALF_UP);
            if (initialAssets.compareTo(required) < 0) {
                throw new IllegalArgumentException("인출식 VR 전략의 초기 자산은 " + required + " 이상이어야 합니다");
            }
        }

        // 램프 파라미터 검증 — 정규화된(null 아님) 값 기준
        if (ramp.initialGradient() <= 0) {
            throw new IllegalArgumentException("VR 전략의 초기 gradient(initialGradient)는 0보다 커야 합니다");
        }
        if (ramp.gStepWeeks() < 0) {
            throw new IllegalArgumentException("VR 전략의 gradient 스텝 주기(gStepWeeks)는 0 이상이어야 합니다");
        }
        if (ramp.gGraceWeeks() < 0) {
            throw new IllegalArgumentException("VR 전략의 gradient 유예 주수(gGraceWeeks)는 0 이상이어야 합니다");
        }
        // gStepWeeks=0은 gradient 램프 비활성화 — 이때 gMax는 계산에 사용되지 않으므로 0을 허용
        if (ramp.gStepWeeks() > 0 && ramp.gMax() < ramp.initialGradient()) {
            throw new IllegalArgumentException("VR 전략의 gradient 상한(gMax)은 initialGradient 이상이어야 합니다");
        }
        if (ramp.pStepWeeks() < 0) {
            throw new IllegalArgumentException("VR 전략의 poolLimitRate 스텝 주기(pStepWeeks)는 0 이상이어야 합니다");
        }
        if (ramp.pGraceWeeks() < 0) {
            throw new IllegalArgumentException("VR 전략의 poolLimitRate 유예 주수(pGraceWeeks)는 0 이상이어야 합니다");
        }
        if (ramp.initialPoolLimitRate().compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("VR 전략의 초기 poolLimitRate(initialPoolLimitRate)는 1 이하여야 합니다");
        }
        // poolLimitFloor 범위는 pStepWeeks와 무관하게 항상 검증 — DB CHECK(pool_limit_floor <= initial_pool_limit_rate)와
        // 어긋나는 값이 여기서 걸러지지 않으면 INSERT 시 매핑되지 않은 DataIntegrityViolationException → 500으로 새는 것을 방지
        if (ramp.poolLimitFloor().signum() < 0 || ramp.poolLimitFloor().compareTo(ramp.initialPoolLimitRate()) > 0) {
            throw new IllegalArgumentException(
                    "VR 전략의 poolLimitRate 하한(poolLimitFloor)은 0 이상 initialPoolLimitRate 이하여야 합니다");
        }
        // pStepWeeks=0은 poolLimitRate 램프 비활성화(항상 initialPoolLimitRate 유지) — 이때는 poolLimitFloor=0도 허용
        if (ramp.pStepWeeks() > 0 && ramp.poolLimitFloor().signum() <= 0) {
            throw new IllegalArgumentException("VR 전략의 poolLimitRate 램프는 poolLimitFloor가 0보다 커야 합니다");
        }
    }

    // VR 램프 파라미터 정규화 — 미지정 필드를 recurringAmount 부호·관례값(52주 유예/26주 스텝)으로 채운다
    // gMax/poolLimitFloor 미지정 시 initial* 값을 그대로 사용해 램프가 사실상 비활성화(no-op)되도록 한다
    private VrRampParams normalizeVrRampParams(RegisterStrategyCommand cmd, int normalizedRecurringAmount) {
        int initialGradient = cmd.initialGradient() != null
                ? cmd.initialGradient()
                : (normalizedRecurringAmount < 0 ? 20 : 10);
        int gGraceWeeks = cmd.gGraceWeeks() != null ? cmd.gGraceWeeks() : 52;
        int gStepWeeks = cmd.gStepWeeks() != null ? cmd.gStepWeeks() : 26;
        int gMax = cmd.gMax() != null ? cmd.gMax() : initialGradient;
        BigDecimal initialPoolLimitRate = cmd.initialPoolLimitRate() != null
                ? cmd.initialPoolLimitRate()
                : normalizedRecurringAmount > 0 ? new BigDecimal("0.75")
                        : normalizedRecurringAmount == 0 ? new BigDecimal("0.50") : new BigDecimal("0.25");
        int pGraceWeeks = cmd.pGraceWeeks() != null ? cmd.pGraceWeeks() : 52;
        int pStepWeeks = cmd.pStepWeeks() != null ? cmd.pStepWeeks() : 26;
        BigDecimal poolLimitFloor = cmd.poolLimitFloor() != null ? cmd.poolLimitFloor() : initialPoolLimitRate;
        return new VrRampParams(initialGradient, gGraceWeeks, gStepWeeks, gMax,
                initialPoolLimitRate, pGraceWeeks, pStepWeeks, poolLimitFloor);
    }

    // VR 금액 입력 null은 사용자가 0을 입력한 것과 동일하게 취급
    private BigDecimal normalizeMoney(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
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
        UserSettings settings = userSettingsPort.findOrDefault(userId);
        if (settings.balanceCheckEnabled() && initialUsdDeposit != null) {
            BigDecimal freeCash = calcFreeCash(account, accountId);
            if (initialUsdDeposit.compareTo(freeCash) > 0) {
                throw new IllegalArgumentException(
                        "다른 전략이 사용 중인 시드를 제외한 예수금(" + freeCash + ")을 초과했습니다");
            }
        }
    }

    // strategy → strategy_versions → 전략 타입별 detail 순 저장
    // ramp: VR 등록일 때만 non-null (register()에서 정규화 완료 후 전달)
    private SavedStrategyAndVersion saveStrategyWithVersion(
            UUID accountId, Strategy.Type type, Strategy.Ticker ticker,
            Strategy.CycleSeedType seedType, int divisionCount,
            Integer intervalWeeks, BigDecimal bandWidth, Integer recurringAmount, VrRampParams ramp) {
        Strategy strategy = new Strategy(null, accountId, type, Strategy.Status.ACTIVE, ticker, seedType);
        Strategy saved = strategyPort.save(strategy);
        StrategyVersion version = strategyVersionPort.save(
                new StrategyVersion(null, saved.id(), strategyVersionPort.nextVersionNo(saved.id()), null, null)
        );
        StrategyVrDetail vrDetail = null;
        if (saved.isInfinite()) {
            strategyInfiniteDetailPort.save(new StrategyInfiniteDetail(version.id(), divisionCount));
        } else if (saved.isVr()) {
            vrDetail = vrStrategyLifecycle.saveVersionDetail(version.id(), intervalWeeks, bandWidth, recurringAmount,
                    ramp.initialGradient(), ramp.gGraceWeeks(), ramp.gStepWeeks(), ramp.gMax(),
                    ramp.initialPoolLimitRate(), ramp.pGraceWeeks(), ramp.pStepWeeks(), ramp.poolLimitFloor());
        }
        return new SavedStrategyAndVersion(saved, version, vrDetail);
    }

    // strategy_cycles → cycle_positions → 전략 타입별 cycle_detail 순 저장
    // startAmount = 현금 + 시장가×보유수량 — VR도 총 시작자산을 동일하게 보존한다.
    private InitialCycleResult saveInitialCycleAndPosition(
            Strategy saved, UUID versionId, BigDecimal initialUsdDeposit,
            int initialHoldings, BigDecimal initialAvgPrice, BigDecimal marketPrice,
            BigDecimal initialStockValue, StrategyVrDetail vrDetail, LocalDate scheduledStart) {
        BigDecimal normalizedInitialUsdDeposit = normalizeMoney(initialUsdDeposit);
        BigDecimal startAmount = normalizedInitialUsdDeposit.add(initialStockValue);
        StrategyCycle cycle = strategyCyclePort.save(StrategyCycle.start(saved.id(), versionId, startAmount, scheduledStart));

        CyclePosition initialPosition = initialHoldings > 0
                ? cyclePositionPort.save(CyclePosition.bootstrapSnapshot(
                        cycle.id(), normalizedInitialUsdDeposit, initialHoldings, initialAvgPrice, marketPrice))
                : cyclePositionPort.save(CyclePosition.initialSnapshot(cycle.id(), normalizedInitialUsdDeposit));

        if (saved.isInfinite()) {
            cyclePositionInfiniteDetailPort.save(new CyclePositionInfiniteDetail(initialPosition.id(), false));
            return new InitialCycleResult(cycle, initialPosition, null);
        } else if (saved.isVr()) {
            StrategyCycleVrDetail savedCycleVr = vrStrategyLifecycle.saveInitialCycleDetail(
                    cycle.id(), normalizedInitialUsdDeposit, initialStockValue, vrDetail);
            return new InitialCycleResult(cycle, initialPosition, savedCycleVr);
        } else {
            // PRIVACY
            return new InitialCycleResult(cycle, initialPosition, null);
        }
    }

    // 전략 저장 후 버전 ID + VR 상세를 함께 전달하기 위한 내부 전달 객체
    private record SavedStrategyAndVersion(Strategy strategy, StrategyVersion version, StrategyVrDetail vrDetail) {}

    // 초기 사이클·개장 포지션·VR 전용 cycleVr 저장 결과 — VR 외 cycleVr는 null
    private record InitialCycleResult(StrategyCycle cycle, CyclePosition initialPosition,
                                      StrategyCycleVrDetail cycleVr) {}

    // VR 램프 파라미터 정규화 결과 — 8필드 모두 non-null(int/BigDecimal), normalizeVrRampParams()의 반환 묶음
    private record VrRampParams(int initialGradient, int gGraceWeeks, int gStepWeeks, int gMax,
                                 BigDecimal initialPoolLimitRate, int pGraceWeeks, int pStepWeeks,
                                 BigDecimal poolLimitFloor) {}

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
        if (strategy.isVr() && cmd.newSeed() != null) {
            throw new IllegalArgumentException("VR 전략의 시드/시작금액은 일반 수정으로 변경할 수 없습니다. VR 재설정을 사용하세요");
        }

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
                .map(s -> cyclePositionPort.findLatestOneByStrategyId(s.id())
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
        CyclePosition latest = cyclePositionPort.findLatestOneByStrategyId(strategyId)
                .orElseThrow(() -> new IllegalStateException("포지션 이력 없음: " + strategyId));

        if (latest.holdings() != 0) {
            throw new IllegalArgumentException("보유 수량이 있는 사이클은 시드를 수정할 수 없습니다");
        }

        strategyCyclePort.updateStartAmount(cycle.id(), newSeed);
        cyclePositionPort.updateCycleStartSnapshot(strategyId, newSeed);
        log.info("시드 수정: strategyId={}, newSeed={}, holdings={}", strategyId, newSeed, latest.holdings());
    }

    // 최신 사이클 개장금액을 조립하고, VR pool은 개장 포지션, 리버스모드는 최신 포지션에서 판단한다.
    private StrategyDetail toDetail(Strategy strategy) {
        var latestCycle = strategyCyclePort.findLatestByStrategyId(strategy.id());
        Optional<CyclePosition> openingPosition = strategy.isVr()
                ? latestCycle.map(cycle -> cyclePositionPort.findFirstOne(cycle.id())
                        .orElseThrow(() -> new IllegalStateException(
                                "VR 시작 포지션 없음: cycleId=" + cycle.id())))
                : Optional.empty();
        BigDecimal initialUsdDeposit = strategy.isVr()
                ? openingPosition.map(CyclePosition::usdDeposit).orElse(null)
                : latestCycle.map(StrategyCycle::startAmount).orElse(null);
        LocalDate startDate = latestCycle.map(StrategyCycle::startDate).orElse(null);

        Integer divisionCount = strategy.isInfinite()
                ? strategyVersionPort.findActiveByStrategyId(strategy.id())
                        .flatMap(version -> strategyInfiniteDetailPort.findByStrategyVersionId(version.id()))
                        .map(StrategyInfiniteDetail::divisionCount)
                        .orElse(Strategy.DEFAULT_DIVISION_COUNT)
                : null;

        Optional<CyclePosition> latestPos = cyclePositionPort.findLatestOneByStrategyId(strategy.id());

        boolean isReverseMode = latestPos
                .flatMap(pos -> cyclePositionInfiniteDetailPort.findByCyclePositionId(pos.id()))
                .map(CyclePositionInfiniteDetail::isReverseMode)
                .orElse(false);

        // VR은 currentRound 없음 — INFINITE만 계산
        Double currentRound = strategy.isVr() ? null :
                latestPos.map(pos -> InfinitePosition.calcCurrentRound(
                        pos.avgPrice(), pos.holdings(), pos.usdDeposit(),
                        divisionCount == null ? 0 : divisionCount)).orElse(null);

        Integer currentHoldings = latestPos.map(CyclePosition::holdings).orElse(null);

        // VR 전략: 최신 활성 버전 + 최신 사이클 상세를 helper가 합산
        StrategyDetail.VrSummary vrSummary = strategy.isVr()
                ? vrStrategyLifecycle.findSummary(strategy.id(), latestCycle).orElse(null)
                : null;

        return new StrategyDetail(strategy, initialUsdDeposit, startDate, divisionCount, isReverseMode, currentRound, currentHoldings, vrSummary);
    }

}
