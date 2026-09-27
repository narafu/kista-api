package com.kista.user.adapter.out.persistence.settings;

import com.kista.sharedkernel.NotificationType;
import com.kista.support.DataJpaTestBase;
import com.kista.user.domain.model.UserSettings;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// application-test.yml 로 연결된 로컬 PostgreSQL 위에서 UserSettingsPersistenceAdapter JPA 저장/조회 검증
// strategySuggestions 필드(List<String>, jsonb)가 Hibernate를 통해 실제로 왕복되는지가 이 테스트의 핵심
@Tag("integration")
@Import(UserSettingsPersistenceAdapter.class)
@DisplayName("UserSettingsPersistenceAdapter — PG 통합 테스트")
class UserSettingsPersistenceAdapterIT extends DataJpaTestBase {

    @Autowired UserSettingsJpaRepository settingsRepo;
    @Autowired UserNotificationPrefJpaRepository prefRepo;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;

    UserSettingsPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new UserSettingsPersistenceAdapter(settingsRepo, prefRepo);
    }

    private void insertUser(UUID userId) {
        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, notification_channel, created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())",
                userId, "kakao_" + userId, "ACTIVE", "USER", "TELEGRAM");
    }

    @Test
    @DisplayName("save() — strategySuggestions List를 jsonb로 저장 후 loadByUserId로 왕복 복원")
    void save_and_load_roundtrips_strategySuggestions() {
        UUID userId = UUID.randomUUID();
        insertUser(userId);
        UserSettings settings = new UserSettings(userId, false,
                Map.of(NotificationType.TRADING_ALERT, false), List.of("VR", "PRIVACY"));

        adapter.save(settings);
        // 1차 캐시(identity map)에 남은 인스턴스가 아니라 DB에 저장된 jsonb를 실제로 역직렬화해 읽는지 검증
        entityManager.flush();
        entityManager.clear();

        Optional<UserSettings> result = adapter.loadByUserId(userId);
        assertThat(result).isPresent();
        assertThat(result.get().strategySuggestions()).containsExactly("VR", "PRIVACY");
        assertThat(result.get().balanceCheckEnabled()).isFalse();
        assertThat(result.get().isNotificationEnabled(NotificationType.TRADING_ALERT)).isFalse();
    }

    @Test
    @DisplayName("loadByUserId() — strategy_suggestions 컬럼 null이면 기본 추천 목록으로 대체")
    void load_defaultsStrategySuggestions_whenColumnNull() {
        UUID userId = UUID.randomUUID();
        insertUser(userId);
        // strategySuggestions=null로 직접 저장 (jsonb 컬럼 미설정 상태 재현)
        settingsRepo.save(new UserSettingsJpaEntity(userId, true, null));

        Optional<UserSettings> result = adapter.loadByUserId(userId);

        assertThat(result).isPresent();
        assertThat(result.get().strategySuggestions()).isEqualTo(UserSettings.DEFAULT_STRATEGY_SUGGESTIONS);
    }
}
