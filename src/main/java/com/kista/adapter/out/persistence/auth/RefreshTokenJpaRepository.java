package com.kista.adapter.out.persistence.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

// package-private — 외부 패키지 직접 참조 금지 (constraints.md)
interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    // 토큰 해시로 단건 조회 (로그인/검증)
    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    // 특정 토큰 삭제 (로그아웃 단건)
    void deleteByTokenHash(String tokenHash);

    // 사용자 전체 토큰 삭제 (회원 탈퇴 / 전체 로그아웃)
    void deleteAllByUserId(UUID userId);

    // 동일 기기 재로그인 시 구 RT 교체
    void deleteAllByUserIdAndUserAgent(UUID userId, String userAgent);

    // 만료된 토큰 일괄 삭제 (스케줄러)
    int deleteAllByExpiresAtBefore(java.time.Instant threshold);
}
