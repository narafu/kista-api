package com.kista.application.service.strategy;

import com.kista.application.event.TradingCyclePausedEvent;
import com.kista.application.event.TradingCycleResumedEvent;
import com.kista.common.CycleLookups;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.Currency;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.RegisterStrategyCommand;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.strategy.StrategyDetail;
import com.kista.domain.model.strategy.UpdateStrategyCommand;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.StrategyUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.KisMarginPort;
import com.kista.domain.port.out.KisPricePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.UserPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class StrategyService implements StrategyUseCase {

    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final AccountPort accountPort;
    private final UserPort userPort;
    private final KisPricePort kisPricePort;                     // 등록 시점 현재가(종가) 조회
    private final KisMarginPort kisMarginPort;                   // 등록 시점 가용 시드 검증
    private final ApplicationEventPublisher eventPublisher; // 트랜잭션 커밋 후 알림 발행용

    @Override
    public StrategyDetail register(UUID userId, UUID accountId, RegisterStrategyCommand cmd) {
        Account account = accountPort.requireOwnedAccount(accountId, userId);

        // PRIVACY는 SOXL 강제, INFINITE는 요청값 우선 → fallback
        Strategy.CycleSeedType seedType = cmd.cycleSeedType() != null
                ? cmd.cycleSeedType()
                : Strategy.CycleSeedType.NONE;
        Strategy.Ticker resolvedTicker = cmd.type().resolveTicker(cmd.ticker(), Strategy.Ticker.SOXL);

        // 같은 계좌 내 종목 중복 방지 — 체결 귀속(KIS 종목별 합산 잔고 ↔ 전략) 일대일 보장
        if (strategyPort.existsByAccountIdAndTicker(accountId, resolvedTicker)) {
            throw new IllegalStateException("이미 해당 종목으로 등록된 전략이 있습니다: " + resolvedTicker);
        }

        // 새 시드는 KIS 가용금액에서 기존 전략들이 점유한 시드를 뺀 자유 현금 한도 내에서만 허용
        if (cmd.initialUsdDeposit() != null) {
            BigDecimal freeCash = calcFreeCash(account, accountId);
            if (cmd.initialUsdDeposit().compareTo(freeCash) > 0) {
                throw new IllegalArgumentException(
                        "다른 전략이 사용 중인 시드를 제외한 자유 현금(" + freeCash + ")을 초과했습니다");
            }
        }

        Strategy strategy = new Strategy(null, accountId, cmd.type(), Strategy.Status.ACTIVE, resolvedTicker, seedType);
        Strategy saved = strategyPort.save(strategy);

        // 첫 번째 StrategyCycle 생성
        StrategyCycle cycle = strategyCyclePort.save(StrategyCycle.start(saved.id(), cmd.initialUsdDeposit()));

        // 초기 스냅샷 저장: 입금액 기준, 보유 없음, 종가는 등록 시점 현재가
        BigDecimal currentPrice = kisPricePort.getPrice(resolvedTicker, account);
        cyclePositionPort.save(CyclePosition.startSnapshot(cycle.id(), cmd.initialUsdDeposit(), currentPrice));

        log.info("전략 등록: accountId={}, strategyId={}, type={}", accountId, saved.id(), saved.type());
        return new StrategyDetail(saved, cycle.startAmount());
    }

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
        if (strategy.status() == Strategy.Status.PAUSED) {
            throw new IllegalStateException("이미 중지된 전략입니다: " + strategyId);
        }
        Account account = accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        // save() 전 사용자 조회 — 사용자 없으면 저장 불필요
        User user = userPort.findByIdOrThrow(requesterId);
        Strategy paused = strategy.withStatus(Strategy.Status.PAUSED);
        strategyPort.save(paused);
        log.info("전략 중지: strategyId={}", strategyId);
        // 커밋 성공 후에만 텔레그램 알림 — 롤백 시 중복 발송 방지
        eventPublisher.publishEvent(new TradingCyclePausedEvent(user, account, paused));
    }

    @Override
    public void resume(UUID strategyId, UUID requesterId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        // 중복 상태 guard — 이미 활성화된 전략은 재활성화 불가
        if (strategy.status() == Strategy.Status.ACTIVE) {
            throw new IllegalStateException("이미 활성화된 전략입니다: " + strategyId);
        }
        Account account = accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        // save() 전 사용자 조회 — 사용자 없으면 저장 불필요
        User user = userPort.findByIdOrThrow(requesterId);
        Strategy active = strategy.withStatus(Strategy.Status.ACTIVE);
        strategyPort.save(active);
        log.info("전략 재개: strategyId={}", strategyId);
        // 커밋 성공 후에만 텔레그램 알림 — 롤백 시 중복 발송 방지
        eventPublisher.publishEvent(new TradingCycleResumedEvent(user, account, active));
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

    // 자유 현금 = KIS 통합주문가능금액(USD) - 기존 전략들이 보유한 미투자 현금(usdDeposit) 합
    private BigDecimal calcFreeCash(Account account, UUID accountId) {
        BigDecimal kisUsdAmount = kisMarginPort.getMargin(account).stream()
                .filter(m -> Currency.USD == m.currency())
                .findFirst()
                .map(MarginItem::purchasableAmount)
                .orElseThrow(() -> new IllegalStateException("KIS USD 잔고 조회 실패: accountId=" + accountId));

        BigDecimal reserved = strategyPort.findByAccountId(accountId).stream()
                .map(s -> cyclePositionPort.findLatestByStrategyId(s.id(), 1).stream()
                        .findFirst()
                        .map(CyclePosition::usdDeposit)
                        .orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return kisUsdAmount.subtract(reserved);
    }

    // 시드 수정: 새 시드를 총자산 B로 교체 — usdDeposit = newSeed - M (M = avgPrice * holdings)
    private void updateSeed(UUID strategyId, BigDecimal newSeed) {
        if (newSeed.signum() <= 0) {
            throw new IllegalArgumentException("시드는 0보다 커야 합니다");
        }
        StrategyCycle cycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategyId);
        CyclePosition latest = cyclePositionPort.findLatestByStrategyId(strategyId, 1).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("포지션 이력 없음: " + strategyId));

        BigDecimal purchaseAmount = latest.holdings() == 0
                ? BigDecimal.ZERO
                : latest.avgPrice().multiply(BigDecimal.valueOf(latest.holdings()));
        // 새 시드는 이미 매수한 금액보다 작을 수 없음 (현금 음수 방지)
        if (newSeed.compareTo(purchaseAmount) < 0) {
            throw new IllegalArgumentException("시드는 이미 매수한 금액보다 적을 수 없습니다");
        }
        BigDecimal newDeposit = newSeed.subtract(purchaseAmount);

        strategyCyclePort.updateStartAmount(cycle.id(), newSeed);
        cyclePositionPort.save(new CyclePosition(null, cycle.id(), newDeposit,
                latest.closingPrice(), latest.avgPrice(), latest.holdings(), null, null));
        log.info("시드 수정: strategyId={}, newSeed={}, newDeposit={}", strategyId, newSeed, newDeposit);
    }

    // 현재 StrategyCycle의 startAmount를 묶어 응답용 StrategyDetail 조립
    private StrategyDetail toDetail(Strategy strategy) {
        BigDecimal initialUsdDeposit = strategyCyclePort.findLatestByStrategyId(strategy.id())
                .map(StrategyCycle::startAmount)
                .orElse(null);
        return new StrategyDetail(strategy, initialUsdDeposit);
    }
}
