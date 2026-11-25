package com.kista.api.user.application.service;

import com.kista.shared.support.FixedClockTestConfig;
import java.time.Clock;
import com.kista.api.user.domain.auth.InvalidRefreshTokenException;
import com.kista.api.user.domain.auth.RefreshToken;
import com.kista.api.user.domain.auth.TokenRefreshResult;
import com.kista.api.user.domain.model.User;
import com.kista.api.user.application.port.output.BlacklistPort;
import com.kista.api.user.application.port.output.RefreshTokenPort;
import com.kista.api.user.application.port.output.UserPort;
import com.kista.api.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import com.kista.shared.kernel.NotificationChannel;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    static final String KEY = "dGVzdC1hZXMtMzItYnl0ZXMta2V5LXBhZGRpbmchISE="; // 테스트 AES 키(Base64)

    @Mock RefreshTokenPort refreshTokenPort;
    @Mock BlacklistPort blacklistPort;
    @Mock UserPort userPort;
    final Clock clock = FixedClockTestConfig.FIXED; // 고정 시각
    final RefreshTokenSuccessorDeriver deriver = new RefreshTokenSuccessorDeriver(KEY);
    TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService(refreshTokenPort, blacklistPort, userPort, clock, deriver);
    }

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
        assertThat(saved.expiresAt()).isEqualTo(Instant.now(clock).plus(Duration.ofDays(5))); // 쿠키 maxAge와 같은 값
    }

    // 정상 회전: markRotated 승자 → 결정적 후계 저장·반환
    @Test
    void refresh_validToken_rotatesAndIssuesDerivedSuccessor() {
        String rawToken = "validRawToken12345";
        String hash = TokenService.sha256Hex(rawToken);
        String successor = deriver.successorOf(rawToken);
        given(refreshTokenPort.findByTokenHash(hash)).willReturn(Optional.of(makeRt(hash, future(), null)));
        given(refreshTokenPort.markRotated(eq(hash), any())).willReturn(1);
        given(userPort.findByIdOrThrow(USER_ID)).willReturn(mockUser());

        TokenRefreshResult result = tokenService.refresh(rawToken, "ua");

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenPort).save(captor.capture());
        assertThat(captor.getValue().tokenHash()).isEqualTo(TokenService.sha256Hex(successor));
        assertThat(result.newRawRefreshToken()).isEqualTo(successor);
        assertThat(result.userId()).isEqualTo(USER_ID);
    }

    // 응답 유실 후 재시도: T 회전됨, 후계 미회전 존재 → 같은 후계 반환, 저장·폐기 없음
    @Test
    void refresh_rotatedWithLiveSuccessor_returnsSameSuccessor() {
        assertRetryReturnsSameSuccessor(Instant.now(clock).minusSeconds(30));
    }

    // 재시도 허용 창 경계: 정확히 AT_TTL 경과는 같은 후계 반환
    @Test
    void refresh_rotatedExactlyRetryWindowAgo_returnsSameSuccessor() {
        assertRetryReturnsSameSuccessor(Instant.now(clock).minus(Duration.ofDays(1)));
    }

    // AT_TTL+1초 경과 → 이 기기만 거부, 저장·폐기 없음
    @Test
    void refresh_rotatedBeyondRetryWindow_rejectsWithoutRevoke() {
        String rawToken = "oldRotatedToken";
        String hash = TokenService.sha256Hex(rawToken);
        String successorHash = TokenService.sha256Hex(deriver.successorOf(rawToken));
        given(refreshTokenPort.findByTokenHash(hash)).willReturn(
                Optional.of(makeRt(hash, future(), Instant.now(clock).minus(Duration.ofDays(1)).minusSeconds(1))));
        given(refreshTokenPort.findByTokenHash(successorHash))
                .willReturn(Optional.of(makeRt(successorHash, future(), null)));

        assertThatThrownBy(() -> tokenService.refresh(rawToken, "ua"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokenPort, never()).save(any());
        verify(refreshTokenPort, never()).deleteAllByUserId(any());
    }

    private void assertRetryReturnsSameSuccessor(Instant rotatedAt) {
        String rawToken = "rotatedToken";
        String hash = TokenService.sha256Hex(rawToken);
        String successor = deriver.successorOf(rawToken);
        given(refreshTokenPort.findByTokenHash(hash)).willReturn(Optional.of(makeRt(hash, future(), rotatedAt)));
        given(refreshTokenPort.findByTokenHash(TokenService.sha256Hex(successor)))
                .willReturn(Optional.of(makeRt(TokenService.sha256Hex(successor), future(), null)));
        given(userPort.findByIdOrThrow(USER_ID)).willReturn(mockUser());

        TokenRefreshResult result = tokenService.refresh(rawToken, "ua");

        assertThat(result.newRawRefreshToken()).isEqualTo(successor);
        verify(refreshTokenPort, never()).save(any());
        verify(refreshTokenPort, never()).deleteAllByUserId(any());
    }

    // grace 안 T0 재제시, S1 회전·S2 미사용 → 체인 따라가 S2 반환, 폐기·저장 없음
    @Test
    void refresh_withinGraceSuccessorRotated_followsChainToS2() {
        String t0 = "chainToken";
        String s1 = deriver.successorOf(t0);
        String s2 = deriver.successorOf(s1);
        stubChain(t0, Instant.now(clock).minusSeconds(30), s1, s2, true);
        given(userPort.findByIdOrThrow(USER_ID)).willReturn(mockUser());

        TokenRefreshResult result = tokenService.refresh(t0, "ua");

        assertThat(result.newRawRefreshToken()).isEqualTo(s2);
        verify(refreshTokenPort, never()).save(any());
        verify(refreshTokenPort, never()).deleteAllByUserId(any());
    }

    // grace 밖 같은 상태(후계 사용 후 T 재제시, 탈취 RT 재사용) → 전체 세션 폐기
    @Test
    void refresh_beyondGraceSuccessorRotated_revokesAllSessions() {
        String t0 = "stolenToken";
        String s1 = deriver.successorOf(t0);
        stubChain(t0, Instant.now(clock).minusSeconds(120), s1, deriver.successorOf(s1), true);

        assertThatThrownBy(() -> tokenService.refresh(t0, "ua"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokenPort).deleteAllByUserId(USER_ID);
    }

    // grace 안 체인 도중 행 없음 → 이 기기만 거부, 폐기 없음
    @Test
    void refresh_withinGraceChainRowMissing_rejectsWithoutRevoke() {
        String t0 = "chainGapToken";
        String s1 = deriver.successorOf(t0);
        stubChain(t0, Instant.now(clock).minusSeconds(30), s1, deriver.successorOf(s1), false);

        assertThatThrownBy(() -> tokenService.refresh(t0, "ua"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokenPort, never()).deleteAllByUserId(any());
    }

    // T0(회전됨)·S1(회전됨) 시드, S2는 s2Exists일 때 미회전으로 시드
    private void stubChain(String t0, Instant t0RotatedAt, String s1, String s2, boolean s2Exists) {
        String h0 = TokenService.sha256Hex(t0);
        String h1 = TokenService.sha256Hex(s1);
        String h2 = TokenService.sha256Hex(s2);
        given(refreshTokenPort.findByTokenHash(h0)).willReturn(Optional.of(makeRt(h0, future(), t0RotatedAt)));
        given(refreshTokenPort.findByTokenHash(h1)).willReturn(Optional.of(makeRt(h1, future(), Instant.now(clock).minusSeconds(10))));
        lenient().when(refreshTokenPort.findByTokenHash(h2)).thenReturn(s2Exists ? Optional.of(makeRt(h2, future(), null)) : Optional.empty());
    }

    // 후계 없음(로그아웃·재로그인으로 삭제) → 이 기기만 거부, 폐기 없음
    @Test
    void refresh_noSuccessor_rejectsWithoutRevoke() {
        String rawToken = "orphanToken";
        String hash = TokenService.sha256Hex(rawToken);
        given(refreshTokenPort.findByTokenHash(hash))
                .willReturn(Optional.of(makeRt(hash, future(), Instant.now(clock).minusSeconds(120))));
        given(refreshTokenPort.findByTokenHash(TokenService.sha256Hex(deriver.successorOf(rawToken))))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> tokenService.refresh(rawToken, "ua"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokenPort, never()).deleteAllByUserId(any());
    }

    // markRotated=0(경쟁 패자) → T 재조회 없이 후계 조회 경로로 같은 후계 반환
    @Test
    void refresh_lostRace_returnsSameSuccessorWithoutRereadingToken() {
        String rawToken = "raceToken";
        String hash = TokenService.sha256Hex(rawToken);
        String successor = deriver.successorOf(rawToken);
        String successorHash = TokenService.sha256Hex(successor);
        given(refreshTokenPort.findByTokenHash(hash)).willReturn(Optional.of(makeRt(hash, future(), null))); // stale: 미회전
        given(refreshTokenPort.markRotated(eq(hash), any())).willReturn(0);
        given(refreshTokenPort.findByTokenHash(successorHash)).willReturn(Optional.of(makeRt(successorHash, future(), null)));
        given(userPort.findByIdOrThrow(USER_ID)).willReturn(mockUser());

        TokenRefreshResult result = tokenService.refresh(rawToken, "ua");

        assertThat(result.newRawRefreshToken()).isEqualTo(successor);
        verify(refreshTokenPort, times(1)).findByTokenHash(hash); // T 재조회 없음
        verify(refreshTokenPort, never()).save(any());
        verify(refreshTokenPort, never()).deleteAllByUserId(any());
    }

    @Test
    void deriver_deterministicKeyDependentAndBase64Url43() {
        String out = deriver.successorOf("abc");
        assertThat(deriver.successorOf("abc")).isEqualTo(out);
        assertThat(deriver.successorOf("abd")).isNotEqualTo(out);
        assertThat(out).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        String otherKey = java.util.Base64.getEncoder().encodeToString("another-32-bytes-key-padding!!!!!".getBytes());
        assertThat(new RefreshTokenSuccessorDeriver(otherKey).successorOf("abc")).isNotEqualTo(out);
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
        RefreshToken expired = makeRt(hash, Instant.now(clock).minusSeconds(1), null);

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
        RefreshToken rt = makeRt(hash, Instant.now(clock).plusSeconds(1000), null);
        String jti = UUID.randomUUID().toString();
        Instant atExpiresAt = Instant.now(clock).plusSeconds(3600); // 미래 만료

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
        RefreshToken rt = makeRt(hash, Instant.now(clock).plusSeconds(1000), null);
        String jti = UUID.randomUUID().toString();
        Instant alreadyExpired = Instant.now(clock).minusSeconds(1); // 이미 만료된 AT

        given(refreshTokenPort.findByTokenHash(hash)).willReturn(Optional.of(rt));

        tokenService.logout(raw, jti, alreadyExpired);

        verify(refreshTokenPort).deleteByTokenHash(hash);
        verify(blacklistPort, never()).addJti(any(), any()); // 만료된 AT는 블랙리스트 생략
    }

    @Test
    void logout_withNullJti_skipsJtiBlacklist() {
        String raw = "logoutToken3";
        String hash = TokenService.sha256Hex(raw);
        RefreshToken rt = makeRt(hash, Instant.now(clock).plusSeconds(1000), null);

        given(refreshTokenPort.findByTokenHash(hash)).willReturn(Optional.of(rt));

        tokenService.logout(raw, null, null); // jti 없이 호출 (AT 파싱 실패 케이스)

        verify(refreshTokenPort).deleteByTokenHash(hash);
        verify(blacklistPort, never()).addJti(any(), any()); // jti 없으면 블랙리스트 생략
    }

    @Test
    void logout_withoutRefreshToken_stillBlacklistsJti() {
        String jti = UUID.randomUUID().toString();

        tokenService.logout(null, jti, Instant.now(clock).plusSeconds(3600)); // RT 쿠키 없이 AT만 제시

        verify(blacklistPort).addJti(eq(jti), any());
        verify(refreshTokenPort, never()).deleteByTokenHash(any());
    }

    @Test
    void logout_unknownRefreshToken_stillBlacklistsJti() {
        String jti = UUID.randomUUID().toString();
        given(refreshTokenPort.findByTokenHash(any())).willReturn(Optional.empty());

        tokenService.logout("alreadyRotatedOrGone", jti, Instant.now(clock).plusSeconds(3600));

        verify(blacklistPort).addJti(eq(jti), any());
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

    private Instant future() {
        return Instant.now(clock).plus(Duration.ofHours(120));
    }

    private RefreshToken makeRt(String hash, Instant expiresAt, Instant rotatedAt) {
        return new RefreshToken(UUID.randomUUID(), USER_ID, hash, "ua", expiresAt, rotatedAt, Instant.now(clock));
    }

    private User mockUser() {
        return DomainFixtures.activeUser(USER_ID, NotificationChannel.FCM);
    }
}
