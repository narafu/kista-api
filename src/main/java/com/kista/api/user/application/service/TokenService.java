package com.kista.api.user.application.service;

import com.kista.shared.platform.crypto.Sha256;
import com.kista.api.user.domain.auth.InvalidRefreshTokenException;
import com.kista.api.user.domain.auth.RefreshToken;
import com.kista.api.user.domain.auth.TokenConstants;
import com.kista.api.user.domain.auth.TokenRefreshResult;
import com.kista.api.user.domain.model.User;
import com.kista.api.user.application.port.output.BlacklistPort;
import com.kista.api.user.application.port.output.RefreshTokenPort;
import com.kista.api.user.application.port.output.UserPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class TokenService {

    private static final Duration RT_TTL = TokenConstants.RT_TTL; // 리프레시 토큰 유효 기간
    private static final Duration AT_TTL = TokenConstants.AT_TTL; // AT 수명 — 블랙리스트 등재 기간
    // 회전 직후 연속 회전 허용 창 — 이 안에서는 체인을 따라가 미사용 후계를 반환
    static final Duration RT_GRACE = Duration.ofSeconds(60);
    private static final Duration RETRY_WINDOW = AT_TTL; // 회전 RT 재시도 허용 창 — 넘으면 이 기기만 거부(탈취 RT 노출 상한)
    private static final int MAX_CHAIN_WALK = 5; // grace 안 체인 따라가기 상한 — 넘으면 재사용 공격으로 간주

    private final RefreshTokenPort refreshTokenPort;
    private final BlacklistPort blacklistPort;
    private final UserPort userPort;
    private final Clock clock; // 현재 시각 기준 — 테스트에서 Clock.fixed 주입
    private final RefreshTokenSuccessorDeriver successorDeriver; // RTR 후계 RT 결정적 유도

    // 로그인 성공 후 RT 발급 — rawToken 반환 (컨트롤러가 쿠키로 전달)
    public String issueRefreshToken(UUID userId, String userAgent) {
        // 동일 기기(User-Agent) 기존 RT 교체 — 따닥 로그인 고아 토큰 방지, 다른 기기 세션은 유지
        refreshTokenPort.deleteByUserIdAndUserAgent(userId, userAgent);
        return issueNewRt(userId, userAgent);
    }

    // RTR: 구 RT 회전 마킹 + 결정적 후계 RT 발급 (같은 RT 재시도는 같은 후계 반환), 새 AT 발급에 필요한 userId/role 반환
    // 후계를 이미 쓴 뒤 회전 RT 재제시(진짜 재사용)는 revokeAllSessionsAndReject()가 deleteAllByUserId 후 예외를 던지는데,
    // 기본 롤백 규칙이면 폐기까지 롤백되므로 noRollbackFor로 폐기 커밋 보장
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public TokenRefreshResult refresh(String rawRefreshToken, String userAgent) {
        String hash = sha256Hex(rawRefreshToken);
        Instant now = Instant.now(clock);

        RefreshToken rt = refreshTokenPort.findByTokenHash(hash)
                .orElseThrow(() -> {
                    log.warn("[RT] refresh 거부: 알 수 없는 토큰 (uaHash={})", userAgent != null ? userAgent.hashCode() : "null");
                    return new InvalidRefreshTokenException("유효하지 않은 refresh token");
                });

        if (rt.expiresAt().isBefore(now)) {
            refreshTokenPort.deleteByTokenHash(hash);
            log.warn("[userId={}] [RT] refresh 거부: 만료된 토큰", rt.userId());
            throw new InvalidRefreshTokenException("만료된 refresh token");
        }

        String successorRaw = successorDeriver.successorOf(rawRefreshToken);
        if (rt.rotatedAt() == null && refreshTokenPort.markRotated(hash, now) == 1) {
            // 회전 승자 — 결정적 후계 저장
            refreshTokenPort.save(RefreshToken.issue(rt.userId(), sha256Hex(successorRaw), userAgent, now.plus(RT_TTL)));
            return refreshResult(rt.userId(), successorRaw);
        }

        // 이미 회전됨(재시도·동시 경쟁 패자) — markRotated 0이면 승자 커밋을 기다렸으므로 후계가 커밋돼 있다 (T는 1차 캐시 stale이라 재조회 안 함)
        RefreshToken successor = refreshTokenPort.findByTokenHash(sha256Hex(successorRaw)).orElse(null);
        if (successor == null) {
            // 로그아웃·재로그인으로 후계가 삭제됨(rotatedAt null이면 요청 도중 T 삭제) — 이 기기만 거부
            log.warn("[userId={}] [RT] {}", rt.userId(), rt.rotatedAt() == null
                    ? "refresh 거부: 갱신 도중 토큰 삭제됨" : "refresh 거부: 회전된 토큰의 후계 없음");
            throw new InvalidRefreshTokenException("유효하지 않은 refresh token");
        }
        if (successor.rotatedAt() == null) {
            // 재시도 허용 창 초과 — 이 기기만 거부 (전체 폐기 없음)
            if (rt.rotatedAt() != null && Duration.between(rt.rotatedAt(), now).compareTo(RETRY_WINDOW) > 0) {
                log.warn("[userId={}] [RT] refresh 거부: 재시도 허용 창 초과", rt.userId());
                throw new InvalidRefreshTokenException("유효하지 않은 refresh token");
            }
            // 응답 유실 후 재시도 — 같은 후계 재반환 (재-insert 없음)
            return refreshResult(rt.userId(), successorRaw);
        }
        // 후계도 이미 회전됨 — rt.rotatedAt null(경쟁 패자)이면 방금 회전된 것이라 grace 안으로 취급
        if (rt.rotatedAt() == null || Duration.between(rt.rotatedAt(), now).compareTo(RT_GRACE) <= 0) {
            return followChain(rt.userId(), successorRaw);
        }
        // grace 밖에서 후계를 이미 쓴 뒤 회전 토큰 재제시 → 재사용 공격 의심, 해당 사용자 전체 세션 폐기
        throw revokeAllSessionsAndReject(rt.userId(), "재사용 공격 의심: 후계 사용 후 회전 토큰 재제시");
    }

    // grace 안 연속 회전 — 회전된 후계의 후계를 따라가 첫 미사용 RT 반환 (새 RT 발급·재-insert 없음)
    private TokenRefreshResult followChain(UUID userId, String rotatedRaw) {
        String curRaw = rotatedRaw;
        for (int i = 0; i < MAX_CHAIN_WALK; i++) {
            curRaw = successorDeriver.successorOf(curRaw);
            RefreshToken cur = refreshTokenPort.findByTokenHash(sha256Hex(curRaw)).orElse(null);
            if (cur == null) {
                log.warn("[userId={}] [RT] refresh 거부: 회전 체인 도중 토큰 없음", userId);
                throw new InvalidRefreshTokenException("유효하지 않은 refresh token");
            }
            if (cur.rotatedAt() == null) {
                return refreshResult(userId, curRaw);
            }
        }
        throw revokeAllSessionsAndReject(userId, "재사용 공격 의심: 회전 체인 상한 초과");
    }

    // 로그아웃: RT 삭제 + AT jti 블랙리스트 등재 (jti/atExpiresAt null이면 jti 차단 생략)
    public void logout(@Nullable String rawRefreshToken, @Nullable String jti, @Nullable Instant atExpiresAt) {
        if (rawRefreshToken != null) {
            String hash = sha256Hex(rawRefreshToken);
            refreshTokenPort.findByTokenHash(hash).ifPresent(rt -> refreshTokenPort.deleteByTokenHash(hash));
        }
        // RT 쿠키가 없거나 이미 회전·삭제됐어도 제시된 AT는 차단한다 — jti만 막으므로 재로그인 후 새 AT는 영향 없음
        if (jti != null && atExpiresAt != null) {
            Duration remaining = Duration.between(Instant.now(clock), atExpiresAt);
            if (remaining.isPositive()) {
                blacklistPort.addJti(jti, remaining);
            }
        }
    }

    // 만료 RT 일괄 정리 — 스케쥴러 전용
    public int cleanupExpiredTokens() {
        return refreshTokenPort.deleteAllExpired();
    }

    // package-private — TokenServiceTest에서 직접 테스트
    static String sha256Hex(String input) {
        return Sha256.hex(input);
    }

    private String issueNewRt(UUID userId, String userAgent) {
        String newRaw = generateRawToken();
        // RefreshToken.issue() — id·rotatedAt·createdAt은 DB가 채움
        refreshTokenPort.save(RefreshToken.issue(userId, sha256Hex(newRaw), userAgent, Instant.now(clock).plus(RT_TTL)));
        return newRaw;
    }

    // 새 AT 발급용 결과 조립
    private TokenRefreshResult refreshResult(UUID userId, String newRawToken) {
        User user = userPort.findByIdOrThrow(userId);
        return new TokenRefreshResult(user.id(), user.role(), newRawToken);
    }

    // 재사용 공격 의심 — 해당 사용자 전체 세션 폐기 후 거부 예외 반환 (throw 용)
    private InvalidRefreshTokenException revokeAllSessionsAndReject(UUID userId, String warnContext) {
        log.warn("[userId={}] [RT] {}", userId, warnContext);
        refreshTokenPort.deleteAllByUserId(userId);
        return new InvalidRefreshTokenException("유효하지 않은 refresh token");
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[32]; // 256비트 엔트로피
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
