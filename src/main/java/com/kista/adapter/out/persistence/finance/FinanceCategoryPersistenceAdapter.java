package com.kista.adapter.out.persistence.finance;

import com.kista.domain.model.finance.FinanceCategory;
import com.kista.domain.port.out.FinanceCategoryPort;
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
    public List<FinanceCategory> findSelectableByGroup(UUID groupId, FinanceCategory.Type type) {
        return jpaRepository.findSelectableByGroup(groupId, type == null ? null : type.name()).stream()
                .map(FinanceCategoryEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<FinanceCategory> findById(UUID id) {
        // §4.2 (B) — 필터 없음, 소프트 삭제된 카테고리도 과거 거래 렌더링용으로 조회돼야 한다
        return jpaRepository.findById(id).map(FinanceCategoryEntity::toDomain);
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
    public void softDeleteByCreatedBy(UUID userId) {
        jpaRepository.softDeleteByCreatedBy(userId, Instant.now());
    }

    @Override
    public void reassignGroup(UUID fromGroupId, UUID toGroupId, UUID createdBy) {
        // 그룹 이탈 이관 대상 그룹에 같은 부모·이름의 카테고리가 이미 있으면 uq_finance_categories_group_parent_name
        // 위반 — save()와 동일하게 여기서도 DuplicateNameException(기존 409 매핑)으로 변환한다.
        // @Modifying 벌크 UPDATE는 호출 즉시(플러시 대기 없이) 실행되므로 saveAndFlush 없이도 예외가 바로 던져진다.
        try {
            jpaRepository.reassignGroup(fromGroupId, toGroupId, createdBy);
        } catch (DataIntegrityViolationException e) {
            throw new FinanceCategory.DuplicateNameException("이관 대상 그룹에 이름이 겹치는 카테고리가 있습니다");
        }
    }
}
