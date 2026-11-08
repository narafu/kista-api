package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Currency;
import java.util.NoSuchElementException;
import com.kista.tradingcore.trading.domain.creation.VrRampParams;
import com.kista.tradingcore.trading.domain.creation.VrRampValidator;
import com.kista.tradingcore.trading.application.event.NewCycleStartedEvent;
import com.kista.tradingcore.trading.application.event.TradingErrorEvent;
import com.kista.tradingcore.broker.application.service.BrokerCallGuard;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.DstInfo;
import com.kista.tradingcore.trading.domain.model.ReconfigureVrCommand;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.domain.model.StrategyCycleVrDetail;
import com.kista.tradingcore.matching.domain.model.StrategyVrDetail;
import com.kista.tradingcore.trading.application.usecase.VrReconfigureUseCase;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyCycleVrPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.service.support.OwnedStrategy;
import com.kista.tradingcore.trading.application.port.output.StrategyVrDetailPort;
import com.kista.tradingcore.broker.application.port.output.BrokerPricePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.kista.tradingcore.trading.application.event.StrategyCapitalChangedEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

// VR 전략 운영 중 재설정 — 램프 파라미터 교체(새 버전 발급) + 강제 롤오버(현재 사이클 종료→새 사이클) + 선택적 투자금 추가
// 램프 시계(경과 주수)는 전략 최초 사이클 startDate 기준으로 고정 — 재설정해도 리셋하지 않음(VrCycleRolloverService와 동일 정책)
// package-private — application/service/trading 패키지 전용 (CycleSnapshotCreator·OrderCancelService 재사용을 위해 같은 패키지 위치)
@Slf4j
@Service
@RequiredArgsConstructor
class VrReconfigureService implements VrReconfigureUseCase {

    private final StrategyPort strategyPort;
    private final AccountPort accountPort;
    private final StrategyCyclePort strategyCyclePort;
    private final StrategyVrDetailPort strategyVrDetailPort;
    private final StrategyCycleVrPort strategyCycleVrPort;
    private final CyclePositionPort cyclePositionPort;
    private final BrokerPricePort brokerPricePort;
    private final CycleSnapshotCreator cycleSnapshotCreator; // 버전 교체 + 사이클 종료 + 새 사이클 원자 저장
    private final OrderCancelService orderCancelService;     // 재설정 전 미체결 주문 정리
    private final ApplicationEventPublisher eventPublisher;   // 새 사이클 시작 이벤트 발행 + 사용자 알림 실패 시 관리자 알림 이벤트
    private final Clock clock; // 현재 시각 기준 — 테스트에서 Clock.fixed 주입

