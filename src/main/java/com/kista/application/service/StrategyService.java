package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.DeleteStrategyUseCase;
import com.kista.domain.port.in.GetStrategyUseCase;
import com.kista.domain.port.in.PauseStrategyUseCase;
import com.kista.domain.port.in.RegisterStrategyUseCase;
import com.kista.domain.port.in.ResumeStrategyUseCase;
import com.kista.domain.port.in.UpdateStrategyUseCase;
import com.kista.domain.port.out.AccountRepository;
import com.kista.domain.port.out.StrategyRepository;
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
public class StrategyService implements RegisterStrategyUseCase, UpdateStrategyUseCase,
        DeleteStrategyUseCase, GetStrategyUseCase, PauseStrategyUseCase, ResumeStrategyUseCase {

    private static final int MAX_STRATEGIES_PER_ACCOUNT = 1; // 운영 정책: 계좌당 1전략

    private final StrategyRepository strategyRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final UserNotificationPort notificationPort;

    @Override
    public Strategy register(UUID userId, UUID accountId, RegisterStrategyUseCase.Command cmd) {
        Account account = accountRepository.findByIdOrThrow(accountId);
        account.verifyOwnedBy(userId);

        // 전략 수 제한
        if (strategyRepository.findByAccountId(accountId).size() >= MAX_STRATEGIES_PER_ACCOUNT) {
            throw new IllegalStateException("계좌당 최대 " + MAX_STRATEGIES_PER_ACCOUNT + "개의 전략만 등록할 수 있습니다");
        }

        // 같은 type 중복 방지
        if (strategyRepository.existsByAccountIdAndType(accountId, cmd.type())) {
            throw new IllegalStateException("이미 등록된 전략 종류입니다: " + cmd.type());
        }

        // ticker 결정: PRIVACY → SOXL 강제, INFINITE → 요청값 (null이면 TQQQ)
        Ticker ticker = resolveTicker(cmd.type(), cmd.ticker());
        BigDecimal multiple = cmd.multiple() != null ? cmd.multiple() : BigDecimal.ONE;

        Strategy strategy = new Strategy(
                null, accountId, cmd.type(), Strategy.StrategyStatus.ACTIVE,
                ticker, multiple, null, null
        );
        Strategy saved = strategyRepository.save(strategy);
        log.info("전략 등록: accountId={}, strategyId={}, type={}", accountId, saved.id(), saved.type());
        return saved;
    }

    @Override
    public Strategy update(UUID strategyId, UUID requesterId, UpdateStrategyUseCase.Command cmd) {
        Strategy strategy = strategyRepository.findByIdOrThrow(strategyId);
        Account account = accountRepository.findByIdOrThrow(strategy.accountId());
        account.verifyOwnedBy(requesterId);

        // PRIVACY ticker는 항상 SOXL 고정
        Ticker updatedTicker = strategy.type() == Strategy.StrategyType.PRIVACY
                ? Ticker.SOXL
                : (cmd.ticker() != null ? cmd.ticker() : strategy.ticker());
        BigDecimal updatedMultiple = cmd.multiple() != null ? cmd.multiple() : strategy.multiple();

        Strategy updated = new Strategy(
                strategy.id(), strategy.accountId(), strategy.type(), strategy.status(),
                updatedTicker, updatedMultiple, strategy.createdAt(), null
        );
        return strategyRepository.save(updated);
    }

    @Override
    public void delete(UUID strategyId, UUID requesterId) {
        Strategy strategy = strategyRepository.findByIdOrThrow(strategyId);
        Account account = accountRepository.findByIdOrThrow(strategy.accountId());
        account.verifyOwnedBy(requesterId);
        strategyRepository.delete(strategyId);
        log.info("전략 삭제: strategyId={}, requesterId={}", strategyId, requesterId);
    }

    @Override
    public void pause(UUID strategyId, UUID requesterId) {
        Strategy strategy = strategyRepository.findByIdOrThrow(strategyId);
        Account account = accountRepository.findByIdOrThrow(strategy.accountId());
        account.verifyOwnedBy(requesterId);
        Strategy paused = withStatus(strategy, Strategy.StrategyStatus.PAUSED);
        strategyRepository.save(paused);
        log.info("전략 중지: strategyId={}", strategyId);
        User user = findUserOrThrow(requesterId);
        notificationPort.notifyStrategyChanged(user, account, paused, "중지");
    }

    @Override
    public void resume(UUID strategyId, UUID requesterId) {
        Strategy strategy = strategyRepository.findByIdOrThrow(strategyId);
        Account account = accountRepository.findByIdOrThrow(strategy.accountId());
        account.verifyOwnedBy(requesterId);
        Strategy active = withStatus(strategy, Strategy.StrategyStatus.ACTIVE);
        strategyRepository.save(active);
        log.info("전략 재개: strategyId={}", strategyId);
        User user = findUserOrThrow(requesterId);
        notificationPort.notifyStrategyChanged(user, account, active, "재개");
    }

    @Override
    @Transactional(readOnly = true)
    public List<Strategy> listByAccountId(UUID accountId, UUID requesterId) {
        Account account = accountRepository.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        return strategyRepository.findByAccountId(accountId);
    }

    @Override
    @Transactional(readOnly = true)
    public Strategy getById(UUID strategyId, UUID requesterId) {
        Strategy strategy = strategyRepository.findByIdOrThrow(strategyId);
        Account account = accountRepository.findByIdOrThrow(strategy.accountId());
        account.verifyOwnedBy(requesterId);
        return strategy;
    }

    private Strategy withStatus(Strategy s, Strategy.StrategyStatus status) {
        return new Strategy(s.id(), s.accountId(), s.type(), status,
                s.ticker(), s.multiple(), s.createdAt(), null);
    }

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + userId));
    }

    private Ticker resolveTicker(Strategy.StrategyType type, Ticker requested) {
        return switch (type) {
            case PRIVACY -> Ticker.SOXL;
            case INFINITE -> requested != null ? requested : Ticker.TQQQ;
        };
    }
}
