package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.broker.adapter.out.marketdata.MarketDataClient;
import com.kista.tradingcore.broker.application.service.BrokerCallGuard;
import com.kista.tradingcore.matching.domain.model.BootstrapPosition;
import com.kista.tradingcore.matching.domain.model.DcaStrategyConfig;
import com.kista.shared.kernel.StrategyCreationSettings;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.trading.application.port.output.StrategyCreationPolicyPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.port.output.TradingUserProfilePort;
import com.kista.tradingcore.trading.application.service.support.StrategyHistoryQueryService;
import com.kista.tradingcore.trading.domain.model.DepositAmounts;
import com.kista.tradingcore.trading.domain.model.FieldRejectedException;
import com.kista.tradingcore.trading.domain.model.StrategySeedPreview;
import com.kista.tradingcore.trading.domain.model.StrategyValidation;
import com.kista.tradingcore.trading.domain.model.StrategyValidation.FieldError;
import com.kista.tradingcore.trading.domain.model.DuplicateStrategyTickerException;
import com.kista.tradingcore.trading.domain.model.RegisterStrategyCommand;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyDetail;
import com.kista.tradingcore.trading.domain.creation.StrategyCreationRequest;
import com.kista.tradingcore.trading.domain.creation.StrategyCreationResolver;
import com.kista.tradingcore.trading.domain.creation.StrategyCreationResolver.ResolvedCreation;
import com.kista.tradingcore.trading.domain.creation.VrRampValidator;
import com.kista.tradingcore.trading.domain.creation.StrategyCreationResolvers;
import com.kista.tradingcore.trading.domain.creation.VrRegistration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

// 신규 전략 등록 전용 — VR 파라미터 검증, 잔고 검증, strategy/version/cycle/position 초기 저장 일체
@Slf4j
@Service
@RequiredArgsConstructor
class StrategyCreationService {

    private static final String ACTIVE_TICKER_INDEX = "uq_strategy_account_ticker_active"; // V16 부분 유니크 인덱스

    private final StrategyPort strategyPort;
    private final AccountPort accountPort;
    private final TradingUserProfilePort tradingUserProfilePort; // 사용자 복제본 존재 확인용
    private final MarketDataClient marketData; // 전일종가 공용 시세
    private final StrategyCreationPolicyPort strategyCreationPolicyPort;
    private final StrategyCreationResolvers creationResolvers;
    private final StrategyCreationPersister creationPersister; // 저장 5단계 트랜잭션 경계 (self-invocation 회피용 별도 빈)
    private final InfiniteMinSeedGuard minSeedGuard;            // INFINITE 최소 시드 검사(등록·시드 수정·인출 공유)
    private final StrategyDetailAssembler detailAssembler;      // 응답 조립(조회 경로와 동일 SSOT)
    private final StrategyHistoryQueryService seedPreviewQuery;  // 검증 파생값의 시드 미리보기(GET strategy-seed-preview와 같은 계산)
    private final Clock clock; // 현재 시각 기준 — 테스트에서 Clock.fixed 주입

    // 응답은 조회 경로와 같은 StrategyDetailAssembler로 조립한다(UI가 POST 응답을 캐시) — 이미 조회한 계좌의 market 재사용
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 잔고 검증·시세 HTTP 호출 포함 — 검증은 트랜잭션 밖, 저장만 creationPersister 트랜잭션
    StrategyDetail register(UUID userId, UUID accountId, RegisterStrategyCommand cmd) {
        Account account = accountPort.requireOwnedAccount(accountId, userId);
        ResolvedCreation resolved = resolveCreationSettings(cmd);
        StrategyCreationPersister.Draft draft = plan(userId, account, cmd, resolved);

        // 저장 5단계(strategy·version·detail·cycle·position)는 별도 빈의 한 트랜잭션 — 중간 실패 시 부분 저장 없이 롤백
        // (증권사 호출은 plan에서 이미 끝났으므로 트랜잭션이 HTTP 대기를 물지 않는다)
        Strategy strategy;
        try {
            strategy = creationPersister.persist(draft);
        } catch (DataIntegrityViolationException e) {
            // 선검사(validateUniqueTicker)를 동시에 통과한 경합 — 유니크 인덱스 위반만 409로 바꾸고 다른 제약 위반은 그대로 던진다
            // 커밋 시점 flush 예외도 persist 프록시 밖(여기)에서 잡힌다
            if (isActiveTickerIndexViolation(e)) throw new DuplicateStrategyTickerException();
            throw e;
        }

        log.info("[strategyId={}] [accountId={}] 전략 등록: type={}", strategy.id(), accountId, strategy.type());
        return detailAssembler.toDetail(strategy, account.broker().getCurrency());
    }

