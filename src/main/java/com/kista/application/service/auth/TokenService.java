package com.kista.application.service.auth;

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
import java.util.NoSuchElementException;
import java.util.UUID;

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
        // 로그인 시 새 RT 발급 — rawToken 반환, 컨트롤러가 HttpOnly 쿠키로 전달
        String rawToken = generateRawToken();
        refreshTokenPort.save(new RefreshToken(
                null, userId, sha256Hex(rawToken), userAgent,
                Instant.now().plus(RT_TTL), null
        ));
        return rawToken;
    }

    @Override
    public TokenRefreshResult refresh(String rawRefreshToken, String userAgent) {
        String hash = sha256Hex(rawRefreshToken);
        RefreshToken rt = refreshTokenPort.findByTokenHash(hash)
                .orElseThrow(() -> new NoSuchElementException("유효하지 않은 refresh token"));

        if (rt.expiresAt().isBefore(Instant.now())) {
            refreshTokenPort.deleteByTokenHash(hash); // 만료 토큰 즉시 정리
            throw new NoSuchElementException("만료된 refresh token");
        }

        // RTR: 구 RT 삭제 후 새 RT 발급
        refreshTokenPort.deleteByTokenHash(hash);
        User user = userPort.findByIdOrThrow(rt.userId());
        String newRaw = generateRawToken();
        refreshTokenPort.save(new RefreshToken(
                null, rt.userId(), sha256Hex(newRaw), userAgent,
                Instant.now().plus(RT_TTL), null
        ));

        return new TokenRefreshResult(user.id(), user.role(), newRaw);
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
