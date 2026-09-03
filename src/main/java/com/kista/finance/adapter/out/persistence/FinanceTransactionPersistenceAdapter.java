package com.kista.finance.adapter.out.persistence;

import com.kista.finance.domain.model.FinanceTransaction;
import com.kista.finance.application.port.output.FinanceTransactionPort;
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
    public List<FinanceTransaction> findMyScope(UUID userId, UUID currentGroupId, LocalDate from, LocalDate to, UUID categoryId, UUID filterUserId) {
        return jpaRepository.findMyScope(userId, currentGroupId, from, to, categoryId, filterUserId).stream()
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
    public void softDeleteByUserId(UUID userId) {
        jpaRepository.softDeleteByUserId(userId, Instant.now());
    }
}
