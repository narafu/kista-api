package com.kista.adapter.out.persistence.asset;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AssetMonthlyCheckJpaRepository extends JpaRepository<AssetMonthlyCheckEntity, UUID> {

    List<AssetMonthlyCheckEntity> findByUserId(UUID userId);
    Optional<AssetMonthlyCheckEntity> findByUserIdAndMonth(UUID userId, String month);

    // (user_id, month) 유니크 제약 위 네이티브 upsert — 동시 요청 race를 애플리케이션 레벨 조회-후-저장 없이 원천 차단
    @Modifying
    @Query(value = """
            INSERT INTO asset_monthly_checks (user_id, month, completed)
            VALUES (:userId, :month, :completed)
            ON CONFLICT (user_id, month)
            DO UPDATE SET completed = excluded.completed, updated_at = now()
            """, nativeQuery = true)
    void upsert(@Param("userId") UUID userId, @Param("month") String month, @Param("completed") boolean completed);

    @Modifying
    @Query("DELETE FROM AssetMonthlyCheckEntity a WHERE a.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
