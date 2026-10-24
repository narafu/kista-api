package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.TimeZones;

import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.broker.application.port.output.BrokerPricePort;
import com.kista.tradingcore.broker.application.port.output.MarginPort;
import com.kista.tradingcore.broker.application.service.BrokerCallGuard;
import com.kista.tradingcore.matching.domain.model.BootstrapPosition;
import com.kista.tradingcore.matching.domain.model.DcaStrategyConfig;
import com.kista.tradingcore.matching.domain.model.StrategyVrDetail;
import com.kista.tradingcore.privacy.application.port.output.PrivacyTradePort;
import com.kista.tradingcore.privacy.domain.model.PrivacyCurrentBase;
import com.kista.shared.kernel.*;
import com.kista.tradingcore.trading.application.port.output.StrategyCreationPolicyPort;
import com.kista.tradingcore.trading.application.port.output.*;
import com.kista.tradingcore.trading.application.usecase.VrStrategyDetailUseCase;
import com.kista.tradingcore.trading.domain.model.*;
import com.kista.tradingcore.trading.domain.strategy.StrategyCreationResolver.ResolvedCreation;
import com.kista.tradingcore.trading.domain.strategy.StrategyCreationResolvers;
import com.kista.tradingcore.trading.domain.strategy.VrRampParams;
import com.kista.tradingcore.trading.domain.strategy.VrRampValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

// 신규 전략 등록 전용 — VR 파라미터 검증, 잔고 검증, strategy/version/cycle/position 초기 저장 일체
@Slf4j
@Service
@RequiredArgsConstructor
class StrategyCreationService {

    // 계좌 시장에서 종목 거래 가능 + DCA 규칙의 시장별 규칙(주문유형·금액·요일)
    private void validateMarket(Account account, DcaStrategyConfig dca, StrategyTicker ticker) {
        Market market = account.broker().getMarket();
        if (!ticker.tradableOn(market)) {
            throw new IllegalArgumentException("계좌와 종목의 시장이 다릅니다: " + ticker);
        }
        if (dca != null) dca.requireCompatible(market);
    }

