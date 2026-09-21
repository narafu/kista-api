package com.kista.trading.adapter.out.persistence;

import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyVrSchemaTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void strategyVrVersionSchema_followAuditConventionWithoutDeletedAt() throws Exception {
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'trading' AND table_name = 'strategy_vr_version'
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

        assertThat(columnSpecs("strategy_vr_version", "recurring_amount", "g_max"))
                .containsExactly("g_max:integer:NO", "recurring_amount:integer:NO");
        assertThat(checkConstraints("strategy_vr_version"))
                .anyMatch(d -> d.contains("interval_weeks > 0"))
                .anyMatch(d -> d.contains("g_max >= initial_gradient"));
    }

    @Test
    void strategyCycleVrSchema_followAuditConventionWithoutDeletedAt() throws Exception {
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'trading' AND table_name = 'strategy_cycle_vr'
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

        assertThat(columnSpecs("strategy_cycle_vr", "pool_limit_rate"))
                .containsExactly("pool_limit_rate:numeric:NO");
        assertThat(checkConstraints("strategy_cycle_vr"))
                .anyMatch(d -> d.contains("gradient > 0"))
                .anyMatch(d -> d.contains("pool_limit_rate > (0)::numeric") && d.contains("pool_limit_rate <= (1)::numeric"));
    }

    // 컬럼 "이름:타입:NULL허용" — 파일 텍스트가 아닌 실제 DB 카탈로그로 규약을 검사한다
    private java.util.List<String> columnSpecs(String table, String... columns) {
        return jdbcTemplate.queryForList("""
                SELECT column_name || ':' || data_type || ':' || is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'trading' AND table_name = ? AND column_name = ANY (?)
                ORDER BY column_name
                """, String.class, table, columns);
    }

    private java.util.List<String> checkConstraints(String table) {
        return jdbcTemplate.queryForList("""
                SELECT pg_get_constraintdef(c.oid)
                FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = 'trading' AND t.relname = ? AND c.contype = 'c'
                """, String.class, table);
    }
}
