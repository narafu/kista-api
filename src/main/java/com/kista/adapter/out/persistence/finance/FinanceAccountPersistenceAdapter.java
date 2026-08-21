package com.kista.adapter.out.persistence.finance;

import com.kista.adapter.out.crypto.AesCryptoService;
import com.kista.domain.model.finance.FinanceAccount;
import com.kista.domain.port.out.FinanceAccountPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FinanceAccountPersistenceAdapter implements FinanceAccountPort {

    private final FinanceAccountJpaRepository jpaRepository;
    private final AesCryptoService crypto; // accountNo AES-256 암호화/복호화

    @Override
    public List<FinanceAccount> findMyScope(UUID userId, UUID currentGroupId) {
        return jpaRepository.findMyScope(userId, currentGroupId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<FinanceAccount> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<FinanceAccount> findActiveById(UUID id) {
        return jpaRepository.findByIdAndDeletedAtIsNull(id).map(this::toDomain);
    }

    @Override
    public FinanceAccount save(FinanceAccount account) {
        // V16에서 uq_finance_accounts_group_name을 DROP — 같은 그룹 안 계좌명 중복이 이제 허용되므로
        // 더 이상 DataIntegrityViolationException을 DuplicateNameException으로 변환할 이유가 없다.
        FinanceAccountEntity entity = FinanceAccountEntity.fromModel(account);
        if (entity.getAccountNo() != null) {
            entity.setAccountNo(crypto.encrypt(entity.getAccountNo()));
        }
        return toDomain(jpaRepository.saveAndFlush(entity));
    }

    @Override
    public void softDelete(UUID id) {
        jpaRepository.softDeleteById(id, Instant.now());
    }

    @Override
    public void softDeleteByUserId(UUID userId) {
        jpaRepository.softDeleteByUserId(userId, Instant.now());
    }

    // persistence 경계에서 accountNo 복호화
    private FinanceAccount toDomain(FinanceAccountEntity e) {
        FinanceAccount raw = FinanceAccountEntity.toDomain(e);
        if (raw.accountNo() == null) {
            return raw;
        }
        return new FinanceAccount(raw.id(), raw.groupId(), raw.userId(), raw.accountType(), raw.name(),
                crypto.decrypt(raw.accountNo()), raw.memo(), raw.createdAt());
    }
}
