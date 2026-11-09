package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.trading.application.event.StrategyCapitalChangedEvent;
import com.kista.tradingcore.trading.application.port.output.CycleCapitalEventPort;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.TradingBatchRunPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.service.support.OwnedStrategy;
import com.kista.tradingcore.trading.domain.model.CycleCapitalEvent;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.DepositAmounts;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.domain.model.StrategyDetail;
import com.kista.tradingcore.trading.domain.model.UpdateStrategyCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

// StrategyService.update의 DB 쓰기 전용 — 전일종가 HTTP(최소 시드 검사)를 트랜잭션 밖에 두기 위해 분리 (StrategyCreationPersister·StrategyStateWriter와 같은 패턴)
// 메서드는 반드시 public — Spring 기본 proxy 모드는 non-public @Transactional을 무시(no-op)함
@Slf4j
@Service
@RequiredArgsConstructor
class StrategyUpdateWriter {

    private final StrategyPort strategyPort;
    private final AccountPort accountPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final CycleCapitalEventPort cycleCapitalEventPort; // 원장 차액 이력 기록
    private final StrategyDetailAssembler detailAssembler;
    private final ApplicationEventPublisher eventPublisher;    // stats 캐시 무효화 이벤트 발행
    private final Clock clock;                                 // 이력 발생 시각·마감 리포트 창 판정 기준
    private final TradingBatchRunPort batchRunPort;            // 마감 리포트 마커 조회(리포트 창 거절)
    private final OrderPort orderPort;                         // 오늘 주문 조회(리포트 대상 판정)

    private static final String ENDED_CYCLE = "종료된 사이클은 예수금을 수정할 수 없습니다. 재개한 뒤 수정해야 합니다.";

    @Transactional
    public StrategyDetail update(UUID strategyId, UUID requesterId, UpdateStrategyCommand cmd, BigDecimal minSeed) {
        OwnedStrategy owned = OwnedStrategy.load(strategyPort, accountPort, strategyId, requesterId);
        Strategy strategy = owned.strategy();
        strategy.requireNotArchived();
        if (strategy.isVr() && cmd.newSeed() != null) {
            throw new IllegalArgumentException("VR 전략의 시작 예수금은 일반 수정으로 변경할 수 없습니다. VR 재설정을 이용해야 합니다.");
        }
        if (strategy.isDca() && cmd.newSeed() != null) {
            throw new IllegalArgumentException("DCA 전략의 예수금은 일반 수정으로 변경할 수 없습니다. 투자금 추가·인출을 이용해야 합니다.");
        }
        // 연속 사이클 정책 미지원 전략(VR·DCA)은 NONE으로 고정된다
        if (!strategy.supportsCycleSeed() && cmd.cycleSeedType() != null && cmd.cycleSeedType() != StrategyCycleSeedType.NONE) {
            throw new IllegalArgumentException(strategy.type().getDescription() + " 전략의 연속 사이클 정책은 변경할 수 없습니다.");
        }

        StrategyCycleSeedType seedType = cmd.cycleSeedType() != null
                ? cmd.cycleSeedType()
                : strategy.cycleSeedType();
        Strategy updated = strategy.withCycleSeedType(seedType);
        // cycleSeedType 컬럼만 조건부 갱신 — save()는 status까지 덮어써 동시 보관(ARCHIVED)을 되돌릴 수 있다
        if (!strategyPort.updateCycleSeedTypeUnlessArchived(strategyId, seedType)) {
            throw new IllegalStateException("보관된 전략은 변경할 수 없습니다."); // 사전 검사 이후 동시 보관된 경합
        }

        if (cmd.newSeed() != null) {
            updateSeed(owned, cmd.newSeed(), minSeed);
            eventPublisher.publishEvent(new StrategyCapitalChangedEvent(requesterId));
        }

        log.info("[strategyId={}] 전략 수정: cycleSeedType={}", strategyId, seedType);
        return detailAssembler.toDetail(updated, owned.account().broker().getMarket());
    }

    // 시드 입력 검증 — 0 초과, 계좌 통화 자릿수 이하 (HTTP 전 선검증에도 쓴다)
    static void requireValidSeed(BigDecimal newSeed, Currency currency) {
        if (newSeed.signum() <= 0) {
            throw new IllegalArgumentException("예수금은 0보다 커야 합니다.");
        }
        DepositAmounts.requireScale(newSeed, currency);
    }

    // 시드 수정: holdings=0 시작점에서만 허용 — 개장 평가(openAmount)는 그대로 두고 startAmount·최신 포지션 예수금을 in-place 보정하며 차액을 RESET 이력으로 남긴다
    private void updateSeed(OwnedStrategy owned, BigDecimal newSeed, BigDecimal minSeed) {
        UUID strategyId = owned.strategy().id();
        requireValidSeed(newSeed, owned.currency());
        StrategyCycle first = strategyCyclePort.requireLatestByStrategyId(strategyId);
        if (first.isEnded()) {
            throw new IllegalStateException(ENDED_CYCLE); // 투자금 추가·인출의 종료 사이클 거절과 같은 예외
        }
        // 사이클 행 잠금 — 마감 리포트의 markEnded·수동 체결 보정과 직렬화, 잠금 후 재조회
        strategyCyclePort.lockForUpdate(first.id());
        StrategyCycle cycle = strategyCyclePort.requireLatestByStrategyId(strategyId);
        if (cycle.isEnded() || !cycle.id().equals(first.id())) {
            throw new IllegalStateException("진행 중인 사이클이 바뀌어 예수금을 수정할 수 없습니다.");
        }
        // 마감 리포트 창에는 in-place 예수금 변경이 리포트 append에 덮어써진다(코인은 리포트 없음)
        if (!owned.crypto() && CloseReportScope.inReportWindow(owned.strategy(), clock, batchRunPort, orderPort)) {
            throw new IllegalStateException("오늘 마감 리포트가 끝나기 전에는 예수금을 수정할 수 없습니다.");
        }
        CyclePosition latest = cyclePositionPort.findLatestOne(cycle.id())
                .orElseThrow(() -> new NoSuchElementException("포지션 이력 없음: " + strategyId));

        if (latest.holdings().signum() != 0) {
            throw new IllegalArgumentException("보유 수량이 있는 사이클은 예수금을 수정할 수 없습니다.");
        }
        InfiniteMinSeedGuard.requireAtLeast(minSeed, newSeed); // INFINITE는 등록과 같은 최소 시드 미만 거부(기존 거절 사유 뒤)

        // openAmount(개장 평가)는 개장 시 1회만 기록 — 이후 변경은 RESET 이력으로 남겨 원금 = openAmount + Σ principalDelta를 유지
        strategyCyclePort.updateStartAmount(cycle.id(), newSeed);
        cyclePositionPort.updateBalance(latest.id(), latest.toBalance().withDeposit(newSeed));
        // 보유 0이라 예수금 차액 = 원금 차액
        BigDecimal delta = newSeed.subtract(latest.deposit());
        if (delta.signum() != 0) {
            cycleCapitalEventPort.save(new CycleCapitalEvent(null, cycle.id(), latest.id(), CycleCapitalEvent.Kind.RESET,
                    delta, BigDecimal.ZERO, delta, Instant.now(clock)));
        }
        log.info("[strategyId={}] 시드 수정: newSeed={}, delta={}", strategyId, newSeed, delta);
    }
}
