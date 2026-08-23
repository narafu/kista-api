package com.kista.adapter.out.persistence.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface FinanceCategoryJpaRepository extends JpaRepository<FinanceCategoryEntity, UUID> {

    // §4.2 (A) — 폼 선택지용, 활성 행만. type은 null이면 무시.
    // 3-way 매칭: 시스템 전역(user_id IS NULL AND group_id IS NULL) ∪ 내 개인(user_id = :userId AND group_id IS NULL)
    // ∪ 내 그룹(group_id = :currentGroupId). "(group_id IS NULL OR group_id = :groupId)" 형태는 새 모델에서
    // 전 사용자의 개인 카테고리(group_id NULL)를 전원에게 노출시키는 데이터 유출 버그라 반드시 이 3-way로 유지한다.
    // HQL의 "(:type IS NULL OR ...)" 형태는 다른 finance 리포지토리들의 동일 패턴(:from/:to/:date)과 같은 이유로
    // PostgreSQL이 IS NULL 자리 바인드 파라미터 타입을 추론하지 못해 실패한다 — 네이티브 쿼리로 우회하고,
    // enum 바인딩 모호성을 피하기 위해 파라미터 타입을 String으로 받아 어댑터에서 type.name()을 넘긴다.
    @Query(nativeQuery = true, value = "SELECT * FROM finance_categories WHERE deleted_at IS NULL AND (" +
            "(user_id IS NULL AND group_id IS NULL) " +
            "OR (user_id = :userId AND group_id IS NULL) " +
            "OR (group_id = :currentGroupId)" +
            ") AND (CAST(:type AS varchar) IS NULL OR type = :type)")
    List<FinanceCategoryEntity> findSelectable(@Param("userId") UUID userId, @Param("currentGroupId") UUID currentGroupId, @Param("type") String type);

    // 쓰기 경로(update/updateSystem) 전용 — 삭제된 카테고리는 제외해 save() merge로 조용히 되살아나는 것을 막는다
    Optional<FinanceCategoryEntity> findByIdAndDeletedAtIsNull(UUID id);

    // 소프트 삭제 시 모든 하위 세대(임의 depth) 동반 — recursive CTE로 전체 서브트리 조회 후 일괄 UPDATE
    @Modifying
    @Query(nativeQuery = true, value = "WITH RECURSIVE descendants AS (" +
            "SELECT id FROM finance_categories WHERE id = :id " +
            "UNION ALL " +
            "SELECT c.id FROM finance_categories c INNER JOIN descendants d ON c.parent_id = d.id" +
            ") UPDATE finance_categories SET deleted_at = :now WHERE id IN (SELECT id FROM descendants)")
    void softDeleteWithChildren(@Param("id") UUID id, @Param("now") Instant now);

    // 회원 탈퇴 시 내가 만든 그룹 카테고리만 (시스템은 userId NULL이라 자동 제외)
    @Modifying
    @Query("UPDATE FinanceCategoryEntity c SET c.deletedAt = :now WHERE c.userId = :userId AND c.deletedAt IS NULL")
    void softDeleteByUserId(@Param("userId") UUID userId, @Param("now") Instant now);

    // 그룹 공유 전환 시 모든 하위 세대(임의 depth) 동반 — softDeleteWithChildren과 동일한 recursive CTE 패턴
    @Modifying
    @Query(nativeQuery = true, value = "WITH RECURSIVE descendants AS (" +
            "SELECT id FROM finance_categories WHERE id = :id " +
            "UNION ALL " +
            "SELECT c.id FROM finance_categories c INNER JOIN descendants d ON c.parent_id = d.id" +
            ") UPDATE finance_categories SET group_id = :groupId WHERE id IN (SELECT id FROM descendants)")
    void shareToGroupWithChildren(@Param("id") UUID id, @Param("groupId") UUID groupId);

    // 그룹 공유 해제 시 모든 하위 세대(임의 depth) 동반
    @Modifying
    @Query(nativeQuery = true, value = "WITH RECURSIVE descendants AS (" +
            "SELECT id FROM finance_categories WHERE id = :id " +
            "UNION ALL " +
            "SELECT c.id FROM finance_categories c INNER JOIN descendants d ON c.parent_id = d.id" +
            ") UPDATE finance_categories SET group_id = NULL WHERE id IN (SELECT id FROM descendants)")
    void unshareWithChildren(@Param("id") UUID id);
}
