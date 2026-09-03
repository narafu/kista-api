package com.kista.finance.adapter.out.persistence;

import com.kista.finance.domain.model.FinanceCategory;
import com.kista.finance.application.port.output.FinanceCategoryPort;
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
public class FinanceCategoryPersistenceAdapter implements FinanceCategoryPort {

    private final FinanceCategoryJpaRepository jpaRepository;

    @Override
    public List<FinanceCategory> findSelectable(UUID userId, UUID currentGroupId, FinanceCategory.Type type) {
        return jpaRepository.findSelectable(userId, currentGroupId, type == null ? null : type.name()).stream()
                .map(FinanceCategoryEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<FinanceCategory> findById(UUID id) {
        // §4.2 (B) — 필터 없음, 소프트 삭제된 카테고리도 과거 거래 렌더링용으로 조회돼야 한다
        return jpaRepository.findById(id).map(FinanceCategoryEntity::toDomain);
    }

    @Override
    public Optional<FinanceCategory> findActiveById(UUID id) {
        return jpaRepository.findByIdAndDeletedAtIsNull(id).map(FinanceCategoryEntity::toDomain);
    }

    @Override
    public FinanceCategory save(FinanceCategory category) {
        FinanceCategoryEntity entity = FinanceCategoryEntity.fromModel(category);
        try {
            // saveAndFlush로 uq_finance_categories_group_parent_name 위반을 이 어댑터 안에서 즉시 터뜨린다
            return FinanceCategoryEntity.toDomain(jpaRepository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException e) {
            throw new FinanceCategory.DuplicateNameException(category.name());
        }
    }

    @Override
    public void softDeleteWithChildren(UUID id) {
        jpaRepository.softDeleteWithChildren(id, Instant.now());
    }

    @Override
    public void softDeleteByUserId(UUID userId) {
        jpaRepository.softDeleteByUserId(userId, Instant.now());
    }

    @Override
    public void shareToGroupWithChildren(UUID id, UUID groupId) {
        jpaRepository.shareToGroupWithChildren(id, groupId);
    }

    @Override
    public void unshareWithChildren(UUID id) {
        jpaRepository.unshareWithChildren(id);
    }
}
