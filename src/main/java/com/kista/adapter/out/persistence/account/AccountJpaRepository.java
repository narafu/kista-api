package com.kista.adapter.out.persistence.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface AccountJpaRepository extends JpaRepository<AccountEntity, UUID> {

    List<AccountEntity> findAllByOrderByCreatedAtDesc(); // 관리자 전체 조회 — 최신순
    List<AccountEntity> findByUserId(UUID userId);

    int countByUserId(UUID userId);

    // 전역 계좌번호 중복 체크 — deleted_at IS NULL인 활성 계좌만 대상
    boolean existsByAccountNoHashAndDeletedAtIsNull(String accountNoHash);

    @Modifying
    @Query("UPDATE AccountEntity a SET a.deletedAt = :now WHERE a.id = :id")
    void softDeleteById(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE AccountEntity a SET a.deletedAt = :now WHERE a.userId = :userId AND a.deletedAt IS NULL")
    void softDeleteByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
