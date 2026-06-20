package com.kista.domain.port.in;

import com.kista.domain.model.auth.TokenRefreshResult;

import java.util.UUID;

public interface TokenUseCase {
    // 로그인 성공 후 RT 발급 — rawToken 반환 (컨트롤러가 쿠키로 전달)
    String issueRefreshToken(UUID userId, String userAgent);
    // RTR: 구 RT 회전 마킹 + 새 RT 발급, 새 AT 발급에 필요한 userId/role 반환 (grace window 60초)
    TokenRefreshResult refresh(String rawRefreshToken, String userAgent);
    // 로그아웃: RT 삭제 + userId 블랙리스트 등재
    void logout(String rawRefreshToken);
    // 만료 RT 일괄 정리 — 스케쥴러 전용
    int cleanupExpiredTokens();
    // grace 기간이 지난 회전 RT 정리 — 스케쥴러 전용
    int cleanupRotatedTokens();
}