    // 등록 검증 전용 — register와 같은 해석(resolveCreationSettings)·검증(plan)만 태우고 저장·이벤트는 없다
    // 소유권 실패는 등록과 같은 403/404로 전파하고, 등록이 400·409로 거절하는 입력은 fieldErrors로 돌려준다
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 시세 HTTP 포함 — register와 같은 이유
    StrategyValidation validate(UUID userId, UUID accountId, RegisterStrategyCommand cmd) {
        Account account = accountPort.requireOwnedAccount(accountId, userId);
        ResolvedCreation resolved;
        try {
            resolved = resolveCreationSettings(cmd);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // 런타임 정책 해석 실패 — 종목·분할 수를 몰라 파생값을 낼 수 없다
            return new StrategyValidation(List.of(FieldError.of(e)), List.of(), StrategyValidation.Derived.EMPTY);
        }
        List<FieldError> fieldErrors = List.of();
        try {
            plan(userId, account, cmd, resolved);
        } catch (IllegalArgumentException | IllegalStateException e) {
            fieldErrors = List.of(FieldError.of(e));
        }
        // 시드 미리보기는 GET strategy-seed-preview와 같은 계산 — 시세 실패는 등록을 막지 않으므로 파생값만 비운다(fail-open)
        StrategySeedPreview preview = BrokerCallGuard.tryGet("검증용 시드 미리보기: accountId=" + accountId,
                        () -> seedPreviewQuery.strategySeedPreview(account, cmd.type(), resolved.ticker(), resolved.divisionCount()))
                .orElse(null);
        return new StrategyValidation(fieldErrors,
                preview != null ? StrategyValidation.depositWarnings(cmd.initialDeposit(), preview.availableCash()) : List.of(),
                derived(cmd, resolved, preview));
    }

    // 검증 파생값 — 해석된 런타임 기본값 + VR 램프 실효값(VrRegistration과 같은 withDefaults)·인출식 필요 자산 + 시드 미리보기
    private static StrategyValidation.Derived derived(RegisterStrategyCommand cmd, ResolvedCreation resolved, StrategySeedPreview preview) {
        boolean vr = cmd.type() == StrategyType.VR;
        Integer recurringAmount = resolved.recurringAmount();
        Integer intervalWeeks = resolved.intervalWeeks();
        BigDecimal requiredWithdrawal = vr && recurringAmount != null && recurringAmount < 0 && intervalWeeks != null && intervalWeeks > 0
                ? VrRampValidator.requiredWithdrawalAssets(recurringAmount, intervalWeeks) : null;
        return new StrategyValidation.Derived(
                resolved.ticker(), resolved.divisionCount(),
                vr ? StrategyCreationResolver.recurringModeOf(recurringAmount) : null,
                resolved.bandWidth(), intervalWeeks,
                vr ? cmd.vrRamp().withDefaults(recurringAmount) : null,
                requiredWithdrawal,
                preview != null ? preview.basePrice() : null,
                preview != null ? preview.minSeed() : null,
                preview != null ? preview.skipReason() : null,
                preview != null ? preview.availableCash() : null);
    }

