package com.kista.application.service.account;

import com.kista.broker.application.service.BrokerConnectionTesters;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.RegisterAccountCommand;
import com.kista.domain.model.account.UpdateAccountCommand;
import com.kista.application.usecase.AccountUseCase;
import com.kista.application.port.output.AccountPort;
import com.kista.application.port.output.RuntimeSettingsPort;
import com.kista.application.port.output.StrategyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class AccountService implements AccountUseCase {

    private static final int MAX_ACCOUNTS_PER_USER = 10;

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final BrokerConnectionTesters connectionTesters; // 증권사별 연결테스트 라우터
    private final RuntimeSettingsPort runtimeSettingsPort; // 증권사 신규 등록 허용 설정 조회

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // Toss accountSeq 조회 HTTP 호출 포함 — 트랜잭션 없이 실행 (단건 저장은 JPA auto-commit)
    public Account register(UUID userId, RegisterAccountCommand cmd) {
        // broker 미지정 시 KIS 기본값 적용 후 신규 등록 정책을 확인한다.
        Account.Broker broker = cmd.broker() != null ? cmd.broker() : Account.Broker.KIS;
        requireBrokerEnabled(broker);

        // MOCK은 서버가 합성 자격증명 생성, 그 외 브로커는 필수 입력 검증
        String accountNo = cmd.accountNo();
        String appKey = cmd.appKey();
        String secretKey = cmd.secretKey();
        if (broker == Account.Broker.MOCK) {
            if (accountNo == null || accountNo.isBlank()) accountNo = generateMockAccountNo();
            if (appKey == null || appKey.isBlank()) appKey = "MOCK";
            if (secretKey == null || secretKey.isBlank()) secretKey = "MOCK";
        } else if (accountNo == null || accountNo.isBlank()) {
            throw new IllegalArgumentException("계좌번호는 필수입니다");
        }
        // 람다 캡처를 위한 effectively-final 복사본 (accountNo는 위 분기에서 재할당됨)
        final String resolvedAccountNo = accountNo;

        if (accountPort.countByUserId(userId) >= MAX_ACCOUNTS_PER_USER) {
            throw new IllegalStateException("계좌는 최대 " + MAX_ACCOUNTS_PER_USER + "개까지 등록 가능합니다");
        }
        // 전역 계좌번호 중복 체크 (크로스-유저, 해시 기반 — V11 이후 신규 등록에만 적용)
        if (accountPort.existsByAccountNo(resolvedAccountNo)) {
            throw new Account.DuplicateAccountException(resolvedAccountNo);
        }
        // 동일 사용자 중복 체크 (V11 이전 NULL-hash 기존 레코드 대비 fallback)
        accountPort.findByUserId(userId).stream()
                .filter(a -> a.accountNo().equals(resolvedAccountNo))
                .findAny()
                .ifPresent(a -> { throw new Account.DuplicateAccountException(resolvedAccountNo); });
        // 증권사별 자격증명+계좌 검증 — KIS는 accountNo 소유 검증 후 null, Toss는 accountSeq 반환
        String brokerAccountCode = connectionTesters.of(broker)
                .verifyAccount(appKey, secretKey, accountNo);

        Account account = new Account(
                null, userId, cmd.nickname(),
                accountNo, appKey, secretKey,
                brokerAccountCode,
                broker,
                null    // createdAt — DB에서 자동 설정
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
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 외부 API 호출 — 트랜잭션 불필요
    public void test(Account.Broker broker, String appKey, String appSecret, UUID accountId) {
        requireBrokerEnabled(broker);
        connectionTesters.of(broker).verifyCredentials(appKey, appSecret, accountId);
    }

    private void requireBrokerEnabled(Account.Broker broker) {
        // 연결 검증 전에 차단해 비활성 증권사 자격증명이 외부 API로 전달되지 않게 한다.
        if (!runtimeSettingsPort.load().brokers().get(broker).enabled()) {
            throw new IllegalArgumentException(broker + " 증권사 신규 계좌 등록이 비활성화되어 있습니다");
        }
    }

    // MOCK 계좌 등록용 합성 계좌번호 생성 — KIS 형식(XXXXXXXX-XX)을 재사용해 기존 표시·마스킹 로직과 호환
    // 충돌 시(전역 유니크 제약) 재시도 — 공간이 넓어(10^10) 실질적으로 1회 안에 끝나지만 방어적으로 유한 재시도한다
    private String generateMockAccountNo() {
        for (int attempt = 0; attempt < 10; attempt++) {
            int digits = ThreadLocalRandom.current().nextInt(100_000_000);
            int suffix = ThreadLocalRandom.current().nextInt(100);
            String candidate = "%08d-%02d".formatted(digits, suffix);
            if (!accountPort.existsByAccountNo(candidate)) return candidate;
        }
        throw new IllegalStateException("모의계좌 번호 생성 재시도 초과");
    }
}
