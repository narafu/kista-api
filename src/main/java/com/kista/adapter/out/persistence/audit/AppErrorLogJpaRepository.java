package com.kista.adapter.out.persistence.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface AppErrorLogJpaRepository extends JpaRepository<AppErrorLogEntity, UUID> {
    // 최신순 N건 조회
    @Query("SELECT e FROM AppErrorLogEntity e ORDER BY e.createdAt DESC LIMIT :limit")
    List<AppErrorLogEntity> findTopNByOrderByCreatedAtDesc(@Param("limit") int limit);

    // 기간 범위 조회 (최신순, N건)
    @Query("SELECT e FROM AppErrorLogEntity e WHERE e.createdAt >= :from AND e.createdAt < :to ORDER BY e.createdAt DESC LIMIT :limit")
    List<AppErrorLogEntity> findTopNByCreatedAtBetween(@Param("from") Instant from, @Param("to") Instant to, @Param("limit") int limit);
}
