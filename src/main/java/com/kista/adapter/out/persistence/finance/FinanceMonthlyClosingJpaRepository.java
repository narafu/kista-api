package com.kista.adapter.out.persistence.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface FinanceMonthlyClosingJpaRepository extends JpaRepository<FinanceMonthlyClosingEntity, UUID> {

    // 이중축: 내 개인 마감(group_id IS NULL) ∪ 내 현재 그룹 마감(group_id = :currentGroupId).
    @Query(nativeQuery = true, value = "SELECT * FROM finance_monthly_closings WHERE " +
            "((user_id = :userId AND group_id IS NULL) OR group_id = :currentGroupId)")
    List<FinanceMonthlyClosingEntity> findMyScope(@Param("userId") UUID userId, @Param("currentGroupId") UUID currentGroupId);

    Optional<FinanceMonthlyClosingEntity> findByGroupIdAndMonth(UUID groupId, String month);

    // 개인 마감(group_id IS NULL) 전용 재조회 — findByGroupIdAndMonth(null, month)는 group_id IS NULL만
    // 걸러 유저 무관하게 매칭되므로(uq_finance_monthly_closings_personal_month가 user_id별로 여러 개인
    // 마감 행을 허용), 다른 유저가 같은 달에 개인 마감을 upsert하면 Optional에 2건 이상 잡혀
    // IncorrectResultSizeDataAccessException이 난다. user_id까지 좁혀야 한다.
    Optional<FinanceMonthlyClosingEntity> findByUserIdAndGroupIdIsNullAndMonth(UUID userId, String month);

    // group_id/month 유니크 제약(uq_finance_monthly_closings_group_month, WHERE group_id IS NOT NULL) 위
    // 네이티브 upsert — 동시 요청 race를 애플리케이션 레벨 조회-후-저장 없이 원천 차단. groupId가 null인
    // 개인 마감은 별도 partial index(uq_finance_monthly_closings_personal_month)를 쓰는 upsertPersonal 경유.
    // closed_at은 completed=true일 때 값을 갖고 false로 전환되면 NULL로 되돌린다.
    @Modifying
    @Query(value = """
            INSERT INTO finance_monthly_closings (group_id, user_id, month, completed, closed_at)
            VALUES (:groupId, :userId, :month, :completed, CASE WHEN :completed THEN now() END)
            ON CONFLICT (group_id, month) WHERE group_id IS NOT NULL
            DO UPDATE SET completed = excluded.completed,
                          user_id = excluded.user_id,
                          closed_at = excluded.closed_at,
                          updated_at = now()
            """, nativeQuery = true)
    void upsertGroup(@Param("groupId") UUID groupId, @Param("userId") UUID userId,
            @Param("month") String month, @Param("completed") boolean completed);

    // 개인 마감(group_id IS NULL) 전용 — uq_finance_monthly_closings_personal_month(user_id, month) partial index 대상.
    @Modifying
    @Query(value = """
            INSERT INTO finance_monthly_closings (group_id, user_id, month, completed, closed_at)
            VALUES (NULL, :userId, :month, :completed, CASE WHEN :completed THEN now() END)
            ON CONFLICT (user_id, month) WHERE group_id IS NULL AND user_id IS NOT NULL
            DO UPDATE SET completed = excluded.completed,
                          closed_at = excluded.closed_at,
                          updated_at = now()
            """, nativeQuery = true)
    void upsertPersonal(@Param("userId") UUID userId, @Param("month") String month, @Param("completed") boolean completed);

    @Modifying
    @Query("DELETE FROM FinanceMonthlyClosingEntity m WHERE m.groupId = :groupId")
    void deleteByGroupId(@Param("groupId") UUID groupId);
}
