package com.kista.application.service.auth;

import com.kista.domain.model.auth.InvalidRefreshTokenException;
import com.kista.domain.model.auth.RefreshToken;
import com.kista.domain.model.auth.TokenRefreshResult;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.BlacklistPort;
import com.kista.domain.port.out.RefreshTokenPort;
import com.kista.domain.port.out.UserPort;
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
import static org.mockito.Mockito.times;
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
        assertThat(saved.id()).isNull(); // JPA가 생성
        assertThat(saved.expiresAt()).isAfter(Instant.now().plus(Duration.ofHours(119)));
    }

    // 정상 갱신: 유효한 RT → touchExpiry 호출 + 동일 RT 반환 (드리프트 불가 — 회귀 방지 핵심)
    @Test
    void refresh_validToken_touchesExpiryAndReturnsSameToken() {
        String rawToken = "validRawToken12345";
        String hash = TokenService.sha256Hex(rawToken);
        RefreshToken existing = makeRt(hash, Instant.now().plus(Duration.ofHours(120)));
        User user = mockUser(USER_ID, User.UserRole.USER);

        given(refreshTokenPort.findByTokenHash(hash)).willReturn(Optional.of(existing));
        given(userPort.findByIdOrThrow(USER_ID)).willReturn(user);

        TokenRefreshResult result = tokenService.refresh(rawToken, "ua");

        // 회전 없음 — 새 RT 저장·구 RT 삭제 없이 touchExpiry만 호출
        verify(refreshTokenPort, never()).save(any(RefreshToken.class));
        verify(refreshTokenPort, never()).deleteByTokenHash(any());
        verify(refreshTokenPort, never()).deleteAllByUserId(any());
        verify(refreshTokenPort).touchExpiry(eq(hash), any(Instant.class));
        // 동일 RT 반환 — 브라우저 쿠키 드리프트 원천 불가
        assertThat(result.newRawRefreshToken()).isEqualTo(rawToken);
        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.userRole()).isEqualTo(User.UserRole.USER);
    }

    // 회귀 방지: 동일 RT를 Set-Cookie 유실 시뮬레이션으로 여러 번 refresh → 세션 폐기 없이 정상 처리
    @Test
    void refresh_sameToken_repeatedly_neverRevokesSession() {
        String rawToken = "stableRawToken";
        String hash = TokenService.sha256Hex(rawToken);
        RefreshToken rt = makeRt(hash, Instant.now().plus(Duration.ofHours(120)));
        User user = mockUser(USER_ID, User.UserRole.USER);

        given(refreshTokenPort.findByTokenHash(hash)).willReturn(Optional.of(rt));
        given(userPort.findByIdOrThrow(USER_ID)).willReturn(user);

        // 같은 RT로 5번 연속 refresh (드리프트 시 구 RT를 반복 제시하는 상황)
        for (int i = 0; i < 5; i++) {
            TokenRefreshResult result = tokenService.refresh(rawToken, "ua");
            assertThat(result.newRawRefreshToken()).isEqualTo(rawToken);
        }

        // 세션 폐기(deleteAllByUserId) 절대 미호출
        verify(refreshTokenPort, never()).deleteAllByUserId(any());
        // touchExpiry 5번 호출
        verify(refreshTokenPort, times(5)).touchExpiry(eq(hash), any(Instant.class));
    }

    @Test
    void refresh_unknownToken_throws() {
        given(refreshTokenPort.findByTokenHash(any())).willReturn(Optional.empty());
        assertThatThrownBy(() -> tokenService.refresh("unknown", "ua"))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("유효하지 않은");
        // 단건 401 — 전체 세션 폐기 없음
        verify(refreshTokenPort, never()).deleteAllByUserId(any());
    }

    @Test
    void refresh_expiredToken_throwsAndCleansUp() {
        String raw = "expiredToken";
        String hash = TokenService.sha256Hex(raw);
        RefreshToken expired = makeRt(hash, Instant.now().minusSeconds(1));

        given(refreshTokenPort.findByTokenHash(hash)).willReturn(Optional.of(expired));

        assertThatThrownBy(() -> tokenService.refresh(raw, "ua"))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("만료된");
        verify(refreshTokenPort).deleteByTokenHash(hash); // 만료 토큰 정리
        verify(refreshTokenPort, never()).deleteAllByUserId(any()); // 전체 세션 폐기 없음
    }

    @Test
    void logout_validToken_deletesAndBlacklists() {
        String raw = "logoutToken";
        String hash = TokenService.sha256Hex(raw);
        RefreshToken rt = makeRt(hash, Instant.now().plusSeconds(1000));

        given(refreshTokenPort.findByTokenHash(hash)).willReturn(Optional.of(rt));

        tokenService.logout(raw);

        verify(refreshTokenPort).deleteByTokenHash(hash);
        verify(blacklistPort).add(USER_ID, Duration.ofMinutes(15));
    }

    @Test
    void logout_unknownToken_noOp() {
        given(refreshTokenPort.findByTokenHash(any())).willReturn(Optional.empty());
        tokenService.logout("unknownToken"); // 예외 없이 종료
        verify(blacklistPort, never()).add(any(), any());
    }

    @Test
    void sha256Hex_deterministicAndLength64() {
        String hash = TokenService.sha256Hex("test");
        assertThat(hash).hasSize(64);
        assertThat(TokenService.sha256Hex("test")).isEqualTo(hash); // 결정론적
    }

    private RefreshToken makeRt(String hash, Instant expiresAt) {
        return new RefreshToken(UUID.randomUUID(), USER_ID, hash, "ua", expiresAt, Instant.now());
    }

    private User mockUser(UUID id, User.UserRole role) {
        return new User(id, "kakaoId", "닉네임",
                User.UserStatus.ACTIVE, role, null, null, null, null,
                User.NotificationChannel.FCM);
    }
}
