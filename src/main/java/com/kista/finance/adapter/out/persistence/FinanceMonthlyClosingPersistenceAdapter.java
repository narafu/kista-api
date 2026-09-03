package com.kista.finance.adapter.out.persistence;

import com.kista.finance.domain.model.MonthlyClosing;
import com.kista.finance.application.port.output.MonthlyClosingPort;
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
    public List<MonthlyClosing> findMyScope(UUID userId, UUID currentGroupId) {
        return jpaRepository.findMyScope(userId, currentGroupId).stream()
                .map(FinanceMonthlyClosingEntity::toDomain)
                .toList();
    }

    @Override
    public MonthlyClosing upsert(UUID groupId, UUID userId, String month, boolean completed) {
        // group_id null 여부에 따라 서로 다른 partial unique index를 대상으로 하는 upsert로 분기한다
        // (finance_monthly_closings는 group_id IS NOT NULL/IS NULL 두 개의 partial unique index를 갖는다).
        // 네이티브 upsert는 엔티티를 반환하지 않으므로 유니크 제약으로 다시 조회한다. 개인 마감은
        // group_id IS NULL만으로 조회하면 유저 무관하게 매칭돼(다른 유저의 같은 달 개인 마감과 충돌)
        // 2건 이상 잡힐 수 있어 반드시 user_id까지 좁힌 전용 조회를 쓴다.
        if (groupId != null) {
            jpaRepository.upsertGroup(groupId, userId, month, completed);
            return jpaRepository.findByGroupIdAndMonth(groupId, month)
                    .map(FinanceMonthlyClosingEntity::toDomain)
                    .orElseThrow(() -> new NoSuchElementException("월 마감 upsert 직후 조회 실패: " + groupId + "/" + month));
        }
        jpaRepository.upsertPersonal(userId, month, completed);
        return jpaRepository.findByUserIdAndGroupIdIsNullAndMonth(userId, month)
                .map(FinanceMonthlyClosingEntity::toDomain)
                .orElseThrow(() -> new NoSuchElementException("월 마감 upsert 직후 조회 실패: " + userId + "/" + month));
    }

    @Override
    public void deleteByGroupId(UUID groupId) {
        jpaRepository.deleteByGroupId(groupId);
    }
}