    @Override
    public void reconfigure(UUID strategyId, UUID requesterId, ReconfigureVrCommand cmd) {
        OwnedStrategy owned = OwnedStrategy.load(strategyPort, accountPort, strategyId, requesterId);
        Strategy strategy = owned.strategy();
        Account account = owned.account();
        strategy.requireNotArchived();
        if (!strategy.isVr()) {
            throw new IllegalArgumentException("VR 전략만 재설정할 수 있습니다.");
        }

        // 현재 사이클·버전 상세·사이클 상세·최신 포지션 조회 (모두 조회 전용 — 검증을 외부 호출보다 먼저 끝내기 위해 앞으로 이동)
        StrategyCycle currentCycle = strategyCyclePort.requireLatestByStrategyId(strategyId);
        // 다음 거래일 기준 매매 시작 여부 — 시작 전이면 새 사이클 없이 첫 사이클을 in-place 갱신한다
        LocalDate today = DstInfo.nextTradeDate(ZonedDateTime.now(clock));
        boolean beforeStart = !currentCycle.hasStartedBy(today);
        StrategyVrDetail currentDetail = strategyVrDetailPort.findByStrategyVersionId(currentCycle.strategyVersionId())
                .orElseThrow(() -> new NoSuchElementException("VR 전략 버전 상세 없음: strategyId=" + strategyId));
        StrategyCycleVrDetail currentCycleVr = strategyCycleVrPort.findByCycleId(currentCycle.id())
                .orElseThrow(() -> new NoSuchElementException("VR 사이클 상세 없음: cycleId=" + currentCycle.id()));
        CyclePosition latestPosition = cyclePositionPort.findLatestOneByStrategyId(strategyId)
                .orElseThrow(() -> new NoSuchElementException("포지션 이력 없음: strategyId=" + strategyId));

        // 파라미터 상속 — 미지정 필드는 현재 활성 버전 값 유지
        BigDecimal bandWidth = cmd.bandWidth() != null ? cmd.bandWidth() : currentDetail.bandWidth();
        int intervalWeeks = cmd.intervalWeeks() != null ? cmd.intervalWeeks() : currentDetail.intervalWeeks();
        int recurringAmount = cmd.recurringAmount() != null ? cmd.recurringAmount() : currentDetail.recurringAmount();

        // 램프 파라미터 + 투자금 추가 형태(주식 수·단가·예수금 부호) 검증 — 외부 브로커 호출(가격 조회·주문 취소) 이전에 전부 완료
        VrRampParams ramp = cmd.vrRamp().inheritFrom(currentDetail);
        ramp.validate(intervalWeeks, bandWidth);

        // 투자금 추가/인출 반영 (수량·예수금) — 결정 9: 순수 파라미터 수정=V 이월/보유량 불변. 현재가 불필요(가격 조회 전 계산 가능)
        AccountBalance postBalance = applyCapitalAdjustment(cmd, latestPosition);

        // 현재가 조회 — 수량 주입 시 V 증분·holdings 승계 스냅샷 종가 기준 (구조적 검증 통과 후에만 호출, 실패 시 불필요한 API 호출 방지)
        BigDecimal currentPrice = BrokerCallGuard.wrap("현재가 조회",
                () -> brokerPricePort.getPrice(strategy.ticker(), account.toBrokerRef()));
        BigDecimal newValue = computeNewValue(cmd, currentCycleVr, currentPrice);

        // 인출식(recurringAmount<0) 최소자산 검증 — 등록 시점(VrRegistration)과 동일 규칙,
        // 재설정으로 recurringAmount를 인출식으로 바꾸는 경우도 동일하게 검증되도록 재적용
        // 재설정은 override 개념이 없어 gate·required 양쪽에 같은 값을 넘긴다
        BigDecimal totalAssets = newValue.add(postBalance.usdDeposit());
        VrRampValidator.validateWithdrawalSufficiency(recurringAmount, intervalWeeks, totalAssets, totalAssets);

        // 램프 시계 — 전략 최초 사이클 startDate 기준 경과 주수 (재설정해도 리셋하지 않음)
        // 최초 사이클이 시작 전이면 음수가 되므로 0으로 보정 — 시작 전 현재 사이클이 최초 사이클이 아닐 수도 있어(시작 후 재설정이 만든 당일 사이클) 시작 전 경로도 같은 계산을 쓴다
        LocalDate firstStart = strategyCyclePort.findFirstByStrategyId(strategyId)
                .orElseThrow(() -> new NoSuchElementException("VR 최초 사이클 없음: strategyId=" + strategyId))
                .startDate();
        long weeks = Math.max(0, ChronoUnit.WEEKS.between(firstStart, today));

        // 미체결 주문 전체 취소(best-effort) — 위 검증을 모두 통과한 뒤에만 호출 (독립 트랜잭션이라 이후 실패해도 롤백 불가하므로 최대한 뒤로 미룸)
        orderCancelService.cancelByCycle(strategyId, requesterId);

        // 새 버전 발급 + (시작 전) 현재 사이클 in-place 갱신 / (시작 후) 강제 롤오버(사이클 종료 → 새 사이클) 원자 처리
        var reconfiguration = new CycleSnapshotCreator.VrReconfiguration(
                strategyId, currentCycle.id(), intervalWeeks, bandWidth, recurringAmount, ramp,
                postBalance, currentPrice, newValue, weeks);
        if (beforeStart) {
            cycleSnapshotCreator.reconfigureVrCycleBeforeStart(reconfiguration);
        } else {
            cycleSnapshotCreator.reconfigureVrCycle(reconfiguration);
        }

        // stats 캐시 무효화 — @EventListener라 동기 실행되며, 트랜잭션 동기화가 활성이면 afterCommit에 아니면 즉시 비운다
        eventPublisher.publishEvent(new StrategyCapitalChangedEvent(requesterId));

        // 시작 전 재설정은 새 사이클이 없으므로 새 사이클 시작 알림을 보내지 않는다
        if (beforeStart) {
            return;
        }

        // 사용자 알림 이벤트 발행 — 실패해도 재설정 자체는 이미 완료된 상태이므로 로그만 남기고 무시
        // 이 클래스/메서드가 비-트랜잭션이라 publishEvent가 리스너를 즉시 동기 실행하므로 try/catch가 유효함
        // (향후 @Transactional을 붙이면 AFTER_COMMIT 리스너가 지연 실행되어 이 try/catch가 무력화됨에 주의)
        try {
            eventPublisher.publishEvent(new NewCycleStartedEvent(requesterId, account.id(), account.nickname(),
                    strategy.type(), strategy.ticker(), postBalance.usdDeposit()));
        } catch (Exception e) {
            log.warn("[strategyId={}] VR 재설정 알림 실패: {}", strategyId, e.getMessage());
            eventPublisher.publishEvent(new TradingErrorEvent(null, e.getMessage()));
        }
    }

