package com.kista.finance.adapter.out.persistence;

import com.kista.finance.domain.model.FinanceBudget;
import com.kista.finance.domain.port.out.FinanceBudgetPort;
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
    public List<FinanceBudget> findOverlapping(UUID userId, UUID categoryId, LocalDate startDate, LocalDate endDate) {
        return jpaRepository.findOverlapping(userId, categoryId, startDate, endDate).stream()
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
        // 파생 설정이므로 하드 삭제. 즉시 flush — create()의 겹침 자동조정에서 삭제 후 신규 INSERT가
        // 같은 트랜잭션 내에서 이어질 때, flush 없이 두면 Hibernate가 같은 flush 배치 안에서
        // INSERT를 DELETE보다 먼저 실행해 EXCLUDE 제약(아직 안 지워진 기존 행과 겹침) 위반으로
        // 오탐 409가 날 수 있다.
        jpaRepository.deleteById(id);
        jpaRepository.flush();
    }

    @Override
    public void deleteByUserId(UUID userId) {
        jpaRepository.deleteByUserId(userId);
    }
}
