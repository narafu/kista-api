package com.kista.adapter.out.persistence;

import com.kista.adapter.out.crypto.AesCryptoService;
import com.kista.domain.model.Account;
import com.kista.domain.port.out.AccountRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AccountPersistenceAdapter implements AccountRepository {

    private final AccountJpaRepository jpaRepository;
    private final AesCryptoService crypto;

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
    public List<Account> findAllActive() {
        return jpaRepository.findAllActive().stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Account save(Account account) {
        AccountEntity entity = toEntity(account);
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    public void delete(UUID id) {
        jpaRepository.deleteById(id);
    }

    // Account 도메인 모델 → 암호화 후 Entity 변환
    private AccountEntity toEntity(Account a) {
        AccountEntity e = new AccountEntity();
        e.setId(a.id()); // null이면 @GeneratedValue가 UUID 생성
        e.setUserId(a.userId());
        e.setNickname(a.nickname());
        e.setAccountNo(crypto.encrypt(a.accountNo()));
        e.setKisAppKey(crypto.encrypt(a.kisAppKey()));
        e.setKisSecretKey(crypto.encrypt(a.kisSecretKey()));
        e.setKisAccountType(a.kisAccountType());
        e.setStrategy(a.strategy());
        e.setStrategyStatus(a.strategyStatus());
        e.setTelegramBotToken(a.telegramBotToken() != null ? crypto.encrypt(a.telegramBotToken()) : null);
        e.setTelegramChatId(a.telegramChatId());
        e.setCreatedAt(a.createdAt() != null ? a.createdAt() : Instant.now());
        e.setUpdatedAt(a.updatedAt() != null ? a.updatedAt() : Instant.now());
        return e;
    }

    // Entity → 복호화 후 Account 도메인 모델 변환
    private Account toDomain(AccountEntity e) {
        return new Account(
                e.getId(), e.getUserId(), e.getNickname(),
                crypto.decrypt(e.getAccountNo()),
                crypto.decrypt(e.getKisAppKey()),
                crypto.decrypt(e.getKisSecretKey()),
                e.getKisAccountType(), e.getStrategy(), e.getStrategyStatus(),
                e.getTelegramBotToken() != null ? crypto.decrypt(e.getTelegramBotToken()) : null,
                e.getTelegramChatId(), e.getCreatedAt(), e.getUpdatedAt()
        );
    }
}
