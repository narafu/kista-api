package com.kista.adapter.out.persistence.finance;

import com.kista.domain.model.finance.FinanceTransaction;
import com.kista.domain.port.out.FinanceTransactionPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FinanceTransactionPersistenceAdapter implements FinanceTransactionPort {

    private final FinanceTransactionJpaRepository jpaRepository;

    @Override
    public List<FinanceTransaction> findByGroupId(UUID groupId, LocalDate from, LocalDate to, UUID categoryId, UUID createdBy) {
        return jpaRepository.findByGroupId(groupId, from, to, categoryId, createdBy).stream()
                .map(FinanceTransactionEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<FinanceTransaction> findById(UUID id) {
        return jpaRepository.findById(id).map(FinanceTransactionEntity::toDomain);
    }

    @Override
    public FinanceTransaction save(FinanceTransaction transaction) {
        FinanceTransactionEntity entity = FinanceTransactionEntity.fromModel(transaction);
        return FinanceTransactionEntity.toDomain(jpaRepository.save(entity));
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
        jpaRepository.reassignGroup(fromGroupId, toGroupId, createdBy);
    }
}
