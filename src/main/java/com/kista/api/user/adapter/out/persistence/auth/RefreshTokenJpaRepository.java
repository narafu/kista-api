package com.kista.api.user.adapter.out.persistence.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

// package-private — 외부 패키지 직접 참조 금지 (constraints.md)
interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    // 토큰 해시로 단건 조회 (로그인/검증)
    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    // 삭제는 모두 JPQL 벌크 delete — 파생 delete는 SELECT 후 엔티티별 delete라 동시 요청이 같은 행을 먼저 지우면
    // ObjectOptimisticLockingFailureException(expected row count 1 but was 0)으로 500이 난다. 벌크는 0건이어도 성공

    // 특정 토큰 삭제 (로그아웃 단건)
    @Modifying
    @Query("delete from RefreshTokenEntity e where e.tokenHash = :hash")
    void deleteByTokenHash(@Param("hash") String tokenHash);

    // 사용자 전체 토큰 삭제 (회원 탈퇴 / 전체 로그아웃 / 재사용 공격 탐지 시 전체 폐기)
    @Modifying
    @Query("delete from RefreshTokenEntity e where e.userId = :userId")
    void deleteAllByUserId(@Param("userId") UUID userId);

    // 동일 기기 재로그인 시 구 RT 교체 — User-Agent 헤더가 없으면(null) user_agent IS NULL 행끼리 같은 기기로 본다(파생 쿼리와 같은 의미)
    @Modifying
    @Query("delete from RefreshTokenEntity e where e.userId = :userId"
            + " and (e.userAgent = :userAgent or (:userAgent is null and e.userAgent is null))")
    void deleteAllByUserIdAndUserAgent(@Param("userId") UUID userId, @Param("userAgent") String userAgent);

    // 만료된 토큰 일괄 삭제 (스케쥴러)
    @Modifying
    @Query("delete from RefreshTokenEntity e where e.expiresAt < :threshold")
    int deleteAllByExpiresAtBefore(@Param("threshold") Instant threshold);

    // 조건부 rotated_at 마킹 — rotated_at IS NULL인 행만 갱신, affected rows로 회전 승자 판정
    // 동시 요청이 같은 hash로 호출해도 1개만 1을 반환해 경쟁 조건을 안전하게 처리
    @Modifying
    @Query("update RefreshTokenEntity e set e.rotatedAt = :now where e.tokenHash = :hash and e.rotatedAt is null")
    int markRotated(@Param("hash") String hash, @Param("now") Instant now);

    // grace 기간이 지난 회전 토큰 일괄 삭제 (스케쥴러)
    @Modifying
    @Query("delete from RefreshTokenEntity e where e.rotatedAt < :threshold")
    int deleteAllByRotatedAtBefore(@Param("threshold") Instant threshold);
}