    // 등록 해석 뒤 검증·저장 입력 산출 — 쓰기 없음(시세 HTTP·조회만). register와 validate가 공유하는 거절 순서의 SSOT
    private StrategyCreationPersister.Draft plan(UUID userId, Account account, RegisterStrategyCommand cmd, ResolvedCreation resolved) {
        UUID accountId = account.id();
        boolean dca = cmd.type() == StrategyType.DCA;
        if (dca && cmd.dca() == null) {
            throw new FieldRejectedException("dca", "DCA 설정은 필수입니다.");
        }
        // 등록 규칙은 전부 신규 — ruleKey를 발급하고 클라이언트가 보낸 키는 400(현재 버전이 없으므로 존재하지 않는 규칙)
        DcaStrategyConfig dcaConfig = dca ? FieldRejectedException.attribute("dca", () -> cmd.dca().withAssignedKeys(Set.of())) : null;

        StrategyTicker resolvedTicker = resolved.ticker();
        // 증권사 제한 — 전략 타입이 지원하지 않는 증권사 계좌면 등록 거부(PRIVACY는 Limit VWAP 지원 증권사만)
        if (!cmd.type().capability().supportsBroker(account.broker())) {
            throw new IllegalArgumentException("%s 전략은 이 증권사에서 등록할 수 없습니다.".formatted(cmd.type().getDescription()));
        }
        // 중간부터 시작 입력 검증 (전 전략 공통) — holdings>0이면 avgPrice>0 필수, 음수 거부, 정수 주
        BigDecimal initialHoldings = FieldRejectedException.attribute("initialHoldings",
                () -> BootstrapPosition.validate(cmd.initialHoldings(), cmd.initialAvgPrice()));
        boolean bootstrap = initialHoldings.signum() > 0;
        // 시작예정일 검증 — 기본값 오늘(KST), 과거 거부
        LocalDate scheduledStart = resolveScheduledStart(cmd);

        // 종목 중복 검증 (잔고 초과는 차단하지 않는다 — 시드 미리보기의 가용 예수금 경고로 안내)
        validateUniqueTicker(accountId, resolvedTicker);
        // 사용자 복제본이 없으면 배치가 이 전략을 돌릴 수 없다 — 등록 단계에서 거부
        tradingUserProfilePort.findByUserId(userId)
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + userId));
        // 예수금 — 생략(null)은 0으로 여기서 한 번만 정규화, 전 전략 공통으로 음수 거부·계좌 통화 자릿수 검증
        BigDecimal initialDeposit = FieldRejectedException.attribute("initialDeposit",
                () -> DepositAmounts.normalizeForRegistration(cmd.initialDeposit(), account.broker().getCurrency()));

        // 중간부터 시작 시 시장가(전일종가) 1회 조회 — holdings=0이면 조회 자체를 건너뛰어 기존 동작 보존
        // DCA는 시장가를 조회하지 않고 초기 투입 원가(보유 × 평단)를 start_amount로 둔다
        BigDecimal marketPrice = bootstrap && !dca ? fetchMarketPrice(account, resolvedTicker) : null;
        BigDecimal initialStockValue = !bootstrap ? BigDecimal.ZERO
                : (dca ? cmd.initialAvgPrice() : marketPrice).multiply(initialHoldings).setScale(AccountBalance.LEDGER_SCALE, RoundingMode.HALF_UP);

        // VR 전략 파라미터 해석·검증 — V값은 시장가×보유수량 기준 (비VR은 null)
        VrRegistration vr = cmd.type() == StrategyType.VR ? VrRegistration.resolve(cmd, resolved, initialDeposit, initialStockValue) : null;

        // 연속 사이클 정책을 지원하지 않는 전략(VR·DCA)은 NONE으로 강제하고 나머지는 요청 값을 따른다.
        StrategyCycleSeedType seedType = !cmd.type().capability().supportsCycleSeed()
                ? StrategyCycleSeedType.NONE  // capability.supportsCycleSeed=false는 NONE 강제
                : (cmd.cycleSeedType() != null ? cmd.cycleSeedType() : StrategyCycleSeedType.NONE);

        // 시작금액 = 개장 평가(통계 원금) = 예수금 + 보유 평가(DCA는 원가)
        BigDecimal openAmount = initialDeposit.add(initialStockValue);

        // INFINITE 최소 시드 검증 — 폼이 먼저 막지만 API 직접 호출도 0회차 교착 시드를 만들지 못하게 서버에서도 막는다
        if (cmd.type() == StrategyType.INFINITE) {
            BigDecimal minSeed = minSeedGuard.minSeed(resolvedTicker, resolved.divisionCount(), marketPrice);
            FieldRejectedException.attribute("initialDeposit", () -> InfiniteMinSeedGuard.requireAtLeast(minSeed, openAmount));
        }

        return new StrategyCreationPersister.Draft(
                accountId, cmd.type(), seedType, resolved, vr != null ? vr.ramp() : null, initialDeposit, initialHoldings,
                cmd.initialAvgPrice(), marketPrice, openAmount, openAmount, vr != null ? vr.vrValue() : null, scheduledStart, dcaConfig);
    }

    // 시작예정일 — 기본값 오늘(KST), 과거 거부. 상한 없음
    private LocalDate resolveScheduledStart(RegisterStrategyCommand cmd) {
        LocalDate today = LocalDate.now(clock);
        LocalDate scheduled = cmd.scheduledStartDate() != null ? cmd.scheduledStartDate() : today;
        if (scheduled.isBefore(today)) {
            throw new FieldRejectedException("scheduledStartDate", "시작예정일은 오늘 이후여야 합니다.");
        }
        return scheduled;
    }

    // 중간부터 시작 시 시장가(전일종가) 조회 — startAmount·초기 포지션·VR V값을 동일 기준으로 정합
    // 조회 실패 시 등록 자체가 실패한다 — 공용 시세 실패는 BrokerCallGuard가 IllegalStateException으로 감싸 전파한다(증권사 타입 예외면 그대로 전파해 503/422/429 매핑)
    private BigDecimal fetchMarketPrice(Account account, StrategyTicker ticker) {
        return BrokerCallGuard.wrap("전일종가 조회",
                () -> marketData.requireLatestClose(ticker));
    }

    // 등록 시점에만 런타임 생성 정책을 적용해 기존 전략 흐름과 설정 조회를 분리한다.
    // 전략 타입별 필드 해석은 CycleOrderStrategy와 동일한 capability 패턴(StrategyCreationResolvers)에 위임한다.
    private ResolvedCreation resolveCreationSettings(RegisterStrategyCommand cmd) {
        StrategyCreationSettings settings = strategyCreationPolicyPort.find(cmd.type())
                .orElseThrow(() -> new FieldRejectedException("type", "지원하지 않는 전략 유형입니다."));
        if (!settings.enabled()) {
            throw new FieldRejectedException("type", "비활성화된 전략 유형은 새로 등록할 수 없습니다.");
        }
        StrategyCreationRequest request =
                new StrategyCreationRequest(
                        cmd.ticker(), cmd.divisionCount(), cmd.intervalWeeks(), cmd.bandWidth(), cmd.recurringAmount());
        return creationResolvers.of(cmd.type()).resolve(request, settings);
    }

    // PostgreSQL 위반 메시지에 인덱스 이름이 들어 있다
    private static boolean isActiveTickerIndexViolation(DataIntegrityViolationException e) {
        String msg = e.getMostSpecificCause().getMessage();
        return msg != null && msg.contains(ACTIVE_TICKER_INDEX);
    }

    // 같은 계좌 내 종목 중복 방지 — 종목별 합산 잔고 ↔ 전략 일대일 보장 (DCA도 예외 없음 — 매수·매도는 한 전략의 규칙으로)
    private void validateUniqueTicker(UUID accountId, StrategyTicker ticker) {
        // 보관 전략은 같은 종목 재등록을 막지 않는다
        if (strategyPort.findByAccountIdAndTicker(accountId, ticker).stream().anyMatch(s -> !s.isArchived())) {
            throw new DuplicateStrategyTickerException(); // 409 — 전 전략 유형 공통
        }
    }
}