    private final StrategyPort strategyPort;
    private final VrStrategyDetailUseCase vrStrategyLifecycle;
    private final CyclePositionPort cyclePositionPort;
    private final AccountPort accountPort;
    private final TradingUserProfilePort tradingUserProfilePort;
    private final BrokerPricePort brokerPricePort;
    private final MarginPort marginPort;
    private final StrategyCreationPolicyPort strategyCreationPolicyPort;
    private final StrategyCreationResolvers creationResolvers;
    private final StrategyCreationPersister creationPersister; // 저장 5단계 트랜잭션 경계 (self-invocation 회피용 별도 빈)
    private final DcaSummaryAssembler dcaSummaryAssembler;      // DCA 등록 응답의 다음 실행일 요약
    private final PrivacyTradePort privacyTradePort;            // PRIVACY 중간부터 시작 — FIDA 기준값으로 시작금액 산출

    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 잔고 검증·시세 HTTP 호출 포함 — 검증은 트랜잭션 밖, 저장만 creationPersister 트랜잭션
    StrategyDetail register(UUID userId, UUID accountId, RegisterStrategyCommand cmd) {
        Account account = accountPort.requireOwnedAccount(accountId, userId);
        // 코인 계좌는 DCA 전용 — 정책 조회보다 먼저 거부한다
        if (account.broker().isCryptoExchange() && cmd.type() != StrategyType.DCA) {
            throw new IllegalArgumentException("코인 계좌는 DCA 전략만 등록할 수 있습니다.");
        }
        ResolvedCreation resolved = resolveCreationSettings(cmd);
        boolean dca = cmd.type() == StrategyType.DCA;
        if (dca && cmd.dca() == null) {
            throw new IllegalArgumentException("DCA 설정(dca)은 필수입니다");
        }
        // 등록 규칙은 전부 신규 — ruleKey를 발급하고 클라이언트가 보낸 키는 400(현재 버전이 없으므로 존재하지 않는 규칙)
        DcaStrategyConfig dcaConfig = dca ? cmd.dca().withAssignedKeys(Set.of()) : null;

        StrategyTicker resolvedTicker = resolved.ticker();
        // 시장 검증이 중간부터 시작(보유 자릿수) 검증보다 먼저 — 시장 불일치가 자릿수 문구에 가려지지 않게 한다
        validateMarket(account, dcaConfig, resolvedTicker);
        // 중간부터 시작 입력 검증 (전 전략 공통) — holdings>0이면 avgPrice>0 필수, 음수 거부, 주식 정수·코인 소수 8자리
        BigDecimal initialHoldings = BootstrapPosition.validate(cmd.initialHoldings(), cmd.initialAvgPrice(), account.broker().getMarket());
        boolean bootstrap = initialHoldings.signum() > 0;
        // 시작예정일 검증 — 기본값 오늘(KST), 과거 거부
        LocalDate scheduledStart = resolveScheduledStart(cmd);

        // 종목 중복 + 잔고 검증
        validateUniqueTicker(accountId, resolvedTicker);
        BigDecimal initialUsdDeposit = dca ? BigDecimal.ZERO : cmd.initialUsdDeposit(); // DCA는 예수금 시드 없음
        validateBalanceIfRequired(account, accountId, userId, initialUsdDeposit, !dca);

        // 중간부터 시작 시 시장가(전일종가) 1회 조회 — holdings=0이면 조회 자체를 건너뛰어 기존 동작 보존
        // DCA는 시장가를 조회하지 않고 초기 투입 원가(보유 × 평단)를 start_amount로 둔다 — 코인은 BrokerPricePort 경로가 없다
        BigDecimal marketPrice = bootstrap && !dca ? fetchMarketPrice(account, resolvedTicker) : null;
        BigDecimal initialStockValue = !bootstrap ? BigDecimal.ZERO
                : (dca ? cmd.initialAvgPrice() : marketPrice).multiply(initialHoldings).setScale(2, RoundingMode.HALF_UP);

        // VR 전략 파라미터 검증 (서비스 계층 — DTO @NotNull 없이 여기서 처리) — V값은 시장가×보유수량 기준
        // 램프 파라미터(gradient/poolLimitRate 경과주수 함수)는 RuntimeSettings 정책 밖 — 요청값 정규화 후 여기서 직접 검증
        VrRampParams ramp = null;
        BigDecimal vrValue = null;
        if (cmd.type() == StrategyType.VR) {
            int normalizedRecurringAmount = resolved.recurringAmount() != null ? resolved.recurringAmount() : 0;
            vrValue = resolveVrValue(cmd, initialStockValue);
            ramp = normalizeVrRampParams(cmd, normalizedRecurringAmount);
            validateVrCommand(cmd, resolved.intervalWeeks(), resolved.bandWidth(), resolved.recurringAmount(),
                    vrValue, initialStockValue, ramp);
        }

        // VR seed type은 NONE으로 고정하고 나머지는 기존 요청 기본 규칙을 유지한다.
        StrategyCycleSeedType seedType = cmd.type() == StrategyType.VR || dca
                ? StrategyCycleSeedType.NONE  // VR·DCA는 NONE 강제 (순환 재등록 불가)
                : (cmd.cycleSeedType() != null ? cmd.cycleSeedType() : StrategyCycleSeedType.NONE);

        int divisionCount = resolved.divisionCount();

        // startAmount = 현금 + 시장가×보유수량(DCA는 평단×보유 = 초기 투입 원가) — VR도 총 시작자산을 동일하게 보존한다(vrValue override와 무관)
        // PRIVACY 중간부터 시작만 FIDA 기준 배수로 환산한다 — 매매표 배수(startAmount ÷ currentCycleStart)가 보유 비율과 맞도록
        BigDecimal depositOrZero = initialUsdDeposit != null ? initialUsdDeposit : BigDecimal.ZERO;
        BigDecimal startAmount = cmd.type() == StrategyType.PRIVACY && bootstrap
                ? privacyBootstrapStartAmount(depositOrZero, initialHoldings)
                : depositOrZero.add(initialStockValue);

        // 저장 5단계(strategy·version·detail·cycle·position)는 별도 빈의 한 트랜잭션 — 중간 실패 시 부분 저장 없이 롤백
        // (증권사 호출은 위에서 이미 끝났으므로 트랜잭션이 HTTP 대기를 물지 않는다)
        StrategyCreationPersister.Persisted persisted = creationPersister.persist(
                accountId, cmd.type(), resolvedTicker, seedType, divisionCount,
                resolved.intervalWeeks(), resolved.bandWidth(), resolved.recurringAmount(), ramp,
                initialUsdDeposit, initialHoldings, cmd.initialAvgPrice(), marketPrice, startAmount,
                vrValue, scheduledStart, dcaConfig);

        log.info("전략 등록: accountId={}, strategyId={}, type={}", accountId, persisted.strategy().id(), persisted.strategy().type());

        // VR 응답은 개장 포지션의 USD pool을 기준으로 조립한다.
        if (persisted.strategy().isVr()) {
            VrSummary vrSummary = vrStrategyLifecycle.buildSummary(
                    persisted.vrDetail(), persisted.cycleVr(), persisted.initialPosition().usdDeposit(),
                    persisted.initialPosition().usdDeposit()); // 등록 직후엔 개장 pool=현재 pool 동일
            return new StrategyDetail(persisted.strategy(), persisted.initialPosition().usdDeposit(), persisted.cycle().startDate(), null, false, null, initialHoldings.intValueExact(), vrSummary, null);
        }
        if (dca) {
            Market market = account.broker().getMarket();
            CyclePosition ledger = persisted.initialPosition();
            DcaSummary summary = dcaSummaryAssembler.assemble(dcaConfig, Map.of(), persisted.cycle().startDate(), market,
                    LocalDateTime.now(TimeZones.KST), ledger.holdings(), ledger.avgPrice());
            // 코인은 소수 원장이라 정수 보유 수량을 싣지 않는다(DCA 블록이 원장 보유를 싣는다)
            Integer holdings = market.isCrypto() ? null : initialHoldings.intValueExact();
            return new StrategyDetail(persisted.strategy(), BigDecimal.ZERO, persisted.cycle().startDate(), null, false, null, holdings, null, summary);
        }
        return new StrategyDetail(persisted.strategy(), persisted.cycle().startAmount(), persisted.cycle().startDate(), divisionCount, false, 0.0, initialHoldings.intValueExact(), null, null);
    }

