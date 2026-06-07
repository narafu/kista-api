package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.DeleteTradingCycleUseCase;
import com.kista.domain.port.in.GetTradingCycleUseCase;
import com.kista.domain.port.in.PauseTradingCycleUseCase;
import com.kista.domain.port.in.RegisterTradingCycleUseCase;
import com.kista.domain.port.in.ResumeTradingCycleUseCase;
import com.kista.domain.port.in.UpdateTradingCycleUseCase;
import com.kista.domain.model.tradingcycle.TradingCycleHistory;
import com.kista.application.event.TradingCyclePausedEvent;
import com.kista.application.event.TradingCycleResumedEvent;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.TradingCycleHistoryPort;
import com.kista.domain.port.out.TradingCyclePort;
import com.kista.domain.port.out.UserPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TradingCycleService implements RegisterTradingCycleUseCase,
        DeleteTradingCycleUseCase, GetTradingCycleUseCase, PauseTradingCycleUseCase,
        ResumeTradingCycleUseCase, UpdateTradingCycleUseCase {

    private static final int MAX_CYCLES_PER_ACCOUNT = 1; // 운영 정책: 계좌당 1사이클

    private final TradingCyclePort cyclePort;
    private final TradingCycleHistoryPort cycleHistoryPort;
    private final AccountPort accountPort;
    private final UserPort userPort;
    private final ApplicationEventPublisher eventPublisher; // 트랜잭션 커밋 후 알림 발행용

    @Override
    public TradingCycle register(UUID userId, UUID accountId, RegisterTradingCycleUseCase.Command cmd) {
        Account account = accountPort.findByIdOrThrow(accountId);
        account.verifyOwnedBy(userId);

        // 사이클 수 제한
        if (cyclePort.findByAccountId(accountId).size() >= MAX_CYCLES_PER_ACCOUNT) {
            throw new IllegalStateException("계좌당 최대 " + MAX_CYCLES_PER_ACCOUNT + "개의 거래 사이클만 등록할 수 있습니다");
        }

        // 같은 type 중복 방지
        if (cyclePort.existsByAccountIdAndType(accountId, cmd.type())) {
            throw new IllegalStateException("이미 등록된 전략 종류입니다: " + cmd.type());
        }

        TradingCycle.CycleSeedType seedType = cmd.cycleSeedType() != null
                ? cmd.cycleSeedType()
                : TradingCycle.CycleSeedType.NONE;
        TradingCycle cycle = new TradingCycle(
                null, accountId, cmd.type(), TradingCycle.Status.ACTIVE,
                cmd.ticker(), cmd.initialUsdDeposit(), seedType
        );
        TradingCycle saved = cyclePort.save(cycle);

        // 초기 스냅샷 저장: 입금액 기준, 보유 없음 (등록 시점엔 가격 미조회)
        cycleHistoryPort.save(TradingCycleHistory.startSnapshot(saved.id(), saved.initialUsdDeposit(), null));

        log.info("거래 사이클 등록: accountId={}, cycleId={}, type={}", accountId, saved.id(), saved.type());
        return saved;
    }

    @Override
    public void delete(UUID cycleId, UUID requesterId) {
        TradingCycle cycle = cyclePort.findByIdOrThrow(cycleId);
        Account account = accountPort.findByIdOrThrow(cycle.accountId());
        account.verifyOwnedBy(requesterId);
        cyclePort.delete(cycleId);
        log.info("거래 사이클 삭제: cycleId={}, requesterId={}", cycleId, requesterId);
    }

    @Override
    public void pause(UUID cycleId, UUID requesterId) {
        TradingCycle cycle = cyclePort.findByIdOrThrow(cycleId);
        // 중복 상태 guard — 이미 중지된 사이클은 재중지 불가
        if (cycle.status() == TradingCycle.Status.PAUSED) {
            throw new IllegalStateException("이미 중지된 사이클입니다: " + cycleId);
        }
        Account account = accountPort.findByIdOrThrow(cycle.accountId());
        account.verifyOwnedBy(requesterId);
        // save() 전 사용자 조회 — 사용자 없으면 저장 불필요
        User user = findUserOrThrow(requesterId);
        TradingCycle paused = cycle.withStatus(TradingCycle.Status.PAUSED);
        cyclePort.save(paused);
        log.info("거래 사이클 중지: cycleId={}", cycleId);
        // 커밋 성공 후에만 텔레그램 알림 — 롤백 시 중복 발송 방지
        eventPublisher.publishEvent(new TradingCyclePausedEvent(user, account, paused));
    }

    @Override
    public void resume(UUID cycleId, UUID requesterId) {
        TradingCycle cycle = cyclePort.findByIdOrThrow(cycleId);
        // 중복 상태 guard — 이미 활성화된 사이클은 재활성화 불가
        if (cycle.status() == TradingCycle.Status.ACTIVE) {
            throw new IllegalStateException("이미 활성화된 사이클입니다: " + cycleId);
        }
        Account account = accountPort.findByIdOrThrow(cycle.accountId());
        account.verifyOwnedBy(requesterId);
        // save() 전 사용자 조회 — 사용자 없으면 저장 불필요
        User user = findUserOrThrow(requesterId);
        TradingCycle active = cycle.withStatus(TradingCycle.Status.ACTIVE);
        cyclePort.save(active);
        log.info("거래 사이클 재개: cycleId={}", cycleId);
        // 커밋 성공 후에만 텔레그램 알림 — 롤백 시 중복 발송 방지
        eventPublisher.publishEvent(new TradingCycleResumedEvent(user, account, active));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TradingCycle> listByAccountId(UUID accountId, UUID requesterId) {
        Account account = accountPort.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        return cyclePort.findByAccountId(accountId);
    }

    @Override
    @Transactional(readOnly = true)
    public TradingCycle getById(UUID cycleId, UUID requesterId) {
        TradingCycle cycle = cyclePort.findByIdOrThrow(cycleId);
        Account account = accountPort.findByIdOrThrow(cycle.accountId());
        account.verifyOwnedBy(requesterId);
        return cycle;
    }

    @Override
    public TradingCycle update(UUID cycleId, UUID requesterId, UpdateTradingCycleUseCase.Command cmd) {
        TradingCycle cycle = cyclePort.findByIdOrThrow(cycleId);
        Account account = accountPort.findByIdOrThrow(cycle.accountId());
        account.verifyOwnedBy(requesterId);

        TradingCycle.CycleSeedType seedType = cmd.cycleSeedType() != null
                ? cmd.cycleSeedType()
                : cycle.cycleSeedType();
        TradingCycle updated = cycle.withCycleSeedType(seedType);
        TradingCycle saved = cyclePort.save(updated);
        log.info("거래 사이클 수정: cycleId={}, cycleSeedType={}", cycleId, seedType);
        return saved;
    }


    private User findUserOrThrow(UUID userId) {
        return userPort.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + userId));
    }

}
