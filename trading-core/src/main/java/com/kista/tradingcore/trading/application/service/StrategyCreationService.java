package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.TimeZones;

import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.broker.application.port.output.BrokerPricePort;
import com.kista.tradingcore.broker.application.service.BrokerCallGuard;
import com.kista.tradingcore.matching.domain.model.BootstrapPosition;
import com.kista.tradingcore.matching.domain.model.DcaStrategyConfig;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.shared.kernel.*;
import com.kista.tradingcore.trading.application.port.output.StrategyCreationPolicyPort;
import com.kista.tradingcore.trading.application.port.output.*;
import com.kista.tradingcore.trading.domain.model.*;
import com.kista.tradingcore.trading.domain.creation.StrategyCreationRequest;
import com.kista.tradingcore.trading.domain.creation.StrategyCreationResolver.ResolvedCreation;
import com.kista.tradingcore.trading.domain.creation.StrategyCreationResolvers;
import com.kista.tradingcore.trading.domain.creation.VrRegistration;
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
import java.util.Locale;
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
    private final AccountPort accountPort;
    private final TradingUserProfilePort tradingUserProfilePort; // 사용자 복제본 존재 확인용
    private final BrokerPricePort brokerPricePort;
    private final StrategyCreationPolicyPort strategyCreationPolicyPort;
    private final StrategyCreationResolvers creationResolvers;
    private final StrategyCreationPersister creationPersister; // 저장 5단계 트랜잭션 경계 (self-invocation 회피용 별도 빈)
    private final StrategyStartAmountCalculator startAmountCalculator; // 시작금액 산출(PRIVACY 중간부터 시작 환산 포함)
    private final CycleOrderStrategies cycleStrategies;         // INFINITE 최소 시드 산출(minRequiredDeposit)
    private final StrategyDetailAssembler detailAssembler;      // 응답 조립(조회 경로와 동일 SSOT)

    // 응답은 조회 경로와 같은 StrategyDetailAssembler로 조립한다(UI가 POST 응답을 캐시) — 이미 조회한 계좌의 market 재사용
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

        // 종목 중복 검증 (잔고 초과는 차단하지 않는다 — 시드 미리보기의 가용 예수금 경고로 안내)
        validateUniqueTicker(accountId, resolvedTicker);
        // 사용자 복제본이 없으면 배치가 이 전략을 돌릴 수 없다 — 등록 단계에서 거부
        tradingUserProfilePort.findByUserId(userId)
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + userId));
        BigDecimal initialUsdDeposit = dca ? BigDecimal.ZERO : cmd.initialUsdDeposit(); // DCA는 예수금 시드 없음

        // 중간부터 시작 시 시장가(전일종가) 1회 조회 — holdings=0이면 조회 자체를 건너뛰어 기존 동작 보존
        // DCA는 시장가를 조회하지 않고 초기 투입 원가(보유 × 평단)를 start_amount로 둔다 — 코인은 BrokerPricePort 경로가 없다
        BigDecimal marketPrice = bootstrap && !dca ? fetchMarketPrice(account, resolvedTicker) : null;
        BigDecimal initialStockValue = !bootstrap ? BigDecimal.ZERO
                : (dca ? cmd.initialAvgPrice() : marketPrice).multiply(initialHoldings).setScale(2, RoundingMode.HALF_UP);

        // VR 전략 파라미터 해석·검증 — V값은 시장가×보유수량 기준 (비VR은 null)
        VrRegistration vr = cmd.type() == StrategyType.VR ? VrRegistration.resolve(cmd, resolved, initialStockValue) : null;

        // VR seed type은 NONE으로 고정하고 나머지는 기존 요청 기본 규칙을 유지한다.
        StrategyCycleSeedType seedType = cmd.type() == StrategyType.VR || dca
                ? StrategyCycleSeedType.NONE  // VR·DCA는 NONE 강제 (순환 재등록 불가)
                : (cmd.cycleSeedType() != null ? cmd.cycleSeedType() : StrategyCycleSeedType.NONE);

        // 시작금액 산출 — 예수금 null은 0으로 취급
        BigDecimal depositOrZero = initialUsdDeposit != null ? initialUsdDeposit : BigDecimal.ZERO;
        BigDecimal startAmount = startAmountCalculator.calculate(cmd.type(), depositOrZero, initialHoldings, initialStockValue);

        // INFINITE 최소 시드 검증 — 폼이 먼저 막지만 API 직접 호출도 0회차 교착 시드를 만들지 못하게 서버에서도 막는다
        if (cmd.type() == StrategyType.INFINITE) {
            validateInfiniteMinSeed(account, resolvedTicker, marketPrice, resolved.divisionCount(), startAmount);
        }

        // 저장 5단계(strategy·version·detail·cycle·position)는 별도 빈의 한 트랜잭션 — 중간 실패 시 부분 저장 없이 롤백
        // (증권사 호출은 위에서 이미 끝났으므로 트랜잭션이 HTTP 대기를 물지 않는다)
        Strategy strategy = creationPersister.persist(new StrategyCreationPersister.Draft(
                accountId, cmd.type(), seedType, resolved, vr != null ? vr.ramp() : null, initialUsdDeposit, initialHoldings,
                cmd.initialAvgPrice(), marketPrice, startAmount, vr != null ? vr.vrValue() : null, scheduledStart, dcaConfig));

        log.info("전략 등록: accountId={}, strategyId={}, type={}", accountId, strategy.id(), strategy.type());
        return detailAssembler.toDetail(strategy, account.broker().getMarket());
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

    // INFINITE 최소 시드(전일종가×분할×2) 미만이면 거부 — 전일종가 조회 실패는 검증만 건너뛴다(fail-open, 503 변환 불필요)
    private void validateInfiniteMinSeed(Account account, StrategyTicker ticker, BigDecimal marketPrice,
                                         int divisionCount, BigDecimal startAmount) {
        BigDecimal price = marketPrice != null ? marketPrice
                : BrokerCallGuard.tryGet("최소 시드 검증용 전일종가 조회: ticker=" + ticker,
                        () -> brokerPricePort.getPrevClose(ticker, account.toBrokerRef())).orElse(null);
        if (price == null) return; // 조회 실패 — 검증 건너뜀
        BigDecimal min = cycleStrategies.of(StrategyType.INFINITE).minRequiredDeposit(price, null, divisionCount);
        if (min != null && startAmount.compareTo(min) < 0) {
            // 금액 표기는 kista-ui fmtUsd와 같은 #,##0.00 (JVM 로케일 무관)
            throw new IllegalArgumentException("최소 투자금 $%s 이상이 필요합니다.".formatted(String.format(Locale.US, "%,.2f", min)));
        }
    }

    // 등록 시점에만 런타임 생성 정책을 적용해 기존 전략 흐름과 설정 조회를 분리한다.
    // 전략 타입별 필드 해석은 CycleOrderStrategy와 동일한 capability 패턴(StrategyCreationResolvers)에 위임한다.
    private ResolvedCreation resolveCreationSettings(RegisterStrategyCommand cmd) {
        StrategyCreationSettings settings = strategyCreationPolicyPort.find(cmd.type())
                .orElseThrow(() -> new IllegalArgumentException("등록되지 않은 전략 생성 정책: " + cmd.type()));
        if (!settings.enabled()) {
            throw new IllegalArgumentException("비활성화된 전략 유형은 새로 등록할 수 없습니다: " + cmd.type());
        }
        StrategyCreationRequest request =
                new StrategyCreationRequest(
                        cmd.ticker(), cmd.divisionCount(), cmd.intervalWeeks(), cmd.bandWidth(), cmd.recurringAmount());
        return creationResolvers.of(cmd.type()).resolve(request, settings);
    }

    // 같은 계좌 내 종목 중복 방지 — 종목별 합산 잔고 ↔ 전략 일대일 보장 (DCA도 예외 없음 — 매수·매도는 한 전략의 규칙으로)
    private void validateUniqueTicker(UUID accountId, StrategyTicker ticker) {
        if (!strategyPort.findByAccountIdAndTicker(accountId, ticker).isEmpty()) {
            throw new DuplicateStrategyTickerException(); // 409 — 전 전략 유형 공통
        }
    }
}
