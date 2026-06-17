package com.kista.application.service.auth;

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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
        assertThat(saved.id()).isNull(); // JPA가 생성
        assertThat(saved.expiresAt()).isAfter(Instant.now().plus(Duration.ofHours(119)));
    }

    @Test
    void refresh_validToken_rotatesAndReturnsResult() {
        String rawOld = "oldRawToken12345";
        String oldHash = TokenService.sha256Hex(rawOld);
        RefreshToken existing = new RefreshToken(UUID.randomUUID(), USER_ID, oldHash,
                "ua", Instant.now().plus(Duration.ofHours(120)), Instant.now());
        User user = mockUser(USER_ID, User.UserRole.USER);

        given(refreshTokenPort.findByTokenHash(oldHash)).willReturn(Optional.of(existing));
        given(userPort.findByIdOrThrow(USER_ID)).willReturn(user);

        TokenRefreshResult result = tokenService.refresh(rawOld, "ua");

        verify(refreshTokenPort).deleteByTokenHash(oldHash);            // 구 RT 삭제
        verify(refreshTokenPort).save(any(RefreshToken.class));          // 새 RT 저장
        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.userRole()).isEqualTo(User.UserRole.USER);
        assertThat(result.newRawRefreshToken()).isNotEqualTo(rawOld);    // 새 토큰 발급
    }

    @Test
    void refresh_unknownToken_throws() {
        given(refreshTokenPort.findByTokenHash(any())).willReturn(Optional.empty());
        assertThatThrownBy(() -> tokenService.refresh("unknown", "ua"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("유효하지 않은");
    }

    @Test
    void refresh_expiredToken_throwsAndCleansUp() {
        String raw = "expiredToken";
        String hash = TokenService.sha256Hex(raw);
        RefreshToken expired = new RefreshToken(UUID.randomUUID(), USER_ID, hash,
                null, Instant.now().minusSeconds(1), Instant.now().minusSeconds(1000));
        given(refreshTokenPort.findByTokenHash(hash)).willReturn(Optional.of(expired));

        assertThatThrownBy(() -> tokenService.refresh(raw, "ua"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("만료된");
        verify(refreshTokenPort).deleteByTokenHash(hash); // 만료 토큰 정리
    }

    @Test
    void logout_validToken_deletesAndBlacklists() {
        String raw = "logoutToken";
        String hash = TokenService.sha256Hex(raw);
        RefreshToken rt = new RefreshToken(UUID.randomUUID(), USER_ID, hash,
                null, Instant.now().plusSeconds(1000), Instant.now());
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

    private User mockUser(UUID id, User.UserRole role) {
        return new User(id, "kakaoId", "닉네임",
                User.UserStatus.ACTIVE, role, null, null, null, null,
                User.NotificationChannel.FCM, true);
    }
}
