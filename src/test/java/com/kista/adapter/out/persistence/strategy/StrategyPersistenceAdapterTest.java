package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyInfiniteDetail;
import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import({
        StrategyPersistenceAdapter.class,
        StrategyInfiniteDetailPersistenceAdapter.class
})
class StrategyPersistenceAdapterTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StrategyPersistenceAdapter strategyAdapter;
    @Autowired StrategyInfiniteDetailPersistenceAdapter strategyInfiniteDetailAdapter;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                userId, "kakao_" + userId, "ACTIVE", "USER");
        jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, nickname, account_no, app_key, secret_key, kis_account_type, broker, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, userId, "테스트계좌", "74420614", "key", "secret", "01", "KIS");
    }

    @Test
    void save_infiniteStrategy_persistsCommonAndDetailRows() {
        Strategy strategy = new Strategy(
                null, accountId, Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE
        );

        Strategy saved = strategyAdapter.save(strategy);
        strategyInfiniteDetailAdapter.save(new StrategyInfiniteDetail(saved.id(), 20));

        Integer strategyRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM strategy WHERE id = ?",
                Integer.class,
                saved.id());
        Integer detailRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM strategy_infinite WHERE strategy_id = ? AND division_count = 20",
                Integer.class,
                saved.id());

        assertThat(saved.id()).isNotNull();
        assertThat(strategyRows).isEqualTo(1);
        assertThat(detailRows).isEqualTo(1);
        assertThat(strategyInfiniteDetailAdapter.findByStrategyId(saved.id()))
                .contains(new StrategyInfiniteDetail(saved.id(), 20));
    }

    @Test
    void deleteByStrategyId_removesInfiniteDetailRowOnly() {
        Strategy saved = strategyAdapter.save(new Strategy(
                null, accountId, Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE
        ));
        strategyInfiniteDetailAdapter.save(new StrategyInfiniteDetail(saved.id(), 30));

        strategyInfiniteDetailAdapter.deleteByStrategyId(saved.id());

        Integer strategyRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM strategy WHERE id = ?",
                Integer.class,
                saved.id());
        Integer detailRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM strategy_infinite WHERE strategy_id = ?",
                Integer.class,
                saved.id());

        assertThat(strategyRows).isEqualTo(1);
        assertThat(detailRows).isZero();
        assertThat(strategyInfiniteDetailAdapter.findByStrategyId(saved.id())).isEmpty();
    }
}