    // 투자금 추가/인출 계산 — injectShares>0: holdings+=N, 평단가 가중평균, V+=N×현재가 / injectDeposit: usdDeposit+=X
    // withdrawShares>0: holdings-=N(보유량 초과 불가, 평단가는 그대로 유지 — 잔여 수량의 원가는 불변) / withdrawDeposit: usdDeposit-=X(보유 예수금 초과 불가)
    // 인출을 먼저 반영한 뒤(잔여 수량 기준) 주입의 가중평균을 계산 — 같은 요청에서 인출+주입이 함께 와도 순서가 명확하도록
    // 아무것도 없으면 holdings·avgPrice·usdDeposit 그대로 이월(순수 파라미터 수정)
    private AccountBalance applyCapitalAdjustment(ReconfigureVrCommand cmd, CyclePosition latestPosition) {
        int injectShares = cmd.injectShares() != null ? cmd.injectShares() : 0;
        if (injectShares < 0) {
            throw new IllegalArgumentException("투자금 추가 주식 수는 0 이상이어야 합니다.");
        }
        BigDecimal injectDeposit = cmd.injectDeposit() != null ? cmd.injectDeposit() : BigDecimal.ZERO;
        if (injectDeposit.signum() < 0) {
            throw new IllegalArgumentException("투자금 추가 예수금은 0 이상이어야 합니다.");
        }
        int withdrawShares = cmd.withdrawShares() != null ? cmd.withdrawShares() : 0;
        if (withdrawShares < 0) {
            throw new IllegalArgumentException("투자금 인출 주식 수는 0 이상이어야 합니다.");
        }
        if (withdrawShares > latestPosition.toBalance().wholeShares()) {
            throw new IllegalArgumentException(
                    "투자금 인출 주식 수는 보유 수량(" + latestPosition.holdings() + ")을 초과할 수 없습니다.");
        }
        BigDecimal withdrawDeposit = cmd.withdrawDeposit() != null ? cmd.withdrawDeposit() : BigDecimal.ZERO;
        if (withdrawDeposit.signum() < 0) {
            throw new IllegalArgumentException("투자금 인출 예수금은 0 이상이어야 합니다.");
        }
        if (withdrawDeposit.compareTo(latestPosition.usdDeposit()) > 0) {
            throw new IllegalArgumentException(
                    "투자금 인출 예수금은 보유 예수금(" + latestPosition.usdDeposit() + ")을 초과할 수 없습니다.");
        }

        int holdingsAfterWithdrawal = latestPosition.toBalance().wholeShares() - withdrawShares; // VR은 주식 — 정수 수량
        int newHoldings = holdingsAfterWithdrawal + injectShares;
        BigDecimal newAvgPrice;
        if (injectShares > 0) {
            if (cmd.injectSharePrice() == null || cmd.injectSharePrice().signum() <= 0) {
                throw new IllegalArgumentException("투자금 추가 주식의 매수단가는 0보다 커야 합니다.");
            }
            BigDecimal existingCost = latestPosition.avgPrice() != null
                    ? latestPosition.avgPrice().multiply(BigDecimal.valueOf(holdingsAfterWithdrawal))
                    : BigDecimal.ZERO;
            BigDecimal injectedCost = cmd.injectSharePrice().multiply(BigDecimal.valueOf(injectShares));
            newAvgPrice = newHoldings == 0
                    ? null
                    : existingCost.add(injectedCost).divide(BigDecimal.valueOf(newHoldings), AccountBalance.LEDGER_SCALE, RoundingMode.HALF_UP);
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
                .setScale(Currency.USD.amountScale(), RoundingMode.HALF_UP);
    }

}
