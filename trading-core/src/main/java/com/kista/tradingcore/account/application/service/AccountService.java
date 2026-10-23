package com.kista.tradingcore.account.application.service;

import com.kista.tradingcore.broker.application.port.output.AccountTokenEvictionPort;
import com.kista.tradingcore.broker.application.service.BrokerConnectionTesters;
import com.kista.tradingcore.broker.domain.model.BrokerAccountVerification;
import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.account.domain.model.RegisterAccountCommand;
import com.kista.tradingcore.account.domain.model.UpdateAccountCommand;
import com.kista.tradingcore.account.domain.model.UpdateCredentialsCommand;
import com.kista.tradingcore.account.application.usecase.AccountUseCase;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.application.port.output.AccountOpenOrderCancelPort;
import com.kista.tradingcore.account.application.port.output.BrokerEnabledPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class AccountService implements AccountUseCase {

    private static final int MAX_ACCOUNTS_PER_USER = 10;

    private final AccountPort accountPort;
    private final BrokerConnectionTesters connectionTesters; // 증권사별 연결테스트 라우터
    private final BrokerEnabledPort brokerEnabledPort; // 증권사 신규 등록 허용 여부 (trading TradingPolicyService가 구현 — 정책 소유자)
    private final AccountTokenEvictionPort accountTokenEvictionPort; // 키 교체·탈퇴 시 증권사 토큰 캐시(KIS DB·Toss Redis) 제거
    private final AccountOpenOrderCancelPort openOrderCancelPort; // 계좌 삭제 전 미체결 주문 취소 (trading이 구현)
    private final AccountDeletionWriter deletionWriter; // 계좌 삭제 DB 쓰기 (취소 HTTP를 트랜잭션 밖에 두기 위해 분리)

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // Toss accountSeq 조회 HTTP 호출 포함 — 트랜잭션 없이 실행 (단건 저장은 JPA auto-commit)
    public Account register(UUID userId, RegisterAccountCommand cmd) {
        // broker 미지정 시 KIS 기본값 적용 후 신규 등록 정책을 확인한다.
        Broker broker = cmd.broker() != null ? cmd.broker() : Broker.KIS;
        requireBrokerEnabled(broker);

        // MOCK은 서버가 합성 자격증명 생성, UPBIT·MERITZ는 계좌번호 없이 키만, 그 외 브로커는 계좌번호 필수
        String accountNo = cmd.accountNo();
        String appKey = cmd.appKey();
        String secretKey = cmd.secretKey();
        if (broker == Broker.MOCK) {
            if (accountNo == null || accountNo.isBlank()) accountNo = generateMockAccountNo();
            if (appKey == null || appKey.isBlank()) appKey = "MOCK";
            if (secretKey == null || secretKey.isBlank()) secretKey = "MOCK";
        } else if (broker == Broker.UPBIT || broker == Broker.MERITZ) {
            accountNo = null; // 업비트·메리츠는 앱키에 계좌가 묶여 계좌번호를 받지 않는다 — 요청에 와도 무시(저장 안 함)
            if (appKey == null || appKey.isBlank() || secretKey == null || secretKey.isBlank()) {
                throw new IllegalArgumentException("API 키와 시크릿은 필수입니다");
            }
        } else if (accountNo == null || accountNo.isBlank()) {
            throw new IllegalArgumentException("계좌번호는 필수입니다");
        }
        // 람다 캡처를 위한 effectively-final 복사본 (accountNo는 위 분기에서 재할당됨)
        final String resolvedAccountNo = accountNo;

        if (accountPort.countByUserId(userId) >= MAX_ACCOUNTS_PER_USER) {
            throw new IllegalStateException("계좌는 최대 " + MAX_ACCOUNTS_PER_USER + "개까지 등록 가능합니다");
        }
        // 전역 중복 체크 (크로스-유저, 해시 기반) — 업비트는 access key, 그 외 계좌번호
        String dedupeKey = Account.dedupeKeyOf(broker, resolvedAccountNo, appKey);
        if (accountPort.existsByDedupeKey(dedupeKey)) {
            throw new Account.DuplicateAccountException(dedupeKey);
        }
        // 동일 사용자 중복 체크 (V11 이전 NULL-hash 기존 레코드 대비 fallback) — 업비트 기존 계좌는 accountNo null이라 null-safe 비교
        if (resolvedAccountNo != null) {
            accountPort.findByUserId(userId).stream()
                    .filter(a -> resolvedAccountNo.equals(a.accountNo()))
                    .findAny()
                    .ifPresent(a -> { throw new Account.DuplicateAccountException(resolvedAccountNo); });
        }
        // 증권사별 자격증명+계좌 검증 — KIS: 계좌번호 소유, Toss: accountSeq, 업비트: 키 만료일
        BrokerAccountVerification verified = connectionTesters.of(broker)
                .verifyAccount(appKey, secretKey, accountNo);

        Account account = new Account(
                null, userId, cmd.nickname(),
                accountNo, appKey, secretKey,
                verified.brokerAccountCode(),
                broker,
                null,    // createdAt — DB에서 자동 설정
                verified.apiKeyExpiresAt()
        );
        Account saved = accountPort.save(account);
        log.info("계좌 등록: userId={}, accountId={}, broker={}", userId, saved.id(), broker);
        return saved;
    }

    private static final String DEDUPE_UNIQUE_INDEX = "uq_accounts_account_no_hash"; // accounts.account_no_hash(중복 판정 키 해시) 부분 unique 인덱스

    @Override
    public Account update(UUID accountId, UUID requesterId, UpdateAccountCommand cmd) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        // 수정 요청은 등록 전용 제약(@Pattern 계좌번호 등) 때문에 @Valid 없이 받는다 — 별명 필수만 여기서 확인
        if (cmd.nickname() == null || cmd.nickname().isBlank()) {
            throw new IllegalArgumentException("계좌 별명을 입력해 주세요.");
        }
        return accountPort.save(account.withNickname(cmd.nickname().strip()));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 증권사 검증 HTTP 포함 — 트랜잭션 없이 실행(단건 저장은 JPA auto-commit)
    public Account updateCredentials(UUID accountId, UUID requesterId, UpdateCredentialsCommand cmd) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        if (account.broker() == Broker.MOCK) {
            throw new IllegalArgumentException("모의계좌는 API 키를 바꿀 수 없습니다.");
        }
        // 업비트·메리츠는 앱키가 곧 중복 판정 키 — 다른 계좌가 이미 쓰는 키면 거부(같은 키 재입력은 허용)
        if ((account.broker() == Broker.UPBIT || account.broker() == Broker.MERITZ) && !cmd.appKey().equals(account.appKey())
                && accountPort.existsByDedupeKey(cmd.appKey())) {
            throw new Account.DuplicateAccountException(cmd.appKey());
        }
        // 키 교체는 신규 등록이 아니라 requireBrokerEnabled를 적용하지 않는다 — KIS는 저장된 계좌번호 소유까지 검증
        BrokerAccountVerification verified = connectionTesters.of(account.broker())
                .verifyAccount(cmd.appKey(), cmd.secretKey(), account.accountNo());
        if (account.broker() == Broker.TOSS && !Objects.equals(verified.brokerAccountCode(), account.brokerAccountCode())) {
            throw new IllegalArgumentException("등록된 계좌와 다른 계좌의 API 키입니다.");
        }
        Account saved;
        try {
            saved = accountPort.save(account.withCredentials(
                    cmd.appKey(), cmd.secretKey(), account.brokerAccountCode(), verified.apiKeyExpiresAt()));
        } catch (DataIntegrityViolationException e) {
            // 사전 중복 검사와 저장 사이 다른 계좌가 같은 키를 등록한 경합 — dedupe unique 위반만 409로, 그 외 무결성 위반은 그대로 전파
            String cause = e.getMostSpecificCause().getMessage();
            if (cause == null || !cause.contains(DEDUPE_UNIQUE_INDEX)) throw e;
            throw new Account.DuplicateAccountException(cmd.appKey());
        }
        accountTokenEvictionPort.evict(List.of(accountId)); // 옛 키로 발급된 KIS·Toss 토큰 캐시 제거
        log.info("계좌 키 교체: accountId={}, broker={}", accountId, account.broker());
        return saved;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 증권사 주문 취소 HTTP 포함 — DB 쓰기는 AccountDeletionWriter 짧은 트랜잭션
    public void delete(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        // 미체결 주문을 먼저 정리 — 하나라도 증권사 취소에 실패하면 삭제하지 않는다(삭제된 계좌의 주문이 증권사에 남지 않도록)
        if (openOrderCancelPort.cancelOpenOrders(account) > 0) {
            throw new IllegalStateException("증권사 주문 취소에 실패한 주문이 있어 계좌를 삭제할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
        deletionWriter.delete(accountId);
        log.info("계좌 삭제: accountId={}, requesterId={}", accountId, requesterId);
    }

    @Override
    public void deleteAllByUserId(UUID userId) {
        // 소프트 삭제 후엔 @SQLRestriction이 계좌를 숨기므로 토큰 대상 ID를 먼저 확보한다
        List<UUID> accountIds = accountPort.findByUserId(userId).stream().map(Account::id).toList();
        accountTokenEvictionPort.evict(accountIds);
        accountPort.deleteByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Account> listByUser(UUID userId) {
        return accountPort.findByUserId(userId);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 외부 API 호출 — 트랜잭션 불필요
    public void test(Broker broker, String appKey, String appSecret) {
        requireBrokerEnabled(broker);
        connectionTesters.of(broker).verifyCredentials(appKey, appSecret);
    }

    private void requireBrokerEnabled(Broker broker) {
        // 연결 검증 전에 차단해 비활성 증권사 자격증명이 외부 API로 전달되지 않게 한다.
        if (!brokerEnabledPort.enabled(broker)) {
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
            if (!accountPort.existsByDedupeKey(candidate)) return candidate;
        }
        throw new IllegalStateException("모의계좌 번호 생성 재시도 초과");
    }
}
