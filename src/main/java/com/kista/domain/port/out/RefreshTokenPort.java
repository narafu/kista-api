package com.kista.domain.port.out;

import com.kista.domain.model.auth.RefreshToken;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenPort {
    void save(RefreshToken token);
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    void deleteByTokenHash(String tokenHash);
    void deleteAllByUserId(UUID userId); // 탈퇴 / 기기 전체 로그아웃 시 세션 폐기
    void deleteByUserIdAndUserAgent(UUID userId, String userAgent); // 동일 기기 재로그인 시 구 RT 교체
    int deleteAllExpired(); // 스케쥴러 — 만료 토큰 일괄 정리
    // RTR grace 지원 — rotated_at IS NULL인 행만 갱신, 1 반환 시 회전 승자
    int markRotated(String tokenHash, Instant now);
    // 스케쥴러 — grace 기간이 지난 회전 토큰 일괄 삭제
    int deleteAllRotatedBefore(Instant threshold);
}
