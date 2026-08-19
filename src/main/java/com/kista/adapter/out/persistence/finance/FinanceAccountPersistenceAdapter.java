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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FinanceAccountPersistenceAdapter implements FinanceAccountPort {

    private final FinanceAccountJpaRepository jpaRepository;
    private final AesCryptoService crypto; // accountNo AES-256 암호화/복호화

    @Override
    public List<FinanceAccount> findByGroupId(UUID groupId) {
        return jpaRepository.findByGroupIdAndDeletedAtIsNull(groupId).stream()
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
    public void softDeleteByCreatedBy(UUID userId) {
        jpaRepository.softDeleteByCreatedBy(userId, Instant.now());
    }

    @Override
    public void reassignGroup(UUID fromGroupId, UUID toGroupId, UUID createdBy) {
        // V16에서 uq_finance_accounts_group_name을 DROP해(계좌 이름 중복 허용) 더 이상 DB 제약으로
        // 이관 시 이름 충돌을 막을 수 없다 — 하지만 FinanceGroupService.leaveGroup()은 여전히 "이관
        // 대상 그룹에 이름이 겹치는 계좌가 있으면 조용히 병합하지 않고 사용자가 판단하게 실패시킨다"는
        // 계약을 문서화하고 있다(그룹 신규 등록 시의 "같은 이름 여러 계좌 허용"과는 다른 안전장치 —
        // 신규 등록은 사용자 본인이 의도적으로 만드는 계좌, 이관은 서로 다른 두 그룹의 계좌가 우연히
        // 이름이 겹쳐 알아채지 못한 채 합쳐지는 상황이라 구분한다). DB 제약이 사라졌으니 애플리케이션
        // 레벨에서 동일하게 검증한다.
        Set<String> targetNames = jpaRepository.findByGroupIdAndDeletedAtIsNull(toGroupId).stream()
                .map(FinanceAccountEntity::getName)
                .collect(Collectors.toSet());
        boolean hasCollision = jpaRepository.findByGroupIdAndDeletedAtIsNull(fromGroupId).stream()
                .filter(e -> e.getCreatedBy().equals(createdBy))
                .anyMatch(e -> targetNames.contains(e.getName()));
        if (hasCollision) {
            throw new FinanceAccount.DuplicateNameException("이관 대상 그룹에 이름이 겹치는 계좌가 있습니다");
        }
        jpaRepository.reassignGroup(fromGroupId, toGroupId, createdBy);
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
