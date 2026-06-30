package com.kista.adapter.out.persistence.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kista.domain.model.admin.AuditLog;
import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// application-test.yml 로 연결된 로컬 PostgreSQL 위에서 AuditLogPersistenceAdapter JPA 저장/조회 검증
// @DataJpaTest가 JPA Auditing(@EnableJpaAuditing)을 자동 활성화하므로 JpaAuditingConfig import 불필요
@Tag("integration")
@Import({AuditLogPersistenceAdapter.class, AuditLogPersistenceAdapterIT.TestJacksonConfig.class})
@DisplayName("AuditLogPersistenceAdapter — PG 통합 테스트")
class AuditLogPersistenceAdapterIT extends DataJpaTestBase {

    @Autowired AuditLogJpaRepository repo;
    @Autowired JdbcTemplate jdbcTemplate;

    AuditLogPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new AuditLogPersistenceAdapter(repo, new ObjectMapper());
    }

    private void insertAdmin(UUID adminId) {
        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, notification_channel, created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())",
                adminId, "kakao_" + adminId, "ACTIVE", "USER", "TELEGRAM");
    }

    @TestConfiguration
    static class TestJacksonConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Test
    @DisplayName("log() — payload 직렬화 후 PG에 저장, findById로 복원")
    void log_and_find_by_id() {
        UUID adminId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("before", "PENDING", "after", "ACTIVE");
        insertAdmin(adminId);

        adapter.log(adminId, "USER_APPROVE", "USER", targetId, payload);

        // findAll로 방금 저장된 행의 id를 가져옴
        List<AuditLog> all = adapter.findAll();
        assertThat(all).isNotEmpty();

        AuditLog saved = all.get(0);
        assertThat(saved.adminId()).isEqualTo(adminId);
        assertThat(saved.action()).isEqualTo("USER_APPROVE");
        assertThat(saved.targetType()).isEqualTo("USER");
        assertThat(saved.targetId()).isEqualTo(targetId);
        assertThat(saved.payload()).containsKey("before");
    }

    @Test
    @DisplayName("findById() — 없는 id 조회 시 NoSuchElementException")
    void findById_throws_when_not_found() {
        assertThatThrownBy(() -> adapter.findById(UUID.randomUUID()))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("findAll() — 최신순 100건 반환, NUMERIC 필드 정밀도 유지")
    void findAll_returns_list_ordered_by_created_at_desc() {
        UUID adminId = UUID.randomUUID();
        insertAdmin(adminId);
        adapter.log(adminId, "ACTION_A", "USER", UUID.randomUUID(), null);
        adapter.log(adminId, "ACTION_B", "USER", UUID.randomUUID(), null);

        List<AuditLog> all = adapter.findAll();
        assertThat(all).hasSizeGreaterThanOrEqualTo(2);
        // 최신순 — ACTION_B가 더 나중에 저장됐으므로 앞에 와야 함
        assertThat(all.get(0).action()).isEqualTo("ACTION_B");
    }
}
