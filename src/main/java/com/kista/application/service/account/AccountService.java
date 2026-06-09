package com.kista.application.service.account;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.RegisterAccountCommand;
import com.kista.domain.model.account.UpdateAccountCommand;
import com.kista.domain.port.in.AccountUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.KisConnectionTestPort;
import com.kista.domain.port.out.TradingCyclePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class AccountService implements AccountUseCase {

    private static final int MAX_ACCOUNTS_PER_USER = 10;

    private final AccountPort accountPort;
    private final TradingCyclePort cyclePort;
    private final KisConnectionTestPort connectionTestPort; // KIS 자격증명 연결 테스트 포트

    @Override
    public Account register(UUID userId, RegisterAccountCommand cmd) {
        if (accountPort.countByUserId(userId) >= MAX_ACCOUNTS_PER_USER) {
            throw new IllegalStateException("계좌는 최대 " + MAX_ACCOUNTS_PER_USER + "개까지 등록 가능합니다");
        }
        Account account = new Account(
                null, userId, cmd.nickname(),
                cmd.accountNo(), cmd.kisAppKey(), cmd.kisSecretKey(),
                cmd.kisAccountType() != null ? cmd.kisAccountType() : "01",
                Account.Broker.KIS
        );
        Account saved = accountPort.save(account);
        log.info("계좌 등록: userId={}, accountId={}", userId, saved.id());
        return saved;
    }

    @Override
    public Account update(UUID accountId, UUID requesterId, UpdateAccountCommand cmd) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        return accountPort.save(account.withNickname(cmd.nickname()));
    }

    @Override
    public void delete(UUID accountId, UUID requesterId) {
        accountPort.requireOwnedAccount(accountId, requesterId);
        // 계좌에 속한 사이클 먼저 소프트 삭제 (FK CASCADE 대체)
        cyclePort.deleteByAccountId(accountId);
        accountPort.delete(accountId);
        log.info("계좌 삭제: accountId={}, requesterId={}", accountId, requesterId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Account> listByUser(UUID userId) {
        return accountPort.findByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Account getById(UUID id) {
        return accountPort.findByIdOrThrow(id);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // KIS 외부 API 호출 — 트랜잭션 불필요
    public void test(String appKey, String appSecret, UUID accountId) {
        connectionTestPort.test(appKey, appSecret, accountId);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // KIS 외부 API 호출 — 트랜잭션 불필요
    public void testAccountNo(String appKey, String appSecret, String accountNo) {
        connectionTestPort.testAccountNo(appKey, appSecret, accountNo);
    }
}
