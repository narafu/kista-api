package com.kista.application.service.auth;

import com.kista.domain.model.auth.InvalidRefreshTokenException;
import com.kista.domain.model.auth.RefreshToken;
import com.kista.domain.model.auth.TokenRefreshResult;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.BlacklistPort;
import com.kista.domain.port.out.RefreshTokenPort;
import com.kista.domain.port.out.UserPort;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock RefreshTokenPort refreshTokenPort;
    @Mock BlacklistPort blacklistPort;
    @Mock UserPort userPort;
    @InjectMocks TokenService tokenService;

    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    void issueRefreshToken_savesHashedToken() {
        String rawToken = tokenService.issueRefreshToken(USER_ID, "Mozilla/5.0");

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenPort).save(captor.capture());

        RefreshToken saved = captor.getValue();
        assertThat(saved.userId()).isEqualTo(USER_ID);
        assertThat(saved.tokenHash()).isEqualTo(TokenService.sha256Hex(rawToken));
        assertThat(saved.id()).isNull();
        assertThat(saved.rotatedAt()).isNull(); // 신규 발급 — 미회전
        assertThat(saved.expiresAt()).isAfter(Instant.now().plus(Duration.ofHours(119)));
    }

    // 정상 갱신: 미회전 RT → markRotated + 새 RT 발급
    @Test
    void refresh_validToken_rotatesAndIssuesNewToken() {
        String rawToken = "validRawToken12345";
        String hash = TokenService.sha256Hex(rawToken);
        RefreshToken existing = makeRt(hash, Instant.now().plus(Duration.ofHours(120)), null);
        User user = mockUser();

        given(refreshTokenPort.findByTokenHash(hash)).willReturn(Optional.of(existing));
        given(refreshTokenPort.markRotated(eq(hash), any())).willReturn(1); // 회전 승자
        given(userPort.findByIdOrThrow(USER_ID)).willReturn(user);

        TokenRefreshResult result = tokenService.refresh(rawToken, "ua");

        verify(refreshTokenPort).markRotated(eq(hash), any(Instant.class));
        verify(refreshTokenPort).save(any(RefreshToken.class)); // 새 RT 저장
        assertThat(result.newRawRefreshToken()).isNotEqualTo(rawToken); // 새 RT 반환
        assertThat(result.userId()).isEqualTo(USER_ID);
    }

    // grace 이내 회전된 RT 재제시 → 동시 경쟁 패자로 허용, 새 RT 발급
    @Test
    void refresh_rotatedWithinGrace_allowsAndIssuesNewToken() {
        String rawToken = "rotatedToken";
        String hash = TokenService.sha256Hex(rawToken);
        Instant rotatedAt = Instant.now().minusSeconds(30); // 30초 전 회전 (grace 60초 이내)
        RefreshToken rotated = makeRt(hash, Instant.now().plus(Duration.ofHours(120)), rotatedAt);
        User user = mockUser();

        given(refreshTokenPort.findByTokenHash(hash)).willReturn(Optional.of(rotated));
        given(userPort.findByIdOrThrow(USER_ID)).willReturn(user);

        TokenRefreshResult result = tokenService.refresh(rawToken, "ua");

        verify(refreshTokenPort, never()).deleteAllByUserId(any()); // 세션 폐기 없음
        verify(refreshTokenPort).save(any(RefreshToken.class)); // 새 RT 발급
        assertThat(result.userId()).isEqualTo(USER_ID);
    }

    // grace 초과 회전된 RT 재제시 → 재사용 공격 의심, 전체 세션 폐기
    @Test
    void refresh_rotatedBeyondGrace_revokesAllSessions() {
        String rawToken = "staleRotatedToken";
        String hash = TokenService.sha256Hex(rawToken);
        Instant rotatedAt = Instant.now().minusSeconds(120); // 120초 전 회전 (grace 60초 초과)
        RefreshToken rotated = makeRt(hash, Instant.now().plus(Duration.ofHours(120)), rotatedAt);

        given(refreshTokenPort.findByTokenHash(hash)).willReturn(Optional.of(rotated));

        assertThatThrownBy(() -> tokenService.refresh(rawToken, "ua"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokenPort).deleteAllByUserId(USER_ID); // 전체 세션 폐기
    }

    @Test
    void refresh_unknownToken_throws() {
        given(refreshTokenPort.findByTokenHash(any())).willReturn(Optional.empty());
        assertThatThrownBy(() -> tokenService.refresh("unknown", "ua"))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("유효하지 않은");
        verify(refreshTokenPort, never()).deleteAllByUserId(any());
    }

    @Test
    void refresh_expiredToken_throwsAndCleansUp() {
        String raw = "expiredToken";
        String hash = TokenService.sha256Hex(raw);
        RefreshToken expired = makeRt(hash, Instant.now().minusSeconds(1), null);

        given(refreshTokenPort.findByTokenHash(hash)).willReturn(Optional.of(expired));

        assertThatThrownBy(() -> tokenService.refresh(raw, "ua"))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("만료된");
        verify(refreshTokenPort).deleteByTokenHash(hash);
        verify(refreshTokenPort, never()).deleteAllByUserId(any());
    }

    @Test
    void logout_withJti_deletesRtAndBlacklistsJti() {
        String raw = "logoutToken";
        String hash = TokenService.sha256Hex(raw);
        RefreshToken rt = makeRt(hash, Instant.now().plusSeconds(1000), null);
        String jti = UUID.randomUUID().toString();
        Instant atExpiresAt = Instant.now().plusSeconds(3600); // 미래 만료

        given(refreshTokenPort.findByTokenHash(hash)).willReturn(Optional.of(rt));

        tokenService.logout(raw, jti, atExpiresAt);

        verify(refreshTokenPort).deleteByTokenHash(hash);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(blacklistPort).addJti(eq(jti), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isPositive(); // remaining > 0
    }

    @Test
    void logout_withExpiredAt_skipsJtiBlacklist() {
        String raw = "logoutToken2";
        String hash = TokenService.sha256Hex(raw);
        RefreshToken rt = makeRt(hash, Instant.now().plusSeconds(1000), null);
        String jti = UUID.randomUUID().toString();
        Instant alreadyExpired = Instant.now().minusSeconds(1); // 이미 만료된 AT

        given(refreshTokenPort.findByTokenHash(hash)).willReturn(Optional.of(rt));

        tokenService.logout(raw, jti, alreadyExpired);

        verify(refreshTokenPort).deleteByTokenHash(hash);
        verify(blacklistPort, never()).addJti(any(), any()); // 만료된 AT는 블랙리스트 생략
    }

    @Test
    void logout_withNullJti_skipsJtiBlacklist() {
        String raw = "logoutToken3";
        String hash = TokenService.sha256Hex(raw);
        RefreshToken rt = makeRt(hash, Instant.now().plusSeconds(1000), null);

        given(refreshTokenPort.findByTokenHash(hash)).willReturn(Optional.of(rt));

        tokenService.logout(raw, null, null); // jti 없이 호출 (AT 파싱 실패 케이스)

        verify(refreshTokenPort).deleteByTokenHash(hash);
        verify(blacklistPort, never()).addJti(any(), any()); // jti 없으면 블랙리스트 생략
    }

    @Test
    void logout_unknownToken_noOp() {
        given(refreshTokenPort.findByTokenHash(any())).willReturn(Optional.empty());
        tokenService.logout("unknownToken", null, null);
        verify(blacklistPort, never()).addJti(any(), any());
    }

    @Test
    void sha256Hex_deterministicAndLength64() {
        String hash = TokenService.sha256Hex("test");
        assertThat(hash).hasSize(64);
        assertThat(TokenService.sha256Hex("test")).isEqualTo(hash);
    }

    private RefreshToken makeRt(String hash, Instant expiresAt, Instant rotatedAt) {
        return new RefreshToken(UUID.randomUUID(), USER_ID, hash, "ua", expiresAt, rotatedAt, Instant.now());
    }

    private User mockUser() {
        return DomainFixtures.activeUser(USER_ID, User.NotificationChannel.FCM);
    }
}
