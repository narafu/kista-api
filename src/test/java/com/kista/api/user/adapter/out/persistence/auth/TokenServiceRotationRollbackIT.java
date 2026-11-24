package com.kista.api.user.adapter.out.persistence.auth;

import com.kista.shared.support.DataJpaTestBase;
import com.kista.api.user.application.service.TokenUseCaseTestConfig;
import com.kista.api.user.domain.auth.InvalidRefreshTokenException;
import com.kista.api.user.application.service.TokenService;
import com.kista.api.user.application.service.RefreshTokenSuccessorDeriver;
import com.kista.api.user.application.port.output.BlacklistPort;
import com.kista.api.user.application.port.output.UserPort;
import com.kista.api.support.DomainFixtures;
import com.kista.shared.kernel.NotificationChannel;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.mockito.BDDMockito.given;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// grace(60초) 초과 회전 RT 재제시 시 TokenService.revokeAllSessionsAndReject()가 deleteAllByUserId 후
// InvalidRefreshTokenException(unchecked)을 던지는데, refresh()에 noRollbackFor가 없으면
// 클래스 레벨 @Transactional 기본 롤백 규칙에 의해 방금 실행한 세션 폐기(delete)까지 롤백되어
// 재사용 공격 탐지가 무력화됨 — Mockito 단위 테스트는 실제 트랜잭션 프록시를 거치지 않아 이 버그를 못 잡음
// TokenService(application 레이어, package-private)는 TokenUseCaseTestConfig를 통해 빈으로 노출받음
// (adapter → application 참조는 ArchUnit 허용 방향, 반대 방향만 금지되어 있어 패키지 위치를 이쪽으로 둠)
@Tag("integration")
@Import({RefreshTokenPortTestConfig.class, TokenUseCaseTestConfig.class})
@DisplayName("TokenService RT 재사용 공격 감지 — 실제 트랜잭션 커밋 검증 통합 테스트")
class TokenServiceRotationRollbackIT extends DataJpaTestBase {

    @Autowired TokenService tokenService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired Clock clock; // 서비스와 같은 고정 Clock — 시드 시각을 서비스 기준에 맞춘다
    @Autowired RefreshTokenSuccessorDeriver deriver;
    @MockitoBean BlacklistPort blacklistPort;
    @MockitoBean UserPort userPort;

    private UUID userId;