    // PRIVACY 중간부터 시작 시작금액 — 원가 기준 자산 비율로 배수를 구해 FIDA 사이클 시작금액에 곱한다
    // 배수 = (예수금 + 보유×FIDA 평단) ÷ (FIDA 사이클 시작금액 + 실현손익), 소수 2자리 버림(PrivacyStrategy 배수 산출과 동일)
    // 예: 3,445.25 + 417×125.95 = 55,966.40 ÷ (14,434.58 − 454.07) = 4.00배 → 57,738.32
    private BigDecimal privacyBootstrapStartAmount(BigDecimal initialUsdDeposit, BigDecimal initialHoldings) {
        PrivacyCurrentBase base = privacyTradePort.findSeedPreviewBase()
                .orElseThrow(() -> new IllegalArgumentException("PRIVACY 기준 매매표가 없어 중간부터 시작할 수 없습니다."));
        BigDecimal fidaBookValue = base.currentCycleStart().add(base.currentCycleRealizedPnl());
        if (base.avgPrice() == null || fidaBookValue.signum() <= 0) {
            throw new IllegalArgumentException("PRIVACY 기준 매매표에 보유 평단이 없어 중간부터 시작할 수 없습니다.");
        }
        BigDecimal userBookValue = initialUsdDeposit.add(initialHoldings.multiply(base.avgPrice()));
        BigDecimal multiple = userBookValue.divide(fidaBookValue, 2, RoundingMode.FLOOR);
        log.info("[PRIVACY] 중간부터 시작 배수: userBookValue={}, fidaBookValue={}, multiple={}", userBookValue, fidaBookValue, multiple);
        if (multiple.signum() <= 0) {
            throw new IllegalArgumentException("보유 자산이 PRIVACY 기준 매매표 대비 너무 적어 중간부터 시작할 수 없습니다.");
        }
        // CEILING — 매매 시 startAmount ÷ currentCycleStart를 FLOOR로 되돌리므로 반올림 내림이면 배수가 0.01 낮아진다
        return multiple.multiply(base.currentCycleStart()).setScale(2, RoundingMode.CEILING);
    }

