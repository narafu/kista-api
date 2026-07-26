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
                        "initial_gradient",
                        "g_grace_weeks",
                        "g_step_weeks",
                        "g_max",
                        "initial_pool_limit_rate",
                        "p_grace_weeks",
                        "p_step_weeks",
                        "pool_limit_floor",
                        "created_at",
                        "updated_at"
                );

        String migration = Files.readString(Path.of("src/main/resources/db/migration/V1__init.sql"));

        String tableDdl = tableDdl(migration, "strategy_vr_version");

        assertThat(tableDdl).contains("CREATE TABLE strategy_vr_version");
        assertThat(tableDdl).contains("CHECK (interval_weeks > 0)");
        assertThat(tableDdl).contains("recurring_amount        INTEGER        NOT NULL");
        assertThat(tableDdl).contains("g_max                   INTEGER        NOT NULL");
        assertThat(tableDdl).contains("strategy_vr_version_g_max_check CHECK (g_max >= initial_gradient)");
        assertThat(tableDdl).doesNotContain("deleted_at");
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
                        "pool_limit_rate",
                        "created_at",
                        "updated_at"
                );

        String migration = Files.readString(Path.of("src/main/resources/db/migration/V1__init.sql"));

        String tableDdl = tableDdl(migration, "strategy_cycle_vr");

        assertThat(tableDdl).contains("CREATE TABLE strategy_cycle_vr");
        assertThat(tableDdl).contains("CHECK (gradient > 0)");
        assertThat(tableDdl).contains("pool_limit_rate   NUMERIC(6, 2)  NOT NULL");
        assertThat(tableDdl).contains("strategy_cycle_vr_pool_limit_rate_check CHECK (pool_limit_rate > 0 AND pool_limit_rate <= 1)");
        assertThat(tableDdl).doesNotContain("deleted_at");
    }

    private static String tableDdl(String migration, String tableName) {
        int start = migration.indexOf("CREATE TABLE " + tableName);
        int end = migration.indexOf(");\n", start);
        return migration.substring(start, end + 2);
    }
}
