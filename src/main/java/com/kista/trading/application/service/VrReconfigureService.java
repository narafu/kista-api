package com.kista.trading.application.service;

import com.kista.trading.application.event.NewCycleStartedEvent;
import com.kista.trading.application.event.TradingErrorEvent;
import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.broker.application.service.BrokerCallGuard;
import com.kista.common.CycleLookups;
import com.kista.domain.model.account.Account;
import com.kista.trading.domain.model.AccountBalance;
import com.kista.trading.domain.model.CyclePosition;
import com.kista.trading.domain.model.DstInfo;
import com.kista.trading.domain.model.ReconfigureVrCommand;
import com.kista.domain.model.strategy.Strategy;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.trading.domain.model.StrategyCycleVrDetail;
import com.kista.domain.model.strategy.StrategyDetail;
import com.kista.domain.model.strategy.StrategyVrDetail;
import com.kista.user.domain.model.User;
import com.kista.application.usecase.StrategyUseCase;
import com.kista.trading.application.usecase.VrReconfigureUseCase;
import com.kista.application.port.output.AccountPort;
import com.kista.trading.application.port.output.CyclePositionPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.trading.application.port.output.StrategyCycleVrPort;
import com.kista.application.port.output.StrategyPort;
import com.kista.application.port.output.StrategyVrDetailPort;
import com.kista.user.application.port.output.UserPort;
import com.kista.broker.application.port.output.BrokerPricePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

// VR 전략 운영 중 재설정 — 램프 파라미터 교체(새 버전 발급) + 강제 롤오버(현재 사이클 종료→새 사이클) + 선택적 자본 주입
// 램프 시계(경과 주수)는 전략 최초 사이클 startDate 기준으로 고정 — 재설정해도 리셋하지 않음(VrCycleRolloverService와 동일 정책)
// package-private — application/service/trading 패키지 전용 (CycleSnapshotCreator·OrderCancelService 재사용을 위해 같은 패키지 위치)
@Slf4j
@Service
@RequiredArgsConstructor
class VrReconfigureService implements VrReconfigureUseCase {

    private final StrategyPort strategyPort;
    private final AccountPort accountPort;
    private final UserPort userPort;
    private final StrategyCyclePort strategyCyclePort;
    private final StrategyVrDetailPort strategyVrDetailPort;
    private final StrategyCycleVrPort strategyCycleVrPort;
    private final CyclePositionPort cyclePositionPort;
    private final BrokerAdapterRegistry registry;
    private final CycleSnapshotCreator cycleSnapshotCreator; // 버전 교체 + 사이클 종료 + 새 사이클 원자 저장
    private final OrderCancelService orderCancelService;     // 재설정 전 미체결 주문 정리
    private final ApplicationEventPublisher eventPublisher;   // 새 사이클 시작 이벤트 발행 + 사용자 알림 실패 시 관리자 알림 이벤트
    private final StrategyUseCase strategyUseCase; // 재설정 후 최신 StrategyDetail 응답 조립에 재사용

    @Override
    public StrategyDetail reconfigure(UUID strategyId, UUID requesterId, ReconfigureVrCommand cmd) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        Account account = accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        if (!strategy.isVr()) {
            throw new IllegalArgumentException("VR 전략만 재설정할 수 있습니다: " + strategyId);
        }

