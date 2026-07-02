package com.kista.adapter.out.persistence.strategy;

import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyVrSchemaTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void strategyVrVersionSchemaAndMigration_followAuditConventionWithoutDeletedAt() throws Exception {
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_name = 'strategy_vr_version'
                ORDER BY ordinal_position
                """, String.class))
                .containsExactly(
                        "strategy_version_id",
                        "interval_weeks",
                        "band_width",
                        "recurring_amount",
                        "created_at",
                        "updated_at"
                );

        String migration = Files.readString(Path.of("src/main/resources/db/migration/V18__add_vr_strategy_details.sql"));

        assertThat(migration).contains("CREATE TABLE strategy_vr_version");
        assertThat(migration).contains("CHECK (interval_weeks > 0)");
        assertThat(migration).contains("recurring_amount INTEGER NOT NULL");
        assertThat(migration).doesNotContain("deleted_at");
    }

    @Test
    void strategyCycleVrSchemaAndMigration_followAuditConventionWithoutDeletedAt() throws Exception {
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_name = 'strategy_cycle_vr'
                ORDER BY ordinal_position
                """, String.class))
                .containsExactly(
                        "strategy_cycle_id",
                        "value",
                        "gradient",
                        "pool_limit",
                        "created_at",
                        "updated_at"
                );

        String migration = Files.readString(Path.of("src/main/resources/db/migration/V18__add_vr_strategy_details.sql"));

        assertThat(migration).contains("CREATE TABLE strategy_cycle_vr");
        assertThat(migration).contains("CHECK (gradient > 0)");
        assertThat(migration).contains("pool_limit NUMERIC(20, 2) NOT NULL");
        assertThat(migration).doesNotContain("deleted_at");
    }
}
