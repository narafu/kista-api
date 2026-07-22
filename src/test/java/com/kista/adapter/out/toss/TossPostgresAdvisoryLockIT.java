package com.kista.adapter.out.toss;

import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Import(TossAdvisoryLockDataSourceConfig.class)
@DisplayName("Toss PostgreSQL session advisory lock 통합 테스트")
class TossPostgresAdvisoryLockIT extends DataJpaTestBase {

    @Autowired
    DataSource dataSource;

    @Autowired
    TossAdvisoryLockDataSource lockDataSource;

    @Test
    @DisplayName("서로 다른 DB session은 같은 key를 동시에 소유하지 못하고 release 후 획득한다")
    void dedicatedSessions_excludeUntilOwnerReleases() {
        assertThat(lockDataSource.dataSource())
                .isNotSameAs(dataSource)
                .isInstanceOf(HikariDataSource.class);
        assertThat(lockDataSource.dataSource().getMaximumPoolSize()).isEqualTo(2);
        assertThat(lockDataSource.dataSource().getConnectionTimeout()).isEqualTo(1_000L);
        assertThat(lockDataSource.dataSource().getValidationTimeout()).isEqualTo(1_000L);
        TossPostgresAdvisoryLock firstSession = new TossPostgresAdvisoryLock(lockDataSource.dataSource());
        TossPostgresAdvisoryLock secondSession = new TossPostgresAdvisoryLock(lockDataSource.dataSource());
        long lockKey = 7_517_222_026L;

        TossTokenIssuanceLock.Handle owner = firstSession.tryAcquire(lockKey).orElseThrow();
        try {
            assertThat(secondSession.tryAcquire(lockKey)).isEmpty();
        } finally {
            owner.close();
        }

        try (TossTokenIssuanceLock.Handle nextOwner =
                     secondSession.tryAcquire(lockKey).orElseThrow()) {
            assertThat(nextOwner).isNotNull();
        }
    }
}