    @AfterEach
    void cleanUp() {
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    @DisplayName("후계를 이미 쓴 뒤 회전 RT 재제시 → 예외가 발생해도 전체 세션 폐기(delete)는 실제로 커밋된다")
    void revokeAllSessions_commitsDespiteThrownException() {
        seedUser();
        String rawToken = "staleRotatedTokenIT";
        seedRt(rawToken, Instant.now(clock).minus(Duration.ofSeconds(120))); // 회전됨
        seedRt(successorOf(rawToken), Instant.now(clock).minus(Duration.ofSeconds(60))); // 후계도 이미 사용됨

        commitSeed();

        assertThatThrownBy(() -> tokenService.refresh(rawToken, "ua"))
                .isInstanceOf(InvalidRefreshTokenException.class);

        // refresh() 내부 트랜잭션 밖에서 순수 조회 — 세션 폐기(delete)가 실제로 커밋됐는지 확인
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("같은 RT로 동시 갱신 → 둘 다 성공·같은 후계, 행 2개, 폐기 없음")
    void concurrentRefresh_bothSucceedWithSameSuccessor() throws Exception {
        seedUser();
        String rawToken = "concurrentTokenIT";
        seedRt(rawToken, null);
        commitSeed();
        given(userPort.findByIdOrThrow(userId)).willReturn(DomainFixtures.activeUser(userId, NotificationChannel.FCM));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> futures = List.of(
                    pool.submit(() -> { start.await(); return tokenService.refresh(rawToken, "ua").newRawRefreshToken(); }),
                    pool.submit(() -> { start.await(); return tokenService.refresh(rawToken, "ua").newRawRefreshToken(); }));
            start.countDown();
            assertThat(futures.get(0).get(10, TimeUnit.SECONDS)).isEqualTo(successorOf(rawToken));
            assertThat(futures.get(1).get(10, TimeUnit.SECONDS)).isEqualTo(successorOf(rawToken));
        } finally {
            pool.shutdownNow();
        }
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("응답 유실 재시도 → 같은 후계, 행 2개 유지")
    void retryAfterCommit_returnsSameSuccessor() {
        seedUser();
        String rawToken = "retryTokenIT";
        seedRt(rawToken, null);
        commitSeed();
        given(userPort.findByIdOrThrow(userId)).willReturn(DomainFixtures.activeUser(userId, NotificationChannel.FCM));

        String first = tokenService.refresh(rawToken, "ua").newRawRefreshToken();
        String second = tokenService.refresh(rawToken, "ua").newRawRefreshToken();

        assertThat(second).isEqualTo(first);
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("탈취: T→S, S→U 후 T 재제시 → 예외 + 사용자 행 전부 삭제(커밋)")
    void stolenTokenReuse_revokesAllCommitted() {
        seedUser();
        String rawToken = "stolenTokenIT";
        seedRt(rawToken, null);
        commitSeed();
        given(userPort.findByIdOrThrow(userId)).willReturn(DomainFixtures.activeUser(userId, NotificationChannel.FCM));

        String s = tokenService.refresh(rawToken, "ua").newRawRefreshToken();
        tokenService.refresh(s, "ua");
        // T 회전 시각을 grace 밖으로 — 고정 Clock이라 시드 이동으로 시간 경과를 흉내낸다
        jdbcTemplate.update("UPDATE refresh_tokens SET rotated_at = ? WHERE token_hash = ?",
                Timestamp.from(Instant.now(clock).minus(Duration.ofSeconds(120))), sha256Hex(rawToken));

        assertThatThrownBy(() -> tokenService.refresh(rawToken, "ua")).isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("T0→S1→S2 커밋 후 grace 안 T0 재제시 → S2 반환, 행 3개 유지")
    void withinGrace_staleT0_followsChainToS2() {
        seedUser();
        String t0 = "chainTokenIT";
        seedRt(t0, null);
        commitSeed();
        given(userPort.findByIdOrThrow(userId)).willReturn(DomainFixtures.activeUser(userId, NotificationChannel.FCM));

        String s1 = tokenService.refresh(t0, "ua").newRawRefreshToken();
        String s2 = tokenService.refresh(s1, "ua").newRawRefreshToken();

        assertThat(tokenService.refresh(t0, "ua").newRawRefreshToken()).isEqualTo(s2);
        assertThat(rowCount()).isEqualTo(3);
    }

    private void seedUser() {
        userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, notification_channel, created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())",
                userId, "kakao_" + userId, "ACTIVE", "USER", "TELEGRAM");
    }

    // rotatedAt null이면 미회전 RT 시드
    private void seedRt(String raw, Instant rotatedAt) {
        jdbcTemplate.update(
                "INSERT INTO refresh_tokens (id, user_id, token_hash, user_agent, expires_at, rotated_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, sha256Hex(raw), "ua",
                Timestamp.from(Instant.now(clock).plus(Duration.ofHours(120))), rotatedAt == null ? null : Timestamp.from(rotatedAt));
    }

    // 테스트 프레임워크가 잡고 있던 트랜잭션을 커밋·종료 — 이후 서비스 호출이 진짜 물리 트랜잭션으로 커밋/롤백됨
    private void commitSeed() {
        TestTransaction.flagForCommit();
        TestTransaction.end();
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId);
    }

    private String successorOf(String raw) {
        return deriver.successorOf(raw);
    }

    // TokenService.sha256Hex와 동일 알고리즘 — package-private이라 직접 호출 불가, 테스트 전용 재구현
    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
