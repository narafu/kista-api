package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.port.in.DeleteAccountUseCase;
import com.kista.domain.port.in.GetAccountUseCase;
import com.kista.domain.port.in.RegisterAccountUseCase;
import com.kista.domain.port.in.UpdateAccountUseCase;
import com.kista.domain.port.out.AccountRepository;
import com.kista.domain.port.out.KisTokenPort;
import com.kista.domain.port.out.TradingCycleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AccountService implements RegisterAccountUseCase, UpdateAccountUseCase,
        DeleteAccountUseCase, GetAccountUseCase {

    private static final int MAX_ACCOUNTS_PER_USER = 10;

    private final AccountRepository accountRepository;
    private final KisTokenPort kisTokenPort;
    private final TradingCycleRepository cycleRepository;

    @Override
    public Account register(UUID userId, RegisterAccountUseCase.Command cmd) {
        if (accountRepository.countByUserId(userId) >= MAX_ACCOUNTS_PER_USER) {
            throw new IllegalStateException("계좌는 최대 " + MAX_ACCOUNTS_PER_USER + "개까지 등록 가능합니다");
        }
        Account account = new Account(
                null, userId, cmd.nickname(),
                cmd.accountNo(), cmd.kisAppKey(), cmd.kisSecretKey(),
                cmd.kisAccountType() != null ? cmd.kisAccountType() : "01",
                Account.Broker.KIS,
                null, null
        );
        Account saved = accountRepository.save(account);
        log.info("계좌 등록: userId={}, accountId={}", userId, saved.id());
        return saved;
    }

    @Override
    public Account update(UUID accountId, UUID requesterId, UpdateAccountUseCase.Command cmd) {
        Account account = accountRepository.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        // 키 변경 시에만 유효성 검증
        String newAppKey = cmd.kisAppKey() != null ? cmd.kisAppKey() : account.kisAppKey();
        String newSecretKey = cmd.kisSecretKey() != null ? cmd.kisSecretKey() : account.kisSecretKey();
        if (cmd.kisAppKey() != null || cmd.kisSecretKey() != null) {
            kisTokenPort.testToken(accountId, newAppKey, newSecretKey);
        }
        Account updated = new Account(
                account.id(), account.userId(),
                cmd.nickname() != null ? cmd.nickname() : account.nickname(),
                account.accountNo(),
                newAppKey, newSecretKey,
                account.kisAccountType(), account.broker(),
                account.createdAt(), null
        );
        return accountRepository.save(updated);
    }

    @Override
    public void delete(UUID accountId, UUID requesterId) {
        Account account = accountRepository.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        // 계좌에 속한 사이클 먼저 소프트 삭제 (FK CASCADE 대체)
        cycleRepository.deleteByAccountId(accountId);
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
        return accountRepository.findByIdOrThrow(id);
    }
}