    // VR V값 우선순위 — 초기 V 직접 입력(>0)이 있으면 그 값을, 없으면 평가금(전일종가×보유수량)을 사용한다.
    // 실제 포지션(CyclePosition)·startAmount는 이 override와 무관하게 항상 evaluatedStockValue(실제 시장가) 기준을 유지한다.
    private BigDecimal resolveVrValue(RegisterStrategyCommand cmd, BigDecimal evaluatedStockValue) {
        BigDecimal explicit = cmd.initialVrValue();
        if (explicit != null && explicit.signum() < 0) {
            throw new IllegalArgumentException("VR 전략의 초기 V값(initialVrValue)은 0 이상이어야 합니다");
        }
        return explicit != null && explicit.signum() > 0 ? explicit : evaluatedStockValue;
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
    // 조회 실패 시 등록 자체가 실패한다 — 증권사 예외는 BrokerCallGuard가 그대로 전파해 TradingExceptionHandler가 503/422/429 매핑
    private BigDecimal fetchMarketPrice(Account account, StrategyTicker ticker) {
        return BrokerCallGuard.wrap("전일종가 조회",
                () -> brokerPricePort.getPrevClose(ticker, account.toBrokerRef()));
    }

    // 등록 시점에만 런타임 생성 정책을 적용해 기존 전략 흐름과 설정 조회를 분리한다.
    // 전략 타입별 필드 해석은 CycleOrderStrategy와 동일한 capability 패턴(StrategyCreationResolvers)에 위임한다.
    private ResolvedCreation resolveCreationSettings(RegisterStrategyCommand cmd) {
        StrategyCreationSettings settings = strategyCreationPolicyPort.find(cmd.type())
                .orElseThrow(() -> new IllegalArgumentException("등록되지 않은 전략 생성 정책: " + cmd.type()));
        if (!settings.enabled()) {
            throw new IllegalArgumentException("비활성화된 전략 유형은 새로 등록할 수 없습니다: " + cmd.type());
        }
        com.kista.tradingcore.trading.domain.strategy.StrategyCreationRequest request =
                new com.kista.tradingcore.trading.domain.strategy.StrategyCreationRequest(
                        cmd.ticker(), cmd.divisionCount(), cmd.intervalWeeks(), cmd.bandWidth(), cmd.recurringAmount());
        return creationResolvers.of(cmd.type()).resolve(request, settings);
    }

    // VR 전용 파라미터 검증 — 각 항목이 null이거나 범위 위반이면 IllegalArgumentException
    // vrValue: register()에서 override 우선순위로 해석된 V값(게이트 판정용) — resolveVrValue() 참고
    // evaluatedStockValue: 실제 시장가 기준 평가금(전일종가×보유수량) — 인출식 최소자산 검증은 이 값만 사용해 override로 우회할 수 없게 한다
    private void validateVrCommand(RegisterStrategyCommand cmd, Integer intervalWeeks,
                                   BigDecimal bandWidth, Integer recurringAmount,
                                   BigDecimal vrValue, BigDecimal evaluatedStockValue,
                                   VrRampParams ramp) {
        if (intervalWeeks == null || intervalWeeks <= 0) {
            throw new IllegalArgumentException("VR 전략의 리밸런싱 주기(intervalWeeks)는 1 이상이어야 합니다");
        }
        BigDecimal initialUsdDeposit = normalizeMoney(cmd.initialUsdDeposit());
        int normalizedRecurringAmount = recurringAmount != null ? recurringAmount : 0;
        BigDecimal initialAssets = vrValue.add(initialUsdDeposit);
        BigDecimal evaluatedAssets = evaluatedStockValue.add(initialUsdDeposit);

        // gate 체크는 override 가능한 vrValue 기준, 인출액 대비 필요자산 비교는 시장가(evaluatedStockValue) 기준
        // — override로 우회할 수 없게 별도 값을 넘긴다 (VrRampValidator 참고)
        VrRampValidator.validateWithdrawalSufficiency(normalizedRecurringAmount, intervalWeeks, initialAssets, evaluatedAssets);

        // 램프 파라미터 + intervalWeeks/bandWidth 검증 — 정규화된(null 아님) 값 기준
        ramp.validate(intervalWeeks, bandWidth);
    }

    // VR 램프 파라미터 정규화 — 기본값 표는 VrRampParams.withDefaults()가 SSOT(백테스트와 공유)
    private VrRampParams normalizeVrRampParams(RegisterStrategyCommand cmd, int normalizedRecurringAmount) {
        return VrRampParams.withDefaults(normalizedRecurringAmount,
                cmd.initialGradient(), cmd.gGraceWeeks(), cmd.gStepWeeks(), cmd.gMax(),
                cmd.initialPoolLimitRate(), cmd.pGraceWeeks(), cmd.pStepWeeks(), cmd.poolLimitFloor());
    }

    // VR 금액 입력 null은 사용자가 0을 입력한 것과 동일하게 취급
    private BigDecimal normalizeMoney(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    // 같은 계좌 내 종목 중복 방지 — 종목별 합산 잔고 ↔ 전략 일대일 보장 (DCA도 예외 없음 — 매수·매도는 한 전략의 규칙으로)
    private void validateUniqueTicker(UUID accountId, StrategyTicker ticker) {
        if (!strategyPort.findByAccountIdAndTicker(accountId, ticker).isEmpty()) {
            throw new DuplicateStrategyTickerException(); // 409 — 전 전략 유형 공통
        }
    }

    // 잔고 검증 활성 시: 새 시드는 증권사 가용금액에서 기존 전략 점유 시드를 뺀 예수금 한도 내
    private void validateBalanceIfRequired(Account account, UUID accountId, UUID userId, BigDecimal initialUsdDeposit,
                                           boolean compareFreeCash) {
        TradingUserProfile userProfile = tradingUserProfilePort.findByUserId(userId) // 사용자 존재 확인 겸용
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + userId));
        if (compareFreeCash && userProfile.balanceCheckEnabled() && initialUsdDeposit != null) {
            BigDecimal freeCash = calcFreeCash(account, accountId);
            if (initialUsdDeposit.compareTo(freeCash) > 0) {
                throw new IllegalArgumentException(
                        "다른 전략이 사용 중인 시드를 제외한 예수금(" + freeCash + ")을 초과했습니다");
            }
        }
    }

    // 예수금 = 증권사 USD 매수가능금액 - 기존 전략들이 보유한 미투자 현금(usdDeposit) 합
    private BigDecimal calcFreeCash(Account account, UUID accountId) {
        BigDecimal kisUsdAmount = marginPort.getUsdBuyableAmount(account.toBrokerRef());

        BigDecimal reserved = strategyPort.findByAccountId(accountId).stream()
                .map(s -> cyclePositionPort.findLatestOneByStrategyId(s.id())
                        .map(CyclePosition::usdDeposit)
                        .orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return kisUsdAmount.subtract(reserved);
    }
}
