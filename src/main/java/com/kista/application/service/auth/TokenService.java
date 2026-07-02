package com.kista.application.service.auth;

import com.kista.domain.model.auth.InvalidRefreshTokenException;
import com.kista.domain.model.auth.RefreshToken;
import com.kista.domain.model.auth.TokenConstants;
import com.kista.domain.model.auth.TokenRefreshResult;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.TokenUseCase;
import com.kista.domain.port.out.BlacklistPort;
import com.kista.domain.port.out.RefreshTokenPort;
import com.kista.domain.port.out.UserPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
class TokenService implements TokenUseCase {

    private static final Duration RT_TTL = Duration.ofDays(5); // 리프레시 토큰 유효 기간
    private static final Duration AT_TTL = TokenConstants.AT_TTL; // AT 수명 — 블랙리스트 등재 기간
    // RTR 동시 경쟁 허용 윈도우 — 이 안에 회전된 RT를 재제시하면 동시 요청 패자로 허용
    static final Duration RT_GRACE = Duration.ofSeconds(60);

    private final RefreshTokenPort refreshTokenPort;
    private final BlacklistPort blacklistPort;
    private final UserPort userPort;

    @Override
    public String issueRefreshToken(UUID userId, String userAgent) {
        // 동일 기기(User-Agent) 기존 RT 교체 — 따닥 로그인 고아 토큰 방지, 다른 기기 세션은 유지
        refreshTokenPort.deleteByUserIdAndUserAgent(userId, userAgent);
        String rawToken = generateRawToken();
        refreshTokenPort.save(new RefreshToken(
                null, userId, sha256Hex(rawToken), userAgent,
                Instant.now().plus(RT_TTL), null, null
        ));
        return rawToken;
    }

    /**
     * RTR + 60초 grace window.
     * <p>
     * 미회전 RT → markRotated(원자적 UPDATE) → 새 RT 발급 (회전 승자).
     * 이미 회전된 RT 재제시:
     *   - grace 이내  → 동시 요청의 패자로 허용, 새 RT 발급
     *   - grace 초과  → 재사용 공격 의심, 해당 사용자 전체 세션 폐기
     */
    @Override
    public TokenRefreshResult refresh(String rawRefreshToken, String userAgent) {
        String hash = sha256Hex(rawRefreshToken);
        Instant now = Instant.now();

        RefreshToken rt = refreshTokenPort.findByTokenHash(hash)
                .orElseThrow(() -> {
                    log.warn("[RT] refresh 거부: 알 수 없는 토큰 (uaHash={})", userAgent != null ? userAgent.hashCode() : "null");
                    return new InvalidRefreshTokenException("유효하지 않은 refresh token");
                });

        if (rt.expiresAt().isBefore(now)) {
            refreshTokenPort.deleteByTokenHash(hash);
            log.warn("[RT] refresh 거부: 만료된 토큰 (userId={})", rt.userId());
            throw new InvalidRefreshTokenException("만료된 refresh token");
        }

        if (rt.rotatedAt() != null) {
            // 이미 회전된 RT 재제시
            if (Duration.between(rt.rotatedAt(), now).compareTo(RT_GRACE) <= 0) {
                // grace 이내 → 동시 경쟁의 패자, 정상 처리
                User user = userPort.findByIdOrThrow(rt.userId());
                return new TokenRefreshResult(user.id(), user.role(), issueNewRt(rt.userId(), userAgent));
            }
            // grace 초과 → 재사용 공격 의심, 해당 사용자 전체 세션 폐기
            throw revokeAllSessionsAndReject(rt.userId(), "재사용 공격 의심: grace 초과 회전 토큰 재제시");
        }

        // 미회전 RT — markRotated로 원자적 회전 마킹 (동시 요청 시 1건만 1 반환)
        int rotated = refreshTokenPort.markRotated(hash, now);
        if (rotated == 0) {
            // 다른 스레드/인스턴스가 먼저 회전 — 최신 상태 재조회 후 grace 판정
            rt = refreshTokenPort.findByTokenHash(hash)
                    .orElseThrow(() -> new InvalidRefreshTokenException("유효하지 않은 refresh token"));
            if (rt.rotatedAt() == null || Duration.between(rt.rotatedAt(), now).compareTo(RT_GRACE) > 0) {
                throw revokeAllSessionsAndReject(rt.userId(), "refresh 거부: markRotated 경쟁 후 grace 초과");
            }
            // grace 이내 경쟁 패자 → 정상 처리
        }
        User user = userPort.findByIdOrThrow(rt.userId());
        return new TokenRefreshResult(user.id(), user.role(), issueNewRt(rt.userId(), userAgent));
    }

    @Override
    public void logout(String rawRefreshToken, String jti, Instant atExpiresAt) {
        String hash = sha256Hex(rawRefreshToken);
        refreshTokenPort.findByTokenHash(hash).ifPresent(rt -> {
            refreshTokenPort.deleteByTokenHash(hash);
            // AT jti만 차단 — 재로그인 후 새 AT는 영향 없음
            if (jti != null && atExpiresAt != null) {
                Duration remaining = Duration.between(Instant.now(), atExpiresAt);
                if (remaining.isPositive()) {
                    blacklistPort.addJti(jti, remaining);
                }
            }
        });
    }

    @Override
    public int cleanupExpiredTokens() {
        return refreshTokenPort.deleteAllExpired();
    }

    @Override
    public int cleanupRotatedTokens() {
        // grace 2배 여유를 두어 극단적 지연 요청도 처리 후 삭제
        return refreshTokenPort.deleteAllRotatedBefore(Instant.now().minus(RT_GRACE.multipliedBy(2)));
    }

    // package-private — TokenServiceTest에서 직접 테스트
    static String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘 없음", e);
        }
    }

    private String issueNewRt(UUID userId, String userAgent) {
        String newRaw = generateRawToken();
        refreshTokenPort.save(new RefreshToken(
                null, userId, sha256Hex(newRaw), userAgent,
                Instant.now().plus(RT_TTL), null, null
        ));
        return newRaw;
    }

    // grace 초과 — 해당 사용자 전체 세션 폐기 후 거부 예외 반환 (throw 용)
    private InvalidRefreshTokenException revokeAllSessionsAndReject(UUID userId, String warnContext) {
        log.warn("[RT] {} (userId={})", warnContext, userId);
        refreshTokenPort.deleteAllByUserId(userId);
        return new InvalidRefreshTokenException("유효하지 않은 refresh token");
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[32]; // 256비트 엔트로피
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
