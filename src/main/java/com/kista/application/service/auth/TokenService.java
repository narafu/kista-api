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

@Service
@Transactional
@RequiredArgsConstructor
class TokenService implements TokenUseCase {

    private static final Duration RT_TTL = Duration.ofHours(120); // 5일
    private static final Duration AT_TTL = Duration.ofMinutes(15); // AT 수명 — 블랙리스트 등재 기간
    // RTR 동시 경쟁 허용 윈도우 — 이 안에 회전된 RT를 재제시하면 정상 처리
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

    @Override
    public TokenRefreshResult refresh(String rawRefreshToken, String userAgent) {
        String hash = sha256Hex(rawRefreshToken);
        Instant now = Instant.now();

        RefreshToken rt = refreshTokenPort.findByTokenHash(hash)
                .orElseThrow(() -> new InvalidRefreshTokenException("유효하지 않은 refresh token"));

        if (rt.expiresAt().isBefore(now)) {
            // 만료 — 즉시 정리 후 401
            refreshTokenPort.deleteByTokenHash(hash);
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
            refreshTokenPort.deleteAllByUserId(rt.userId());
            throw new InvalidRefreshTokenException("유효하지 않은 refresh token");
        }

        // 미회전 RT — markRotated로 회전 마킹 (조건부 update, 경쟁 시 1건만 1 반환)
        refreshTokenPort.markRotated(hash, now);
        User user = userPort.findByIdOrThrow(rt.userId());
        return new TokenRefreshResult(user.id(), user.role(), issueNewRt(rt.userId(), userAgent));
    }

    // 새 RT 행 발급 — rawToken 반환
    private String issueNewRt(UUID userId, String userAgent) {
        String newRaw = generateRawToken();
        refreshTokenPort.save(new RefreshToken(
                null, userId, sha256Hex(newRaw), userAgent,
                Instant.now().plus(RT_TTL), null, null
        ));
        return newRaw;
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

    @Override
    public int cleanupRotatedTokens() {
        // grace 2배 여유를 두어 극단적 지연 요청도 처리 후 삭제
        return refreshTokenPort.deleteAllRotatedBefore(Instant.now().minus(RT_GRACE.multipliedBy(2)));
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[32]; // 256비트 엔트로피
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
