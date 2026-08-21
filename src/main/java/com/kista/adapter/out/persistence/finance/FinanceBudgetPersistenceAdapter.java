package com.kista.adapter.out.persistence.finance;

import com.kista.domain.model.finance.FinanceBudget;
import com.kista.domain.port.out.FinanceBudgetPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FinanceBudgetPersistenceAdapter implements FinanceBudgetPort {

    private final FinanceBudgetJpaRepository jpaRepository;

    @Override
    public List<FinanceBudget> findMyScope(UUID userId, UUID currentGroupId, UUID categoryId, LocalDate date) {
        return jpaRepository.findMyScope(userId, currentGroupId, categoryId, date).stream()
                .map(FinanceBudgetEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<FinanceBudget> findById(UUID id) {
        return jpaRepository.findById(id).map(FinanceBudgetEntity::toDomain);
    }

    @Override
    public FinanceBudget save(FinanceBudget budget) {
        FinanceBudgetEntity entity = FinanceBudgetEntity.fromModel(budget);
        try {
            // saveAndFlush 필수 — save()만 쓰면 finance_budgets_no_overlap EXCLUDE 위반이 커밋 시점에
            // 터져 이 어댑터 밖으로 새고, 매핑 없는 500으로 떨어진다 (§4.1).
            return FinanceBudgetEntity.toDomain(jpaRepository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException e) {
            throw new FinanceBudget.OverlappingPeriodException("해당 기간에 이미 적용 중인 예산이 있습니다");
        }
    }

    @Override
    public void delete(UUID id) {
        // 파생 설정이므로 하드 삭제
        jpaRepository.deleteById(id);
    }

    @Override
    public void deleteByUserId(UUID userId) {
        jpaRepository.deleteByUserId(userId);
    }
}
