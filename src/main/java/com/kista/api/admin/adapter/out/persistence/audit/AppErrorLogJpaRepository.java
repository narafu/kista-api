package com.kista.api.admin.adapter.out.persistence.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface AppErrorLogJpaRepository extends JpaRepository<AppErrorLogEntity, UUID> {
    // 미처리 건 기간 범위 조회 (최신순, N건) — deletedAt 조건은 롤백한 옛 이미지가 deleted_at만 채운 행용, contract 때 제거
    @Query("SELECT e FROM AppErrorLogEntity e WHERE e.resolvedAt IS NULL AND e.deletedAt IS NULL AND e.createdAt >= :from AND e.createdAt < :to ORDER BY e.createdAt DESC LIMIT :limit")
    List<AppErrorLogEntity> findTopNByCreatedAtBetween(@Param("from") Instant from, @Param("to") Instant to, @Param("limit") int limit);
}
