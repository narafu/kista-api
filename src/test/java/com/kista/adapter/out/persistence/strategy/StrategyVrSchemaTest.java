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
        // V31이 램프 파라미터 8컬럼을 ADD COLUMN(항상 맨 뒤)으로 추가 — created_at/updated_at 뒤에 이어붙는다
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
                        "updated_at",
                        "initial_gradient",
                        "g_grace_weeks",
                        "g_step_weeks",
                        "g_max",
                        "initial_pool_limit_rate",
                        "p_grace_weeks",
                        "p_step_weeks",
                        "pool_limit_floor"
                );

        String migration = Files.readString(Path.of("src/main/resources/db/migration/V18__add_vr_strategy_details.sql"));

        assertThat(migration).contains("CREATE TABLE strategy_vr_version");
        assertThat(migration).contains("CHECK (interval_weeks > 0)");
        assertThat(migration).contains("recurring_amount INTEGER NOT NULL");
        assertThat(migration).doesNotContain("deleted_at");

        // V31: 경과주수 기반 램프 파라미터 8컬럼 추가 마이그레이션 검증
        String rampMigration = Files.readString(
                Path.of("src/main/resources/db/migration/V31__vr_ramp_and_pool_limit_rate.sql"));
        assertThat(rampMigration).contains("ADD COLUMN initial_gradient");
        assertThat(rampMigration).contains("strategy_vr_version_g_max_check CHECK (g_max >= initial_gradient)");
        assertThat(rampMigration).doesNotContain("deleted_at");
    }

    @Test
    void strategyCycleVrSchemaAndMigration_followAuditConventionWithoutDeletedAt() throws Exception {
        // V31이 pool_limit(고정 금액) 컬럼을 DROP하고 pool_limit_rate(고정 비율)를 ADD COLUMN(맨 뒤)으로 대체
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
                        "created_at",
                        "updated_at",
                        "pool_limit_rate"
                );

        String migration = Files.readString(Path.of("src/main/resources/db/migration/V18__add_vr_strategy_details.sql"));

        assertThat(migration).contains("CREATE TABLE strategy_cycle_vr");
        assertThat(migration).contains("CHECK (gradient > 0)");
        assertThat(migration).contains("pool_limit NUMERIC(20, 2) NOT NULL");
        assertThat(migration).doesNotContain("deleted_at");

        // V31: pool_limit(고정 금액) → pool_limit_rate(고정 비율) 전환 검증
        String rateMigration = Files.readString(
                Path.of("src/main/resources/db/migration/V31__vr_ramp_and_pool_limit_rate.sql"));
        assertThat(rateMigration).contains("ADD COLUMN pool_limit_rate");
        assertThat(rateMigration).contains("DROP COLUMN pool_limit");
        assertThat(rateMigration).doesNotContain("deleted_at");
    }
}
