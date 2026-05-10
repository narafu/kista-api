package com.kista.adapter.out.persistence;

import com.kista.adapter.out.crypto.AesCryptoService;
import com.kista.domain.model.Account;
import com.kista.domain.port.out.AccountRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AccountPersistenceAdapter implements AccountRepository {

    private final AccountJpaRepository jpaRepository;
    private final StrategyJpaRepository strategyJpaRepository;
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
        AccountEntity saved = jpaRepository.save(entity);

        if (account.id() == null) {
            // 신규 계좌 - strategy 생성
            StrategyEntity strategyEntity = new StrategyEntity();
            strategyEntity.setAccountId(saved.getId());
            strategyEntity.setType(account.strategy());
            strategyEntity.setStatus(account.strategyStatus());
            strategyJpaRepository.save(strategyEntity);
        } else {
            // 기존 계좌 - strategy status/type 업데이트
            StrategyEntity strategyEntity = strategyJpaRepository
                    .findByAccountId(account.id()).orElseThrow();
            strategyEntity.setType(account.strategy());
            strategyEntity.setStatus(account.strategyStatus());
            strategyJpaRepository.save(strategyEntity);
        }

        return toDomain(saved);
    }

    @Override
    public void delete(UUID id) {
        jpaRepository.deleteById(id); // strategies는 ON DELETE CASCADE로 자동 삭제
    }

    // Account 도메인 모델 → 암호화 후 Entity 변환 (strategy 필드 제외 — strategies 테이블 별도 관리)
    private AccountEntity toEntity(Account a) {
        AccountEntity e = new AccountEntity();
        e.setId(a.id()); // null이면 @GeneratedValue가 UUID 생성
        e.setUserId(a.userId());
        e.setNickname(a.nickname());
        e.setAccountNo(crypto.encrypt(a.accountNo()));
        e.setKisAppKey(crypto.encrypt(a.kisAppKey()));
        e.setKisSecretKey(crypto.encrypt(a.kisSecretKey()));
        e.setKisAccountType(a.kisAccountType());
        e.setTelegramBotToken(a.telegramBotToken() != null ? crypto.encrypt(a.telegramBotToken()) : null);
        e.setTelegramChatId(a.telegramChatId());
        e.setSymbol(a.symbol());
        e.setExchangeCode(a.exchangeCode());
        e.setCreatedAt(a.createdAt()); // null이면 @CreatedDate가 INSERT 시 자동 설정
        return e;
    }

    // Entity → 복호화 후 Account 도메인 모델 변환 (strategy는 strategies 테이블에서 로드)
    private Account toDomain(AccountEntity e) {
        StrategyEntity s = strategyJpaRepository.findByAccountId(e.getId()).orElseThrow();
        return new Account(
                e.getId(), e.getUserId(), e.getNickname(),
                crypto.decrypt(e.getAccountNo()),
                crypto.decrypt(e.getKisAppKey()),
                crypto.decrypt(e.getKisSecretKey()),
                e.getKisAccountType(), s.getType(), s.getStatus(),
                e.getTelegramBotToken() != null ? crypto.decrypt(e.getTelegramBotToken()) : null,
                e.getTelegramChatId(), e.getSymbol(), e.getExchangeCode(),
                e.getCreatedAt(), e.getUpdatedAt()
        );
    }
}
