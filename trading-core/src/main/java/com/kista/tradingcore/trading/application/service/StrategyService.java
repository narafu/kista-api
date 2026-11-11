package com.kista.tradingcore.trading.application.service;

import java.util.NoSuchElementException;
import com.kista.shared.kernel.Market;
import com.kista.tradingcore.trading.application.service.support.OwnedStrategy;
import com.kista.tradingcore.trading.application.service.support.StrategyHistoryQueryService;

import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.trading.domain.model.*;
import com.kista.tradingcore.matching.domain.model.*;
import com.kista.tradingcore.trading.application.usecase.StrategyUseCase;
import com.kista.tradingcore.trading.application.port.output.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import com.kista.tradingcore.trading.application.event.StrategyCapitalChangedEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyCycleSeedType;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class StrategyService implements StrategyUseCase {

    private final StrategyPort strategyPort;
    private final StrategyVersionPort strategyVersionPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final AccountPort accountPort;
    private final StrategyCreationService creationService;         // 신규 전략 등록 전용
    private final StrategyDetailAssembler detailAssembler;         // 응답 StrategyDetail 조립(조회·등록 공용)
    private final StrategyHistoryQueryService historyQueryService; // 시드 미리보기 + 조회 2종
    private final CycleSnapshotCreator cycleSnapshotCreator;       // 재개 시 종료된 사이클 재오픈 전용
    private final OrderCancelService orderCancelService;           // 일시중지·삭제 전 미체결 주문 정리
    private final ApplicationEventPublisher eventPublisher;       // stats 캐시 무효화 이벤트 발행
    private final StrategyStateWriter stateWriter;                 // 일시중지·삭제·보관 DB 쓰기 (취소 HTTP를 트랜잭션 밖에 두기 위해 분리)
    private final OrderPort orderPort;                             // 보관 전 당일 접수 주문 확인
    private final TradingBatchRunPort batchRunPort;                // 보관 전 당일 마감 리포트 반영 여부
    private final InfiniteMinSeedGuard minSeedGuard;               // 시드 수정 시 INFINITE 최소 시드 검사
    private final StrategyUpdateWriter updateWriter;               // update DB 쓰기 (전일종가 HTTP를 트랜잭션 밖에 두기 위해 분리)
    private final Clock clock;                                     // 당일(KST) 기준

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public StrategyDetail register(UUID userId, UUID accountId, RegisterStrategyCommand cmd) {
        StrategyDetail detail = creationService.register(userId, accountId, cmd);
        eventPublisher.publishEvent(new StrategyCapitalChangedEvent(userId)); // 신규 전략 등록 — stats 캐시 무효화
        return detail;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 증권사 주문 취소 HTTP 포함 — DB 쓰기는 StrategyStateWriter 짧은 트랜잭션
    public void delete(UUID strategyId, UUID requesterId) {
        OwnedStrategy owned = OwnedStrategy.load(strategyPort, accountPort, strategyId, requesterId);
        Account account = owned.account();
        // 먼저 PAUSED로 기록해 이후 배치가 이 전략으로 새 주문을 만들지 않게 한 뒤 미체결 주문을 정리한다
        // 일시 장애로 증권사 취소에 실패한 주문이 있으면 삭제하지 않는다(삭제된 전략의 주문이 증권사에 남지 않도록) — 전략은 PAUSED로 남는다
        // 자격증명 오류(키 만료·철회)는 재시도로 풀리지 않아 삭제를 막지 않는다 — 그 주문은 PLACED로 남고 관리자에게 알린다
        orderCancelService.requireNoCryptoInFlight(strategyId, account); // 코인 체결 확인 중이면 PAUSED 기록 전에 거부
        stateWriter.pause(strategyId); // 보관 전략은 갱신 0건이어도 삭제는 진행한다
        CancelResult cancelled = orderCancelService.cancelOpenOrders(strategyId, account);
        if (cancelled.retryableFailedCount() > 0) {
            throw new IllegalStateException("증권사 주문 취소에 실패한 주문이 있어 전략을 삭제할 수 없습니다. 잠시 후 다시 시도하시기 바랍니다.");
        }
        stateWriter.delete(strategyId);
        eventPublisher.publishEvent(new StrategyCapitalChangedEvent(requesterId)); // 삭제된 전략의 사이클이 통계에서 빠지므로 캐시 무효화
        log.info("[strategyId={}] 전략 삭제: requesterId={}, cancelledOrders={}", strategyId, requesterId, cancelled.cancelledCount());
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 증권사 주문 취소 HTTP 포함 — DB 쓰기는 StrategyStateWriter 짧은 트랜잭션
    public void pause(UUID strategyId, UUID requesterId) {
        OwnedStrategy owned = OwnedStrategy.load(strategyPort, accountPort, strategyId, requesterId);
        Strategy strategy = owned.strategy();
        Account account = owned.account();
        strategy.requireNotArchived(); // 보관 전략은 일시중지도 불가 (소유권 검증 이후 수행)
        // 중복 상태 guard — 이미 일시중지된 전략은 다시 일시중지 불가
        if (strategy.isPaused()) {
            throw new IllegalStateException("이미 일시중지된 전략입니다.");
        }
        // 먼저 PAUSED로 기록해 이후 배치가 새 주문을 만들지 않게 하고, 배치가 정지 뒤 체결을 기록하지 않으므로 오늘 미체결 주문을 정리한다
        // 취소 실패분은 관리자 알림 후 PLACED로 남는다
        orderCancelService.requireNoCryptoInFlight(strategyId, account); // 코인 체결 확인 중이면 PAUSED 기록 전에 거부
        if (!stateWriter.pause(strategyId)) { // 읽은 뒤 보관이 커밋된 경합
            throw new IllegalStateException("보관된 전략은 변경할 수 없습니다.");
        }
        CancelResult cancelled = orderCancelService.cancelOpenOrders(strategyId, account);
        log.info("[strategyId={}] 전략 일시중지: cancelledOrders={}, failedOrders={}",
                strategyId, cancelled.cancelledCount(), cancelled.failedCount());
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 증권사 주문 취소 HTTP 포함 — DB 쓰기는 StrategyStateWriter 짧은 트랜잭션
    public void archive(UUID strategyId, UUID requesterId) {
        OwnedStrategy owned = OwnedStrategy.load(strategyPort, accountPort, strategyId, requesterId);
        Strategy strategy = owned.strategy();
        Account account = owned.account();
        if (strategy.isArchived()) {
            throw new IllegalStateException("이미 보관된 전략입니다.");
        }
        // 보유 수량이 남은 전략은 보관 불가 — 보관 후엔 원장이 갱신되지 않으므로 포지션이 비어 있어야 한다(포지션 없음은 통과)
        boolean holdsShares = cyclePositionPort.findLatestOneByStrategyId(strategyId)
                .map(position -> position.holdings().signum() != 0)
                .orElse(false);
        if (holdsShares) {
            throw new IllegalStateException("보유 수량을 모두 매도한 뒤 보관할 수 있습니다.");
        }
        requireTodayOrdersReported(strategyId, account);
        // PAUSED로 먼저 기록해 이후 배치가 새 주문을 만들지 않게 한 뒤 미체결 주문을 정리한다 — 취소 실패 시 PAUSED로 남는다
        orderCancelService.requireNoCryptoInFlight(strategyId, account);
        stateWriter.pause(strategyId);
        CancelResult cancelled = orderCancelService.cancelOpenOrders(strategyId, account);
        if (cancelled.retryableFailedCount() > 0) {
            throw new IllegalStateException("증권사 주문 취소에 실패한 주문이 있어 보관할 수 없습니다. 잠시 후 다시 시도하시기 바랍니다.");
        }
        // 첫 점검과 PAUSED 기록 사이에 접수된 주문 재점검 — 마감 반영 전이면 PAUSED로 남기고 거부
        requireTodayOrdersReported(strategyId, account);
        stateWriter.archive(strategyId);
        log.info("[strategyId={}] 전략 보관: cancelledOrders={}", strategyId, cancelled.cancelledCount());
    }

    // 접수 주문이 마감 리포트에 반영되기 전에 보관하면 체결이 원장에 남지 않는다 — OrderCancelService와 같이 오늘·다음 거래일(개장 배치 tradeDate) 둘 다 본다
    // 수동 보정 행(UNKNOWN_LEG)은 전략 주문이 아니라 제외. 코인은 리포트 마커가 없어(ManualTradeCorrectionService와 동일) 건너뛰고 체결 확인 중 주문은 requireNoCryptoInFlight가 막는다
    private void requireTodayOrdersReported(UUID strategyId, Account account) {
        if (account.broker().getMarket().isCrypto()) return;
        ZonedDateTime now = ZonedDateTime.now(clock);
        for (LocalDate date : SessionTradeDates.tradeDateCandidates(now)) {
            boolean hasPlaced = orderPort.findByStrategyId(strategyId, date, date).stream()
                    .anyMatch(Order::isBrokerSubmitted);
            if (hasPlaced && !batchRunPort.isReported(date, strategyId)) {
                throw new IllegalStateException("오늘 접수된 주문이 마감 반영된 뒤 보관할 수 있습니다.");
            }
        }
    }

    @Override
    public void resume(UUID strategyId, UUID requesterId) {
        Strategy strategy = OwnedStrategy.load(strategyPort, accountPort, strategyId, requesterId).strategy();
        strategy.requireNotArchived(); // 보관은 한 방향 — 재개 불가
        // 중복 상태 guard — 이미 활성화된 전략은 재활성화 불가 (소유권 검증 이후 수행)
        if (strategy.isActive()) {
            throw new IllegalStateException("이미 활성화된 전략입니다.");
        }
        reopenCycleIfEnded(strategy, requesterId);
        if (!strategyPort.updateStatusUnlessArchived(strategyId, StrategyStatus.ACTIVE)) { // 읽은 뒤 보관이 커밋된 경합 — 스냅샷 save로 덮어쓰지 않는다
            throw new IllegalStateException("보관된 전략은 변경할 수 없습니다.");
        }
        log.info("[strategyId={}] 전략 재개", strategyId);
    }

    // cycleSeedType=NONE 전략은 청산 시 자동 rotation 없이 사이클이 종료된 채로 PAUSED된다(CycleRotationService).
    // 그 상태에서 status만 ACTIVE로 되돌리면 종료된 사이클(endDate 있음)이 여전히 "최신 사이클"로 남아
    // BatchContextFactory가 매 배치마다 좀비 사이클로 판정해 skip — 재개가 실제로는 매매를 재개시키지 못한다.
    // 재개 시점에 종료 사이클의 원장 금액(endAmount)을 그대로 승계한 새 사이클을 열어 이를 방지한다.
    private void reopenCycleIfEnded(Strategy strategy, UUID requesterId) {
        // VR은 endsCycleOnLiquidation()=false라 이 경로로 종료되지 않는다(항상 rollover/재설정이 원자적으로
        // 대체 사이클을 만든다) — 그 불변식이 깨졌을 때 여기서 VR 상세(strategy_cycle_vr) 없는 사이클을
        // 만들어버리는 사고를 막기 위해 명시적으로 제외한다
        if (strategy.isVr()) return;
        StrategyCycle latestCycle = strategyCyclePort.findLatestByStrategyId(strategy.id()).orElse(null);
        if (latestCycle == null || !latestCycle.isEnded()) return;
        StrategyVersion activeVersion = strategyVersionPort.findActiveByStrategyId(strategy.id())
                .orElseThrow(() -> new NoSuchElementException("활성 전략 버전이 없습니다: " + strategy.id()));
        BigDecimal closingPrice = cyclePositionPort.findLatestOneByStrategyId(strategy.id())
                .map(CyclePosition::closingPrice)
                .orElse(null);
        // MAINTAIN은 rotation(CycleRotationService)과 같이 min(시작 시드, 종료 원장) — 수익분은 미배정 현금으로 남긴다, 그 외는 종료 원장 전액
        BigDecimal seed = strategy.cycleSeedType() == StrategyCycleSeedType.MAINTAIN
                ? latestCycle.startAmount().min(latestCycle.endAmount())
                : latestCycle.endAmount();
        // 1주도 못 사는 원장으로 재개하면 0회차 매수가 원장 상한에 막혀 교착 — 거부
        if (closingPrice != null && seed.compareTo(closingPrice) < 0) {
            throw new IllegalStateException(String.format(Locale.US,
                    "남은 원장($%.2f)이 1주 가격($%.2f)보다 작아 재개할 수 없습니다. 전략을 삭제한 뒤 충분한 투자금으로 다시 등록해야 합니다.",
                    seed, closingPrice));
        }
        cycleSnapshotCreator.createCycleAndSnapshot(strategy.id(), activeVersion.id(), seed, closingPrice);
        eventPublisher.publishEvent(new StrategyCapitalChangedEvent(requesterId)); // 새 사이클 생성 — stats 캐시 무효화
        log.info("[strategyId={}] 재개 — 종료된 사이클 재오픈: seed={} ({})", strategy.id(), seed, strategy.cycleSeedType());
    }

    @Override
    @Transactional(readOnly = true)
    public List<StrategyDetail> listByUserId(UUID userId, boolean includeArchived) {
        List<Account> accounts = accountPort.findByUserId(userId);
        List<UUID> accountIds = accounts.stream().map(Account::id).toList();
        Map<UUID, Market> marketByAccountId = accounts.stream()
                .collect(Collectors.toMap(Account::id, a -> a.broker().getMarket())); // 이미 조회한 계좌에서 시장 맵 구성
        Map<UUID, List<Strategy>> strategiesByAccount = strategyPort.findByAccountIds(accountIds);
        List<Strategy> strategies = accountIds.stream()
                .flatMap(id -> strategiesByAccount.getOrDefault(id, List.of()).stream())
                .filter(s -> includeArchived || !s.isArchived()) // 보관 전략은 요청 시에만 포함
                .toList();
        return detailAssembler.toDetails(strategies, marketByAccountId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StrategyDetail> listByAccountId(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        return detailAssembler.toDetails(strategyPort.findByAccountId(accountId).stream().filter(s -> !s.isArchived()).toList(), Map.of(accountId, account.broker().getMarket()));
    }

    @Override
    @Transactional(readOnly = true)
    public StrategyDetail getById(UUID strategyId, UUID requesterId) {
        OwnedStrategy owned = OwnedStrategy.load(strategyPort, accountPort, strategyId, requesterId);
        Strategy strategy = owned.strategy();
        Account account = owned.account();
        return detailAssembler.toDetail(strategy, account.broker().getMarket());
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // INFINITE 최소 시드 검사의 전일종가 HTTP는 트랜잭션 밖 — 소유권·잠금·쓰기는 StrategyUpdateWriter 트랜잭션
    public StrategyDetail update(UUID strategyId, UUID requesterId, UpdateStrategyCommand cmd) {
        BigDecimal minSeed = null;
        if (cmd.newSeed() != null) {
            OwnedStrategy owned = OwnedStrategy.load(strategyPort, accountPort, strategyId, requesterId);
            if (owned.strategy().isInfinite()) {
                // HTTP 전에 싼 입력 검증부터 — 무효 시드로 시세 조회를 낭비하지 않는다
                StrategyUpdateWriter.requireValidSeed(cmd.newSeed(), owned.currency());
                minSeed = minSeedGuard.minSeed(owned.strategy(), owned.account().toBrokerRef()); // 조회 실패는 null(검사 건너뜀)
            }
        }
        return updateWriter.update(strategyId, requesterId, cmd, minSeed);
    }

    // ── 조회 전용 (stats에서 이관 — 전략 소유 read) ─────────────────────────────

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public StrategySeedPreview strategySeedPreview(
            UUID accountId, UUID requesterId,
            StrategyType type, StrategyTicker ticker, int divisionCount) {
        return historyQueryService.strategySeedPreview(accountId, requesterId, type, ticker, divisionCount);
    }

    @Override
    @Transactional(readOnly = true)
    public CycleHistoryPage getByStrategy(UUID strategyId, UUID requesterId,
                                          LocalDate from, LocalDate to,
                                          Instant cursor, int size) {
        return historyQueryService.getByStrategy(strategyId, requesterId, from, to, cursor, size);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> getOrdersByStrategy(UUID strategyId, UUID requesterId, LocalDate from, LocalDate to) {
        return historyQueryService.getOrdersByStrategy(strategyId, requesterId, from, to);
    }

}
