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

    // 슬라이딩 만료 연장 — refresh 성공 시 expires_at만 갱신 (RT 회전 없음, 드리프트 원천 차단)
    @Modifying
    @Query("update RefreshTokenEntity e set e.expiresAt = :exp where e.tokenHash = :hash")
    void touchExpiry(@Param("hash") String hash, @Param("exp") Instant newExpiresAt);
}
