package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.trading.application.event.StrategyCapitalChangedEvent;
import com.kista.tradingcore.trading.application.port.output.CycleCapitalEventPort;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.service.support.OwnedStrategy;
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
    private final CycleCapitalEventPort cycleCapitalEventPort; // 투자금 추가·인출 이력 존재 확인(시드 수정 거절용)
    private final StrategyDetailAssembler detailAssembler;
    private final ApplicationEventPublisher eventPublisher;    // stats 캐시 무효화 이벤트 발행

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
            updateSeed(strategyId, cmd.newSeed(), owned.currency(), minSeed);
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

    // 시드 수정: holdings=0 시작점에서만 허용 — strategy_cycle + 최신 cycle_position 함께 보정
    private void updateSeed(UUID strategyId, BigDecimal newSeed, Currency currency, BigDecimal minSeed) {
        requireValidSeed(newSeed, currency);
        StrategyCycle cycle = strategyCyclePort.requireLatestByStrategyId(strategyId);
        CyclePosition latest = cyclePositionPort.findLatestOneByStrategyId(strategyId)
                .orElseThrow(() -> new NoSuchElementException("포지션 이력 없음: " + strategyId));

        if (latest.holdings().signum() != 0) {
            throw new IllegalArgumentException("보유 수량이 있는 사이클은 예수금을 수정할 수 없습니다.");
        }
        // 이력 합계(원금)와 어긋나므로 투자금 추가·인출 이력이 있으면 일반 시드 수정 불가
        if (cycleCapitalEventPort.existsByCycleId(cycle.id())) {
            throw new IllegalArgumentException("투자금 추가·인출 이력이 있는 사이클은 예수금을 수정할 수 없습니다. 투자금 추가·인출을 이용해야 합니다.");
        }
        InfiniteMinSeedGuard.requireAtLeast(minSeed, newSeed); // INFINITE는 등록과 같은 최소 시드 미만 거부(기존 거절 사유 뒤)

        strategyCyclePort.updateStartAmount(cycle.id(), newSeed);
        cyclePositionPort.updateCycleStartSnapshot(strategyId, newSeed);
        log.info("[strategyId={}] 시드 수정: newSeed={}, holdings={}", strategyId, newSeed, latest.holdings());
    }
}
