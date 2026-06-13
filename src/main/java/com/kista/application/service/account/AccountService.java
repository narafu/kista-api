package com.kista.application.service.account;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.RegisterAccountCommand;
import com.kista.domain.model.account.UpdateAccountCommand;
import com.kista.domain.port.in.AccountUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.KisConnectionTestPort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.TossConnectionTestPort;
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
    private final StrategyPort strategyPort;
    private final KisConnectionTestPort connectionTestPort;   // KIS 자격증명 연결 테스트 포트
    private final TossConnectionTestPort tossConnectionTestPort; // Toss 자격증명 연결 테스트 + accountSeq 조회

    @Override
    public Account register(UUID userId, RegisterAccountCommand cmd) {
        if (accountPort.countByUserId(userId) >= MAX_ACCOUNTS_PER_USER) {
            throw new IllegalStateException("계좌는 최대 " + MAX_ACCOUNTS_PER_USER + "개까지 등록 가능합니다");
        }
        // 전역 계좌번호 중복 체크 (크로스-유저, 해시 기반 — V11 이후 신규 등록에만 적용)
        if (accountPort.existsByAccountNo(cmd.accountNo())) {
            throw new Account.DuplicateAccountException(cmd.accountNo());
        }
        // 동일 사용자 중복 체크 (V11 이전 NULL-hash 기존 레코드 대비 fallback)
        accountPort.findByUserId(userId).stream()
                .filter(a -> a.accountNo().equals(cmd.accountNo()))
                .findAny()
                .ifPresent(a -> { throw new Account.DuplicateAccountException(cmd.accountNo()); });
        // broker 미지정 시 KIS 기본값 적용
        Account.Broker broker = cmd.broker() != null ? cmd.broker() : Account.Broker.KIS;

        // 증권사별 계좌 상품 코드 / 계좌 시퀀스 결정
        String accountTypeOrSeq = switch (broker) {
            case KIS -> cmd.kisAccountType() != null ? cmd.kisAccountType() : "01";
            case TOSS -> tossConnectionTestPort.testAndFetchAccountSeq(cmd.kisAppKey(), cmd.kisSecretKey());
        };

        Account account = new Account(
                null, userId, cmd.nickname(),
                cmd.accountNo(), cmd.kisAppKey(), cmd.kisSecretKey(),
                accountTypeOrSeq,
                broker
        );
        Account saved = accountPort.save(account);
        log.info("계좌 등록: userId={}, accountId={}, broker={}", userId, saved.id(), broker);
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
        // 계좌에 속한 전략 먼저 소프트 삭제 (FK CASCADE 대체)
        strategyPort.deleteByAccountId(accountId);
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
