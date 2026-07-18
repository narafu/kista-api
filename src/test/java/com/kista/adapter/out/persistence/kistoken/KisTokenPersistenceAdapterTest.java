package com.kista.adapter.out.persistence.kistoken;

import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.TestTransaction;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(KisTokenPersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD) // @DataJpaTest + parallel execution — 트랜잭션 경합 방지
class KisTokenPersistenceAdapterTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired KisTokenPersistenceAdapter tokenAdapter;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();

        // broker_tokens.account_id → accounts(id) FK 충족을 위한 선행 삽입
        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                userId, "kakao_" + userId, "ACTIVE", "USER");
        jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, userId, "테스트계좌", "KIS", "74420614", "01", "key", "secret");

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();
    }

    @AfterEach
    void tearDown() {
        if (TestTransaction.isActive()) {
            TestTransaction.end();
        }
        jdbcTemplate.update("DELETE FROM broker_tokens WHERE account_id = ?", accountId);
        jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", accountId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void saveToken_thenFindValidToken_returnsToken() {
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);

        tokenAdapter.saveToken(accountId, "access-token-1", expiresAt);

        Optional<String> found = tokenAdapter.findValidToken(accountId, OffsetDateTime.now(ZoneOffset.UTC));

        assertThat(found).contains("access-token-1");
    }

    @Test
    void saveToken_sameAccountAgain_upsertsToLatestValue() {
        OffsetDateTime firstExpiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
        OffsetDateTime secondExpiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2);

        tokenAdapter.saveToken(accountId, "access-token-1", firstExpiresAt);
        tokenAdapter.saveToken(accountId, "access-token-2", secondExpiresAt);

        Optional<String> found = tokenAdapter.findValidToken(accountId, OffsetDateTime.now(ZoneOffset.UTC));

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM broker_tokens WHERE account_id = ?",
                Integer.class,
                accountId);

        assertThat(found).contains("access-token-2");
        assertThat(rowCount).isEqualTo(1); // account_id가 PK이므로 신규 행이 아닌 갱신
    }

    @Test
    void findValidToken_expiredToken_returnsEmpty() {
        // 이미 만료된 토큰만 존재
        tokenAdapter.saveToken(accountId, "expired-token", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));

        Optional<String> found = tokenAdapter.findValidToken(accountId, OffsetDateTime.now(ZoneOffset.UTC));

        assertThat(found).isEmpty();
    }

    @Test
    void findValidToken_unknownAccount_returnsEmpty() {
        Optional<String> found = tokenAdapter.findValidToken(UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC));

        assertThat(found).isEmpty();
    }

    @Test
    void saveToken_outerTransactionRollsBack_tokenRemainsCommitted() {
        tokenAdapter.saveToken(
                accountId,
                "committed-token",
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));

        TestTransaction.flagForRollback();
        TestTransaction.end();

        String accessToken = jdbcTemplate.queryForObject(
                "SELECT access_token FROM broker_tokens WHERE account_id = ?",
                String.class,
                accountId);

        assertThat(accessToken).isEqualTo("committed-token");
    }

    @Test
    void invalidateToken_matchingAccessToken_expiresStoredToken() {
        tokenAdapter.saveToken(
                accountId,
                "rejected-token",
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));

        tokenAdapter.invalidateToken(
                accountId,
                "rejected-token",
                OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));

        Optional<String> found = tokenAdapter.findValidToken(accountId, OffsetDateTime.now(ZoneOffset.UTC));
        String storedToken = jdbcTemplate.queryForObject(
                "SELECT access_token FROM broker_tokens WHERE account_id = ?",
                String.class,
                accountId);

        assertThat(found).isEmpty();
        assertThat(storedToken).isEqualTo("EXPIRED");
    }

    @Test
    void invalidateToken_staleAccessToken_preservesFreshToken() {
        tokenAdapter.saveToken(
                accountId,
                "fresh-token",
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));

        tokenAdapter.invalidateToken(
                accountId,
                "stale-token",
                OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));

        Optional<String> found = tokenAdapter.findValidToken(accountId, OffsetDateTime.now(ZoneOffset.UTC));

        assertThat(found).contains("fresh-token");
    }
}
