package com.kista.adapter.out.persistence.finance;

import com.kista.domain.model.finance.MonthlyClosing;
import com.kista.domain.port.out.MonthlyClosingPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FinanceMonthlyClosingPersistenceAdapter implements MonthlyClosingPort {

    private final FinanceMonthlyClosingJpaRepository jpaRepository;

    @Override
    public List<MonthlyClosing> findByGroupId(UUID groupId) {
        return jpaRepository.findByGroupId(groupId).stream()
                .map(FinanceMonthlyClosingEntity::toDomain)
                .toList();
    }

    @Override
    public MonthlyClosing upsert(UUID groupId, UUID closedBy, String month, boolean completed) {
        jpaRepository.upsert(groupId, closedBy, month, completed);
        // 네이티브 upsert는 엔티티를 반환하지 않으므로 (group_id, month) 유니크 제약으로 다시 조회한다
        return jpaRepository.findByGroupIdAndMonth(groupId, month)
                .map(FinanceMonthlyClosingEntity::toDomain)
                .orElseThrow(() -> new NoSuchElementException("월 마감 upsert 직후 조회 실패: " + groupId + "/" + month));
    }

    @Override
    public void deleteByGroupId(UUID groupId) {
        jpaRepository.deleteByGroupId(groupId);
    }
}
