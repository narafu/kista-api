package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.DeleteTradingCycleUseCase;
import com.kista.domain.port.in.GetTradingCycleUseCase;
import com.kista.domain.port.in.PauseTradingCycleUseCase;
import com.kista.domain.port.in.RegisterTradingCycleUseCase;
import com.kista.domain.port.in.ResumeTradingCycleUseCase;
import com.kista.domain.port.in.UpdateTradingCycleUseCase;
import com.kista.domain.model.tradingcycle.TradingCycleHistory;
import com.kista.domain.port.out.AccountRepository;
import com.kista.domain.port.out.TradingCycleHistoryRepository;
import com.kista.domain.port.out.TradingCycleRepository;
import com.kista.domain.port.out.UserNotificationPort;
import com.kista.domain.port.out.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TradingCycleService implements RegisterTradingCycleUseCase, UpdateTradingCycleUseCase,
        DeleteTradingCycleUseCase, GetTradingCycleUseCase, PauseTradingCycleUseCase, ResumeTradingCycleUseCase {

    private static final int MAX_CYCLES_PER_ACCOUNT = 1; // 운영 정책: 계좌당 1사이클

    private final TradingCycleRepository cycleRepository;
    private final TradingCycleHistoryRepository cycleHistoryRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final UserNotificationPort notificationPort;

    @Override
    public TradingCycle register(UUID userId, UUID accountId, RegisterTradingCycleUseCase.Command cmd) {
        Account account = accountRepository.findByIdOrThrow(accountId);
        account.verifyOwnedBy(userId);

        // 사이클 수 제한
        if (cycleRepository.findByAccountId(accountId).size() >= MAX_CYCLES_PER_ACCOUNT) {
            throw new IllegalStateException("계좌당 최대 " + MAX_CYCLES_PER_ACCOUNT + "개의 거래 사이클만 등록할 수 있습니다");
        }

        // 같은 type 중복 방지
        if (cycleRepository.existsByAccountIdAndType(accountId, cmd.type())) {
            throw new IllegalStateException("이미 등록된 전략 종류입니다: " + cmd.type());
        }

        // ticker 결정: PRIVACY → SOXL 강제, 그 외 → 요청값 (null이면 타입 기본값)
        Ticker ticker = cmd.type().resolveTicker(cmd.ticker(), cmd.type().getDefaultTicker());
        BigDecimal multiple = cmd.multiple() != null ? cmd.multiple() : BigDecimal.ONE;

        TradingCycle cycle = new TradingCycle(
                null, accountId, cmd.type(), TradingCycle.Status.ACTIVE,
                ticker, multiple, cmd.initialUsdDeposit(), null, null
        );
        TradingCycle saved = cycleRepository.save(cycle);

        // 초기 스냅샷 저장: 입금액 기준, 보유 없음
        cycleHistoryRepository.save(new TradingCycleHistory(
                null, saved.id(), saved.initialUsdDeposit(), null, BigDecimal.ZERO, null
        ));

        log.info("거래 사이클 등록: accountId={}, cycleId={}, type={}", accountId, saved.id(), saved.type());
        return saved;
    }

    @Override
    public TradingCycle update(UUID cycleId, UUID requesterId, UpdateTradingCycleUseCase.Command cmd) {
        TradingCycle cycle = cycleRepository.findByIdOrThrow(cycleId);
        Account account = accountRepository.findByIdOrThrow(cycle.accountId());
        account.verifyOwnedBy(requesterId);

        // ticker 결정: PRIVACY → SOXL 강제, 그 외 → 요청값 (null이면 기존값 유지)
        Ticker updatedTicker = cycle.type().resolveTicker(cmd.ticker(), cycle.ticker());
        BigDecimal updatedMultiple = cmd.multiple() != null ? cmd.multiple() : cycle.multiple();

        TradingCycle updated = new TradingCycle(
                cycle.id(), cycle.accountId(), cycle.type(), cycle.status(),
                updatedTicker, updatedMultiple, cycle.initialUsdDeposit(), cycle.createdAt(), null
        );
        return cycleRepository.save(updated);
    }

    @Override
    public void delete(UUID cycleId, UUID requesterId) {
        TradingCycle cycle = cycleRepository.findByIdOrThrow(cycleId);
        Account account = accountRepository.findByIdOrThrow(cycle.accountId());
        account.verifyOwnedBy(requesterId);
        cycleRepository.delete(cycleId);
        log.info("거래 사이클 삭제: cycleId={}, requesterId={}", cycleId, requesterId);
    }

    @Override
    public void pause(UUID cycleId, UUID requesterId) {
        TradingCycle cycle = cycleRepository.findByIdOrThrow(cycleId);
        Account account = accountRepository.findByIdOrThrow(cycle.accountId());
        account.verifyOwnedBy(requesterId);
        TradingCycle paused = withStatus(cycle, TradingCycle.Status.PAUSED);
        cycleRepository.save(paused);
        log.info("거래 사이클 중지: cycleId={}", cycleId);
        User user = findUserOrThrow(requesterId);
        notificationPort.notifyStrategyChanged(user, account, paused, "중지");
    }

    @Override
    public void resume(UUID cycleId, UUID requesterId) {
        TradingCycle cycle = cycleRepository.findByIdOrThrow(cycleId);
        Account account = accountRepository.findByIdOrThrow(cycle.accountId());
        account.verifyOwnedBy(requesterId);
        TradingCycle active = withStatus(cycle, TradingCycle.Status.ACTIVE);
        cycleRepository.save(active);
        log.info("거래 사이클 재개: cycleId={}", cycleId);
        User user = findUserOrThrow(requesterId);
        notificationPort.notifyStrategyChanged(user, account, active, "재개");
    }

    @Override
    @Transactional(readOnly = true)
    public List<TradingCycle> listByAccountId(UUID accountId, UUID requesterId) {
        Account account = accountRepository.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        return cycleRepository.findByAccountId(accountId);
    }

    @Override
    @Transactional(readOnly = true)
    public TradingCycle getById(UUID cycleId, UUID requesterId) {
        TradingCycle cycle = cycleRepository.findByIdOrThrow(cycleId);
        Account account = accountRepository.findByIdOrThrow(cycle.accountId());
        account.verifyOwnedBy(requesterId);
        return cycle;
    }

    private TradingCycle withStatus(TradingCycle c, TradingCycle.Status status) {
        return new TradingCycle(c.id(), c.accountId(), c.type(), status,
                c.ticker(), c.multiple(), c.initialUsdDeposit(), c.createdAt(), null);
    }

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + userId));
    }

}
