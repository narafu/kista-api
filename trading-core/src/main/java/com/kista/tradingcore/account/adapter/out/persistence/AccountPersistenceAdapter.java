package com.kista.tradingcore.account.adapter.out.persistence;

import com.kista.shared.platform.crypto.AccountNoHasher;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AccountPersistenceAdapter implements AccountPort {

    private final AccountJpaRepository jpaRepository;
    private final AccountNoHasher hasher;

    @Override
    public List<Account> findByUserId(UUID userId) {
        return jpaRepository.findByUserId(userId).stream()
                .map(this::toDomain)
                .sorted(Account.DISPLAY_ORDER) // 계좌 목록·전체 전략 목록 공통 표시 순서
                .toList();
    }

    @Override
    public Optional<Account> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public int countByUserId(UUID userId) {
        return jpaRepository.countByUserId(userId);
    }

    @Override
    public List<Account> findAll() {
        return jpaRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Account save(Account account) {
        AccountEntity entity = toEntity(account);
        AccountEntity saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public void delete(UUID id) {
        jpaRepository.softDeleteById(id, Instant.now());
    }

    @Override
    public void deleteByUserId(UUID userId) {
        jpaRepository.softDeleteByUserId(userId, Instant.now());
    }

    @Override
    public long countAll() {
        return jpaRepository.count();
    }

    @Override
    public boolean existsByDedupeKey(String dedupeKey) {
        // 플레인텍스트 해시 후 DB 조회 — AES-GCM은 비결정론적이므로 해시 경유 필수
        return jpaRepository.existsByAccountNoHashAndDeletedAtIsNull(hasher.hash(dedupeKey));
    }

    // Account 도메인 모델 → Entity 변환 (암호화는 AesStringConverter, 해시만 여기서 평문으로 계산)
    private AccountEntity toEntity(Account a) {
        AccountEntity e = new AccountEntity();
        e.setId(a.id()); // null이면 @UuidGenerator가 UUID v7 생성
        e.setUserId(a.userId());
        e.setNickname(a.nickname());
        e.setAccountNo(a.accountNo()); // 업비트는 계좌번호 없음(null)
        e.setAccountNoHash(hasher.hash(a.dedupeKey())); // 전역 중복 체크용 HMAC-SHA256 해시 — 업비트는 access key
        e.setAppKey(a.appKey());
        e.setSecretKey(a.secretKey());
        e.setBrokerAccountCode(a.brokerAccountCode());
        e.setApiKeyExpiresAt(a.apiKeyExpiresAt());
        e.setBroker(a.broker());
        return e;
    }

    // Entity → Account 도메인 모델 변환
    private Account toDomain(AccountEntity e) {
        return new Account(
                e.getId(), e.getUserId(), e.getNickname(),
                e.getAccountNo(), e.getAppKey(), e.getSecretKey(),
                e.getBrokerAccountCode(),
                e.getBroker(),
                e.getCreatedAt(),
                e.getApiKeyExpiresAt()
        );
    }
}
