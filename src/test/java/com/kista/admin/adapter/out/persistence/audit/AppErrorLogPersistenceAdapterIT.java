package com.kista.admin.adapter.out.persistence.audit;

import com.kista.admin.domain.model.AppErrorLog;
import com.kista.support.DataJpaTestBase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// application-test.yml 로 연결된 로컬 PostgreSQL 위에서 AppErrorLogPersistenceAdapter JPA 저장/조회 검증
// context 필드(Map<String,String>, jsonb)가 Hibernate를 통해 실제로 왕복되는지가 이 테스트의 핵심
@Tag("integration")
@Import(AppErrorLogPersistenceAdapter.class)
@DisplayName("AppErrorLogPersistenceAdapter — PG 통합 테스트")
class AppErrorLogPersistenceAdapterIT extends DataJpaTestBase {

    @Autowired AppErrorLogJpaRepository repo;
    @Autowired EntityManager entityManager;

    AppErrorLogPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new AppErrorLogPersistenceAdapter(repo);
    }

    @Test
    @DisplayName("save() — context Map을 jsonb로 저장 후 findRecent로 왕복 복원")
    void save_and_findRecent_roundtrips_context() {
        Instant from = Instant.now().minusSeconds(60);

        adapter.save("TypeError", "cannot read property", "at foo()\nat bar()",
                java.util.Map.of("pathname", "/login", "caller", "TradingService"));
        // 1차 캐시(identity map)에 남은 인스턴스가 아니라 DB에 저장된 jsonb를 실제로 역직렬화해 읽는지 검증
        entityManager.flush();
        entityManager.clear();

        Instant to = Instant.now().plusSeconds(1);
        List<AppErrorLog> result = adapter.findRecent(50, from, to);

        assertThat(result).isNotEmpty();
        AppErrorLog saved = result.getFirst();
        assertThat(saved.errorType()).isEqualTo("TypeError");
        assertThat(saved.message()).isEqualTo("cannot read property");
        assertThat(saved.context()).containsEntry("pathname", "/login").containsEntry("caller", "TradingService");
    }

    @Test
    @DisplayName("save(Exception, caller) — caller 컨텍스트가 jsonb 왕복 후에도 유지")
    void save_exceptionOverload_roundtrips_context() {
        Instant from = Instant.now().minusSeconds(60);

        adapter.save(new RuntimeException("테스트 오류"), "TradingOpenScheduler");
        entityManager.flush();
        entityManager.clear();

        Instant to = Instant.now().plusSeconds(1);
        List<AppErrorLog> result = adapter.findRecent(50, from, to);

        assertThat(result).isNotEmpty();
        AppErrorLog saved = result.getFirst();
        assertThat(saved.errorType()).isEqualTo("RuntimeException");
        assertThat(saved.context()).containsEntry("caller", "TradingOpenScheduler");
    }
}
