package com.kista.application.service.auth;

import com.kista.domain.model.auth.InvalidRefreshTokenException;
import com.kista.domain.model.auth.RefreshToken;
import com.kista.domain.model.auth.TokenRefreshResult;
import com.kista.domain.port.in.TokenUseCase;
import com.kista.domain.port.out.BlacklistPort;
import com.kista.domain.port.out.RefreshTokenPort;
import com.kista.domain.port.out.UserPort;
import com.kista.domain.model.user.User;
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

    private static final Duration RT_TTL = Duration.ofHours(120); // 5일
    private static final Duration AT_TTL = Duration.ofMinutes(15); // AT 수명 — 블랙리스트 등재 기간

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
                Instant.now().plus(RT_TTL), null
        ));
        return rawToken;
    }

    /**
     * 안정 RT + 슬라이딩 갱신.
     * RT를 회전(교체)하지 않고 동일 토큰을 유지하면서 expires_at만 연장한다.
     * 회전 없음 → 브라우저 Set-Cookie relay 실패 시에도 구 RT가 여전히 유효 → 드리프트 불가.
     * 도난 대응: 알 수 없는/만료된 RT → 단건 401 (전체 세션 폐기 없음).
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
            // 만료 — 즉시 정리 후 401 (단건, 전체 세션 폐기 없음)
            refreshTokenPort.deleteByTokenHash(hash);
            log.warn("[RT] refresh 거부: 만료된 토큰 (userId={})", rt.userId());
            throw new InvalidRefreshTokenException("만료된 refresh token");
        }

        // 유효 — expires_at 슬라이딩 연장 후 동일 rawToken 반환
        refreshTokenPort.touchExpiry(hash, now.plus(RT_TTL));
        User user = userPort.findByIdOrThrow(rt.userId());
        return new TokenRefreshResult(user.id(), user.role(), rawRefreshToken);
    }

    @Override
    public void logout(String rawRefreshToken) {
        String hash = sha256Hex(rawRefreshToken);
        refreshTokenPort.findByTokenHash(hash).ifPresent(rt -> {
            refreshTokenPort.deleteByTokenHash(hash);
            blacklistPort.add(rt.userId(), AT_TTL); // 남은 AT 수명 동안 즉시 차단
        });
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

    @Override
    public int cleanupExpiredTokens() {
        return refreshTokenPort.deleteAllExpired();
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[32]; // 256비트 엔트로피
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
