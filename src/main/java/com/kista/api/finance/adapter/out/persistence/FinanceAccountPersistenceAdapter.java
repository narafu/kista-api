package com.kista.api.finance.adapter.out.persistence;

import com.kista.shared.platform.crypto.AccountNoHasher;
import com.kista.api.finance.domain.model.FinanceAccount;
import com.kista.api.finance.application.port.output.FinanceAccountPort;
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
    private final AccountNoHasher hasher;  // accountNo 전역 중복 체크용 HMAC-SHA256 해시

    @Override
    public List<FinanceAccount> findMyScope(UUID userId, UUID currentGroupId) {
        return jpaRepository.findMyScope(userId, currentGroupId).stream()
                .map(FinanceAccountEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<FinanceAccount> findById(UUID id) {
        return jpaRepository.findById(id).map(FinanceAccountEntity::toDomain);
    }

    @Override
    public Optional<FinanceAccount> findActiveById(UUID id) {
        return jpaRepository.findByIdAndDeletedAtIsNull(id).map(FinanceAccountEntity::toDomain);
    }

    @Override
    public boolean existsByAccountNo(String accountNo, UUID excludeId) {
        // 플레인텍스트 해시 후 DB 조회 — AES는 비결정론적이라 해시 경유 필수
        String hash = hasher.hash(accountNo);
        return excludeId == null
                ? jpaRepository.existsByAccountNoHashAndDeletedAtIsNull(hash)
                : jpaRepository.existsByAccountNoHashAndDeletedAtIsNullAndIdNot(hash, excludeId);
    }

    @Override
    public FinanceAccount save(FinanceAccount account) {
        // V16에서 uq_finance_accounts_group_name을 DROP — 같은 그룹 안 계좌명 중복이 이제 허용되므로
        // 더 이상 DataIntegrityViolationException을 DuplicateNameException으로 변환할 이유가 없다.
        FinanceAccountEntity entity = FinanceAccountEntity.fromModel(account);
        if (entity.getAccountNo() != null) {
            entity.setAccountNoHash(hasher.hash(entity.getAccountNo())); // 전역 중복 체크용 해시 — 평문 기준
        }
        return FinanceAccountEntity.toDomain(jpaRepository.saveAndFlush(entity));
    }

    @Override
    public void softDelete(UUID id) {
        jpaRepository.softDeleteById(id, Instant.now());
    }

    @Override
    public void softDeleteByUserId(UUID userId) {
        jpaRepository.softDeleteByUserId(userId, Instant.now());
    }
}
