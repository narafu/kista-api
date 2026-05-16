package com.kista.adapter.out.persistence;

import com.kista.adapter.out.crypto.AesCryptoService;
import com.kista.domain.model.Account;
import com.kista.domain.model.Ticker;
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

        StrategyEntity strategyEntity;
        if (account.id() == null) {
            // 신규 계좌 - strategy 생성
            strategyEntity = new StrategyEntity();
            strategyEntity.setAccountId(saved.getId());
            strategyEntity.setType(account.strategyType());
            strategyEntity.setStatus(account.strategyStatus());
        } else {
            // 기존 계좌 - strategy type/status 업데이트
            strategyEntity = strategyJpaRepository
                    .findByAccountId(account.id()).orElseThrow();
            strategyEntity.setType(account.strategyType());
            strategyEntity.setStatus(account.strategyStatus());
        }
        strategyJpaRepository.save(strategyEntity);

        return buildDomain(saved, strategyEntity); // 이중 findByAccountId 방지
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
        e.setSymbol(a.ticker().name());
        e.setCreatedAt(a.createdAt()); // null이면 @CreatedDate가 INSERT 시 자동 설정
        return e;
    }

    // Entity → 복호화 후 Account 도메인 모델 변환 (strategy는 strategies 테이블에서 로드)
    private Account toDomain(AccountEntity e) {
        StrategyEntity s = strategyJpaRepository.findByAccountId(e.getId()).orElseThrow();
        return buildDomain(e, s);
    }

    // 이미 로드된 StrategyEntity를 받아 Account 조합 — save()에서 이중 쿼리 방지
    private Account buildDomain(AccountEntity e, StrategyEntity s) {
        return new Account(
                e.getId(), e.getUserId(), e.getNickname(),
                crypto.decrypt(e.getAccountNo()),
                crypto.decrypt(e.getKisAppKey()),
                crypto.decrypt(e.getKisSecretKey()),
                e.getKisAccountType(), s.getType(), s.getStatus(),
                e.getTelegramBotToken() != null ? crypto.decrypt(e.getTelegramBotToken()) : null,
                e.getTelegramChatId(), Ticker.valueOf(e.getSymbol()),
                e.getCreatedAt(), e.getUpdatedAt()
        );
    }
}
