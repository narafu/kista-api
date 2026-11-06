package com.kista.api.user.adapter.out.persistence.auth;

import com.kista.api.user.domain.auth.RefreshToken;
import com.kista.api.web.RootDataJpaTestBase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// 벌크 delete JPQL 검증 — 동시 요청이 이미 지운 행을 다시 지워도 예외가 없어야 하고(파생 delete의 낙관적 락 500 회귀 방지),
// User-Agent null 행도 파생 쿼리와 같은 의미(IS NULL)로 지워져야 한다
@Execution(ExecutionMode.SAME_THREAD)
class RefreshTokenJpaRepositoryDbTest extends RootDataJpaTestBase {

    @Autowired RefreshTokenJpaRepository repository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;

    private UUID userId;

    @BeforeEach
    void setUp() {
        // refresh_tokens.user_id → users FK 선행 행
        userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, notification_channel, created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())",
                userId, "kakao_" + userId, "ACTIVE", "USER", "TELEGRAM");
    }

    @Test
    void 같은_기기_토큰만_지우고_null_User_Agent는_null끼리_같은_기기로_본다() {
        save("h-chrome", "Chrome");
        save("h-null", null);
        save("h-safari", "Safari");

        repository.deleteAllByUserIdAndUserAgent(userId, "Chrome");
        repository.deleteAllByUserIdAndUserAgent(userId, null);
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByTokenHash("h-chrome")).isEmpty();
        assertThat(repository.findByTokenHash("h-null")).isEmpty();
        assertThat(repository.findByTokenHash("h-safari")).isPresent();
    }

    @Test
    void 삭제_직후_다른_요청이_같은_행을_먼저_지워도_flush가_예외_없이_끝난다() {
        save("h-race", "Chrome");

        // 파생 delete였다면 SELECT 후 em.remove만 예약된 상태 — 그 사이 동시 요청이 같은 행을 지운다(JdbcTemplate은 Hibernate auto-flush를 거치지 않는다)
        repository.deleteAllByUserIdAndUserAgent(userId, "Chrome");
        jdbcTemplate.update("DELETE FROM refresh_tokens WHERE token_hash = ?", "h-race");

        // 예약된 엔티티 delete가 0행이면 ObjectOptimisticLockingFailureException — 벌크 delete는 예약 없이 즉시 실행돼 무사하다
        assertThatCode(entityManager::flush).doesNotThrowAnyException();
    }

    private void save(String hash, String userAgent) {
        repository.saveAndFlush(RefreshTokenEntity.from(
                new RefreshToken(null, userId, hash, userAgent, Instant.now().plusSeconds(3600), null, null)));
    }
}