        // 현재 사이클·버전 상세·사이클 상세·최신 포지션 조회 (모두 조회 전용 — 검증을 외부 호출보다 먼저 끝내기 위해 앞으로 이동)
        StrategyCycle currentCycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategyId);
        StrategyVrDetail currentDetail = strategyVrDetailPort.findByStrategyVersionId(currentCycle.strategyVersionId())
                .orElseThrow(() -> new IllegalStateException("VR 전략 버전 상세 없음: strategyId=" + strategyId));
        StrategyCycleVrDetail currentCycleVr = strategyCycleVrPort.findByCycleId(currentCycle.id())
                .orElseThrow(() -> new IllegalStateException("VR 사이클 상세 없음: cycleId=" + currentCycle.id()));
        CyclePosition latestPosition = cyclePositionPort.findLatestOneByStrategyId(strategyId)
                .orElseThrow(() -> new IllegalStateException("포지션 이력 없음: strategyId=" + strategyId));

        // 파라미터 상속 — 미지정 필드는 현재 활성 버전 값 유지
        BigDecimal bandWidth = cmd.bandWidth() != null ? cmd.bandWidth() : currentDetail.bandWidth();
        int intervalWeeks = cmd.intervalWeeks() != null ? cmd.intervalWeeks() : currentDetail.intervalWeeks();
        int recurringAmount = cmd.recurringAmount() != null ? cmd.recurringAmount() : currentDetail.recurringAmount();
        int initialGradient = cmd.initialGradient() != null ? cmd.initialGradient() : currentDetail.initialGradient();
        int gGraceWeeks = cmd.gGraceWeeks() != null ? cmd.gGraceWeeks() : currentDetail.gGraceWeeks();
        int gStepWeeks = cmd.gStepWeeks() != null ? cmd.gStepWeeks() : currentDetail.gStepWeeks();
        int gMax = cmd.gMax() != null ? cmd.gMax() : currentDetail.gMax();
        BigDecimal initialPoolLimitRate = cmd.initialPoolLimitRate() != null
                ? cmd.initialPoolLimitRate() : currentDetail.initialPoolLimitRate();
        int pGraceWeeks = cmd.pGraceWeeks() != null ? cmd.pGraceWeeks() : currentDetail.pGraceWeeks();
        int pStepWeeks = cmd.pStepWeeks() != null ? cmd.pStepWeeks() : currentDetail.pStepWeeks();
        BigDecimal poolLimitFloor = cmd.poolLimitFloor() != null ? cmd.poolLimitFloor() : currentDetail.poolLimitFloor();

        // 램프 파라미터 + 자본 주입 형태(주식 수·단가·예수금 부호) 검증 — 외부 브로커 호출(가격 조회·주문 취소) 이전에 전부 완료
        validateRampParams(intervalWeeks, bandWidth, initialGradient, gGraceWeeks, gStepWeeks, gMax,
                initialPoolLimitRate, pGraceWeeks, pStepWeeks, poolLimitFloor);

        // 자본 주입/인출 반영 (수량·예수금) — 결정 9: 순수 파라미터 수정=V 이월/보유량 불변. 현재가 불필요(가격 조회 전 계산 가능)
        AccountBalance postBalance = applyCapitalAdjustment(cmd, latestPosition);

        // 현재가 조회 — 수량 주입 시 V 증분·holdings 승계 스냅샷 종가 기준 (구조적 검증 통과 후에만 호출, 실패 시 불필요한 API 호출 방지)
        BigDecimal currentPrice = BrokerCallGuard.wrap("현재가 조회",
                () -> registry.require(account, BrokerPricePort.class).getPrice(strategy.ticker(), account));
        BigDecimal newValue = computeNewValue(cmd, currentCycleVr, currentPrice);

        // 인출식(recurringAmount<0) 최소자산 검증 — 등록 시점(StrategyService.validateVrCommand)과 동일 규칙,
        // 재설정으로 recurringAmount를 인출식으로 바꾸는 경우도 동일하게 검증되도록 재적용
        validateWithdrawalSufficiency(recurringAmount, intervalWeeks, newValue.add(postBalance.usdDeposit()));

        // 램프 시계 — 전략 최초 사이클 startDate 기준 경과 주수 (재설정해도 리셋하지 않음)
        LocalDate firstStart = strategyCyclePort.findFirstByStrategyId(strategyId)
                .orElseThrow(() -> new IllegalStateException("VR 최초 사이클 없음: strategyId=" + strategyId))
                .startDate();
        LocalDate today = DstInfo.nextTradeDate();
        long weeks = ChronoUnit.WEEKS.between(firstStart, today);

        // 미체결 주문 전체 취소(best-effort) — 위 검증을 모두 통과한 뒤에만 호출 (독립 트랜잭션이라 이후 실패해도 롤백 불가하므로 최대한 뒤로 미룸)
        orderCancelService.cancelByCycle(strategyId, requesterId);

        // 새 버전 발급 + 강제 롤오버(사이클 종료 → 새 사이클) 원자 처리
        cycleSnapshotCreator.reconfigureVrCycle(strategyId, currentCycle.id(), today,
                intervalWeeks, bandWidth, recurringAmount,
                initialGradient, gGraceWeeks, gStepWeeks, gMax,
                initialPoolLimitRate, pGraceWeeks, pStepWeeks, poolLimitFloor,
                postBalance, currentPrice, newValue, weeks);

        // 사용자 알림 이벤트 발행 — 실패해도 재설정 자체는 이미 완료된 상태이므로 로그만 남기고 무시
        // 이 클래스/메서드가 비-트랜잭션이라 publishEvent가 fallbackExecution 리스너를 즉시 동기 실행하므로 try/catch가 유효함
        // (향후 @Transactional을 붙이면 리스너가 AFTER_COMMIT으로 지연 실행되어 이 try/catch가 무력화됨에 주의)
        try {
            User user = userPort.findByIdOrThrow(requesterId);
            eventPublisher.publishEvent(new NewCycleStartedEvent(user.id(), account.id(), strategy, postBalance.usdDeposit()));
        } catch (Exception e) {
            log.warn("[strategyId={}] VR 재설정 알림 실패: {}", strategyId, e.getMessage());
            eventPublisher.publishEvent(new TradingErrorEvent(null, e.getMessage()));
        }

        return strategyUseCase.getById(strategyId, requesterId);
    }

    // 자본 주입/인출 계산 — injectShares>0: holdings+=N, 평단가 가중평균, V+=N×현재가 / injectDeposit: usdDeposit+=X
    // withdrawShares>0: holdings-=N(보유량 초과 불가, 평단가는 그대로 유지 — 잔여 수량의 원가는 불변) / withdrawDeposit: usdDeposit-=X(보유 예수금 초과 불가)
    // 인출을 먼저 반영한 뒤(잔여 수량 기준) 주입의 가중평균을 계산 — 같은 요청에서 인출+주입이 함께 와도 순서가 명확하도록
    // 아무것도 없으면 holdings·avgPrice·usdDeposit 그대로 이월(순수 파라미터 수정)
    private AccountBalance applyCapitalAdjustment(ReconfigureVrCommand cmd, CyclePosition latestPosition) {
        int injectShares = cmd.injectShares() != null ? cmd.injectShares() : 0;
        if (injectShares < 0) {
            throw new IllegalArgumentException("주입 주식 수(injectShares)는 0 이상이어야 합니다");
        }
        BigDecimal injectDeposit = cmd.injectDeposit() != null ? cmd.injectDeposit() : BigDecimal.ZERO;
        if (injectDeposit.signum() < 0) {
            throw new IllegalArgumentException("주입 예수금(injectDeposit)은 0 이상이어야 합니다");
        }
        int withdrawShares = cmd.withdrawShares() != null ? cmd.withdrawShares() : 0;
        if (withdrawShares < 0) {
            throw new IllegalArgumentException("인출 주식 수(withdrawShares)는 0 이상이어야 합니다");
        }
        if (withdrawShares > latestPosition.holdings()) {
            throw new IllegalArgumentException(
                    "인출 주식 수(withdrawShares)는 보유 수량(" + latestPosition.holdings() + ")을 초과할 수 없습니다");
        }
        BigDecimal withdrawDeposit = cmd.withdrawDeposit() != null ? cmd.withdrawDeposit() : BigDecimal.ZERO;
        if (withdrawDeposit.signum() < 0) {
            throw new IllegalArgumentException("인출 예수금(withdrawDeposit)은 0 이상이어야 합니다");
        }
        if (withdrawDeposit.compareTo(latestPosition.usdDeposit()) > 0) {
            throw new IllegalArgumentException(
                    "인출 예수금(withdrawDeposit)은 보유 예수금(" + latestPosition.usdDeposit() + ")을 초과할 수 없습니다");
        }

        int holdingsAfterWithdrawal = latestPosition.holdings() - withdrawShares;
        int newHoldings = holdingsAfterWithdrawal + injectShares;
        BigDecimal newAvgPrice;
        if (injectShares > 0) {
            if (cmd.injectSharePrice() == null || cmd.injectSharePrice().signum() <= 0) {
                throw new IllegalArgumentException("주식 주입 시 매수단가(injectSharePrice)는 0보다 커야 합니다");
            }
            BigDecimal existingCost = latestPosition.avgPrice() != null
                    ? latestPosition.avgPrice().multiply(BigDecimal.valueOf(holdingsAfterWithdrawal))
                    : BigDecimal.ZERO;
            BigDecimal injectedCost = cmd.injectSharePrice().multiply(BigDecimal.valueOf(injectShares));
            newAvgPrice = newHoldings == 0
                    ? null
                    : existingCost.add(injectedCost).divide(BigDecimal.valueOf(newHoldings), 4, RoundingMode.HALF_UP);
        } else {
            newAvgPrice = newHoldings == 0 ? null : latestPosition.avgPrice();
        }

        BigDecimal newUsdDeposit = latestPosition.usdDeposit().add(injectDeposit).subtract(withdrawDeposit);
        return new AccountBalance(newHoldings, newAvgPrice, newUsdDeposit);
    }

    // V 증분 — 수량 주입 시 V += N×현재가, 없으면 V 이월(순수 파라미터 수정)
    private BigDecimal computeNewValue(ReconfigureVrCommand cmd, StrategyCycleVrDetail currentCycleVr, BigDecimal currentPrice) {
        int injectShares = cmd.injectShares() != null ? cmd.injectShares() : 0;
        int withdrawShares = cmd.withdrawShares() != null ? cmd.withdrawShares() : 0;
        int netShares = injectShares - withdrawShares;
        if (netShares == 0) {
            return currentCycleVr.value();
        }
        return currentCycleVr.value()
                .add(BigDecimal.valueOf(netShares).multiply(currentPrice))
                .setScale(2, RoundingMode.HALF_UP);
    }

    // StrategyService.validateVrCommand의 램프 검증 규칙과 동일 정책 — 패키지가 달라(strategy↔trading) private 메서드 재사용 불가하므로 재구현
    private void validateRampParams(int intervalWeeks, BigDecimal bandWidth,
                                     int initialGradient, int gGraceWeeks, int gStepWeeks, int gMax,
                                     BigDecimal initialPoolLimitRate, int pGraceWeeks, int pStepWeeks, BigDecimal poolLimitFloor) {
        if (intervalWeeks <= 0) {
            throw new IllegalArgumentException("VR 전략의 리밸런싱 주기(intervalWeeks)는 1 이상이어야 합니다");
        }
        if (bandWidth == null || bandWidth.signum() <= 0) {
            throw new IllegalArgumentException("VR 전략의 밴드 폭(bandWidth)은 0보다 커야 합니다");
        }
        if (initialGradient <= 0) {
            throw new IllegalArgumentException("VR 전략의 초기 gradient(initialGradient)는 0보다 커야 합니다");
        }
        if (gStepWeeks < 0) {
            throw new IllegalArgumentException("VR 전략의 gradient 스텝 주기(gStepWeeks)는 0 이상이어야 합니다");
        }
        if (gGraceWeeks < 0) {
            throw new IllegalArgumentException("VR 전략의 gradient 유예 주수(gGraceWeeks)는 0 이상이어야 합니다");
        }
        // gStepWeeks=0은 gradient 램프 비활성화 — 이때 gMax는 계산에 사용되지 않으므로 0을 허용
        if (gStepWeeks > 0 && gMax < initialGradient) {
            throw new IllegalArgumentException("VR 전략의 gradient 상한(gMax)은 initialGradient 이상이어야 합니다");
        }
        if (pStepWeeks < 0) {
            throw new IllegalArgumentException("VR 전략의 poolLimitRate 스텝 주기(pStepWeeks)는 0 이상이어야 합니다");
        }
        if (pGraceWeeks < 0) {
            throw new IllegalArgumentException("VR 전략의 poolLimitRate 유예 주수(pGraceWeeks)는 0 이상이어야 합니다");
        }
        if (initialPoolLimitRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("VR 전략의 초기 poolLimitRate(initialPoolLimitRate)는 1 이하여야 합니다");
        }
        // poolLimitFloor 범위는 pStepWeeks와 무관하게 항상 검증 — DB CHECK(pool_limit_floor <= initial_pool_limit_rate)와
        // 어긋나는 값이 여기서 걸러지지 않으면 INSERT 시 매핑되지 않은 DataIntegrityViolationException → 500으로 새는 것을 방지
        if (poolLimitFloor == null || poolLimitFloor.signum() < 0 || poolLimitFloor.compareTo(initialPoolLimitRate) > 0) {
            throw new IllegalArgumentException(
                    "VR 전략의 poolLimitRate 하한(poolLimitFloor)은 0 이상 initialPoolLimitRate 이하여야 합니다");
        }
        // pStepWeeks=0은 poolLimitRate 램프 비활성화(항상 initialPoolLimitRate 유지) — 이때는 poolLimitFloor=0도 허용
        if (pStepWeeks > 0 && poolLimitFloor.signum() <= 0) {
            throw new IllegalArgumentException("VR 전략의 poolLimitRate 램프는 poolLimitFloor가 0보다 커야 합니다");
        }
    }

    // 인출식(recurringAmount<0) 최소자산 검증 — StrategyService.validateVrCommand와 동일 규칙(constraints.md "VR 공식").
    // 재설정으로 recurringAmount를 새로 인출식으로 바꾸거나 인출액을 키우는 경우에도 등록 시점과 동일하게 재검증한다.
    private void validateWithdrawalSufficiency(int recurringAmount, int intervalWeeks, BigDecimal totalAssets) {
        if (recurringAmount <= 0 && totalAssets.signum() <= 0) {
            throw new IllegalArgumentException("VR 거치식/인출식은 V값과 예수금 합이 0보다 커야 합니다");
        }
        if (recurringAmount < 0) {
            BigDecimal required = BigDecimal.valueOf(Math.abs((long) recurringAmount))
                    .multiply(BigDecimal.valueOf(100))
                    .multiply(BigDecimal.valueOf(4))
                    .divide(BigDecimal.valueOf(intervalWeeks), 2, RoundingMode.HALF_UP);
            if (totalAssets.compareTo(required) < 0) {
                throw new IllegalArgumentException("인출식 VR 전략의 자산은 " + required + " 이상이어야 합니다");
            }
        }
    }
}
