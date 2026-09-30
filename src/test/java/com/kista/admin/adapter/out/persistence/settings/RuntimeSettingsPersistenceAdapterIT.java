package com.kista.admin.adapter.out.persistence.settings;

import tools.jackson.databind.ObjectMapper;
import com.kista.admin.domain.model.BenchmarkSettings;
import com.kista.admin.domain.model.RuntimeSettings;
import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;


import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class RuntimeSettingsPersistenceAdapterIT extends DataJpaTestBase {

    @Autowired RuntimeSettingsJpaRepository repository; // 실제 PostgreSQL 저장소

    @Test
    void seededSettingsLoadAndSavedSettingsRoundTrip() {
        RuntimeSettingsPersistenceAdapter adapter = new RuntimeSettingsPersistenceAdapter(repository, new ObjectMapper());
        RuntimeSettings seeded = adapter.load();
        assertThat(seeded).isEqualTo(RuntimeSettings.defaults());

        // 승인 정책을 끈 설정을 저장한 뒤 다시 조회한다.
        RuntimeSettings changed = new RuntimeSettings(false, seeded.benchmarks());
        adapter.save(changed);
        assertThat(adapter.load()).isEqualTo(changed);
    }

    // brokers/strategies 섹션이 trading-core로 이동하기 전 저장된 행(두 키 잔존)도 그대로 읽힌다
    @Test
    void legacyRowWithTradingSectionsStillLoads() {
        RuntimeSettingsPersistenceAdapter adapter = new RuntimeSettingsPersistenceAdapter(repository, new ObjectMapper());
        repository.save(new RuntimeSettingsEntity(RuntimeSettingsPersistenceAdapter.SETTING_KEY,
                "{\"approvalRequired\":false,\"brokers\":{\"KIS\":{\"enabled\":false}},\"strategies\":{}}"));

        RuntimeSettings loaded = adapter.load();

        assertThat(loaded.approvalRequired()).isFalse();
        assertThat(loaded.benchmarks()).isEqualTo(BenchmarkSettings.defaults());
    }
}
