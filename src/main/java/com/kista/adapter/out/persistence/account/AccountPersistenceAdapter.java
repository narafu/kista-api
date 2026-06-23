package com.kista.adapter.out.persistence.account;

import com.kista.adapter.out.crypto.AccountNoHasher;
import com.kista.adapter.out.crypto.AesCryptoService;
import com.kista.domain.model.account.Account;
import com.kista.domain.port.out.AccountPort;
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
    private final AesCryptoService crypto;
    private final AccountNoHasher hasher;

    @Override
    public List<Account> findByUserId(UUID userId) {
        return jpaRepository.findByUserId(userId).stream()
                .map(this::toDomain)
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
        return jpaRepository.findAll().stream()
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
    public boolean existsByAccountNo(String accountNo) {
        // 플레인텍스트 해시 후 DB 조회 — AES-GCM은 비결정론적이므로 해시 경유 필수
        return jpaRepository.existsByAccountNoHashAndDeletedAtIsNull(hasher.hash(accountNo));
    }

    // Account 도메인 모델 → 암호화 후 Entity 변환
    private AccountEntity toEntity(Account a) {
        AccountEntity e = new AccountEntity();
        e.setId(a.id()); // null이면 @GeneratedValue가 UUID 생성
        e.setUserId(a.userId());
        e.setNickname(a.nickname());
        e.setAccountNo(crypto.encrypt(a.accountNo()));
        e.setAccountNoHash(hasher.hash(a.accountNo())); // 전역 중복 체크용 HMAC-SHA256 해시
        e.setAppKey(crypto.encrypt(a.appKey()));
        e.setSecretKey(crypto.encrypt(a.secretKey()));
        e.setBrokerAccountCode(a.brokerAccountCode());
        e.setBroker(a.broker() != null ? a.broker() : Account.Broker.KIS); // null 방어 — persistence 경계에서 보장
        return e;
    }

    // Entity → 복호화 후 Account 도메인 모델 변환
    private Account toDomain(AccountEntity e) {
        return new Account(
                e.getId(), e.getUserId(), e.getNickname(),
                crypto.decrypt(e.getAccountNo()),
                crypto.decrypt(e.getAppKey()),
                crypto.decrypt(e.getSecretKey()),
                e.getBrokerAccountCode(),
                e.getBroker(),
                e.getCreatedAt()
        );
    }
}
