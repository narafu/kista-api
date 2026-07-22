package com.kista.adapter.out.toss;

import com.kista.support.DataJpaTestBase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("TossAuthApi Redis canonical token JPA 격리")
class TossAuthApiNoJpaTest extends DataJpaTestBase {

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("outer transaction 안 Toss 발급도 broker_tokens 행·추가 JPA connection을 사용하지 않는다")
    void accountIssuanceInsideOuterTransaction_doesNotUseJpaTokenPersistence() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource hikari = (HikariDataSource) dataSource;
        UUID accountId = UUID.randomUUID();
        String scope = TossDistributedTokenCoordinator.accountScope(accountId);
        TossTokenStore tokenStore = mock(TossTokenStore.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(tokenStore.find(scope)).thenReturn(Optional.empty());
        when(tokenStore.tryAcquire(eq(scope), anyString(), eq(Duration.ofMinutes(1))))
                .thenReturn(Optional.of(new TossTokenStore.Lease(scope, "owner", 1L)));
        when(tokenStore.storeIfCurrent(any(), any(), eq(Duration.ofHours(23).plusMinutes(55))))
                .thenReturn(TossTokenStore.StoreResult.STORED);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(TossAuthApi.TokenResponse.class)))
                .thenReturn(ResponseEntity.ok(new TossAuthApi.TokenResponse("redis-token", 86_400L)));
        TossDistributedTokenCoordinator coordinator = new TossDistributedTokenCoordinator(
                tokenStore, ignored -> {}, () -> "owner", 3);
        TossAuthApi api = new TossAuthApi(
                restTemplate, coordinator, "http://toss.test", "admin-id", "admin-secret");

        int rowsBefore = brokerTokenRows(accountId);
        int activeConnectionsBefore = hikari.getHikariPoolMXBean().getActiveConnections();

        String token = api.getToken(accountId, "client-id", "client-secret");

        assertThat(token).isEqualTo("redis-token");
        assertThat(brokerTokenRows(accountId)).isEqualTo(rowsBefore).isZero();
        assertThat(hikari.getHikariPoolMXBean().getActiveConnections())
                .isEqualTo(activeConnectionsBefore);
    }

    private int brokerTokenRows(UUID accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM broker_tokens WHERE account_id = ?",
                Integer.class,
                accountId);
    }
}
