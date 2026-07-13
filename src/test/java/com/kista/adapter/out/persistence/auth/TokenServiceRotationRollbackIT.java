package com.kista.adapter.out.persistence.auth;

import com.kista.application.service.auth.TokenUseCaseTestConfig;
import com.kista.domain.model.auth.InvalidRefreshTokenException;
import com.kista.domain.port.in.TokenUseCase;
import com.kista.domain.port.out.BlacklistPort;
import com.kista.domain.port.out.UserPort;
import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.TestTransaction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
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
// TokenService(application 레이어, package-private)는 TokenUseCaseTestConfig를 통해 TokenUseCase로 노출받음
// (adapter → application 참조는 ArchUnit 허용 방향, 반대 방향만 금지되어 있어 패키지 위치를 이쪽으로 둠)
@Tag("integration")
@Import({RefreshTokenPortTestConfig.class, TokenUseCaseTestConfig.class})
@DisplayName("TokenService RT 재사용 공격 감지 — 실제 트랜잭션 커밋 검증 통합 테스트")
class TokenServiceRotationRollbackIT extends DataJpaTestBase {

    @Autowired TokenUseCase tokenUseCase;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean BlacklistPort blacklistPort;
    @MockBean UserPort userPort;

    private UUID userId;

    @AfterEach
    void cleanUp() {
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    @DisplayName("grace 초과 회전 RT 재제시 → 예외가 발생해도 전체 세션 폐기(delete)는 실제로 커밋된다")
    void revokeAllSessions_commitsDespiteThrownException() {
        userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, notification_channel, created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())",
                userId, "kakao_" + userId, "ACTIVE", "USER", "TELEGRAM");

        String rawToken = "staleRotatedTokenIT";
        String hash = sha256Hex(rawToken);
        Instant rotatedAt = Instant.now().minus(Duration.ofSeconds(120)); // grace(60초) 초과 회전
        jdbcTemplate.update(
                "INSERT INTO refresh_tokens (id, user_id, token_hash, user_agent, expires_at, rotated_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, hash, "ua",
                Timestamp.from(Instant.now().plus(Duration.ofHours(120))), Timestamp.from(rotatedAt));

        // 테스트 프레임워크가 잡고 있던 트랜잭션을 종료 — 이후 서비스 호출이 진짜 물리 트랜잭션으로 커밋/롤백됨
        TestTransaction.flagForCommit();
        TestTransaction.end();

        assertThatThrownBy(() -> tokenUseCase.refresh(rawToken, "ua"))
                .isInstanceOf(InvalidRefreshTokenException.class);

        // refresh() 내부 트랜잭션 밖에서 순수 조회 — 세션 폐기(delete)가 실제로 커밋됐는지 확인
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId);
        assertThat(remaining).isZero();
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
