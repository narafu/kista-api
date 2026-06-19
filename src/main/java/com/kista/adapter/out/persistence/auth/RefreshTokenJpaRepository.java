package com.kista.adapter.out.persistence.auth;

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

    // 특정 토큰 삭제 (로그아웃 단건)
    void deleteByTokenHash(String tokenHash);

    // 사용자 전체 토큰 삭제 (회원 탈퇴 / 전체 로그아웃 / 재사용 공격 탐지 시 전체 폐기)
    void deleteAllByUserId(UUID userId);

    // 동일 기기 재로그인 시 구 RT 교체
    void deleteAllByUserIdAndUserAgent(UUID userId, String userAgent);

    // 만료된 토큰 일괄 삭제 (스케쥴러)
    int deleteAllByExpiresAtBefore(Instant threshold);

    // 조건부 rotated_at 마킹 — rotated_at IS NULL인 행만 갱신, affected rows로 회전 승자 판정
    // 동시 요청이 같은 hash로 호출해도 1개만 1을 반환해 경쟁 조건을 안전하게 처리
    @Modifying
    @Query("update RefreshTokenEntity e set e.rotatedAt = :now where e.tokenHash = :hash and e.rotatedAt is null")
    int markRotated(@Param("hash") String hash, @Param("now") Instant now);

    // grace 기간이 지난 회전 토큰 일괄 삭제 (스케쥴러)
    int deleteAllByRotatedAtBefore(Instant threshold);
}
