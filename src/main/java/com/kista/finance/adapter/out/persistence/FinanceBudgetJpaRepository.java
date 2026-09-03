package com.kista.finance.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface FinanceBudgetJpaRepository extends JpaRepository<FinanceBudgetEntity, UUID> {

    // categoryId/date는 선택적 필터 — null이면 무시. date 지정 시 그 날짜에 유효한(기간 포함, 무기한 포함) 예산만.
    // 이중축: 내 개인 예산(group_id IS NULL) ∪ 내 현재 그룹 예산(group_id = :currentGroupId).
    // HQL의 "(:date IS NULL OR ...)" 형태는 PostgreSQL이 IS NULL 자리 바인드 파라미터의 타입을 추론하지 못해
    // "could not determine data type of parameter"로 실패하고, HQL CAST(:date AS date)로 우회를 시도하면
    // 이번엔 Hibernate가 그 파라미터를 bytea로 바인딩해 "cannot cast type bytea to date"로 실패한다.
    // 네이티브 쿼리 + PostgreSQL ::date 캐스트로 우회한다 — JDBC가 LocalDate를 곧바로 바인딩하므로 문제없다.
    @Query(nativeQuery = true, value = "SELECT * FROM finance_budgets WHERE " +
            "((user_id = :userId AND group_id IS NULL) OR group_id = :currentGroupId) " +
            "AND (:categoryId IS NULL OR category_id = :categoryId) " +
            "AND (CAST(:date AS date) IS NULL OR (apply_start_date <= CAST(:date AS date) " +
            "AND (apply_end_date IS NULL OR apply_end_date >= CAST(:date AS date))))")
    List<FinanceBudgetEntity> findMyScope(@Param("userId") UUID userId, @Param("currentGroupId") UUID currentGroupId,
            @Param("categoryId") UUID categoryId, @Param("date") LocalDate date);

    // 개인 스코프(user_id 일치, group_id IS NULL) + 동일 category에서 [startDate, endDate]와 겹치는 예산.
    // endDate가 NULL(무기한)이면 시작일 이후 전부가 겹침 대상 — findMyScope와 동일한 CAST(:x AS date) IS NULL
    // 우회 패턴 사용(PostgreSQL이 NULL 파라미터 타입을 추론 못 하는 문제 회피).
    @Query(nativeQuery = true, value = "SELECT * FROM finance_budgets WHERE " +
            "user_id = :userId AND group_id IS NULL AND category_id = :categoryId " +
            "AND (CAST(:endDate AS date) IS NULL OR apply_start_date <= CAST(:endDate AS date)) " +
            "AND (apply_end_date IS NULL OR apply_end_date >= :startDate)")
    List<FinanceBudgetEntity> findOverlapping(@Param("userId") UUID userId, @Param("categoryId") UUID categoryId,
            @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    // 파생 설정이므로 하드 삭제 (회원 탈퇴 시)
    @Modifying
    @Query("DELETE FROM FinanceBudgetEntity b WHERE b.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
