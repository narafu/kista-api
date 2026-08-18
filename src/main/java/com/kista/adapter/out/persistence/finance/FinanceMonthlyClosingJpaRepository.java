package com.kista.adapter.out.persistence.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface FinanceMonthlyClosingJpaRepository extends JpaRepository<FinanceMonthlyClosingEntity, UUID> {

    List<FinanceMonthlyClosingEntity> findByGroupId(UUID groupId);

    Optional<FinanceMonthlyClosingEntity> findByGroupIdAndMonth(UUID groupId, String month);

    // (group_id, month) 유니크 제약 위 네이티브 upsert — 동시 요청 race를 애플리케이션 레벨 조회-후-저장 없이 원천 차단.
    // closed_by/closed_at은 completed=true일 때만 값을 갖고, false로 전환되면 NULL로 되돌린다 (V14 데이터 변환 로직과 동일 규칙).
    @Modifying
    @Query(value = """
            INSERT INTO finance_monthly_closings (group_id, closed_by, month, completed, closed_at)
            VALUES (:groupId, CASE WHEN :completed THEN :closedBy END, :month, :completed, CASE WHEN :completed THEN now() END)
            ON CONFLICT (group_id, month)
            DO UPDATE SET completed = excluded.completed,
                          closed_by = excluded.closed_by,
                          closed_at = excluded.closed_at,
                          updated_at = now()
            """, nativeQuery = true)
    void upsert(@Param("groupId") UUID groupId, @Param("closedBy") UUID closedBy,
            @Param("month") String month, @Param("completed") boolean completed);

    @Modifying
    @Query("DELETE FROM FinanceMonthlyClosingEntity m WHERE m.groupId = :groupId")
    void deleteByGroupId(@Param("groupId") UUID groupId);
}
