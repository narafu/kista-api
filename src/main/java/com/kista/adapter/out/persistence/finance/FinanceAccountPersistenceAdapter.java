package com.kista.adapter.out.persistence.finance;

import com.kista.adapter.out.crypto.AesCryptoService;
import com.kista.domain.model.finance.FinanceAccount;
import com.kista.domain.port.out.FinanceAccountPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
    public List<FinanceAccount> findByGroupId(UUID groupId) {
        return jpaRepository.findByGroupId(groupId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<FinanceAccount> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public FinanceAccount save(FinanceAccount account) {
        FinanceAccountEntity entity = FinanceAccountEntity.fromModel(account);
        if (entity.getAccountNo() != null) {
            entity.setAccountNo(crypto.encrypt(entity.getAccountNo()));
        }
        try {
            // saveAndFlush로 uq_finance_accounts_group_name 위반을 이 어댑터 안에서 즉시 터뜨린다
            return toDomain(jpaRepository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException e) {
            throw new FinanceAccount.DuplicateNameException(account.name());
        }
    }

    @Override
    public void softDelete(UUID id) {
        jpaRepository.softDeleteById(id, Instant.now());
    }

    @Override
    public void softDeleteByCreatedBy(UUID userId) {
        jpaRepository.softDeleteByCreatedBy(userId, Instant.now());
    }

    @Override
    public void reassignGroup(UUID fromGroupId, UUID toGroupId, UUID createdBy) {
        // 그룹 이탈 이관 대상 그룹에 같은 이름의 계좌가 이미 있으면 uq_finance_accounts_group_name 위반 —
        // save()와 동일하게 여기서도 DuplicateNameException(기존 409 매핑)으로 변환한다. @Modifying 벌크
        // UPDATE는 호출 즉시(플러시 대기 없이) 실행되므로 saveAndFlush 없이도 예외가 바로 던져진다.
        try {
            jpaRepository.reassignGroup(fromGroupId, toGroupId, createdBy);
        } catch (DataIntegrityViolationException e) {
            throw new FinanceAccount.DuplicateNameException("이관 대상 그룹에 이름이 겹치는 계좌가 있습니다");
        }
    }

    // persistence 경계에서 accountNo 복호화
    private FinanceAccount toDomain(FinanceAccountEntity e) {
        FinanceAccount raw = FinanceAccountEntity.toDomain(e);
        if (raw.accountNo() == null) {
            return raw;
        }
        return new FinanceAccount(raw.id(), raw.groupId(), raw.createdBy(), raw.accountType(), raw.name(),
                crypto.decrypt(raw.accountNo()), raw.memo(), raw.createdAt());
    }
}
