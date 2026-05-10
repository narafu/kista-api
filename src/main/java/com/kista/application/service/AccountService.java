package com.kista.application.service;

import com.kista.domain.model.Account;
import com.kista.domain.model.StrategyStatus;
import com.kista.domain.model.User;
import com.kista.domain.port.in.DeleteAccountUseCase;
import com.kista.domain.port.in.GetAccountUseCase;
import com.kista.domain.port.in.PauseStrategyUseCase;
import com.kista.domain.port.in.RegisterAccountUseCase;
import com.kista.domain.port.in.ResumeStrategyUseCase;
import com.kista.domain.port.in.UpdateAccountUseCase;
import com.kista.domain.port.out.AccountRepository;
import com.kista.domain.port.out.UserNotificationPort;
import com.kista.domain.port.out.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AccountService implements RegisterAccountUseCase, UpdateAccountUseCase,
        DeleteAccountUseCase, GetAccountUseCase, PauseStrategyUseCase, ResumeStrategyUseCase {

    private static final int MAX_ACCOUNTS_PER_USER = 10;

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;       // pause/resume 시 사용자 조회용
    private final UserNotificationPort notificationPort; // 관리자 알림용

    @Override
    public Account register(UUID userId, RegisterAccountUseCase.Command cmd) {
        if (accountRepository.countByUserId(userId) >= MAX_ACCOUNTS_PER_USER) {
            throw new IllegalStateException("계좌는 최대 " + MAX_ACCOUNTS_PER_USER + "개까지 등록 가능합니다");
        }
        Account account = new Account(
                null, userId, cmd.nickname(),
                cmd.accountNo(), cmd.kisAppKey(), cmd.kisSecretKey(),
                cmd.kisAccountType() != null ? cmd.kisAccountType() : "01",
                cmd.strategy(), StrategyStatus.ACTIVE,
                cmd.telegramBotToken(), cmd.telegramChatId(),
                cmd.symbol() != null ? cmd.symbol() : "SOXL",
                cmd.exchangeCode() != null ? cmd.exchangeCode() : "AMS",
                null, null
        );
        Account saved = accountRepository.save(account);
        log.info("계좌 등록: userId={}, accountId={}", userId, saved.id());
        return saved;
    }

    @Override
    public Account update(UUID accountId, UUID requesterId, UpdateAccountUseCase.Command cmd) {
        Account account = findOrThrow(accountId);
        verifyOwner(account, requesterId);

        Account updated = new Account(
                account.id(), account.userId(),
                cmd.nickname() != null ? cmd.nickname() : account.nickname(),
                account.accountNo(), // 계좌번호 변경 불가 (보안상)
                cmd.kisAppKey() != null ? cmd.kisAppKey() : account.kisAppKey(),
                cmd.kisSecretKey() != null ? cmd.kisSecretKey() : account.kisSecretKey(),
                account.kisAccountType(), account.strategy(), account.strategyStatus(),
                cmd.telegramBotToken(), cmd.telegramChatId(),
                cmd.symbol() != null ? cmd.symbol() : account.symbol(),
                cmd.exchangeCode() != null ? cmd.exchangeCode() : account.exchangeCode(),
                account.createdAt(), null
        );
        return accountRepository.save(updated);
    }

    @Override
    public void delete(UUID accountId, UUID requesterId) {
        Account account = findOrThrow(accountId);
        verifyOwner(account, requesterId);
        accountRepository.delete(accountId);
        log.info("계좌 삭제: accountId={}, requesterId={}", accountId, requesterId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Account> listByUser(UUID userId) {
        return accountRepository.findByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Account getById(UUID id) {
        return findOrThrow(id);
    }

    private Account findOrThrow(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new NoSuchElementException("계좌를 찾을 수 없습니다: " + accountId));
    }

    @Override
    public void pause(UUID accountId, UUID requesterId) {
        Account account = findOrThrow(accountId);
        verifyOwner(account, requesterId);
        User user = findUserOrThrow(requesterId);
        Account paused = withStrategyStatus(account, StrategyStatus.PAUSED);
        accountRepository.save(paused);
        log.info("전략 중지: accountId={}, userId={}", accountId, requesterId);
        notificationPort.notifyStrategyChanged(user, paused, "중지");
    }

    @Override
    public void resume(UUID accountId, UUID requesterId) {
        Account account = findOrThrow(accountId);
        verifyOwner(account, requesterId);
        User user = findUserOrThrow(requesterId);
        Account active = withStrategyStatus(account, StrategyStatus.ACTIVE);
        accountRepository.save(active);
        log.info("전략 재개: accountId={}, userId={}", accountId, requesterId);
        notificationPort.notifyStrategyChanged(user, active, "재개");
    }

    // 소유권 검증 실패 시 SecurityException(unchecked) → 컨트롤러에서 403 매핑
    private void verifyOwner(Account account, UUID requesterId) {
        if (!account.userId().equals(requesterId)) {
            throw new SecurityException("계좌에 대한 접근 권한이 없습니다");
        }
    }

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + userId));
    }

    private Account withStrategyStatus(Account account, StrategyStatus status) {
        return new Account(account.id(), account.userId(), account.nickname(),
                account.accountNo(), account.kisAppKey(), account.kisSecretKey(),
                account.kisAccountType(), account.strategy(), status,
                account.telegramBotToken(), account.telegramChatId(),
                account.symbol(), account.exchangeCode(),
                account.createdAt(), null);
    }
}
